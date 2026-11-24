"""backend → report-ai 계약 API(명세 12절). 클러스터 내부에서 backend 만 부른다.

POST /v1/nl-query   text2SQL(RPT-11). 흐름은 LangGraph(app/chat/graph.py), 응답은 RPT-11 과 같은 모양.
"""
from fastapi import APIRouter
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from ..chat import service

router = APIRouter(prefix="/v1", tags=["backend contract"])


class NlQueryRequest(BaseModel):
    question: str = Field(min_length=1, max_length=1000)
    baseYear: int = Field(ge=2000, le=9999)
    baseMonth: int = Field(ge=1, le=12)
    conversationId: str | None = Field(default=None, max_length=64)


@router.post("/nl-query")
async def nl_query(req: NlQueryRequest):
    return await run_in_threadpool(service.nl_query, req.question, req.baseYear, req.baseMonth, conversation_id=req.conversationId)
