"""backend 계약(명세 12절)의 내용 처리. API 입구는 app/api/contract.py.

- data()      RPT-10 블록 데이터 채우기: 보고서용 뷰 값으로 코드가 계산(AI 문장 없음). data_select.fill_report 를 그대로 쓴다.
              블록에 sql 이 있으면(지표 사전·확정 SQL) 기준 월 인자로 sqlguard 를 거쳐 실행한다
- sql_check() RPT-09 지표 등록 전 SQL 검사(sqlguard, 실행 안 함)
- edit()      RPT-15 대화 편집 변경안: chat_edit.interpret(LLM 의도 → 코드 검증) 결과를 proposals 로 바꾼다. 숫자는 바꾸지 않는다
- pdf_job()   RPT-01·02 PDF 틀 추출: extract_structure 결과를 틀 초안(StoredTemplate)으로 만들어 backend 에 보고한다
              1차는 HTTP 로 받은 PDF 바이트를 처리하고, 2차는 pdf.jobs 큐·S3 에서 읽는 것만 다르다(보고 본문은 같음)
"""
from __future__ import annotations

import base64
import re
import logging
from pathlib import PurePath

import httpx

from .config import Settings, get_settings

log = logging.getLogger(__name__)

_STATE = {"확정": "CONFIRMED", "마감": "CONFIRMED", "잠정": "PROVISIONAL"}
_EDIT_STATUS = {"edit": "EDIT", "clarify": "CLARIFY", "unknown": "CLARIFY", "refuse": "REFUSED", "no_data": "REFUSED", "ai_unavailable": "UNAVAILABLE"}
NOT_AN_EDIT = "자료 편집 요청만 처리합니다. 데이터 조회는 자연어 질의를 이용해 주세요."


def _flat_table(block: dict) -> dict:
    """PDF 분석이 준 표는 table{rows, header_rows, header_spans} 안에 있다. 채우기는 블록 바로 아래의 rows·header_rows·header_spans 를 읽으므로 펼쳐 준다(이미 펼친 블록은 그대로)."""
    table = block.get("table")
    if block.get("kind") != "table" or not isinstance(table, dict) or "rows" in block:
        return block
    return {**block, "rows": table.get("rows") or [], "header_rows": table.get("header_rows"), "header_spans": table.get("header_spans")}


_ORIG_MONTH = re.compile(r"'(\d{2})\.(\d{1,2})월")


def _orig_month(blocks: list[dict]) -> tuple[int, int] | None:
    """틀이 만들어진 원래 기준 월(예: 제목의 '26.11월). 문장·제목에서 처음 보이는 '연.월 표기를 쓴다(데모와 같은 규칙). 없으면 None."""
    for b in blocks:
        m = _ORIG_MONTH.search(str(b.get("text") or ""))
        if m:
            return int(m.group(1)), int(m.group(2))
    return None


def data(base: dict, blocks: list[dict]) -> dict:
    from .pdf_structure import data_select
    # 원래 월을 알려 주어야 선택한 기준 월로 날짜 표기('26.11월 등)가 바뀐다.
    r = data_select.fill_report(base, [_flat_table(b) for b in blocks], _orig_month(blocks))
    status = _STATE.get(r.get("state") or "", "NONE") if r.get("version") else "NONE"
    return {"dataStatus": status, "blocks": r.get("blocks", [])}


def sql_check(sql: str) -> dict:
    """지표 사전 등록(RPT-09) 전 SQL 검사. 챗봇과 같은 sqlguard 규칙(SELECT 한 문장, 보고서용 뷰만, version 조건 등). 실행하지 않는다."""
    from .chat import sqlguard
    try:
        return {"ok": True, "sql": sqlguard.validate(sql), "code": None, "message": None}
    except sqlguard.SqlRejected as e:
        return {"ok": False, "sql": None, "code": e.code, "message": e.message}


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
    if r["status"] == "answer":                            # 데이터를 묻는 말은 쌓인 데이터로 답한다(글 + 표). 보고서는 바꾸지 않는다
        return {"status": "ANSWER", "message": r.get("message"), "proposals": [], "table": r.get("table")}
    status = _EDIT_STATUS.get(r["status"])
    if status is None:                                     # 그 밖의 상태는 편집도 답변도 아니다
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
                      "extraction": {"stats": r.get("stats"), "vlm": r.get("vlm"),
                                       "pages": [{"no": p["page"], "width": p["width"], "height": p["height"]} for p in r["pages"]]}}      # 쪽 크기(pt): 블록 bbox 는 이 크기 기준이라 화면이 박스를 그림 위에 올릴 때 쓴다(backend 의 pages 응답에는 크기가 없다)
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
    if s.require_internal_token and not s.report_service_token:
        log.warning("REPORT_AI_TOKEN 이 없어 PDF 분석 결과를 보고하지 않았습니다(job %s, AI_REQUIRE_INTERNAL_TOKEN=true)", job_id)
        return False
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
