"""PDF 구조 추출 데모 — 개발 전용(AI_PDF_DEV=1 일 때만 열림, 아니면 404). 저장·목록은 없다.

POST /v1/dev/pdf-structure   PDF 한 개 → 페이지 이미지(PNG, base64) + 블록 구조 JSON. ai=true 면 페이지 그림(숫자 가림)과 줄 목록을 OpenAI 에 보내 구성을 읽는다(키 없음·상한·실패 시 코드 규칙).
"""
import base64

from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool
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
def report_gaps(year: int, month: int):
    """소명 분류 단계: 기준 월의 영업이익 Gap 카드와 연결된 소명. 읽기만 한다(분류 결과는 저장하지 않는다)."""
    from ..pdf_structure import data_select
    return data_select.gaps(year, month)


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


class _GuessReq(BaseModel):
    labels: list[str] = Field(default_factory=list)


@router.post("/report-data/guess")
async def report_guess(req: _GuessReq):
    """[현재 화면에서는 쓰지 않음 — 나중을 위해 남김] 읽지 못한 표의 행 이름이 어느 DB 지표인지 AI 가 추측한다(추측만, 값은 DB 에서 코드가 계산). 지표 사전에 있는 지표만 돌려준다."""
    from ..pdf_structure import row_guess
    return await run_in_threadpool(row_guess.guess, req.labels)


class _BlockGuessReq(BaseModel):
    action: str
    block: dict = Field(default_factory=dict)
    candidates: list[dict] = Field(default_factory=list)


@router.post("/report-data/guess-block")
async def report_guess_block(req: _BlockGuessReq):
    """[현재 화면에서는 쓰지 않음 — 나중을 위해 남김] 2단계 노란 블록 AI 추측(link=각주가 붙은 블록, kind=블록 종류, metric=행 이름의 지표). 허용 목록 밖의 답은 버린다."""
    from ..pdf_structure import block_guess
    return await run_in_threadpool(block_guess.guess_block, req.action, req.block, req.candidates)


class _SetReq(BaseModel):
    name: str


@router.get("/report-data/datasets")
def report_datasets():
    """데이터 묶음 목록(조직 수·최신 월은 파일 안 내용에서 읽음)과 지금 쓰는 묶음."""
    from ..chat import datasets
    return datasets.list_sets()


@router.post("/report-data/dataset")
def report_dataset_select(req: _SetReq):
    """묶음을 고르면 그 묶음의 DB 가 조회 DB 가 된다."""
    from ..chat import datasets
    try:
        return datasets.select(req.name)
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e)) from e


@router.post("/report-data/upload")
async def report_upload(files: list[UploadFile] = File(...)):
    """데이터 묶음(엑셀·JSON 또는 zip)을 올려 기존 업로드 실행(pipeline_run.start)을 시작한다. AI 는 쓰지 않는다."""
    from ..services import pipeline_run
    blobs = [(f.filename or "", await f.read()) for f in files]
    return await run_in_threadpool(pipeline_run.start, blobs, ai=False)


@router.get("/report-data/upload/{run_id}")
def report_upload_status(run_id: str):
    from ..services import pipeline_run
    st = pipeline_run.read_status(run_id)
    if st is None:
        raise HTTPException(status_code=404, detail="실행을 찾을 수 없습니다.")
    return st


@router.post("/report-data/upload/{run_id}/load")
async def report_upload_load(run_id: str):
    """끝난 실행 결과를 조회 DB 에 적재한다(기존 챗봇 적재 경로 loader_run.load_run, 현재 DB 를 교체)."""
    from ..chat import loader_run
    try:
        return await run_in_threadpool(loader_run.load_run, run_id)
    except FileNotFoundError as e:
        raise HTTPException(status_code=404, detail=str(e)) from e
    except (ValueError, KeyError) as e:
        raise HTTPException(status_code=422, detail=f"적재할 수 없습니다: {e}") from e


class _ChatReq(BaseModel):
    request: str = Field(min_length=1, max_length=300)
    base: dict
    blocks: list[dict] = Field(default_factory=list)


@router.post("/report-data/chat")
async def report_chat(req: _ChatReq):
    """5단계 AI 챗봇: 요청 → (OpenAI) 의도 JSON → 코드 검증·계산. 조회는 DB 로 답하고, 편집은 계획만 돌려준다(적용은 화면에서)."""
    from ..pdf_structure import chat_edit
    return await run_in_threadpool(chat_edit.interpret, req.request, req.base, req.blocks)
