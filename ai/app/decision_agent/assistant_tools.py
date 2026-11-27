"""대화형 Agent가 고른 허용 읽기 도구를 Spring capability 경계로 전달한다."""
from __future__ import annotations

from decimal import Decimal
import json
import time
from typing import Any
from urllib.parse import urlsplit

import httpx

from ..config import Settings
from .assistant_core import MAX_OBSERVATION_BYTES, normalize_arguments, validate_observation


def call_tool(tool: str, arguments: dict[str, Any], context: dict[str, Any], token: str, settings: Settings,
              *, timeout_seconds: float = 10.0, transport: httpx.BaseTransport | None = None) -> dict[str, Any]:
    """분석 범위를 모델 인자에서 분리한 고정 callback을 한 번 호출한다.

    Args:
        tool: 허용된 조회 도구 이름.
        arguments: 스키마를 검증할 모델 인자.
        context: 서버에서 고정한 master·분석 범위.
        token: 실행 한정 capability. 모델·로그·응답에 전달하지 않는다.
        settings: 백엔드 주소와 공유 내부 토큰.
        timeout_seconds: 전체 실행에서 남은 시간 이하의 호출 예산.
        transport: 무네트워크 검증용 전송 대역.
    Returns:
        원문을 변형하지 않은 고정 범위 관찰.
    Raises:
        ValueError 또는 HTTPError 또는 TimeoutError: 인자·범위·응답·HTTP·시간 제한 오류.
    Side Effects:
        고정 내부 읽기 API를 한 번 호출한다. 리디렉션·프록시·DB·파일 접근은 없다.
    """
    args = normalize_arguments(tool, arguments)
    base = (settings.backend_url or "").rstrip("/")
    url = urlsplit(base)
    if url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password or url.query or url.fragment:
        raise ValueError("내부 도구 백엔드 주소를 확인해 주세요.")
    if settings.require_internal_token and not settings.report_service_token:
        raise ValueError("공유 내부 토큰이 없습니다.")
    if timeout_seconds <= 0:
        raise TimeoutError("실행 시간이 소진됐습니다.")
    bounded_timeout = min(timeout_seconds, 10.0)
    deadline = time.monotonic() + bounded_timeout
    headers = {"X-Decision-Tool-Token": token}
    if settings.report_service_token:
        headers["X-Internal-Token"] = settings.report_service_token
    with httpx.Client(timeout=bounded_timeout, follow_redirects=False, trust_env=False, transport=transport) as client:
        with client.stream("POST", f"{base}/internal/decision-agent/tools/call",
                           json={"tool": tool, "arguments": args}, headers=headers) as response:
            response.raise_for_status()
            body = bytearray()
            for chunk in response.iter_bytes():
                if time.monotonic() >= deadline:
                    raise TimeoutError("도구 응답 시간이 소진됐습니다.")
                if len(body) + len(chunk) > MAX_OBSERVATION_BYTES:
                    raise ValueError("도구 응답이 허용 크기를 초과했습니다.")
                body.extend(chunk)
    if token.encode() in body:
        raise ValueError("도구 응답에 공개할 수 없는 인증값이 포함됐습니다.")
    return validate_observation(json.loads(body, parse_float=Decimal), tool, context)
