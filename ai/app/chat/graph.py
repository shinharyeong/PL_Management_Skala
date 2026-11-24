"""text2SQL 챗봇 흐름(LangGraph). service.run_chat 이 이 그래프를 실행한다.

    차단 확인 → DB 열기 → 해석 → 후속 질문 규칙 → (되묻기) → 준비 → 경로 고르기
        ├ 규칙(후속 질문)       ─┐
        ├ 예시 질문(고정 SQL)   ─┤
        └ AI: 저장된 SQL → SQL 생성 ⇄ 검사·실행(실패하면 고쳐서 다시, 최대 CHAT_MAX_FIX번)
                 └ AI 를 못 쓰면 → 기본 질의(예시 질문과 맞으면)
                                 ─┴→ 설명 문장 → 근거 구성 → 기억 갱신 → 응답

- 노드는 기존 단계 함수(interpret·followup·nl2sql·sqlguard·quick)를 그대로 부른다. 숫자는 항상 SQL 결과에서 코드가 계산한다.
- 중간에 끝나는 경로(차단·되묻기·조회 불가·오류)는 state["resp"] 를 채우고 END 로 간다.
- 상태에는 DB 연결·설정 같은 파이썬 객체가 들어 있어 체크포인터를 쓰지 않는다(대화 기억은 memory.py, 서버 메모리).
"""
from __future__ import annotations

import time
from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph

from . import db, followup, interpret, memory, nl2sql, quick, sqlguard

GUARD_STEP_CODES = ("EMPTY", "MULTI_OR_SEMICOLON", "COMMENT", "NOT_SELECT", "FORBIDDEN_KEYWORD", "RECURSIVE", "TABLE_NOT_ALLOWED",
                    "NO_VERSION_FILTER", "VERSION_LITERAL")


class ChatState(TypedDict, total=False):
    # 입력
    question: str
    version: str | None
    quick_id: str | None
    conversation_id: str | None
    settings: Any
    steps: Any          # service.Steps
    budget: Any         # nl2sql.Budget
    flags: dict         # followup·handled·fixes·cache
    # 진행
    con: Any
    st: Any             # memory.State
    it: Any             # interpret 결과
    fu: dict | None
    rule_sql: str | None
    rule_params: dict | None
    params: dict
    basis: str | None
    merged: dict
    route: str
    # AI 경로
    cache_key: str
    ctx: dict | None
    attempt: int
    prev: tuple | None
    ai_sql: str | None
    ai_kind: str
    # 결과
    sql: str | None
    res: Any
    sql_source: str | None
    notice: str | None
    text: str | None
    text_source: str
    ev: list
    file: dict | None
    resp: dict | None
    vlabel: str | None


# ------------------------------------------------------------------ 공통
def _run_sql(state: ChatState, sql: str, params: dict) -> sqlguard.Result:
    s = state["settings"]
    return sqlguard.execute(state["con"], sql, params, row_limit=s.chat_row_limit, timeout_s=s.chat_sql_timeout_seconds)


def _end(state: ChatState, status: str, **kw) -> dict:
    from .service import _response
    return {"resp": _response(status, steps=state["steps"].items, **kw)}


def _ended(state: ChatState) -> str:
    return END if state.get("resp") else "next"


# ------------------------------------------------------------------ 노드
def guard(state: ChatState) -> dict:
    from .service import BLOCK_TEXT
    br = interpret.blocked_reason(state["question"])
    if br:                                                            # 데이터를 바꾸거나 지우라는 요청: SQL 을 만들지 않고 정중히 거절
        state["steps"].add("해석", "blocked", "데이터를 바꾸거나 지우는 요청(조회만 가능)")
        return _end(state, "blocked", answer_text=BLOCK_TEXT[br])
    return {}


def open_db(state: ChatState) -> dict:
    try:
        con = db.open_readonly(state["settings"])
    except FileNotFoundError as e:
        state["steps"].add("준비", "fail", str(e))
        return _end(state, "error", answer_text=str(e), notice="조회 DB 가 비어 있습니다. '최신 실행 불러오기'를 눌러 주세요.")
    state["flags"]["con"] = con                                      # 중간에 예외가 나도 run_chat 이 닫을 수 있게
    return {"con": con}


def understand(state: ChatState) -> dict:
    from .service import _stamp
    s, con, steps = state["settings"], state["con"], state["steps"]
    st = memory.get(state.get("conversation_id"), _stamp(con), on=s.chat_memory)
    t0 = time.perf_counter()
    it = interpret.interpret(con, state["question"], version=state.get("version"))
    steps.add("해석", "ok" if not (it.unknown or it.clarify) else ("unknown" if it.unknown else "clarify"),
              f"조직 {it.org_names or '없음(전사 합산)'} · 월 {it.months or '없음'} · 버전 {it.version or '없음'}({it.version_note}) · 시나리오 {it.scenario}", t0)
    if it.unknown:
        return {"st": st, "it": it, **_end(state, "unknown", answer_text=it.unknown, version=it.version)}
    return {"st": st, "it": it}


def follow_up(state: ChatState) -> dict:
    """짧은 후속 질문: AI 없이 규칙으로 이전 조건에 덮어쓴다. 바꿀 수 없으면 이전 조건을 SQL 생성 AI 에 함께 보낸다."""
    st, it, steps = state["st"], state["it"], state["steps"]
    fu = followup.detect(state["question"], it, st) if st else None
    rule_sql = rule_params = None
    if fu:
        state["flags"]["followup"] = True
        t1 = time.perf_counter()
        applied = followup.apply(st, fu)
        if applied:
            rule_sql, rule_params = applied
            it.version = rule_params.get("version", it.version)                    # 이전 대화의 버전을 이어 쓴다
            steps.add("후속 질문", "ok", f"규칙으로 이전 조건에 덮어씀({fu['kind']}) · AI 호출 없음", t1)
        else:
            steps.add("후속 질문", "skip", f"규칙으로 바꿀 수 없어({fu['kind']}) 이전 조건을 SQL 생성 AI 에 함께 보냄", t1)
    out = {"fu": fu, "rule_sql": rule_sql, "rule_params": rule_params}
    if not rule_sql and it.clarify:
        if st:
            st.pending = it.clarify["slot"]
        steps.add("되묻기", "clarify", it.clarify["question"])
        return {**out, **_end(state, "clarify", clarify=it.clarify, answer_text=it.clarify["question"], version=it.version)}
    if not it.version:
        steps.add("준비", "fail", "적재된 버전이 없습니다")
        return {**out, **_end(state, "error", answer_text="적재된 버전이 없습니다.")}
    return out


def prepare(state: ChatState) -> dict:
    from .service import _conditions
    con, it, st, fu = state["con"], state["it"], state["st"], state.get("fu")
    params = interpret.base_params(con, it)
    vrow = next((dict(r) for r in con.execute("SELECT version, kind, month, label FROM dim_version WHERE version=?", (it.version,))), None)
    basis = interpret.version_label(vrow)
    merged = _conditions(it, st.conditions if (st and fu) else None)
    if fu and fu.get("months") and "months" not in merged:
        merged["months"] = fu["months"]
    route = "rule" if state.get("rule_sql") else "quick" if state.get("quick_id") else "ai"
    return {"params": params, "basis": basis, "vlabel": basis, "merged": merged, "route": route, "notice": None}


def run_rule(state: ChatState) -> dict:
    params, sql, steps = dict(state["rule_params"]), state["rule_sql"], state["steps"]
    t2 = time.perf_counter()
    try:
        sqlguard.validate(sql)
        res = _run_sql(state, sql, params)
    except sqlguard.SqlRejected as e:
        steps.add("실행", "fail", f"{e.code}: {e.message}", t2)
        return _end(state, "error", answer_text="후속 질문을 규칙으로 실행하지 못했습니다: " + e.message, version=state["it"].version)
    steps.add("실행", "ok", f"{res.row_count}행 · {res.seconds}초", t2)
    state["flags"]["handled"] = "rule"
    return {"params": params, "sql": sql, "res": res, "sql_source": "rule"}


def run_quick(state: ChatState) -> dict:
    steps = state["steps"]
    q = quick.BY_ID.get(state["quick_id"])
    if not q:
        return _end(state, "error", answer_text=f"알 수 없는 예시 질문입니다: {state['quick_id']}")
    sql, extra = quick.render(q, state["it"].orgs)
    params = {**state["params"], **extra}
    t1 = time.perf_counter()
    steps.add("SQL 생성", "ok", f"예시 질문 '{q['label']}' 고정 SQL(AI 호출 없음)", t1)
    sqlguard.validate(sql)
    steps.add("SQL 검사", "ok", "SELECT 한 문장 · 허용 테이블/함수 · version 조건 확인", t1)
    t2 = time.perf_counter()
    res = _run_sql(state, sql, params)
    steps.add("실행", "ok", f"{res.row_count}행 · {res.seconds}초", t2)
    state["flags"]["handled"] = "quick"
    return {"params": params, "sql": sql, "res": res, "sql_source": "quick"}


def ai_cached(state: ChatState) -> dict:
    """같은 질문의 저장된 SQL 이 있으면 재사용(AI 호출 없음). 없거나 무효면 AI 로 만든다."""
    s, st, it, steps, budget = state["settings"], state["st"], state["it"], state["steps"], state["budget"]
    ctx = None
    if st and st.conditions and len(state["question"].strip()) <= followup.SHORT:
        ctx = {k: v for k, v in st.conditions.items() if k in ("org_names", "year", "months", "version", "metric", "scenario")}
    key = nl2sql.sql_cache_key(s, state["question"], {**it.values(), "context": ctx or None})
    out = {"ctx": ctx, "cache_key": key, "attempt": 0, "prev": None}
    cached = nl2sql.cache_get(s, key)
    if cached and cached.get("sql"):
        t0 = time.perf_counter()
        try:
            sqlguard.validate(cached["sql"])
            res = _run_sql(state, cached["sql"], state["params"])
            budget.cache_hits += 1
            steps.add("SQL 생성", "ok", "같은 질문의 저장된 SQL 을 재사용(AI 호출 없음)", t0)
            steps.add("SQL 검사", "ok", "SELECT 한 문장 · 허용 테이블/함수 · version 조건 확인", t0)
            steps.add("실행", "ok", f"{res.row_count}행 · {res.seconds}초", t0)
            state["flags"]["handled"], state["flags"]["cache"] = "ai", True
            return {**out, "ai_kind": "ok", "sql": cached["sql"], "res": res, "sql_source": "cache"}
        except sqlguard.SqlRejected:
            steps.add("SQL 생성", "skip", "저장된 SQL 이 더 이상 유효하지 않아 새로 만듭니다")
    why = nl2sql.ai_ready(s, budget)
    if why:
        budget.add_reason(nl2sql.reason_ko(why))
        steps.add("SQL 생성", "skip", f"AI 를 쓸 수 없음: {nl2sql.reason_ko(why)}")
        return {**out, "ai_kind": "fallback"}
    return {**out, "ai_kind": "generate"}


def ai_generate(state: ChatState) -> dict:
    from .service import BLOCK_TEXT, _clean_ai_clarify
    s, it, steps, budget, attempt = state["settings"], state["it"], state["steps"], state["budget"], state["attempt"]
    t0 = time.perf_counter()
    try:
        resp = nl2sql.generate_sql(s, state["question"], it.values(), list(nl2sql.schema.PARAMS), budget, previous=state.get("prev"),
                                   context=state.get("ctx"))
    except nl2sql.AiUnavailable as e:
        budget.add_reason(nl2sql.reason_ko(e.reason))
        steps.add("SQL 생성", "fail", f"AI 호출 실패: {nl2sql.reason_ko(e.reason)}", t0)
        return {"ai_kind": "fallback"}
    if resp.get("unknown"):
        msg = str(resp["unknown"])
        steps.add("SQL 생성", "unknown", f"AI 판단: {msg}", t0)
        if "조회만 가능" in msg:
            return {"ai_kind": "blocked", **_end(state, "blocked", answer_text=BLOCK_TEXT["write"])}
        return {"ai_kind": "unknown", **_end(state, "unknown", answer_text=msg, version=it.version)}
    cl = resp.get("clarify")
    if isinstance(cl, dict) and cl.get("question"):
        cleaned = _clean_ai_clarify(state["con"], cl)
        steps.add("되묻기", "clarify", "AI 가 질문이 모호하다고 판단(선택지는 DB 값만 사용)", t0)
        if state["st"]:
            state["st"].pending = cleaned["slot"]
        return {"ai_kind": "clarify", **_end(state, "clarify", clarify=cleaned, answer_text=cleaned["question"], version=it.version)}
    steps.add("SQL 생성" if attempt == 0 else f"자기 수정 {attempt}회차", "ok", "AI 가 SQL 을 작성", t0)
    return {"ai_kind": "check", "ai_sql": resp.get("sql")}


def ai_check(state: ChatState) -> dict:
    """검사·실행. 실패하면 오류를 붙여 SQL 생성으로 돌아간다(그래프 순환). 횟수를 넘기면 기본 질의로."""
    s, steps, sql, attempt = state["settings"], state["steps"], state["ai_sql"], state["attempt"]
    t1 = time.perf_counter()
    try:
        sqlguard.validate(sql)
        steps.add("SQL 검사", "ok", "SELECT 한 문장 · 허용 테이블/함수 · version 조건 확인", t1)
        t2 = time.perf_counter()
        res = _run_sql(state, sql, state["params"])
        steps.add("실행", "ok", f"{res.row_count}행 · {res.seconds}초" + (" · 행 상한으로 잘림" if res.truncated else ""), t2)
        nl2sql.cache_put(s, state["cache_key"], {"sql": sql})
        state["flags"]["fixes"], state["flags"]["handled"] = attempt, "ai"
        return {"ai_kind": "ok", "sql": sql.strip(), "res": res, "sql_source": "ai"}
    except sqlguard.SqlRejected as e:
        steps.add("SQL 검사" if e.code in GUARD_STEP_CODES else "실행", "fail", f"{e.code}: {e.message}", t1)
        if attempt < s.chat_max_fix:
            return {"ai_kind": "retry", "attempt": attempt + 1, "prev": (str(sql), e.message)}
        steps.add("자기 수정", "fail", f"{s.chat_max_fix}번 고쳐도 통과하지 못함")
        state["flags"]["fixes"] = s.chat_max_fix
        return {"ai_kind": "fallback"}


def fallback_quick(state: ChatState) -> dict:
    """AI 없이 예시 질문과 맞으면 그 고정 SQL 로 답한다."""
    steps, it, params = state["steps"], state["it"], state["params"]
    q = quick.match(state["question"])
    if not q:
        steps.add("기본 질의", "clarify", "AI 없이 답할 수 있는 예시 질문과 맞지 않음")
        return _end(state, "clarify", answer_text="지금은 AI 를 쓸 수 없어 아래 예시 질문만 답할 수 있습니다. 하나를 골라 주세요.",
                    clarify={"question": "어떤 조회를 할까요?", "options": [x["label"] for x in quick.public(params.get("base_month"), (it.org_names or [None])[0])],
                             "slot": "quick", "quick_ids": [x["id"] for x in quick.QUICK]},
                    version=it.version, notice="AI 없이 기본 질의")
    sql, extra = quick.render(q, it.orgs)
    params = {**params, **extra}
    t1 = time.perf_counter()
    try:
        sqlguard.validate(sql)
        res = _run_sql(state, sql, params)
    except sqlguard.SqlRejected as e:
        steps.add("기본 질의", "fail", f"{e.code}: {e.message}", t1)
        return _end(state, "error", answer_text="기본 질의를 실행하지 못했습니다: " + e.message)
    steps.add("기본 질의", "ok", f"AI 없이 예시 질문 '{q['label']}' 고정 SQL · {res.row_count}행 · {res.seconds}초", t1)
    state["flags"]["handled"] = "quick"
    return {"params": params, "sql": sql, "res": res, "sql_source": "quick", "notice": "AI 없이 기본 질의"}


def explain(state: ChatState) -> dict:
    """설명 문장. AI 문장은 숫자가 모두 조회 결과에 있을 때만 쓰고, 아니면 기본문구."""
    s, res, steps, budget, it = state["settings"], state["res"], state["steps"], state["budget"], state["it"]
    t3 = time.perf_counter()
    preview = res.rows[:20]
    text, text_source = None, "template"
    if res.row_count == 0:
        text = "해당 데이터가 없습니다."
        steps.add("문장", "ok", "결과 0행 → 고정 문장", t3)
    elif state["sql_source"] in ("rule",) or (state["sql_source"] == "quick" and state.get("notice")):
        text = nl2sql.template_text(res.columns, res.rows, res.row_count, res.truncated)
        steps.add("문장", "ok", "AI 를 쓰지 않아 기본문구(숫자는 결과에서 계산)", t3)
    else:
        why = nl2sql.ai_ready(s, budget)
        if why:
            budget.add_reason(nl2sql.reason_ko(why))
            text = nl2sql.template_text(res.columns, res.rows, res.row_count, res.truncated)
            steps.add("문장", "skip", f"AI 를 쓸 수 없음({nl2sql.reason_ko(why)}) → 기본문구", t3)
        else:
            try:
                ai_text = nl2sql.generate_text(s, state["question"], res.columns, preview, res.row_count, res.truncated, budget)
                bad = nl2sql.validate_text(ai_text, nl2sql.allowed_numbers(res.columns, res.rows, state["question"], it.values(), res.row_count))
                if bad:
                    budget.add_reason("문장 검증 실패 → 기본문구(" + bad[0].split(":")[0] + ")")
                    text = nl2sql.template_text(res.columns, res.rows, res.row_count, res.truncated)
                    steps.add("문장 검증", "fail", f"{'; '.join(bad)} → 기본문구로 대체", t3)
                else:
                    text, text_source = ai_text.strip(), "ai"
                    steps.add("문장 검증", "ok", "문장 속 숫자가 모두 조회 결과(억원 환산 포함)에 있음", t3)
            except nl2sql.AiUnavailable as e:
                budget.add_reason(nl2sql.reason_ko(e.reason))
                text = nl2sql.template_text(res.columns, res.rows, res.row_count, res.truncated)
                steps.add("문장", "fail", f"AI 호출 실패({nl2sql.reason_ko(e.reason)}) → 기본문구", t3)
    return {"text": text, "text_source": text_source}


def collect_evidence(state: ChatState) -> dict:
    """조회 응답의 출처만 구성하고 파일 내보내기는 백엔드에 맡긴다.

    Args:
        state: 조회 결과·버전·기준 설명을 가진 그래프 상태.
    Returns:
        근거 목록 ev와 파일 없음(file=None).
    Raises:
        KeyError: 내부 그래프 필수 상태가 누락된 경우.
    Side Effects:
        없음. 파일 생성이나 별도 저장을 하지 않는다.
    """
    from .service import _evidence
    res, it = state["res"], state["it"]
    notice = "파일은 보고서 화면의 내보내기에서 XLSX로 받을 수 있습니다." if (state.get("fu") or {}).get("kind") == "excel" else state.get("notice")
    return {"ev": _evidence(it.version, state["basis"], res.columns, res.rows), "file": None, "notice": notice}


def respond(state: ChatState) -> dict:
    """기억 갱신(조건·직전 SQL·열 이름·행 수만. 질문 원문과 결과 값은 저장하지 않는다)과 응답."""
    from .service import _jsonable, _response
    st, res, merged, params, it = state["st"], state["res"], state["merged"], state["params"], state["it"]
    if st is not None:
        st.conditions = merged
        st.pending = None
        st.last_sql, st.last_params, st.last_columns, st.last_rows = state["sql"], dict(params), list(res.columns), res.row_count
        st.turns.append({k: v for k, v in merged.items() if k in ("org_names", "year", "months", "metric")})
    scope = "전사 기준" if not merged.get("orgs") else None
    preview = res.rows[:20]
    return {"resp": _response("ok", answer_text=state["text"], answer_source=state["text_source"], sql_source=state["sql_source"], sql=state["sql"],
                              params=params, version=it.version, basis=state["basis"], scope=scope, steps=state["steps"].items, columns=res.columns,
                              rows_preview=[[_jsonable(v) for v in r] for r in preview], row_count=res.row_count, truncated=res.truncated,
                              evidence=state["ev"], file=state["file"], notice=state.get("notice"))}


# ------------------------------------------------------------------ 그래프
def build():
    """읽기 전용 질의·검사·재시도·근거 응답의 그래프를 구성한다.

    Args:
        없음.
    Returns:
        파일 내보내기 노드가 없는 컴파일된 LangGraph.
    Raises:
        ValueError: 그래프 연결이 유효하지 않은 경우.
    Side Effects:
        메모리에 그래프를 구성한다. 모델·DB·파일에는 접근하지 않는다.
    """
    g = StateGraph(ChatState)
    for name, fn in (("guard", guard), ("open_db", open_db), ("understand", understand), ("follow_up", follow_up), ("prepare", prepare),
                     ("run_rule", run_rule), ("run_quick", run_quick), ("ai_cached", ai_cached), ("ai_generate", ai_generate),
                     ("ai_check", ai_check), ("fallback_quick", fallback_quick), ("explain", explain), ("evidence", collect_evidence), ("respond", respond)):
        g.add_node(name, fn)
    g.add_edge(START, "guard")
    for a, b in (("guard", "open_db"), ("open_db", "understand"), ("understand", "follow_up"), ("follow_up", "prepare")):
        g.add_conditional_edges(a, _ended, {END: END, "next": b})
    g.add_conditional_edges("prepare", lambda s: s["route"], {"rule": "run_rule", "quick": "run_quick", "ai": "ai_cached"})
    for a in ("run_rule", "run_quick", "fallback_quick"):
        g.add_conditional_edges(a, _ended, {END: END, "next": "explain"})
    g.add_conditional_edges("ai_cached", lambda s: s["ai_kind"], {"ok": "explain", "fallback": "fallback_quick", "generate": "ai_generate"})
    g.add_conditional_edges("ai_generate", lambda s: s["ai_kind"],
                            {"check": "ai_check", "fallback": "fallback_quick", "unknown": END, "blocked": END, "clarify": END})
    g.add_conditional_edges("ai_check", lambda s: s["ai_kind"], {"ok": "explain", "retry": "ai_generate", "fallback": "fallback_quick"})
    g.add_edge("explain", "evidence")
    g.add_edge("evidence", "respond")
    g.add_edge("respond", END)
    return g.compile()


GRAPH = build()
