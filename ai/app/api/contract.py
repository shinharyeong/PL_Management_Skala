"""backend → report-ai 계약 API(명세 12절). 클러스터 내부에서 backend 만 부른다.

POST /v1/nl-query   text2SQL(RPT-11). 흐름은 LangGraph(app/chat/graph.py)
POST /v1/data       블록 데이터 채우기(RPT-10). AI 문장 없음, 숫자는 보고서용 뷰에서 코드가 계산
POST /v1/edit       대화 편집 변경안(RPT-15). 숫자는 바꾸지 않음
POST /v1/pdf-jobs   PDF 틀 추출 요청(RPT-01, 1차 전송). 202 를 먼저 돌려주고 끝나면 backend 에 결과를 보고한다
REPORT_AI_TOKEN 이 있으면 X-Internal-Token 헤더가 같아야 한다.
"""
from fastapi import APIRouter, BackgroundTasks, Depends, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from .. import contract
from ..chat import service
from ..config import get_settings

MAX_PDF_MB = 30


def internal_only(x_internal_token: str | None = Header(default=None)) -> None:
    expected = get_settings().report_service_token
    if expected and x_internal_token != expected:
        raise HTTPException(status_code=401, detail="내부 토큰이 맞지 않습니다.")


router = APIRouter(prefix="/v1", tags=["backend contract"], dependencies=[Depends(internal_only)])


class NlQueryRequest(BaseModel):
    question: str = Field(min_length=1, max_length=1000)
    baseYear: int = Field(ge=2000, le=9999)
    baseMonth: int = Field(ge=1, le=12)
    conversationId: str | None = Field(default=None, max_length=64)


class Base(BaseModel):
    year: int = Field(ge=2000, le=9999)
    month: int = Field(ge=1, le=12)


class Block(BaseModel):
    model_config = {"extra": "allow"}
    id: str = Field(min_length=1, max_length=64)
    kind: str = Field(min_length=1, max_length=40)


class DataRequest(BaseModel):
    base: Base
    blocks: list[Block] = Field(max_length=500)


class EditRequest(BaseModel):
    definition: dict
    instruction: str = Field(min_length=1, max_length=1000)


@router.post("/nl-query")
async def nl_query(req: NlQueryRequest):
    return await run_in_threadpool(service.nl_query, req.question, req.baseYear, req.baseMonth, conversation_id=req.conversationId)


@router.post("/data")
async def data(req: DataRequest):
    return await run_in_threadpool(contract.data, req.base.model_dump(), [b.model_dump() for b in req.blocks])


@router.post("/edit")
async def edit(req: EditRequest):
    return await run_in_threadpool(contract.edit, req.definition, req.instruction)


@router.post("/pdf-jobs", status_code=202)
async def pdf_jobs(background: BackgroundTasks, jobId: str = Form(..., min_length=1, max_length=64), maskNumbers: bool = Form(True),
                   file: UploadFile = File(...)):
    pdf = await file.read()
    if len(pdf) > MAX_PDF_MB * 1024 * 1024:
        raise HTTPException(status_code=413, detail=f"PDF 가 너무 큽니다(최대 {MAX_PDF_MB}MB).")
    background.add_task(contract.pdf_job, jobId, pdf, file.filename, maskNumbers)
    return {"jobId": jobId, "accepted": True}
