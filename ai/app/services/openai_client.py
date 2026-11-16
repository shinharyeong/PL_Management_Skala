"""OpenAI 공식 SDK 클라이언트 생성. 테스트는 이 함수를 모의로 바꾼다(실제 네트워크 호출 금지)."""
from __future__ import annotations

from ..config import Settings


def make_client(settings: Settings):
    from openai import OpenAI

    # 재시도는 SDK 에서도 끈다(AI_MAX_RETRIES, 기본 0). 키 값은 어디에도 기록하지 않는다.
    return OpenAI(api_key=settings.openai_api_key, timeout=settings.ai_timeout_seconds, max_retries=settings.ai_max_retries)
