"""PDF 표의 행 이름이 DB 지표 사전에 바로 맞지 않을 때, 어느 지표를 뜻하는지 AI 가 추측한다.

AI 는 '행 이름 → 지표 이름'만 고른다(숫자는 만들지 않는다). 코드는 고른 지표가 지표 사전에 있는지만 확인하고, 값은 항상 DB 에서 계산한다.
확신하지 못하면 null → 그 행은 '데이터 없음'. 추측한 행은 화면에 ⓘ 로 추측임을 표시한다. 키 없음·한도 초과·오류면 추측하지 않는다.
※ 현재 화면에서는 쓰지 않는다(AI 추측은 화면에서 뺐고, 나중을 위해 코드만 남김).
"""
from __future__ import annotations

import hashlib
import json

from ..config import get_settings
from ..services import llm, openai_client  # noqa: F401 — 테스트가 이 모듈의 openai_client 로 가짜를 끼운다
from ..services.call_log import CallLog
from . import data_select as D

PROMPT_VERSION = "row-guess-v2"
MAX_LABELS = 24
SYSTEM = """보고서 표의 행 이름이 조회 DB 의 어느 지표를 뜻하는지 추측한다.
입력은 JSON: labels(행 이름 목록)와 metrics(DB 지표 이름 목록). 출력은 JSON 하나이며, 입력 labels 의 각 행 이름을 그대로 키로 쓰고 값은 metrics 중 하나의 이름이거나 null 이다.
예) 입력 {"labels":["총수익","직원 수"],"metrics":["매출","영업비용","영업이익"]} → 출력 {"guess":{"총수익":"매출","직원 수":null}}
규칙: 값은 metrics 에 있는 이름만 쓴다. 뜻이 분명하지 않으면 null(억지로 고르지 않는다). 숫자나 새 지표를 만들지 않는다. OP·영업 이익·이익 같은 줄임말·띄어쓰기 차이는 같은 지표로 본다."""


def unknown_labels(labels: list[str]) -> list[str]:
    info = D.months()
    ms = [m for m in info["metrics"] if m in D.METRIC_COL]
    out = []
    for l in labels:
        l = str(l or "").strip()
        if l and l not in out and not D._metric_in(l, ms) and not D.match_org(l, info["orgs"]):
            out.append(l)
    return out[:MAX_LABELS]


def guess(labels: list[str]) -> dict:
    s = get_settings()
    info = D.months()
    metrics = [m for m in info["metrics"] if m in D.METRIC_COL]
    todo = unknown_labels(labels)
    res = {"guess": {}, "usage": {"calls": 0, "input_tokens": 0, "output_tokens": 0, "cached": False}, "status": {"ok": True, "message": ""}}
    if not todo:
        res["status"]["message"] = "추측할 행이 없습니다."
        return res
    key = hashlib.sha256((f"{s.openai_model}|{PROMPT_VERSION}|{','.join(metrics)}|" + json.dumps(todo, ensure_ascii=False)).encode("utf-8")).hexdigest()
    cp = s.output_dir / "cache" / "row_guess" / f"{key}.json"
    log = CallLog(s.log_dir or s.output_dir)
    meta = {"task": "row_guess", "status": "-", "model": s.openai_model, "prompt_version": PROMPT_VERSION, "facts_hash": key[:12]}
    raw = None
    try:
        if cp.exists():
            raw = json.loads(cp.read_text(encoding="utf-8"))
            res["usage"]["cached"] = True
    except (OSError, ValueError):
        raw = None
    if raw is None:
        if not s.key_present:
            res["status"] = {"ok": False, "message": "키 없음 — 추측하지 않고 ‘데이터 없음’으로 둡니다."}
        elif log.calls_today() >= s.ai_daily_call_limit:
            res["status"] = {"ok": False, "message": f"오늘 AI 호출 한도({s.ai_daily_call_limit}회)에 도달해 추측하지 않았습니다."}
        else:
            try:
                reply = llm.ask_json(s, SYSTEM, json.dumps({"labels": todo, "metrics": metrics}, ensure_ascii=False), max_tokens=600, temperature=0)
                out, tin, tout = reply.data, reply.input_tokens, reply.output_tokens
                raw = out.get("guess") if isinstance(out, dict) else None
                res["usage"].update({"calls": 1, "input_tokens": tin, "output_tokens": tout})
                log.append({**meta, "outcome": "ok", "input_tokens": tin, "output_tokens": tout})
                if isinstance(raw, dict):
                    cp.parent.mkdir(parents=True, exist_ok=True)
                    cp.write_text(json.dumps(raw, ensure_ascii=False), encoding="utf-8")
            except Exception as e:  # noqa: BLE001 — 어떤 오류든 추측하지 않는다
                log.append({**meta, "outcome": "error", "input_tokens": 0, "output_tokens": 0})
                res["status"] = {"ok": False, "message": f"AI 호출 실패({type(e).__name__}) — 추측하지 않았습니다."}
                raw = None
    if isinstance(raw, dict):
        res["guess"] = {k: v for k, v in raw.items() if k in todo and v in D.METRIC_COL}          # 지표 사전에 있는 지표만 받는다
        res["status"]["message"] = f"{len(todo)}개 행 중 {len(res['guess'])}개를 추측했습니다."
    elif res["status"]["ok"]:
        res["status"] = {"ok": False, "message": "AI 응답 형식이 맞지 않아 추측하지 않았습니다."}
    return res
