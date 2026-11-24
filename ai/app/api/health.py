"""GET /health — 서버 상태. OpenAI 를 호출하지 않고 키는 존재 여부(true/false)만 알려준다."""
from fastapi import APIRouter

from ..config import get_settings
from ..schemas.health import HealthResponse

router = APIRouter()


@router.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    s = get_settings()
    return HealthResponse(status="ok", model=s.openai_model, openai_key_present=s.key_present)
