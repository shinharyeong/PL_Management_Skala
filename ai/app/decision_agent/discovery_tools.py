"""분석에 한정된 capability로 고정 근거만 읽는 내부 HTTP Tool."""
from __future__ import annotations

import json
from typing import Any
from urllib.parse import urlsplit

import httpx

from ..config import Settings
from .discovery_core import validate_read_result, validate_references

MAX_EVIDENCE_RESPONSE_BYTES = 512 * 1024


def read_evidence(payload: dict[str, Any], refs: list[str], settings: Settings,
                  *, transport: httpx.BaseTransport | None = None) -> list[dict[str, Any]]:
    """설정된 백엔드의 고정 경로에서 허용 근거를 한 번 읽는다.

    Args:
        payload: 분석·revision·허용 목록·단기 Tool 토큰을 가진 내부 요청.
        refs: 허용 목록에서 선택한 최대 스무 개 근거 ID.
        settings: 백엔드 주소와 공유 내부 토큰을 가진 공통 설정.
        transport: 무네트워크 테스트용 httpx 전송 대역. API 입력으로 받지 않는다.
    Returns:
        분석 버전·조직·참조가 대조된 고정 근거 목록.
    Raises:
        ValueError: 설정 주소·본문 상한·JSON·근거 연결이 잘못된 경우.
        HTTPError: 백엔드 실패·인증 실패·연결 제한 시간을 초과한 경우.
    Side Effects:
        고정 내부 경로에 읽기 POST 한 번을 보낸다. 리디렉션·환경 프록시를 사용하지 않는다.
        파일·DB·증빙 URL에 접근하거나 토큰을 로그·모델에 전달하지 않는다.
    """
    validated_refs = validate_references({"refIds": refs}, payload["evidenceCatalog"])
    if not validated_refs:
        return []
    base = (settings.backend_url or "").rstrip("/")
    parsed = urlsplit(base)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("고정 근거 읽기용 백엔드 주소가 설정되지 않았습니다.")
    if settings.require_internal_token and not settings.report_service_token:
        raise ValueError("내부 요청 토큰이 설정되지 않았습니다.")
    headers = {"X-Decision-Tool-Token": payload["toolAccessToken"]}
    if settings.report_service_token:
        headers["X-Internal-Token"] = settings.report_service_token
    request = {"analysisId": payload["analysisId"], "revision": payload["revision"], "refIds": validated_refs}
    with httpx.Client(timeout=15.0, follow_redirects=False, trust_env=False, transport=transport) as client:
        with client.stream("POST", f"{base}/internal/decision-agent/evidence/read", json=request, headers=headers) as response:
            response.raise_for_status()
            body = bytearray()
            for chunk in response.iter_bytes():
                if len(body) + len(chunk) > MAX_EVIDENCE_RESPONSE_BYTES:
                    raise ValueError("근거 읽기 응답이 허용 크기를 초과했습니다.")
                body.extend(chunk)
    return validate_read_result(json.loads(body), payload, validated_refs)
