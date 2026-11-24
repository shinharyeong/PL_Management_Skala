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

    # 흐름은 LangGraph(graph.py). 노드는 기존 단계 함수를 그대로 부르고, 끝난 경로의 응답을 resp 에 담는다.
    from .graph import GRAPH
    final: dict = {}
    try:
        final = GRAPH.invoke({"question": question, "version": version, "quick_id": quick_id, "conversation_id": conversation_id,
                              "settings": s, "steps": steps, "budget": budget, "flags": state, "resp": None},
                             config={"recursion_limit": 25 + 3 * (s.chat_max_fix + 1)})
    finally:
        con = state.pop("con", None)
        if con is not None:
            con.close()
    return finish(final["resp"], final.get("st"), final.get("vlabel") if final["resp"]["status"] == "ok" else None)


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


# ------------------------------------------------------------------ backend 계약(/v1/nl-query, 명세 12절·RPT-11)
_NLQ_STATUS = {"ok": "OK", "clarify": "CLARIFY"}


def _column_type(name: str, values: list) -> str:
    if name.endswith("_won"):
        return "MONEY"
    if "rate" in name or "percent" in name or name.endswith("_pct"):
        return "RATE"
    if values and all(isinstance(v, int) and not isinstance(v, bool) for v in values if v is not None):
        return "INTEGER"
    return "TEXT"


def nl_query(question: str, base_year: int, base_month: int, *, conversation_id: str | None = None, settings: Settings | None = None) -> dict:
    """backend RPT-11 이 부르는 text2SQL. 기준 월의 version(확정 master, 없으면 잠정)으로 run_chat(같은 그래프)을 돌려
    RPT-11 응답 모양(status·sql·explanation·preview·clarifyQuestion·clarifyOptions)으로 바꾼다. 숫자는 SQL 결과 그대로."""
    s = settings or get_settings()
    empty = {"dataStatus": "NONE", "columns": [], "rows": []}
    try:
        con = db.open_readonly(s)
    except FileNotFoundError as e:
        return {"status": "UNKNOWN", "sql": None, "explanation": str(e), "clarifyQuestion": None, "clarifyOptions": [], "preview": empty,
                "rowCount": 0, "truncated": False}
    try:
        row = con.execute("SELECT version, kind FROM dim_version WHERE year = ? AND month = ? "
                          "ORDER BY CASE kind WHEN 'master' THEN 0 WHEN 'provisional' THEN 1 ELSE 2 END LIMIT 1", (base_year, base_month)).fetchone()
    finally:
        con.close()
    if not row:
        return {"status": "UNKNOWN", "sql": None, "explanation": f"{base_year}년 {base_month}월 데이터가 없습니다.", "clarifyQuestion": None,
                "clarifyOptions": [], "preview": empty, "rowCount": 0, "truncated": False}
    r = run_chat(question, version=row["version"], conversation_id=conversation_id, settings=s)
    status = _NLQ_STATUS.get(r["status"], "UNKNOWN")
    clarify = r.get("clarify") or {}
    columns = list(r.get("columns") or [])
    rows = r.get("rows_preview") or []
    preview = {"dataStatus": "CONFIRMED" if row["kind"] == "master" else "PROVISIONAL" if status == "OK" else "NONE",
               "columns": [{"key": c, "label": c, "type": _column_type(c, [x[i] for x in rows])} for i, c in enumerate(columns)],
               "rows": [dict(zip(columns, x)) for x in rows]}
    return {"status": status, "sql": r.get("sql") if status == "OK" else None, "explanation": r.get("answer_text"),
            "clarifyQuestion": clarify.get("question") if status == "CLARIFY" else None,
            "clarifyOptions": list(clarify.get("options") or []) if status == "CLARIFY" else [],
            "preview": preview, "rowCount": int(r.get("row_count") or 0), "truncated": bool(r.get("truncated"))}
