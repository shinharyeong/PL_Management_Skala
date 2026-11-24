"""파이프라인의 핵심 메시지 단계. 사실 목록을 services.key_messages 에 맡긴다(검증·캐시·일일 상한·토큰 로그는 서비스에서).

AI 에는 사실 목록의 id·kind·text 만 보낸다. 호출은 allow_call(= --ai)일 때만 하고, 같은 사실 목록은 캐시를 쓴다.
"""
from __future__ import annotations

from ..config import get_settings
from ..services import key_messages as svc


def ai_input_doc(state: str, pack: dict) -> dict:
    s = get_settings()
    return {"state": state, "model": s.openai_model, "prompt_version": svc.PROMPT_VERSION,
            "settings": {"temperature": s.ai_temperature, "max_output_tokens": s.ai_max_output_tokens, "timeout_sec": s.ai_timeout_seconds, "retry": s.ai_max_retries,
                         "response": "JSON {sentences:[{text,fact_ids}]}"},
            "system_prompt": svc.SYSTEM_PROMPT,
            "sent_to_model": "facts 의 id·kind·text 만 전송(numbers·data 는 검증용으로 파일에만 있음)",
            "excluded_from_input": ["반려된 소명 문장·반려 사유", "엑셀 원본 값", "API 키", "submission_id·gap_key 등 내부 식별자(사업부 이름만 사용)"],
            "fact_count": len(pack["facts"]), "counts_by_kind": pack["counts_by_kind"], "facts": pack["facts"],
            "template_sentences": pack["templates"]}


def generate(state: str, pack: dict, use_ai: bool, force: bool = False) -> dict:
    s = get_settings()
    facts = [{"id": f["id"], "kind": f["kind"], "text": f["text"]} for f in pack["facts"]]
    r = svc.generate(facts, state, templates=pack["templates"], force=force, allow_call=use_ai, settings=s)
    sents = [{"index": x["index"], "text": x["text"], "fact_ids": x["fact_ids"], "numbers_used": x["numbers_used"], "source": x["source"],
              "model": r["model"] if x["source"] == "ai" else None, "failure_reasons": [x["reason"]] if x["reason"] else [],
              **({"replaced_ai_text": x["replaced_ai_text"]} if "replaced_ai_text" in x else {})} for x in r["sentences"]]
    counts = {"ai": sum(1 for x in sents if x["source"] == "ai"), "template": sum(1 for x in sents if x["source"] == "template")}
    err = r["error"]
    if not err and counts["ai"] == 0 and sents:
        err = (sents[0]["failure_reasons"] or [None])[0]
    return {"state": state, "model": r["model"], "prompt_version": r["prompt_version"],
            "settings": {"temperature": s.ai_temperature, "max_output_tokens": s.ai_max_output_tokens, "timeout_sec": s.ai_timeout_seconds, "retry": s.ai_max_retries},
            "fact_count": len(facts), "ai_attempted": r["ai_attempted"], "ai_error": err, "cached": r["cached"], "usage": r["usage"],
            "tokens": r["usage"]["input_tokens"] + r["usage"]["output_tokens"], "sentences": sents, "counts": counts, "notes": r["notes"]}
