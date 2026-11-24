"""보고서 문장 서비스(핵심 메시지·카드 설명·사업부 요약·소명 원인/영향).

계산은 코드가 한다. AI 는 '사실 목록(id, kind, text)' 만 받아 문장을 쓴다. 엑셀 원본·반려 소명 전문은 보내지 않는다.
key_messages 서비스의 패턴을 재사용한다: 캐시(모델+프롬프트 버전+사실 해시) → 키 확인 → 일일 상한 → 1회 호출(재시도 없음)
→ 항목별 검증 → 실패 항목만 템플릿으로 대체 → 호출 기록(토큰). 호출 1건 = 작업 1개(cards / divisions / causes)이고 핵심 메시지는 key_messages.generate 를 쓴다.
검증: fact_ids 필수·존재 / 출력 숫자는 참조 사실의 숫자에만 / 금지어 없음 / 소명 원문에 없는 원인 키워드(이연·회복·일회성 등)는 출력에만 있으면 대체.
"""
from __future__ import annotations

import hashlib
import json
import logging
import re

from ..config import Settings, get_settings, log_key_source
from . import key_messages as km
from . import llm, openai_client  # noqa: F401 — 테스트가 이 모듈의 openai_client 로 가짜를 끼운다
from .call_log import CallLog

logger = logging.getLogger("ai.narratives")

# 원문에 없으면 출력에도 쓸 수 없는 원인·향후 영향 키워드
GUARD_WORDS = ("이연", "회복", "일회성", "만회", "정상화", "재개", "지연", "연기")
# 향후 영향(impact) 문장은 원문에 이 단서가 있을 때만 허용
IMPACT_CUES = ("12월", "추정", "예정", "이후", "다음", "반영", "확정", "미확정")
COMMON_RULES = ("주어진 사실 목록만 사용한다. 새 숫자, 새 원인, 추측, 전망을 쓰지 않는다. "
                "사실 문자열의 숫자 표기와 띄어쓰기를 바꾸지 않는다. 사실에 없는 원인·향후 영향(이연, 회복, 일회성 등)을 만들지 않는다. ")

TASKS = {
    "cards": {
        "version": "narr-cards-v1", "max_tokens": 700, "fields": ("text",),
        "system": ("너는 경영 보고서 초안 작성 보조다. " + COMMON_RULES +
                   '사실 id 마다 해당 카드를 설명하는 보고서체 한 문장(80자 이내)을 쓴다. 출력은 JSON {"items":[{"key":"<사실 id>","text":"...","fact_ids":["<사실 id>"]}]} 만.'),
    },
    "divisions": {
        "version": "narr-divisions-v1", "max_tokens": 1800, "fields": ("text",),
        "system": ("너는 경영 보고서 초안 작성 보조다. " + COMMON_RULES +
                   '사실 id 마다 해당 사업부의 한 줄 요약(보고서체 한 문장)을 쓴다. 출력은 JSON {"items":[{"key":"<사실 id>","text":"...","fact_ids":["<사실 id>"]}]} 만.'),
    },
    "causes": {
        "version": "narr-causes-v1", "max_tokens": 2600, "fields": ("cause", "impact"), "optional": ("impact",),
        "system": ("너는 경영 보고서 초안 작성 보조다. " + COMMON_RULES +
                   "각 사실은 수락된 소명 원문이다. 사실 id 마다 cause(확인된 원인: 원문에 있는 내용만 한 문장)와 impact(향후 영향: 원문에 향후 반영·추정·예정 등 근거가 있을 때만, 없으면 빈 문자열)를 쓴다. "
                   '출력은 JSON {"items":[{"key":"<사실 id>","cause":"...","impact":"...","fact_ids":["<사실 id>"]}]} 만.'),
    },
}
KM_VERSION = "key-messages-v2-order"
KM_SYSTEM = (km.SYSTEM_PROMPT + " 문장 순서는 전체 결과 → 주요 원인 → 향후 영향(연간 영향) 순으로 쓴다. 사실 문자열의 숫자 표기와 띄어쓰기를 바꾸지 않는다.")


def _cache_key(model: str, version: str, fhash: str) -> str:
    return hashlib.sha256(f"{model}|{version}|{fhash}".encode("utf-8")).hexdigest()


def _numbers(text: str) -> set[str]:
    return set(km.numbers_in(text or ""))


def validate_item(task: str, item: dict, by_id: dict) -> list[str]:
    spec = TASKS[task]
    ids = item.get("fact_ids")
    if not isinstance(ids, list) or not ids:
        return ["NO_FACT_IDS"]
    unknown = [i for i in ids if i not in by_id]
    if unknown:
        return [f"UNKNOWN_FACT_ID:{','.join(map(str, unknown))}"]
    src = " ".join(by_id[i]["text"] for i in ids)
    allowed = _numbers(src)
    reasons = []
    for f in spec["fields"]:
        t = item.get(f)
        t = t.strip() if isinstance(t, str) else None
        if t is None:
            reasons.append(f"MISSING_FIELD:{f}")
            continue
        if not t:
            if f not in spec.get("optional", ()):
                reasons.append(f"EMPTY:{f}")
            continue
        bad = [n for n in km.numbers_in(t) if n not in allowed]
        if bad:
            reasons.append(f"NUMBER_NOT_IN_FACTS:{f}:{','.join(bad)}")
        hit = [w for w in km.FORBIDDEN if w in t]
        if hit:
            reasons.append(f"FORBIDDEN_WORD:{f}:{','.join(hit)}")
        inv = [w for w in GUARD_WORDS if w in t and w not in src]
        if inv:
            reasons.append(f"CAUSE_WORD_NOT_IN_SOURCE:{f}:{','.join(inv)}")
        if f == "impact" and not any(c in t and c in src for c in IMPACT_CUES):
            reasons.append("IMPACT_WITHOUT_SOURCE_CUE")
    return reasons


def split_cause_impact(text: str) -> dict:
    """템플릿: 원문 문장을 그대로 나눈다. 첫 문장=원인, 뒤 문장 중 향후 단서(12월·추정·예정 등)가 있는 문장=향후 영향. 새 문장은 만들지 않는다."""
    sents = [s.strip() for s in re.split(r"(?<=[.!?。])\s+", (text or "").strip()) if s.strip()]
    if not sents:
        return {"cause": "", "impact": ""}
    rest = [s for s in sents[1:] if any(c in s for c in IMPACT_CUES)]
    return {"cause": sents[0], "impact": " ".join(rest)}


def _read(path):
    try:
        return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None
    except (OSError, ValueError):
        return None


def _call(s: Settings, task: str, facts: list[dict]):
    spec = TASKS[task]
    reply = llm.ask_json(s, spec["system"], json.dumps({"facts": [{"id": f["id"], "kind": f["kind"], "text": f["text"]} for f in facts]}, ensure_ascii=False),
                         max_tokens=spec["max_tokens"])
    items = reply.data["items"]
    if not isinstance(items, list):
        raise ValueError("items is not a list")
    return items, {"input_tokens": reply.input_tokens, "output_tokens": reply.output_tokens}


def run_items(task: str, facts: list[dict], templates: dict[str, dict], *, allow_call: bool = False, force: bool = False,
              settings: Settings | None = None) -> dict:
    """facts 의 id 가 곧 결과 key. templates[key] = 템플릿 필드(text 또는 cause/impact). 반환: {"items": {key: {...fields, source, reason, fact_ids}}, meta...}"""
    s = settings or get_settings()
    spec = TASKS[task]
    log_key_source(s)
    by_id = {f["id"]: f for f in facts}
    fh = km.facts_hash(facts)
    cpath = s.ai_cache_dir / f"{_cache_key(s.openai_model, spec['version'], fh)}.json"
    log = CallLog(s.log_dir or s.output_dir)
    meta = {"task": task, "status": "-", "model": s.openai_model, "prompt_version": spec["version"], "facts_hash": fh[:12]}
    res = {"task": task, "ai_attempted": False, "error": None, "cached": False, "usage": {"input_tokens": 0, "output_tokens": 0, "cached": False}, "why_not": None}
    ai_items = None
    cached = None if force else _read(cpath)
    if cached and isinstance(cached.get("items"), list):
        ai_items, res["cached"] = cached["items"], True
        res["usage"]["cached"] = True
        log.append({**meta, "outcome": "cache_hit", "input_tokens": 0, "output_tokens": 0})
    elif not allow_call:
        res["why_not"] = "AI_NOT_USED"
    elif not s.key_present:
        res["why_not"] = "NO_API_KEY"
        log.append({**meta, "outcome": "skipped_no_key", "input_tokens": 0, "output_tokens": 0})
    elif log.calls_today() >= s.ai_daily_call_limit:
        res["why_not"] = "DAILY_LIMIT_EXCEEDED"
        log.append({**meta, "outcome": "skipped_daily_limit", "input_tokens": 0, "output_tokens": 0})
    else:
        res["ai_attempted"] = True
        try:
            ai_items, usage = _call(s, task, facts)
            res["usage"] = {**usage, "cached": False}
            s.ai_cache_dir.mkdir(parents=True, exist_ok=True)
            cpath.write_text(json.dumps({"items": ai_items, "usage": usage, "model": s.openai_model, "prompt_version": spec["version"]}, ensure_ascii=False), encoding="utf-8")
            log.append({**meta, "outcome": "success", "input_tokens": usage["input_tokens"], "output_tokens": usage["output_tokens"]})
        except Exception as e:  # 오류 종류만 기록
            kind = type(e).__name__ + (f" HTTP {e.status_code}" if getattr(e, "status_code", None) else "")
            res["error"], res["why_not"], ai_items = kind, f"AI_CALL_FAILED:{kind}", None
            log.append({**meta, "outcome": "error", "input_tokens": 0, "output_tokens": 0, "error": kind})

    by_key = {}
    for it in ai_items or []:
        if isinstance(it, dict) and isinstance(it.get("key"), str):
            by_key[it["key"]] = it
    out = {}
    for key, tpl in templates.items():
        fids = [key] if key in by_id else []
        base = {**tpl, "fact_ids": tpl.get("fact_ids") or fids, "source": "template", "reason": res["why_not"] or "AI_NOT_USED"}
        it = by_key.get(key)
        if ai_items is not None:
            if it is None:
                base["reason"] = "MISSING_IN_AI_OUTPUT"
            else:
                reasons = validate_item(task, it, by_id)
                if not reasons:
                    base = {**{f: (it.get(f) or "").strip() for f in spec["fields"]}, "fact_ids": it["fact_ids"], "source": "ai", "reason": None}
                else:
                    base["reason"] = ";".join(reasons)
                    base["replaced_ai"] = {f: it.get(f) for f in spec["fields"]}
        out[key] = base
    res["items"] = out
    res["counts"] = {"ai": sum(1 for v in out.values() if v["source"] == "ai"), "template": sum(1 for v in out.values() if v["source"] == "template")}
    return res


def key_messages(facts: list[dict], templates: list[dict], state: str, *, allow_call: bool = False, force: bool = False, settings: Settings | None = None,
                 system_prompt: str | None = None, prompt_version: str | None = None) -> dict:
    """핵심 메시지: 기존 key_messages 서비스를 그대로 쓰되 프롬프트·버전을 바꿔 부를 수 있다(기본: 결과→원인→영향 순서 지시)."""
    return km.generate(facts, state, templates=templates, force=force, allow_call=allow_call, settings=settings, system_prompt=system_prompt or KM_SYSTEM,
                       prompt_version=prompt_version or KM_VERSION)
