"""실제 인증·스트림 제한·입력 검증 함수를 분리해 실행하는 HTTP 경계 검증.

레거시 DB·모델 의존성을 불러오지 않도록 production AST의 라우트 정의만 사용한다.
설명 생성 함수는 대역이며 실제 LangGraph 검증은 test_decision_agent_runtime.py가 담당한다.
"""
from __future__ import annotations

import ast
from collections.abc import AsyncIterator
import hmac
import importlib.util
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock

from test_decision_agent import analysis_fixture

sys.path.insert(0, str(Path(__file__).parents[1]))
HAS_HTTP = all(importlib.util.find_spec(name) is not None for name in ("fastapi", "httpx", "pydantic"))


@unittest.skipUnless(HAS_HTTP, "FastAPI·httpx·Pydantic이 설치되어 있지 않습니다.")
class DecisionHttpTests(unittest.IsolatedAsyncioTestCase):
    """기존 내부 인증과 신규 입력 제한을 실제 ASGI 요청으로 검증한다."""

    def setUp(self) -> None:
        """생산 라우트의 AST로 파일·DB 접근 없는 테스트 앱을 구성한다.

        Args: 없음.
        Returns: 없음.
        Raises: ImportError 또는 SyntaxError: 실제 계약 정의가 잘못된 경우.
        Side Effects: 소스 파일을 읽고 프로세스 메모리에 ASGI 앱을 만든다.
        """
        import fastapi
        from pydantic import ValidationError
        from starlette.concurrency import run_in_threadpool
        from app.decision_agent.models import DecisionComposeRequest
        self.settings = SimpleNamespace(report_service_token="internal-test-only", require_internal_token=True)
        self.compose = Mock(return_value={"mode": "TEMPLATE", "summary": "고정 초안", "decisionRequests": [],
                                          "limitations": [], "steps": []})
        namespace = {"__name__": "decision_http_boundary", "hmac": hmac, "get_settings": Mock(return_value=self.settings),
                     "DecisionComposeRequest": DecisionComposeRequest, "ValidationError": ValidationError,
                     "run_in_threadpool": run_in_threadpool, "compose_analysis": self.compose,
                     **{name: getattr(fastapi, name) for name in
                        ("APIRouter", "Depends", "Header", "HTTPException", "Request")}}
        path = Path(__file__).parents[1] / "app" / "api" / "contract.py"
        tree = ast.parse(path.read_text())
        functions = {"internal_only", "read_decision_request", "decision_agent_compose"}
        assignments = {"router", "MAX_DECISION_BODY_BYTES"}
        nodes = [node for node in tree.body if
                 isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name in functions or
                 isinstance(node, ast.Assign) and any(isinstance(target, ast.Name) and target.id in assignments
                                                     for target in node.targets)]
        exec(compile(ast.Module(body=nodes, type_ignores=[]), str(path), "exec"), namespace)
        self.app = fastapi.FastAPI()
        self.app.include_router(namespace["router"])
        self.path = "/v1/decision-agent/compose"
        self.headers = {"X-Internal-Token": "internal-test-only"}
        self.limit = namespace["MAX_DECISION_BODY_BYTES"]

    async def test_internal_token_is_required_before_reading_large_body(self) -> None:
        """미인증 요청은 본문 검증이나 설명 실행 전에 거절한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 인증보다 설명·본문 검증이 먼저 실행되면 발생.
        Side Effects: 메모리 ASGI 요청만 수행한다.
        """
        import httpx
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url="http://test") as client:
            response = await client.post(self.path, content=b"x", headers={"Content-Length": str(self.limit + 1)})
            self.assertEqual(response.status_code, 401)
            self.settings.report_service_token = None
            missing = await client.post(self.path, json=analysis_fixture(), headers=self.headers)
            self.assertEqual(missing.status_code, 401)
        self.compose.assert_not_called()

    async def test_declared_and_chunked_body_limits_are_enforced(self) -> None:
        """Content-Length 및 실제 청크 바이트 수 모두에 상한을 적용한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 256KiB 초과 본문이 통과하면 발생.
        Side Effects: 메모리 ASGI 요청만 수행한다.
        """
        import httpx

        async def chunks() -> AsyncIterator[bytes]:
            """길이 헤더 없이 초과 본문을 여러 청크로 전달한다.

            Args: 없음.
            Returns: 바이트 청크 비동기 이터레이터.
            Raises: 없음.
            Side Effects: 없음.
            """
            yield b" " * (self.limit // 2)
            yield b" " * (self.limit // 2 + 1)

        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url="http://test") as client:
            declared = await client.post(self.path, content=b"x",
                                         headers={**self.headers, "Content-Length": str(self.limit + 1)})
            streamed = await client.post(self.path, content=chunks(), headers=self.headers)
        self.assertEqual(declared.status_code, 413)
        self.assertEqual(streamed.status_code, 413)
        self.compose.assert_not_called()

    async def test_valid_body_reaches_composer_and_invalid_body_does_not(self) -> None:
        """정상 계약만 실행하고 잘못된 입력은 원문 노출 없이 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 잘못된 입력이 실행되거나 응답에 노출되면 발생.
        Side Effects: 메모리 ASGI 요청과 대역 설명 함수를 호출한다.
        """
        import httpx
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url="http://test") as client:
            payload = analysis_fixture()
            response = await client.post(self.path, json=payload, headers=self.headers)
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["mode"], "TEMPLATE")
            self.compose.assert_called_once_with(payload)
            self.compose.reset_mock()
            payload["targetProfitWon"] = 105000000000
            invalid = await client.post(self.path, json=payload, headers=self.headers)
            malformed = await client.post(self.path, content=b'{"secret":"not-echoed"', headers=self.headers)
        self.assertEqual(invalid.status_code, 422)
        self.assertEqual(malformed.status_code, 422)
        self.assertNotIn("not-echoed", malformed.text)
        self.compose.assert_not_called()

    async def test_context_mismatch_is_not_returned_as_success(self) -> None:
        """실행기의 연결 검증 오류를 정상 초안으로 표시하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 연결 오류가 성공 응답이면 발생.
        Side Effects: 메모리 ASGI 요청만 수행한다.
        """
        import httpx
        self.compose.side_effect = ValueError("private-input")
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app), base_url="http://test") as client:
            response = await client.post(self.path, json=analysis_fixture(), headers=self.headers)
        self.assertEqual(response.status_code, 422)
        self.assertNotIn("private-input", response.text)


if __name__ == "__main__":
    unittest.main()
