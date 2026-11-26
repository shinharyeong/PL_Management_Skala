"""실제 native 메시지 변환·LangGraph·HTTP Tool 왕복의 무네트워크 계약 검증."""
from __future__ import annotations

import json
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import httpx

sys.path.insert(0, str(Path(__file__).parents[1]))
from app.config import Settings
from app.decision_agent.assistant_graph import run_assistant
from app.decision_agent import assistant_tools
from app.services import openai_client


def request_fixture() -> dict:
    """선택 마스터만 있고 저장 분석은 없는 대화 요청을 만든다.

    Args: 없음.
    Returns: 실제 DB·사용자와 무관한 합성 요청.
    Raises: 없음.
    Side Effects: 없음.
    """
    return {"message": "계획과 차이가 나는 조직을 확인해 줘.", "history": [],
            "context": {"masterId": "3", "masterLabel": "합성 확정 마스터", "year": 2026,
                        "baseMonth": 11, "analysisId": None, "revision": None,
                        "asOf": "2026-11-26T00:00:00Z"},
            "toolAccessToken": "synthetic-private-capability"}


def sdk_response(name: str, arguments: dict, sequence: int) -> SimpleNamespace:
    """native function 호출을 가진 SDK 응답 대역을 만든다.

    Args: name 도구명, arguments 인자 객체, sequence 호출 식별자 순번.
    Returns: OpenAI chat.completions 응답 모양의 메모리 객체.
    Raises: 없음.
    Side Effects: 없음.
    """
    call = SimpleNamespace(id=f"provider-call-{sequence}", type="function",
                           function=SimpleNamespace(name=name, arguments=json.dumps(arguments, ensure_ascii=False)))
    message = SimpleNamespace(content=None, tool_calls=[call])
    return SimpleNamespace(choices=[SimpleNamespace(message=message)],
                           usage=SimpleNamespace(prompt_tokens=30, completion_tokens=15))


class AdaptiveProvider:
    """도구 관찰의 차이가 있을 때만 다음 도구를 선택하는 제공자 대역."""

    def __init__(self) -> None:
        """SDK 호출 기록을 준비한다.

        Args: 없음.
        Returns: 없음.
        Raises: 없음.
        Side Effects: 인스턴스 메모리만 초기화.
        """
        self.requests: list[dict] = []
        self.chat = SimpleNamespace(completions=SimpleNamespace(create=self.create))

    def create(self, **kwargs) -> SimpleNamespace:
        """이전 HTTP Tool 결과를 실제 SDK 메시지에서 읽고 다음 호출을 결정한다.

        Args: kwargs 공통 LLM 어댑터가 만든 SDK 요청.
        Returns: 전사 조회, 추가 조직 조회 또는 종료 호출.
        Raises: AssertionError 잘못된 native 전송·도구 메시지 연결.
        Side Effects: 제공자 호출의 메모리 기록 추가.
        """
        self.requests.append(kwargs)
        assert "tools" in kwargs and "response_format" not in kwargs
        assert kwargs["parallel_tool_calls"] is False
        assert "synthetic-private-capability" not in json.dumps(kwargs, ensure_ascii=False)
        assert "synthetic-internal-token" not in json.dumps(kwargs, ensure_ascii=False)
        tool_messages = [message for message in kwargs["messages"] if message["role"] == "tool"]
        if not tool_messages:
            return sdk_response("plan_analysis", {"requirements": [{"id": "r1", "requestQuote": request_fixture()["message"],
                                "question": "계획 차이와 검토할 조직은 무엇인가?"}]}, len(self.requests))
        last = tool_messages[-1]
        earlier_calls = [call["id"] for message in kwargs["messages"] if message["role"] == "assistant"
                         for call in message.get("tool_calls", [])]
        assert last["tool_call_id"] in earlier_calls
        observation = json.loads(last["content"])
        if observation.get("status") == "PLANNED":
            return sdk_response("company_pnl", {}, len(self.requests))
        if observation["tool"] == "company_pnl":
            option = next(item for item in observation["citationOptions"] if item["sourceField"] == "/data/kpis/0/vsPlanAmount")
            amount = option["value"]
            if amount != "0":
                return sdk_response("profit_drivers", {"limit": 3}, len(self.requests))
        else:
            option = next(item for item in observation["citationOptions"] if item["sourceField"] == "/data/items/0/opDiff")
        return sdk_response("finish_answer", {
            "summary": "조회 결과의 계획 차이를 근거로 검토 방향을 정리했습니다.",
            "findings": [{"id": "f1", "kind": "FACT", "text": "계획 대비 차이가 조회되었습니다.",
                          "citationIds": [option["id"]], "uncertainty": None}],
            "requirements": [{"id": "r1", "status": "ANSWERED", "findingIds": ["f1"],
                              "missingInformation": None, "followUpQuestion": None}], "nextActions": []}, len(self.requests))


class AssistantNativeContractTests(unittest.TestCase):
    """네트워크 대신 제공자·백엔드 경계만 대체하고 실제 Agent 런타임을 검증한다."""

    def test_observation_changes_the_next_native_tool_call(self) -> None:
        """서로 다른 첫 관찰에서 조회 순서가 달라지고 올바른 출처로 종료한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError 고정 조회 순서·native 메시지 유실·인용 불일치.
        Side Effects: 합성 HTTP와 SDK 호출만 메모리로 처리; DB·외부 네트워크·파일 생성 없음.
        """
        real_client = httpx.Client
        for difference, expected in (("0", ["company_pnl"]),
                                     ("-15000000000", ["company_pnl", "profit_drivers"])):
            with self.subTest(difference=difference):
                provider = AdaptiveProvider()
                requests: list[dict] = []

                def backend(request: httpx.Request) -> httpx.Response:
                    """실제 HTTP 직렬화·토큰 전달을 검사하고 업무 서비스 응답을 모사한다.

                    Args: request 내부 도구 HTTP 요청.
                    Returns: 고정 문맥과 원 단위 문자열이 있는 관찰 응답.
                    Raises: AssertionError 경로·인증·문맥 권한 계약 불일치.
                    Side Effects: 메모리 호출 기록만 추가.
                    """
                    assert str(request.url) == "http://backend.test/internal/decision-agent/tools/call"
                    assert request.headers["X-Internal-Token"] == "synthetic-internal-token"
                    assert request.headers["X-Decision-Tool-Token"] == "synthetic-private-capability"
                    body = json.loads(request.content)
                    requests.append(body)
                    assert set(body) == {"tool", "arguments"}
                    assert not any(key in body["arguments"] for key in ("actorId", "masterId", "year"))
                    if body["tool"] == "company_pnl":
                        data = {"dataStatus": "CONFIRMED", "kpis": [{"key": "OP", "actual": "92000000000",
                                "vsPlanRate": None, "vsPlanAmount": difference}], "rows": [], "opMargin": None}
                    else:
                        assert body == {"tool": "profit_drivers", "arguments": {"limit": 3}}
                        data = {"dataStatus": "CONFIRMED", "total": difference, "items": [
                            {"organization": {"id": "1", "code": "A", "name": "합성 조직"},
                             "opDiff": difference, "opDiffRate": None}]}
                    return httpx.Response(200, json={"id": f"obs-{len(requests)}", "tool": body["tool"],
                        "label": "기존 손익 서비스 조회", "status": "SUCCESS",
                        "basis": {**request_fixture()["context"], "capturedAt": "2026-11-26T00:00:00Z",
                                  "comparisonBasis": "조회 시점 비교"}, "data": data, "warnings": []})

                def client_factory(*args, **kwargs) -> httpx.Client:
                    """HTTP 전송만 대역으로 바꾸고 실제 Client·응답 처리를 유지한다.

                    Args: args/kwargs Agent가 지정한 HTTP Client 설정.
                    Returns: 네트워크를 사용하지 않는 실제 httpx Client.
                    Raises: AssertionError 프록시·리디렉션 정책 불일치.
                    Side Effects: 메모리 Client 객체 생성만 수행.
                    """
                    assert kwargs.get("trust_env") is False
                    assert kwargs.get("follow_redirects") is False
                    kwargs["transport"] = httpx.MockTransport(backend)
                    return real_client(*args, **kwargs)

                settings = Settings(openai_api_key="synthetic-provider-key", backend_url="http://backend.test",
                                    report_service_token="synthetic-internal-token", require_internal_token=True,
                                    ai_daily_call_limit=1000)
                with patch.object(openai_client, "make_client", return_value=provider), \
                     patch.object(assistant_tools.httpx, "Client", side_effect=client_factory):
                    response = run_assistant(request_fixture(), settings=settings)
                self.assertEqual(response["mode"], "MODEL")
                self.assertEqual(response["status"], "COMPLETED")
                self.assertEqual([entry["tool"] for entry in requests], expected)
                self.assertEqual(len(provider.requests), len(expected) + 2)
                self.assertEqual(response["citations"][0]["quote"], difference)
                self.assertNotIn("synthetic-private-capability", json.dumps(response))

    def test_provider_failure_never_fabricates_successful_tool_results(self) -> None:
        """제공자 실패를 기존 서비스가 실행된 성공으로 표시하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError 허위 조회·성공 또는 비밀 노출.
        Side Effects: 제공자 오류 대역만 호출하며 네트워크·DB·파일 접근 없음.
        """
        settings = Settings(openai_api_key="synthetic-provider-key", ai_daily_call_limit=1000)
        with patch.object(openai_client, "make_client", side_effect=RuntimeError("provider unavailable")), \
             patch.object(assistant_tools.httpx, "Client") as client:
            response = run_assistant(request_fixture(), settings=settings)
        self.assertNotEqual(response["status"], "COMPLETED")
        self.assertEqual(response["observations"], [])
        self.assertEqual(response["citations"], [])
        client.assert_not_called()


if __name__ == "__main__":
    unittest.main()
