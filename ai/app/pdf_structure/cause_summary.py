"""'주요 원인' 칸의 긴 소명 원문을 한 줄로 줄인다.

AI 는 문장만 줄이고, 코드가 검증한다: 요약에 나온 숫자·영문 코드는 모두 원문에 있어야 하고(없으면 그 줄은 원문 유지),
요약이 원문보다 짧아야 한다. 원문은 항상 함께 돌려줘 화면에서 확인할 수 있다. 키 없음·한도 초과·오류면 원문 그대로 돌려준다.
"""
from __future__ import annotations

import hashlib
import json
import re
import time

from ..config import get_settings
from ..services import llm
from ..services.call_log import CallLog

PROMPT_VERSION = "cause-summary-v1"
MAX_ITEMS = 24
MAX_LEN = 48
SYSTEM = f"""소명 원문을 보고서 표의 '주요 원인' 칸에 넣을 한 줄({MAX_LEN}자 이내)로 줄인다. JSON 하나만 출력한다: {{"summaries":["...", ...]}} (입력과 같은 순서·같은 개수).
규칙: 원문에 없는 사실·숫자를 만들지 않는다. 숫자·단위·계약번호·코드는 원문 표기 그대로 쓰거나 아예 빼며, 새 숫자를 만들거나 단위를 바꾸지 않는다(예: 4천만원을 0.4억으로 바꾸지 않는다). 핵심 원인(무엇이 왜 바뀌었는가)만 남긴다. 명사형으로 끝낸다."""
_TOKEN = re.compile(r"[A-Za-z]+[-A-Za-z0-9]*\d[-A-Za-z0-9]*|\d[\d,.]*\s*(?:천만원|백만원|천원|만원|억원|억|원|%|일|월|건|곳)?")


def _tokens(text: str) -> set[str]:
    return {re.sub(r"\s+", "", t) for t in _TOKEN.findall(text)}


def valid(orig: str, short: str) -> tuple[bool, str]:
    """요약이 원문보다 짧고, 요약의 모든 숫자·코드 조각이 원문에 있는지."""
    short = (short or "").strip()
    if not short:
        return False, "빈 요약"
    if len(short) >= len(orig):
        return False, "원문보다 짧지 않음"
    extra = [t for t in _tokens(short) if t not in _tokens(orig) and t not in orig.replace(" ", "")]
    if extra:
        return False, f"원문에 없는 숫자·코드: {', '.join(sorted(extra)[:3])}"
    return True, ""


def summarize(texts: list[str]) -> dict:
    s = get_settings()
    uniq = list(dict.fromkeys(t.strip() for t in texts if isinstance(t, str) and len(t.strip()) > MAX_LEN))[:MAX_ITEMS]
    res = {"items": [], "usage": {"calls": 0, "input_tokens": 0, "output_tokens": 0, "cached": False}, "status": {"ok": True, "message": ""}}
    if not uniq:
        res["status"]["message"] = "줄일 만큼 긴 원인이 없습니다."
        return res
    key = hashlib.sha256((f"{s.openai_model}|{PROMPT_VERSION}|" + json.dumps(uniq, ensure_ascii=False)).encode("utf-8")).hexdigest()
    cdir = (s.output_dir / "cache" / "cause_summary") if getattr(s, "output_dir", None) else None
    cp = cdir / f"{key}.json" if cdir else None
    log = CallLog(s.log_dir or s.output_dir)
    meta = {"task": "cause_summary", "status": "-", "model": s.openai_model, "prompt_version": PROMPT_VERSION, "facts_hash": key[:12]}
    raw = None
    try:
        if cp and cp.exists():
            raw = json.loads(cp.read_text(encoding="utf-8"))
            res["usage"]["cached"] = True
    except (OSError, ValueError):
        raw = None
    if raw is None:
        if not s.key_present:
            res["status"] = {"ok": False, "message": "키 없음 — 원문을 그대로 둡니다."}
        elif log.calls_today() >= s.ai_daily_call_limit:
            res["status"] = {"ok": False, "message": f"오늘 AI 호출 한도({s.ai_daily_call_limit}회)에 도달해 원문을 그대로 둡니다."}
        else:
            try:
                reply = llm.ask_json(s, SYSTEM, json.dumps({"texts": uniq}, ensure_ascii=False), max_tokens=1500, temperature=0)
                out, tin, tout = reply.data, reply.input_tokens, reply.output_tokens
                raw = out.get("summaries") if isinstance(out, dict) else None
                res["usage"].update({"calls": 1, "input_tokens": tin, "output_tokens": tout})
                log.append({**meta, "outcome": "ok", "input_tokens": tin, "output_tokens": tout})
                if isinstance(raw, list) and len(raw) == len(uniq) and cp:
                    cdir.mkdir(parents=True, exist_ok=True)
                    cp.write_text(json.dumps(raw, ensure_ascii=False), encoding="utf-8")
            except Exception as e:  # noqa: BLE001 — 어떤 오류든 원문을 그대로 둔다
                log.append({**meta, "outcome": "error", "input_tokens": 0, "output_tokens": 0})
                res["status"] = {"ok": False, "message": f"AI 호출 실패({type(e).__name__}) — 원문을 그대로 둡니다."}
                raw = None
    if not isinstance(raw, list) or len(raw) != len(uniq):
        if res["status"]["ok"]:
            res["status"] = {"ok": False, "message": "AI 응답 형식이 맞지 않아 원문을 그대로 둡니다."}
        res["items"] = [{"orig": t, "short": None, "reason": res["status"]["message"]} for t in uniq]
        return res
    for t, sh in zip(uniq, raw):
        ok, why = valid(t, str(sh))
        res["items"].append({"orig": t, "short": str(sh).strip() if ok else None, "reason": "" if ok else f"검증 실패 — {why}"})
    n_ok = sum(1 for i in res["items"] if i["short"])
    res["status"]["message"] = f"{len(uniq)}건 중 {n_ok}건을 줄였습니다(숫자·코드는 원문과 대조 검증)."
    return res
