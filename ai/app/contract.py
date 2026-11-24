"""backend 계약(명세 12절)의 내용 처리. API 입구는 app/api/contract.py.

- data()      RPT-10 블록 데이터 채우기: 보고서용 뷰 값으로 코드가 계산(AI 문장 없음). data_select.fill_report 를 그대로 쓴다
- edit()      RPT-15 대화 편집 변경안: chat_edit.interpret(LLM 의도 → 코드 검증) 결과를 proposals 로 바꾼다. 숫자는 바꾸지 않는다
- pdf_job()   RPT-01·02 PDF 틀 추출: extract_structure 결과를 틀 초안(StoredTemplate)으로 만들어 backend 에 보고한다
              1차는 HTTP 로 받은 PDF 바이트를 처리하고, 2차는 pdf.jobs 큐·S3 에서 읽는 것만 다르다(보고 본문은 같음)
"""
from __future__ import annotations

import base64
import logging
from pathlib import PurePath

import httpx

from .config import Settings, get_settings

log = logging.getLogger(__name__)

_STATE = {"확정": "CONFIRMED", "마감": "CONFIRMED", "잠정": "PROVISIONAL"}
_EDIT_STATUS = {"edit": "EDIT", "clarify": "CLARIFY", "unknown": "CLARIFY", "refuse": "REFUSED", "no_data": "REFUSED", "ai_unavailable": "UNAVAILABLE"}
NOT_AN_EDIT = "자료 편집 요청만 처리합니다. 데이터 조회는 자연어 질의를 이용해 주세요."


def data(base: dict, blocks: list[dict]) -> dict:
    from .pdf_structure import data_select
    r = data_select.fill_report(base, blocks)
    status = _STATE.get(r.get("state") or "", "NONE") if r.get("version") else "NONE"
    return {"dataStatus": status, "blocks": r.get("blocks", [])}


def _base_of(definition: dict) -> dict | None:
    rule = definition.get("baseRule") or (definition.get("settings") or {}).get("base_month") or {}
    if rule.get("mode") == "fixed" and rule.get("year") and rule.get("month"):
        return {"year": rule["year"], "month": rule["month"]}
    from .pdf_structure import data_select
    return data_select.months().get("default")


def edit(definition: dict, instruction: str) -> dict:
    from .pdf_structure import chat_edit
    blocks = [{**b, "label": b.get("label") or b.get("title") or b.get("text") or b.get("kind")} for b in definition.get("blocks", [])]
    base = _base_of(definition) or {}
    r = chat_edit.interpret(instruction, base, blocks)
    status = _EDIT_STATUS.get(r["status"])
    if status is None:                                     # 조회 답변(answer·not_found 등)은 편집이 아니다
        return {"status": "REFUSED", "message": NOT_AN_EDIT, "proposals": []}
    notes = r.get("notes") or []
    proposals = []
    for i, p in enumerate(r.get("plan") or []):
        target = ",".join(p.get("ids") or []) or p.get("after")
        patch = {k: v for k, v in p.items() if k not in ("op", "ids")}
        proposals.append({"op": p["op"], "target": target, "description": notes[i] if i < len(notes) else p["op"], "patch": patch})
    return {"status": status, "message": r.get("message"), "proposals": proposals}


def _pages(pdf: bytes, extracted: dict, mask_numbers: bool) -> list[dict]:
    from .pdf_structure.extract import read_pdf
    images = read_pdf(pdf, with_images=True, masked_images=True)[3] if mask_numbers else extracted.get("_images", [])
    return [{"no": i + 1, "width": p.get("width"), "height": p.get("height"),
             "imagePng": base64.b64encode(img).decode() if img else None}
            for i, (p, img) in enumerate(zip(extracted["pages"], images + [None] * (len(extracted["pages"]) - len(images))))]


def analyze_pdf(pdf: bytes, filename: str | None, mask_numbers: bool, settings: Settings | None = None) -> dict:
    """PDF → 보고 본문(status·definition·pages·error). 실패해도 예외 대신 FAILED 본문을 돌려준다."""
    from .pdf_structure import data_select
    from .pdf_structure.extract import extract_structure
    s = settings or get_settings()
    try:
        info = data_select.months()
        known = {"metrics": info["metrics"], "orgs": [o["name"] for o in info["orgs"]] + [o["code"] for o in info["orgs"]]}
        r = extract_structure(pdf, with_images=not mask_numbers, use_ai=s.key_present, allow_raw_image=not mask_numbers, known=known)
        name = (PurePath(filename).stem if filename else "") or "PDF 자료 틀"
        # 저장 봉투(schema_version·name·blocks[].id·kind) 모양. 추출이 블록마다 id·kind 를 붙이고, backend 가 저장(RPT-04) 때 다시 검사한다
        definition = {"schema_version": 1, "name": name[:120], "source": "pdf", "blocks": r["blocks"],
                      "extraction": {"stats": r.get("stats"), "vlm": r.get("vlm")}}
        return {"status": "SUCCEEDED", "definition": definition, "pages": _pages(pdf, r, mask_numbers), "error": None}
    except ValueError as e:
        return {"status": "FAILED", "definition": None, "pages": [], "error": {"code": "PDF_UNREADABLE", "message": str(e)[:500]}}
    except Exception as e:  # noqa: BLE001 — 깨진 PDF 등. 원문·키는 남기지 않는다
        log.warning("PDF 분석 실패: %s", type(e).__name__)
        return {"status": "FAILED", "definition": None, "pages": [],
                "error": {"code": "PDF_UNREADABLE", "message": f"PDF 를 읽을 수 없습니다: {type(e).__name__}"}}


def report_pdf_result(job_id: str, body: dict, settings: Settings | None = None) -> bool:
    """backend POST /internal/pdf-jobs/{jobId}/result. 보고 실패는 기록만 한다(backend 가 작업 상태로 시간 초과를 처리)."""
    s = settings or get_settings()
    if not s.backend_url:
        log.warning("AI_BACKEND_URL 이 없어 PDF 분석 결과를 보고하지 못했습니다(job %s)", job_id)
        return False
    headers = {"X-Internal-Token": s.report_service_token} if s.report_service_token else {}
    try:
        resp = httpx.post(f"{s.backend_url.rstrip('/')}/internal/pdf-jobs/{job_id}/result", json=body, headers=headers, timeout=30)
        resp.raise_for_status()
        return True
    except httpx.HTTPError as e:
        log.warning("PDF 분석 결과 보고 실패(job %s): %s", job_id, type(e).__name__)
        return False


def pdf_job(job_id: str, pdf: bytes, filename: str | None, mask_numbers: bool) -> None:
    """백그라운드 작업: 분석하고 backend 에 보고한다."""
    report_pdf_result(job_id, analyze_pdf(pdf, filename, mask_numbers))
