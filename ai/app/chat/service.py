"""POST /v1/chat 처리 순서: 차단 확인 → 해석 → (후속 질문 규칙) → 되묻기 → SQL 생성(AI, 캐시) → 검사 → 자기 수정 → 읽기 전용 실행 → 설명 문장 → 엑셀.
- 조직이 없으면 전사 합산으로 답한다("전사 기준"). 되묻기는 (a) 누구인지 모르는 지칭 (b) 비교 대상 없음 (c) 같은 이름이 둘 이상일 때만 한다.
- 대화 기억(CHAT_MEMORY)이 켜져 있으면 짧은 후속 질문은 AI 없이 규칙으로 이전 조건에 덮어쓴다(memory.py: 서버 메모리에만, 질문 원문·결과 값은 저장하지 않음).
- AI 를 못 쓰면(키 없음·상한 초과·실패) 예시 질문의 고정 SQL 로 대신 답한다. steps 에는 실제로 거친 단계만 기록한다."""
from __future__ import annotations

import hashlib
import json
import re
import time
import uuid
from datetime import datetime
from pathlib import Path

from ..config import Settings, get_settings
from ..services.call_log import CallLog
from . import db, excel_out, followup, interpret, memory, nl2sql, quick, sqlguard

FILE_ID_RE = re.compile(r"^[0-9a-f]{16}$")
BLOCK_TEXT = {"write": "조회만 가능합니다. 데이터를 지우거나 바꾸는 요청은 처리하지 않아요. 조회하고 싶은 내용을 알려 주세요.",
              "sql": "조회만 가능합니다. 데이터를 바꾸거나 지우는 SQL 은 실행하지 않았어요(SELECT 조회만 허용). 조회하고 싶은 내용을 알려 주세요."}


class Steps:
    def __init__(self):
        self.items: list[dict] = []

    def add(self, name: str, status: str, detail: str, t0: float | None = None, **extra) -> None:
        self.items.append({"step": name, "status": status, "detail": detail, "ms": round((time.perf_counter() - t0) * 1000) if t0 else None, **extra})


def _jsonable(v):
    return v if isinstance(v, (int, float, str, type(None))) else str(v)


def _stamp(con) -> str:
    """조회 DB 적재 상태(버전 목록과 적재 시각). 바뀌면 대화 기억을 비운다(보고서 실행·버전이 바뀐 경우)."""
    rows = [tuple(r) for r in con.execute("SELECT version, loaded_at FROM dim_version ORDER BY version")]
    return hashlib.sha256(json.dumps(rows).encode()).hexdigest()[:12]


def status(s: Settings | None = None) -> dict:
    """서버·DB·AI 상태(상단 점 3개)와 DB 범위, 예시 질문."""
    s = s or get_settings()
    used = CallLog(s.log_dir or s.output_dir).calls_today()
    ai = {"key_present": s.key_present, "model": s.openai_model, "daily_limit": s.ai_daily_call_limit, "used_today": used, "remaining_today": max(0, s.ai_daily_call_limit - used),
          "state": "no_key" if not s.key_present else ("limit" if used >= s.ai_daily_call_limit else "ready")}
    try:
        con = db.open_readonly(s)
    except FileNotFoundError as e:
        return {"db_ready": False, "message": str(e), "versions": [], "ai": ai, "quick": quick.public(), "memory": s.chat_memory}
    try:
        vs = db.versions(con)
        rng = con.execute("SELECT COUNT(DISTINCT org_code) AS orgs, MIN(month) AS m1, MAX(month) AS m2, COUNT(*) AS n FROM fact").fetchone()
        dv = interpret.default_version(sorted(vs, key=lambda v: (v["year"], v["month"])))
        lv = next((v for v in vs if v["version"] == dv), None)
        names = [r["org_name"] for r in con.execute("SELECT org_name FROM dim_org ORDER BY org_code")]
        return {"db_ready": True, "versions": vs, "default_version": dv, "default_basis": interpret.version_label(lv), "latest_year": lv["year"] if lv else None, "latest_month": lv["month"] if lv else None,
                "org_samples": names[:2], "org_names": names, "orgs": rng["orgs"], "months": [rng["m1"], rng["m2"]], "fact_rows": rng["n"], "ai": ai, "stamp": _stamp(con),
                "quick": quick.public(lv["month"] if lv else None, names[0] if names else None), "memory": s.chat_memory}
    finally:
        con.close()


def _evidence(version: str, label: str | None, columns: list[str], rows: list[tuple]) -> list[dict]:
    ev = [{"type": "version", "version": version, "ref": label or version}]
    idx = {c: i for i, c in enumerate(columns)}
    seen = set()
    for key, typ in (("gap_id", "gap"), ("explanation_id", "explanation"), ("src", "src")):
        if key in idx:
            for r in rows:
                v = r[idx[key]]
                if v and (typ, v) not in seen and len(seen) < 60:
                    seen.add((typ, v))
                    ev.append({"type": typ, "version": version, "ref": v if typ != "src" else None, "src": v if typ == "src" else None})
    return ev


def _response(status_: str, **kw) -> dict:
    base = {"status": status_, "answer_text": "", "answer_source": None, "sql_source": None, "clarify": None, "sql": None, "params": {}, "version": None, "basis": None, "scope": None,
            "steps": [], "columns": [], "rows_preview": [], "row_count": 0, "truncated": False, "evidence": [], "file": None, "notice": None, "conversation_id": None,
            "memory": {"on": False, "chips": []}, "handled_by": None, "followup": False, "ai": nl2sql.Budget().public(), "metrics": {}}
    base.update(kw)
    return base


def _run_sql(con, sql: str, params: dict, s: Settings) -> sqlguard.Result:
    return sqlguard.execute(con, sql, params, row_limit=s.chat_row_limit, timeout_s=s.chat_sql_timeout_seconds)


def _clean_ai_clarify(con, cl: dict) -> dict:
    """AI 가 만든 되묻기를 정리한다: 조직을 고르는 질문이면 선택지는 DB(dim_org)의 실제 이름만 쓰고, DB 에 없는 선택지는 버린다(없으면 DB 의 전체 이름). '조직 코드' 같은 문구는 이름으로 바꾼다."""
    names = [r["org_name"] for r in con.execute("SELECT org_name FROM dim_org ORDER BY org_code")]
    q = str(cl.get("question") or "").replace("조직 코드", "조직 이름").replace("코드", "이름")
    opts = [str(o) for o in (cl.get("options") or [])][:40]
    unit_words = set(interpret.GENERIC_UNIT_WORDS)
    org_related = any(w in q for w in unit_words) or any(o in names for o in opts)
    if org_related:
        valid = [o for o in opts if o in names]
        return {"question": q or "어느 조직을 말씀하시나요?", "options": valid or names, "slot": "ai_org"}
    return {"question": q, "options": opts[:6], "slot": "ai"}


def _ai_sql(s, con, question, interp, params, budget, steps, context=None) -> dict:
    """AI(또는 캐시)로 SQL 을 만들고 검사·실행·자기 수정까지 한다. 반환 kind: ok / clarify / unknown / fallback."""
    values = interp.values()
    key = nl2sql.sql_cache_key(s, question, {**values, "context": context or None})
    cached = nl2sql.cache_get(s, key)
    if cached and cached.get("sql"):
        t0 = time.perf_counter()
        try:
            sqlguard.validate(cached["sql"])
            res = _run_sql(con, cached["sql"], params, s)
            budget.cache_hits += 1
            steps.add("SQL 생성", "ok", "같은 질문의 저장된 SQL 을 재사용(AI 호출 없음)", t0)
            steps.add("SQL 검사", "ok", "SELECT 한 문장 · 허용 테이블/함수 · version 조건 확인", t0)
            steps.add("실행", "ok", f"{res.row_count}행 · {res.seconds}초", t0)
            return {"kind": "ok", "sql": cached["sql"], "result": res, "source": "cache", "fixes": 0}
        except sqlguard.SqlRejected:
            steps.add("SQL 생성", "skip", "저장된 SQL 이 더 이상 유효하지 않아 새로 만듭니다")
    why = nl2sql.ai_ready(s, budget)
    if why:
        budget.add_reason(nl2sql.reason_ko(why))
        steps.add("SQL 생성", "skip", f"AI 를 쓸 수 없음: {nl2sql.reason_ko(why)}")
        return {"kind": "fallback"}
    prev = None
    for attempt in range(s.chat_max_fix + 1):
        t0 = time.perf_counter()
        try:
            resp = nl2sql.generate_sql(s, question, values, list(nl2sql.schema.PARAMS), budget, previous=prev, context=context)
        except nl2sql.AiUnavailable as e:
            budget.add_reason(nl2sql.reason_ko(e.reason))
            steps.add("SQL 생성", "fail", f"AI 호출 실패: {nl2sql.reason_ko(e.reason)}", t0)
            return {"kind": "fallback"}
        if resp.get("unknown"):
            msg = str(resp["unknown"])
            steps.add("SQL 생성", "unknown", f"AI 판단: {msg}", t0)
            return {"kind": "blocked" if "조회만 가능" in msg else "unknown", "message": msg}
        cl = resp.get("clarify")
        if isinstance(cl, dict) and cl.get("question"):
            cleaned = _clean_ai_clarify(con, cl)
            steps.add("되묻기", "clarify", "AI 가 질문이 모호하다고 판단(선택지는 DB 값만 사용)", t0)
            return {"kind": "clarify", "clarify": cleaned}
        sql = resp.get("sql")
        steps.add("SQL 생성" if attempt == 0 else f"자기 수정 {attempt}회차", "ok", "AI 가 SQL 을 작성", t0)
        t1 = time.perf_counter()
        try:
            sqlguard.validate(sql)
            steps.add("SQL 검사", "ok", "SELECT 한 문장 · 허용 테이블/함수 · version 조건 확인", t1)
            t2 = time.perf_counter()
            res = _run_sql(con, sql, params, s)
            steps.add("실행", "ok", f"{res.row_count}행 · {res.seconds}초" + (" · 행 상한으로 잘림" if res.truncated else ""), t2)
            nl2sql.cache_put(s, key, {"sql": sql})
            return {"kind": "ok", "sql": sql.strip(), "result": res, "source": "ai", "fixes": attempt}
        except sqlguard.SqlRejected as e:
            steps.add("SQL 검사" if e.code in ("EMPTY", "MULTI_OR_SEMICOLON", "COMMENT", "NOT_SELECT", "FORBIDDEN_KEYWORD", "RECURSIVE", "TABLE_NOT_ALLOWED", "NO_VERSION_FILTER", "VERSION_LITERAL") else "실행",
                      "fail", f"{e.code}: {e.message}", t1)
            prev = (str(sql), e.message)
    steps.add("자기 수정", "fail", f"{s.chat_max_fix}번 고쳐도 통과하지 못함")
    return {"kind": "fallback", "fixes": s.chat_max_fix}


def _conditions(it, base: dict | None = None) -> dict:
    c = dict(base or {})
    if it.orgs:
        c["orgs"], c["org_names"] = list(it.orgs), list(it.org_names)
    if it.months:
        c["months"] = list(it.months)
    if it.year:
        c["year"] = it.year
    if it.version_explicit:
        c["version"], c["version_explicit"] = it.version, True
    if it.metric:
        c["metric"] = it.metric
    if it.scenario != "ACTUAL":
        c["scenario"] = it.scenario
    return c


def _test_log(s: Settings, entry: dict) -> None:
    """CHAT_TEST_LOG=1 일 때만 질문마다 한 줄을 남긴다. 질문 원문·키·결과 값은 남기지 않는다."""
    if not s.chat_test_log:
        return
    p = s.ai_cache_dir / "chat" / "test_log.jsonl"
    p.parent.mkdir(parents=True, exist_ok=True)
    with p.open("a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def run_chat(question: str, *, version: str | None = None, quick_id: str | None = None, conversation_id: str | None = None, settings: Settings | None = None) -> dict:
    s = settings or get_settings()
    steps, budget = Steps(), nl2sql.Budget()
    t_all = time.perf_counter()
    state = {"followup": False, "handled": "none", "fixes": 0, "cache": False}

    def finish(resp: dict, st: memory.State | None, vlabel: str | None = None) -> dict:
        resp["conversation_id"] = conversation_id
        resp["memory"] = {"on": s.chat_memory, "chips": memory.chips(st, vlabel)}
        resp["followup"] = state["followup"]
        resp["handled_by"] = state["handled"] if resp["status"] in ("ok",) else resp["status"]
        a = budget.public()
        resp["ai"] = a
        resp["metrics"] = {"handled_by": resp["handled_by"], "ai_calls": a["calls"], "input_tokens": a["input_tokens"], "output_tokens": a["output_tokens"], "fix_count": state["fixes"],
                           "cache_hit": bool(a["cache_hits"]), "seconds": round(time.perf_counter() - t_all, 2)}
        resp["seconds"] = resp["metrics"]["seconds"]
        _test_log(s, {"ts": datetime.now().isoformat(timespec="seconds"), "conversation_id": conversation_id, "followup": state["followup"],
                      "handled": "ai" if a["calls"] else ("rule" if state["handled"] == "rule" else "none"), "ai_calls": a["calls"], "input_tokens": a["input_tokens"],
                      "output_tokens": a["output_tokens"], "fix_count": state["fixes"], "cache_hit": bool(a["cache_hits"]), "row_count": resp["row_count"],
                      "seconds": resp["metrics"]["seconds"], "status": resp["status"], "memory": s.chat_memory})
        return resp

    br = interpret.blocked_reason(question)
    if br:                                                            # 데이터를 바꾸거나 지우라는 요청: SQL 을 만들지 않고 정중히 거절(오류가 아닌 정상 응답)
        steps.add("해석", "blocked", "데이터를 바꾸거나 지우는 요청(조회만 가능)")
        return finish(_response("blocked", answer_text=BLOCK_TEXT[br], steps=steps.items), None)
    try:
        con = db.open_readonly(s)
    except FileNotFoundError as e:
        steps.add("준비", "fail", str(e))
        return finish(_response("error", answer_text=str(e), steps=steps.items, notice="조회 DB 가 비어 있습니다. '최신 실행 불러오기'를 눌러 주세요."), None)
    try:
        st = memory.get(conversation_id, _stamp(con), on=s.chat_memory)
        t0 = time.perf_counter()
        it = interpret.interpret(con, question, version=version)
        steps.add("해석", "ok" if not (it.unknown or it.clarify) else ("unknown" if it.unknown else "clarify"),
                  f"조직 {it.org_names or '없음(전사 합산)'} · 월 {it.months or '없음'} · 버전 {it.version or '없음'}({it.version_note}) · 시나리오 {it.scenario}", t0)
        if it.unknown:
            return finish(_response("unknown", answer_text=it.unknown, version=it.version, steps=steps.items), st)
        # ---- 짧은 후속 질문: AI 없이 규칙으로 이전 조건에 덮어쓴다
        fu = followup.detect(question, it, st) if st else None
        rule_sql = rule_params = None
        if fu:
            state["followup"] = True
            t1 = time.perf_counter()
            applied = followup.apply(st, fu)
            if applied:
                rule_sql, rule_params = applied
                it.version = rule_params.get("version", it.version)                    # 이전 대화의 버전을 이어 쓴다
                steps.add("후속 질문", "ok", f"규칙으로 이전 조건에 덮어씀({fu['kind']}) · AI 호출 없음", t1)
            else:
                steps.add("후속 질문", "skip", f"규칙으로 바꿀 수 없어({fu['kind']}) 이전 조건을 SQL 생성 AI 에 함께 보냄", t1)
        if not rule_sql and it.clarify:
            if st:
                st.pending = it.clarify["slot"]
            steps.add("되묻기", "clarify", it.clarify["question"])
            return finish(_response("clarify", clarify=it.clarify, answer_text=it.clarify["question"], version=it.version, steps=steps.items), st)
        if not it.version:
            steps.add("준비", "fail", "적재된 버전이 없습니다")
            return finish(_response("error", answer_text="적재된 버전이 없습니다.", steps=steps.items), st)
        params = interpret.base_params(con, it)
        vrow = next((dict(r) for r in con.execute("SELECT version, kind, month, label FROM dim_version WHERE version=?", (it.version,))), None)
        basis = interpret.version_label(vrow)
        vlabel = basis
        merged = _conditions(it, st.conditions if (st and fu) else None)
        if fu and fu.get("months") and "months" not in merged:
            merged["months"] = fu["months"]

        sql = res = None
        sql_source = None
        notice = None
        if rule_sql:
            params = dict(rule_params)
            t2 = time.perf_counter()
            sql = rule_sql
            try:
                sqlguard.validate(sql)
                res = _run_sql(con, sql, params, s)
            except sqlguard.SqlRejected as e:
                steps.add("실행", "fail", f"{e.code}: {e.message}", t2)
                return finish(_response("error", answer_text="후속 질문을 규칙으로 실행하지 못했습니다: " + e.message, steps=steps.items, version=it.version), st)
            steps.add("실행", "ok", f"{res.row_count}행 · {res.seconds}초", t2)
            sql_source, state["handled"] = "rule", "rule"
        elif quick_id:
            q = quick.BY_ID.get(quick_id)
            if not q:
                return finish(_response("error", answer_text=f"알 수 없는 예시 질문입니다: {quick_id}", steps=steps.items), st)
            sql, extra = quick.render(q, it.orgs)
            params = {**params, **extra}
            t1 = time.perf_counter()
            steps.add("SQL 생성", "ok", f"예시 질문 '{q['label']}' 고정 SQL(AI 호출 없음)", t1)
            sqlguard.validate(sql)
            steps.add("SQL 검사", "ok", "SELECT 한 문장 · 허용 테이블/함수 · version 조건 확인", t1)
            t2 = time.perf_counter()
            res = _run_sql(con, sql, params, s)
            steps.add("실행", "ok", f"{res.row_count}행 · {res.seconds}초", t2)
            sql_source, state["handled"] = "quick", "quick"
        else:
            ctx = None
            if st and st.conditions and len(question.strip()) <= followup.SHORT:
                ctx = {k: v for k, v in st.conditions.items() if k in ("org_names", "year", "months", "version", "metric", "scenario")}
            r = _ai_sql(s, con, question, it, params, budget, steps, context=ctx)
            state["fixes"] = r.get("fixes", 0)
            if r["kind"] == "unknown":
                return finish(_response("unknown", answer_text=r["message"], version=it.version, steps=steps.items), st)
            if r["kind"] == "blocked":
                return finish(_response("blocked", answer_text=BLOCK_TEXT["write"], steps=steps.items), st)
            if r["kind"] == "clarify":
                if st:
                    st.pending = r["clarify"]["slot"]
                return finish(_response("clarify", clarify=r["clarify"], answer_text=r["clarify"]["question"], version=it.version, steps=steps.items), st)
            if r["kind"] == "ok":
                sql, res, sql_source = r["sql"], r["result"], r["source"]
                state["handled"] = "ai"
                state["cache"] = r["source"] == "cache"
            else:                                                       # AI 없이 기본 질의
                q = quick.match(question)
                if not q:
                    steps.add("기본 질의", "clarify", "AI 없이 답할 수 있는 예시 질문과 맞지 않음")
                    return finish(_response("clarify", answer_text="지금은 AI 를 쓸 수 없어 아래 예시 질문만 답할 수 있습니다. 하나를 골라 주세요.",
                                            clarify={"question": "어떤 조회를 할까요?", "options": [x["label"] for x in quick.public(params.get("base_month"), (it.org_names or [None])[0])],
                                                     "slot": "quick", "quick_ids": [x["id"] for x in quick.QUICK]},
                                            version=it.version, steps=steps.items, notice="AI 없이 기본 질의"), st)
                sql, extra = quick.render(q, it.orgs)
                params = {**params, **extra}
                t1 = time.perf_counter()
                try:
                    sqlguard.validate(sql)
                    res = _run_sql(con, sql, params, s)
                except sqlguard.SqlRejected as e:
                    steps.add("기본 질의", "fail", f"{e.code}: {e.message}", t1)
                    return finish(_response("error", answer_text="기본 질의를 실행하지 못했습니다: " + e.message, steps=steps.items), st)
                steps.add("기본 질의", "ok", f"AI 없이 예시 질문 '{q['label']}' 고정 SQL · {res.row_count}행 · {res.seconds}초", t1)
                sql_source, notice, state["handled"] = "quick", "AI 없이 기본 질의", "quick"

        # ---- 설명 문장
        t3 = time.perf_counter()
        preview = res.rows[:20]
        text, text_source = None, "template"
        if res.row_count == 0:
            text = "해당 데이터가 없습니다."
            steps.add("문장", "ok", "결과 0행 → 고정 문장", t3)
        elif sql_source in ("rule",) or (sql_source == "quick" and notice):
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
                    ai_text = nl2sql.generate_text(s, question, res.columns, preview, res.row_count, res.truncated, budget)
                    bad = nl2sql.validate_text(ai_text, nl2sql.allowed_numbers(res.columns, res.rows, question, it.values(), res.row_count))
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

        # ---- 엑셀
        file = None
        ev = _evidence(it.version, basis, res.columns, res.rows)
        if res.row_count > 0:
            t4 = time.perf_counter()
            fid = uuid.uuid4().hex[:16]
            name = f"chat_{it.version}_{datetime.now().strftime('%Y%m%d_%H%M%S')}.xlsx"
            path = db.files_dir(s) / f"{fid}.xlsx"
            info = excel_out.build_excel(path, question=question, version=basis or it.version, params=params, columns=res.columns, rows=res.rows, sql=sql, ai=budget.public(), answer_text=text,
                                         answer_source=text_source, evidence=ev, truncated=res.truncated)
            (db.files_dir(s) / f"{fid}.json").write_text(json.dumps({"name": name}, ensure_ascii=False), encoding="utf-8")
            file = {"id": fid, "name": name, "url": f"/v1/chat/files/{fid}"}
            steps.add("엑셀", "ok", f"시트 {', '.join(info['sheets'])} · {info['rows']}행" + (" · 차트 포함" if info["chart"] else ""), t4)
        # ---- 기억 갱신(조건·직전 SQL·열 이름·행 수만. 질문 원문과 결과 값은 저장하지 않는다)
        if st is not None:
            st.conditions = merged
            st.pending = None
            st.last_sql, st.last_params, st.last_columns, st.last_rows = sql, {k: v for k, v in params.items()}, list(res.columns), res.row_count
            st.turns.append({k: v for k, v in merged.items() if k in ("org_names", "year", "months", "metric")})
        scope = "전사 기준" if not merged.get("orgs") else None
        out = _response("ok", answer_text=text, answer_source=text_source, sql_source=sql_source, sql=sql, params=params, version=it.version, basis=basis, scope=scope, steps=steps.items,
                        columns=res.columns, rows_preview=[[_jsonable(v) for v in r] for r in preview], row_count=res.row_count, truncated=res.truncated, evidence=ev, file=file, notice=notice)
        return finish(out, st, vlabel)
    finally:
        con.close()


def reset_memory(conversation_id: str | None) -> None:
    memory.reset(conversation_id)


def clear_conditions(conversation_id: str | None, keys: list[str]) -> dict:
    st = memory.clear_keys(conversation_id, keys)
    return {"chips": memory.chips(st, None), "on": get_settings().chat_memory}


def get_file(file_id: str, s: Settings | None = None) -> tuple[Path, str] | None:
    if not FILE_ID_RE.match(file_id or ""):
        return None
    s = s or get_settings()
    p = db.files_dir(s) / f"{file_id}.xlsx"
    if not p.exists():
        return None
    try:
        name = json.loads((db.files_dir(s) / f"{file_id}.json").read_text(encoding="utf-8"))["name"]
    except (OSError, ValueError, KeyError):
        name = f"chat_{file_id}.xlsx"
    return p, name
