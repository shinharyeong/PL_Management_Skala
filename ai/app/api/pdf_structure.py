"""PDF 구조 추출 데모 — 개발 전용(AI_PDF_DEV=1 일 때만 열림, 아니면 404). 저장·목록은 없다.

POST /v1/dev/pdf-structure   PDF 한 개 → 페이지 이미지(PNG, base64) + 블록 구조 JSON. ai=true 면 페이지 그림(숫자 가림)과 줄 목록을 OpenAI 에 보내 구성을 읽는다(키 없음·상한·실패 시 코드 규칙).
"""
import base64

from pydantic import BaseModel, Field
from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from starlette.concurrency import run_in_threadpool

from ..config import get_settings
from ..pdf_structure import extract_structure
from ..pdf_structure.extract import is_image

MAX_MB = 20


def dev_only():
    if not get_settings().pdf_dev:
        raise HTTPException(status_code=404, detail="Not Found")


router = APIRouter(prefix="/v1/dev", tags=["pdf structure (dev)"], dependencies=[Depends(dev_only)])


@router.post("/pdf-structure")
async def pdf_structure(file: UploadFile = File(...), ai: bool = Form(False), raw_image: bool = Form(False), engine: str = Form("vlm")):
    data = await file.read()
    if not (data.startswith(b"%PDF") or is_image(data)):
        raise HTTPException(status_code=422, detail="PDF 또는 PNG/JPG 파일이 아닙니다.")
    if len(data) > MAX_MB * 1024 * 1024:
        raise HTTPException(status_code=413, detail=f"PDF 가 너무 큽니다(최대 {MAX_MB}MB).")
    try:
        from ..pdf_structure import data_select
        info = data_select.months()
        known = {"metrics": info["metrics"], "orgs": [o["name"] for o in info["orgs"]] + [o["code"] for o in info["orgs"]]}
        r = await run_in_threadpool(extract_structure, data, with_images=True, use_ai=ai, allow_raw_image=raw_image, engine=engine if engine in ("vlm", "lines") else "vlm", known=known)
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e
    except Exception as e:                                   # 깨진 PDF 등
        raise HTTPException(status_code=422, detail=f"PDF 를 읽을 수 없습니다: {type(e).__name__}") from e
    for p, img in zip(r["pages"], r.pop("_images")):
        p["image"] = "data:image/png;base64," + base64.b64encode(img).decode()
    r["filename"] = file.filename
    return r


class _ResolveReq(BaseModel):
    base: dict
    blocks: list[dict] = Field(default_factory=list)


@router.get("/report-data/months")
def report_months():
    """데이터 선택 단계: 조회 DB 에서 월별 확정·잠정 현황, 지표 사전, 조직 목록을 읽는다."""
    from ..pdf_structure import data_select
    return data_select.months()


@router.post("/report-data/resolve")
def report_resolve(req: _ResolveReq):
    from ..pdf_structure import data_select
    try:
        return data_select.resolve(req.base, req.blocks)
    except (KeyError, ValueError) as e:
        raise HTTPException(status_code=422, detail=f"블록을 해석할 수 없습니다: {e}") from e


@router.get("/report-data/gaps")
def report_gaps(year: int, month: int) -> dict:
    """기준 월의 backend Gap·소명을 정밀도 손실 없이 개발 화면에 반환한다.

    Args:
        year: 기준 연도.
        month: 기준 월.
    Returns:
        항목별 비교·소명 카드. 큰 정수·소수는 문자열.
    Raises:
        DB 조회 오류를 전달한다.
    Side Effects:
        보고서용 뷰만 읽는다. 분류 결과를 저장하지 않는다.
    """
    from ..pdf_structure import data_select
    from ..services.numeric import exact_json_value
    return exact_json_value(data_select.gaps(year, month))


class _FillReq(BaseModel):
    base: dict
    blocks: list[dict] = Field(default_factory=list)
    orig: list[int] | None = None


@router.post("/report-data/fill")
def report_fill(req: _FillReq):
    """5단계: 블록(표·문장·비용 상세)을 조회 DB 값으로 채운다. 모든 숫자는 코드가 계산한다. 저장하지 않는다."""
    from ..pdf_structure import data_select
    try:
        return data_select.fill_report(req.base, req.blocks, tuple(req.orig) if req.orig and len(req.orig) == 2 else None)
    except (KeyError, ValueError) as e:
        raise HTTPException(status_code=422, detail=f"블록을 채울 수 없습니다: {e}") from e


class _SentReq(BaseModel):
    base: dict
    assign: dict = Field(default_factory=dict)
    gi: int = 0
    force: bool = False


@router.post("/report-data/sentences")
async def report_sentences(req: _SentReq):
    """5단계 보고 문장. 기존 key_messages 서비스(실제 OpenAI 호출·캐시·일일 상한·숫자 검증·기본 문구 대체)를 그대로 쓴다."""
    from ..pdf_structure import data_select
    return await run_in_threadpool(data_select.generate_sentences, req.base, req.assign, req.gi, req.force)


class _CauseReq(BaseModel):
    texts: list[str] = Field(default_factory=list)


@router.post("/report-data/cause-summary")
async def report_cause_summary(req: _CauseReq):
    """5단계 '주요 원인' 칸을 한 줄로 줄인다. AI 는 문장만 줄이고 숫자·코드는 코드가 원문과 대조해 검증한다(실패한 줄은 원문 유지)."""
    from ..pdf_structure import cause_summary
    return await run_in_threadpool(cause_summary.summarize, req.texts)


class _ChatReq(BaseModel):
    request: str = Field(min_length=1, max_length=300)
    base: dict
    blocks: list[dict] = Field(default_factory=list)


@router.post("/report-data/chat")
async def report_chat(req: _ChatReq):
    """5단계 AI 챗봇: 요청 → (OpenAI) 의도 JSON → 코드 검증·계산. 조회는 DB 로 답하고, 편집은 계획만 돌려준다(적용은 화면에서)."""
    from ..pdf_structure import chat_edit
    return await run_in_threadpool(chat_edit.interpret, req.request, req.base, req.blocks)
