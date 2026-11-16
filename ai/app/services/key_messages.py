"""핵심 메시지 초안 서비스.

입력은 사실 목록(id, kind, text)뿐이다. 엑셀 원본·소명 전체·반려된 소명은 받지도 보내지도 않는다.
흐름: 캐시 확인 → (없으면) 키·일일 상한 확인 → OpenAI 1회 호출(재시도 없음) → 문장별 검증 → 실패 문장만 템플릿으로 대체.
검증: 숫자는 fact_ids 의 숫자와 일치, fact_ids 존재, 빈 문장·5문장 초과 금지, 금지어 금지, 미분해 사실은 문장에 밝힐 것.
"""
from __future__ import annotations

import hashlib
import json
import logging
import re
from pathlib import Path

from ..config import Settings, get_settings, log_key_source
from . import openai_client
from .call_log import CallLog

logger = logging.getLogger("ai.key_messages")

PROMPT_VERSION = "key-messages-v1"
SYSTEM_PROMPT = ("너는 경영 보고서 초안 작성 보조다. 주어진 사실 목록만 사용한다. 새 숫자, 새 원인, 추측, 전망을 쓰지 않는다. 금액은 목록의 문자열 그대로 쓴다. "
                 "소명이 없는 증감은 원인을 말하지 않는다. 원인 금액이 미분해라고 적힌 사실은 그 점을 문장에 반드시 밝힌다. 보고서체 한 문장씩, 3~5문장. "
                 '출력은 JSON {"sentences":[{"text":..., "fact_ids":[...]}]} 만.')
FORBIDDEN = ("예상", "전망", "것으로 보인다")
NUM = re.compile(r"\d[\d,]*(?:\.\d+)?")
KIND_ORDER = ("매출", "비용", "영업이익", "연간", "소명", "주의")
KIND_PRIORITY = ("영업이익", "매출", "연간", "소명", "주의", "비용")  # 5문장을 넘으면 뒤에서부터 뺀다


def numbers_in(text: str) -> list[str]:
    return sorted({n.replace(",", "") for n in NUM.findall(text)}, key=lambda x: (len(x), x))


def facts_hash(facts: list[dict]) -> str:
    canon = json.dumps([{"id": f["id"], "kind": f["kind"], "text": f["text"]} for f in facts], ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canon.encode("utf-8")).hexdigest()


def cache_key(model: str, fhash: str, prompt_version: str | None = None) -> str:
    return hashlib.sha256(f"{model}|{prompt_version or PROMPT_VERSION}|{fhash}".encode("utf-8")).hexdigest()


def _prep(facts: list[dict]) -> dict:
    by_id = {}
    for f in facts:
        by_id[f["id"]] = {**f, "numbers": numbers_in(f["text"]), "undistributed": f["kind"] == "주의" and "미분해" in f["text"]}
    return by_id


def default_templates(facts: list[dict]) -> list[dict]:
    """사실 목록만으로 만드는 템플릿 문장(사실 한 개 = 한 문장). 종류별 첫 사실을 쓰고 5문장을 넘기지 않는다."""
    first = {}
    for f in facts:
        first.setdefault(f["kind"], f)
    kept = [k for k in KIND_PRIORITY if k in first][:5]
    out = []
    for k in KIND_ORDER:
        if k in kept:
            text = first[k]["text"].strip().replace("않는다.", "않습니다.").replace("되었다.", "되었습니다.")
            out.append({"text": text, "fact_ids": [first[k]["id"]], "numbers_used": numbers_in(text)})
    return out


def validate_sentence(sentence: dict, by_id: dict) -> list[str]:
    text = (sentence.get("text") or "").strip()
    ids = sentence.get("fact_ids")
    if not text:
        return ["EMPTY_SENTENCE"]
    if not isinstance(ids, list) or not ids:
        return ["NO_FACT_IDS"]
    reasons = []
    unknown = [i for i in ids if i not in by_id]
    if unknown:
        reasons.append(f"UNKNOWN_FACT_ID:{','.join(map(str, unknown))}")
    allowed = set()
    for i in ids:
        if i in by_id:
            allowed |= set(by_id[i]["numbers"])
    bad = [n for n in numbers_in(text) if n not in allowed]
    if bad:
        reasons.append(f"NUMBER_NOT_IN_FACTS:{','.join(bad)}")
    hit = [w for w in FORBIDDEN if w in text]
    if hit:
        reasons.append(f"FORBIDDEN_WORD:{','.join(hit)}")
    if any(by_id[i]["undistributed"] for i in ids if i in by_id) and not ("미분해" in text or "구분되지" in text):
        reasons.append("UNDISTRIBUTED_NOT_STATED")
    return reasons


def _read_cache(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None
    except (OSError, ValueError):
        return None


def _call_openai(settings: Settings, facts: list[dict], system_prompt: str | None = None):
    client = openai_client.make_client(settings)
    resp = client.chat.completions.create(
        model=settings.openai_model, temperature=settings.ai_temperature, max_tokens=settings.ai_max_output_tokens,
        response_format={"type": "json_object"},  # 추론(reasoning) 설정은 보내지 않는다
        messages=[{"role": "system", "content": system_prompt or SYSTEM_PROMPT},
                  {"role": "user", "content": json.dumps({"facts": [{"id": f["id"], "kind": f["kind"], "text": f["text"]} for f in facts]}, ensure_ascii=False)}])
    sentences = json.loads(resp.choices[0].message.content)["sentences"]
    if not isinstance(sentences, list):
        raise ValueError("sentences is not a list")
    usage = {"input_tokens": int(getattr(resp.usage, "prompt_tokens", 0) or 0), "output_tokens": int(getattr(resp.usage, "completion_tokens", 0) or 0)}
    return sentences, usage


def generate(facts: list[dict], status: str, *, templates: list[dict] | None = None, force: bool = False, allow_call: bool = True,
             settings: Settings | None = None, system_prompt: str | None = None, prompt_version: str | None = None) -> dict:
    """system_prompt·prompt_version 을 주면 다른 프롬프트로 같은 흐름(캐시·상한·로그·검증)을 쓴다(narratives 서비스용)."""
    s = settings or get_settings()
    pv = prompt_version or PROMPT_VERSION
    log_key_source(s)
    by_id = _prep(facts)
    templates = templates if templates is not None else default_templates(facts)
    fh = facts_hash(facts)
    ckey = cache_key(s.openai_model, fh, pv)
    cpath = s.ai_cache_dir / f"{ckey}.json"
    log = CallLog(s.log_dir or s.output_dir)
    meta = {"status": status, "model": s.openai_model, "prompt_version": pv, "facts_hash": fh[:12]}
    result = {"ai_attempted": False, "error": None, "cached": False, "usage": {"input_tokens": 0, "output_tokens": 0, "cached": False},
              "model": s.openai_model, "prompt_version": pv, "notes": []}
    ai_sents, why_not = None, None

    cached = None if force else _read_cache(cpath)
    if cached and isinstance(cached.get("sentences"), list):
        ai_sents = cached["sentences"]
        result["cached"] = True
        result["usage"]["cached"] = True
        log.append({**meta, "outcome": "cache_hit", "input_tokens": 0, "output_tokens": 0})
    elif not allow_call:
        why_not = "AI_NOT_USED"
    elif not s.key_present:
        why_not = "NO_API_KEY"
        log.append({**meta, "outcome": "skipped_no_key", "input_tokens": 0, "output_tokens": 0})
    elif log.calls_today() >= s.ai_daily_call_limit:
        why_not = "DAILY_LIMIT_EXCEEDED"
        log.append({**meta, "outcome": "skipped_daily_limit", "input_tokens": 0, "output_tokens": 0})
    else:
        result["ai_attempted"] = True
        try:
            ai_sents, usage = _call_openai(s, facts, system_prompt)
            result["usage"] = {**usage, "cached": False}
            s.ai_cache_dir.mkdir(parents=True, exist_ok=True)
            cpath.write_text(json.dumps({"sentences": ai_sents, "usage": usage, "model": s.openai_model, "prompt_version": pv}, ensure_ascii=False), encoding="utf-8")
            log.append({**meta, "outcome": "success", "input_tokens": usage["input_tokens"], "output_tokens": usage["output_tokens"]})
        except Exception as e:  # 오류 종류만 기록(키·응답 원문 없음)
            kind = type(e).__name__ + (f" HTTP {e.status_code}" if getattr(e, "status_code", None) else "")
            result["error"] = kind
            why_not = f"AI_CALL_FAILED:{kind}"
            ai_sents = None
            log.append({**meta, "outcome": "error", "input_tokens": 0, "output_tokens": 0, "error": kind})

    used, out = set(), []

    def template_for(fids):
        for i, t in enumerate(templates):
            if i not in used and set(t["fact_ids"]) & set(fids or []):
                used.add(i); return t
        for i, t in enumerate(templates):
            if i not in used:
                used.add(i); return t
        return None

    if ai_sents:
        if len(ai_sents) > 5:
            result["notes"].append("EXCEEDS_5_SENTENCES: 6번째 이후 문장 제거")
        for raw in ai_sents[:5]:
            sent = raw if isinstance(raw, dict) else {"text": str(raw), "fact_ids": []}
            reasons = validate_sentence(sent, by_id)
            if not reasons:
                out.append({"text": sent["text"].strip(), "fact_ids": sent["fact_ids"], "numbers_used": numbers_in(sent["text"]), "source": "ai", "reason": None})
            else:
                t = template_for(sent.get("fact_ids"))
                if t:
                    out.append({"text": t["text"], "fact_ids": t["fact_ids"], "numbers_used": t["numbers_used"], "source": "template", "reason": ";".join(reasons),
                                "replaced_ai_text": sent.get("text")})
    fill_reason = "FILL_MIN_3_SENTENCES" if ai_sents else (why_not or "AI_NOT_USED")
    while len(out) < 3 or (not ai_sents and len(out) < len(templates)):
        t = template_for([])
        if not t:
            break
        out.append({"text": t["text"], "fact_ids": t["fact_ids"], "numbers_used": t["numbers_used"], "source": "template", "reason": fill_reason})
    for i, sent in enumerate(out[:5], 1):
        sent["index"] = i
    result["sentences"] = out[:5]
    return result
