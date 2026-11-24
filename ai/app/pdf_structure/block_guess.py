"""2단계 노란 블록을 AI 가 추측해서 정한다. 추측할 수 있는 것은 '정하지 못한 것' 세 가지뿐이다.

- link : 각주가 어느 블록에 붙었는지(후보 블록 중 하나, 모르면 null)
- kind : 블록 종류(허용 종류 중 하나, 모르면 null)
- metric : 행 이름이 어느 DB 지표인지(row_guess)
고른 값은 코드가 허용 목록 안에 있는지 확인한다. 글자 읽기 오류·표 모양·날짜 해석 같은 것은 추측하지 않고 사람이 확인한다.
※ 현재 화면에서는 쓰지 않는다(AI 추측은 화면에서 뺐고, 나중을 위해 코드만 남김).
"""
from __future__ import annotations

import hashlib
import json

from ..config import get_settings
from ..services import llm, openai_client  # noqa: F401 — 테스트가 이 모듈의 openai_client 로 가짜를 끼운다
from ..services.call_log import CallLog
from . import row_guess

PROMPT_VERSION = "block-guess-v1"
KINDS = ("title", "paragraph", "table", "list", "footnote", "chart", "numbercard")
PROMPTS = {
    "link": '보고서 각주가 어느 블록에 붙은 것인지 추측한다. 입력: {"footnote":각주 글자,"candidates":[{"id":..,"label":..}]}. 출력 JSON 하나: {"target":후보 id 또는 null}. 후보에 없는 id 를 쓰지 않는다. 확신이 없으면 null.',
    "kind": '보고서 블록의 종류를 추측한다. 입력: {"text":블록 글자 일부,"current":현재 종류,"allowed":[종류...]}. 출력 JSON 하나: {"kind":allowed 중 하나 또는 null}. 확신이 없으면 null.',
}


def _call(s, task, system, user):
    key = hashlib.sha256((f"{s.openai_model}|{PROMPT_VERSION}|{task}|" + json.dumps(user, ensure_ascii=False, sort_keys=True)).encode("utf-8")).hexdigest()
    cp = s.output_dir / "cache" / "block_guess" / f"{key}.json"
    log = CallLog(s.log_dir or s.output_dir)
    meta = {"task": "block_guess_" + task, "status": "-", "model": s.openai_model, "prompt_version": PROMPT_VERSION, "facts_hash": key[:12]}
    usage = {"calls": 0, "input_tokens": 0, "output_tokens": 0, "cached": False}
    try:
        if cp.exists():
            usage["cached"] = True
            return json.loads(cp.read_text(encoding="utf-8")), usage, None
    except (OSError, ValueError):
        pass
    if not s.key_present:
        return None, usage, "키 없음 — 추측하지 않았습니다."
    if log.calls_today() >= s.ai_daily_call_limit:
        return None, usage, f"오늘 AI 호출 한도({s.ai_daily_call_limit}회)에 도달해 추측하지 않았습니다."
    try:
        reply = llm.ask_json(s, system, json.dumps(user, ensure_ascii=False), max_tokens=200, temperature=0)
        out, tin, tout = reply.data, reply.input_tokens, reply.output_tokens
        usage.update({"calls": 1, "input_tokens": tin, "output_tokens": tout})
        log.append({**meta, "outcome": "ok", "input_tokens": tin, "output_tokens": tout})
        if isinstance(out, dict):
            cp.parent.mkdir(parents=True, exist_ok=True)
            cp.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
            return out, usage, None
        return None, usage, "AI 응답 형식이 맞지 않아 추측하지 않았습니다."
    except Exception as e:  # noqa: BLE001 — 어떤 오류든 추측하지 않는다
        log.append({**meta, "outcome": "error", "input_tokens": 0, "output_tokens": 0})
        return None, usage, f"AI 호출 실패({type(e).__name__}) — 추측하지 않았습니다."


def guess_block(action: str, block: dict, candidates: list[dict] | None = None) -> dict:
    s = get_settings()
    res = {"action": action, "result": None, "usage": {"calls": 0, "input_tokens": 0, "output_tokens": 0, "cached": False}, "status": {"ok": True, "message": ""}}
    if action == "metric":
        r = row_guess.guess([str(x) for x in (block.get("labels") or [])])
        return {"action": action, "result": r["guess"], "usage": r["usage"], "status": r["status"]}
    if action == "link":
        cands = [{"id": str(c.get("id")), "label": str(c.get("label") or "")[:60]} for c in (candidates or []) if c.get("id")]
        if not cands:
            res["status"] = {"ok": False, "message": "붙을 수 있는 다른 블록이 없습니다."}
            return res
        out, res["usage"], err = _call(s, "link", PROMPTS["link"], {"footnote": str(block.get("text") or "")[:200], "candidates": cands})
        t = out.get("target") if out else None
        if err or t not in {c["id"] for c in cands}:
            res["status"] = {"ok": False, "message": err or "AI 가 붙은 블록을 정하지 못했습니다(모름)."}
            return res
        res["result"] = t
        res["status"]["message"] = "각주가 붙은 블록을 추측했습니다."
        return res
    if action == "kind":
        out, res["usage"], err = _call(s, "kind", PROMPTS["kind"], {"text": str(block.get("text") or "")[:300], "current": block.get("kind"), "allowed": list(KINDS)})
        k = out.get("kind") if out else None
        if err or k not in KINDS:
            res["status"] = {"ok": False, "message": err or "AI 가 종류를 정하지 못했습니다(모름)."}
            return res
        res["result"] = k
        res["status"]["message"] = "블록 종류를 추측했습니다."
        return res
    res["status"] = {"ok": False, "message": "이 노란 표시는 AI 추측 대상이 아닙니다. 직접 확인하거나 빼세요."}
    return res
