"""챗봇(AI 작업실) API — 초안(백엔드와 합의 전).

POST /v1/chat            질문 → 해석·(되묻기)·SQL 생성(AI)·검사·읽기 전용 조회·설명 문장·엑셀
GET  /v1/chat/files/{id} 만든 엑셀 내려받기
GET  /v1/chat/status     DB 범위(버전·조직·월)와 오늘 남은 AI 호출 수
"""
from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from ..chat import service

router = APIRouter(prefix="/v1/chat", tags=["chat (draft)"])


class ChatRequest(BaseModel):
    question: str = Field(min_length=1, max_length=500)
    version: str | None = Field(default=None, description="조회 기준 버전(시점). 비우면 최신 버전")
    quick_id: str | None = Field(default=None, description="예시 질문 id(고정 SQL, AI 로 SQL 을 만들지 않음)")
    conversation_id: str | None = Field(default=None, max_length=64, description="클라이언트가 만든 대화 ID(대화 기억용, 서버 메모리에만 보관)")


class MemoryRequest(BaseModel):
    conversation_id: str = Field(max_length=64)
    reset: bool = False
    clear: list[str] = Field(default_factory=list, description="해제할 조건 칩: org, year, month, version, metric, scenario")


@router.post("")
async def chat(req: ChatRequest):
    return await run_in_threadpool(service.run_chat, req.question, version=req.version, quick_id=req.quick_id, conversation_id=req.conversation_id)


@router.post("/memory")
def chat_memory(req: MemoryRequest):
    """대화 기억 초기화(새 대화) 또는 조건 칩 해제. 기억은 서버 메모리에만 있다."""
    if req.reset:
        service.reset_memory(req.conversation_id)
        return {"chips": [], "reset": True}
    return service.clear_conditions(req.conversation_id, req.clear)


@router.get("/status")
def chat_status():
    return service.status()


@router.get("/files/{file_id}")
def chat_file(file_id: str):
    r = service.get_file(file_id)
    if r is None:
        raise HTTPException(status_code=404, detail="파일이 없습니다.")
    path, name = r
    return FileResponse(path, filename=name, media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")


