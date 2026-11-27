"""제공자 출력 잘림의 원문 제거·간결 복구·기존 예산 보존을 합성 응답으로 검증한다."""
from __future__ import annotations

from copy import deepcopy
import json
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from langchain_core.messages import AIMessage, HumanMessage

from test_assistant_guards import (missing_arguments, native_call, observation_fixture,
                                   plan_arguments, request_fixture, selection_arguments)
from test_assistant_native_contract import sdk_response
from test_assistant_repair import repair_arguments
from app.config import Settings
from app.decision_agent import assistant_graph as graph
from app.decision_agent.assistant_core import tool_schemas
from app.services import llm, openai_client


def truncated_call(index: int, name: str = "finish_answer") -> AIMessage:
    """깨진 JSON 원문과 length 종료를 가진 합성 native 응답을 만든다.

    Args: index 호출 ID 순번, name 허용 도구 이름.
    Returns: 실행할 수 없는 부분 인자가 담긴 AIMessage.
    Raises: 없음. Side Effects: 없음.
    """
    return AIMessage(content="synthetic-private-body", invalid_tool_calls=[{
        "name": name, "id": f"truncated-{index}", "args": '{"summary":"synthetic-private-fragment',
        "error": "synthetic JSON error", "type": "invalid_tool_call"}],
        response_metadata={"finish_reason": "length"})


class AssistantTruncationTests(unittest.TestCase):
    """잘린 응답을 근거로 성공을 발명하거나 재시도 예산을 늘리지 않는지 확인한다."""

    def setUp(self) -> None:
        """실제 키·데이터와 무관한 설정과 관찰을 준비한다.

        Args: 없음. Returns: 없음. Raises: 없음.
        Side Effects: 테스트 프로세스의 메모리 사용량만 초기화한다.
        """
        graph.CallLog.clear()
        self.settings = Settings(openai_api_key="synthetic-key", ai_daily_call_limit=1000)
        self.observation = observation_fixture()

    def test_sdk_length_discards_raw_and_recovers_with_original_evidence(self) -> None:
        """실제 SDK 변환 뒤 잘린 원문 없이 같은 계획·관찰·인용으로 간결 종료한다.

        Args: 없음. Returns: 없음.
        Raises: AssertionError: 원문 재전송·근거 변경·native 프로토콜 유실.
        Side Effects: SDK·업무 조회를 메모리 대역으로만 실행한다.
        """
        partial = sdk_response("finish_answer", {}, 2)
        partial.choices[0].finish_reason = "length"
        partial.choices[0].message.content = "synthetic-private-body"
        partial.choices[0].message.tool_calls[0].function.arguments = '{"summary":"synthetic-private-fragment'
        replies = [sdk_response("plan_analysis", plan_arguments(), 0), sdk_response("company_pnl", {}, 1),
                   partial, sdk_response("finish_answer", selection_arguments(), 3)]
        create = Mock(side_effect=replies)
        client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=create)))
        tool = Mock(return_value=self.observation)
        before = deepcopy(self.observation)
        with patch.object(openai_client, "make_client", return_value=client), self.assertLogs(graph.logger, level="WARNING") as captured:
            result = graph.run_assistant(request_fixture(), settings=self.settings, tool_caller=tool)
        self.assertEqual((result["status"], create.call_count), ("COMPLETED", 4))
        self.assertEqual(result["observations"], [before])
        self.assertEqual(self.observation, before)
        self.assertEqual(result["brief"]["requirements"][0]["requestQuote"], request_fixture()["message"])
        self.assertEqual(result["citations"][0]["path"], "/data/amountWon")
        sent = create.call_args.kwargs
        self.assertEqual([item["function"]["name"] for item in sent["tools"]], ["finish_answer"])
        self.assertTrue(sent["tools"][0]["function"]["strict"])
        self.assertFalse(sent["parallel_tool_calls"])
        call = next(call for message in sent["messages"] for call in message.get("tool_calls", []) if call["id"] == "provider-call-2")
        self.assertEqual(json.loads(call["function"]["arguments"]), {})
        error = next(message for message in sent["messages"] if message.get("tool_call_id") == "provider-call-2")
        self.assertEqual(json.loads(error["content"])["code"], "MODEL_OUTPUT_LIMIT")
        for text in (json.dumps(sent, ensure_ascii=False), str(captured.output), json.dumps(result, ensure_ascii=False)):
            self.assertNotIn("synthetic-private-fragment", text)
            self.assertNotIn("synthetic-private-body", text)
        self.assertEqual(len(result["toolCalls"]), 1)
        tool.assert_called_once()

    def test_compact_schema_supports_all_six_requirements_and_real_citation_ids(self) -> None:
        """간결한 스키마도 여섯 독립 요구와 실제 인용 enum을 유지한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 범위 축소·근거 enum 유실.
        Side Effects: 메모리 스키마만 생성한다.
        """
        for count, cap in ((1, 4), (6, 6)):
            plan = [{"id": f"r{index}", "requestQuote": "합성", "question": "확인할 사항?"} for index in range(count)]
            function = tool_schemas(["actual-citation"], plan, compact_finish=True)[0]["function"]
            schema = function["parameters"]
            self.assertEqual(schema["properties"]["findings"]["maxItems"], cap)
            self.assertEqual(schema["properties"]["summary"]["maxLength"], 160)
            finding = schema["$defs"]["GroundedFinding"]
            self.assertEqual(finding["properties"]["text"]["maxLength"], 140)
            self.assertEqual(finding["properties"]["citationIds"]["items"]["enum"], ["actual-citation"])
            self.assertEqual(schema["$defs"]["RequirementResolution"]["properties"]["id"]["enum"], [item["id"] for item in plan])
            self.assertFalse(finding["additionalProperties"])

    def test_repeated_truncation_stops_at_original_call_limit_and_keeps_partial_data(self) -> None:
        """반복 잘림은 기존 여섯 호출에서 멈추고 길이 한도와 실제 부분 결과를 표시한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 예산 증가·거짓 완료·관찰 유실.
        Side Effects: 합성 모델·조회만 실행한다.
        """
        model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 0), native_call("company_pnl", {}, 1)] +
                                 [truncated_call(index) for index in range(2, 9)])
        with self.assertLogs(graph.logger, level="WARNING"):
            result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model,
                                         tool_caller=Mock(return_value=self.observation))
        self.assertEqual((model.call_count, result["mode"], result["status"]), (6, "MODEL", "LIMIT_REACHED"))
        self.assertIn("출력 길이 한도", result["answer"])
        self.assertIsNone(result["brief"])
        self.assertEqual(result["observations"], [self.observation])
        self.assertTrue(result["citations"])

    def test_compact_mode_blocks_new_lookup_and_plan_change(self) -> None:
        """출력 복구 중 새 조회·계획 변경을 거부하며 기존 근거로만 종료한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 추가 조회·계획 덮어쓰기.
        Side Effects: 합성 모델·조회만 실행한다.
        """
        model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 0), native_call("company_pnl", {}, 1),
                                  truncated_call(2), native_call("profit_drivers", {}, 3),
                                  native_call("plan_analysis", plan_arguments(), 4), native_call("finish_answer", selection_arguments(), 5)])
        tool = Mock(return_value=self.observation)
        with self.assertLogs(graph.logger, level="WARNING"):
            result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model, tool_caller=tool)
        self.assertEqual((result["status"], model.call_count), ("COMPLETED", 6))
        text = " ".join(message.content for message in model.call_args.args[1] if isinstance(message.content, str))
        self.assertIn("COMPACT_FINISH_ONLY", text)
        self.assertIn("PLAN_IMMUTABLE", text)
        tool.assert_called_once()

    def test_half_second_after_truncation_does_not_start_another_model_call(self) -> None:
        """잘린 출력 뒤 남은 반초로 실패할 모델 호출을 시작하지 않는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 짧은 시간에 추가 모델 호출.
        Side Effects: 합성 시계와 메모리 모델 호출 목록만 변경한다.
        """
        timer, calls = Mock(return_value=0.0), []
        replies = [native_call("plan_analysis", plan_arguments(), 0), native_call("company_pnl", {}, 1), truncated_call(2)]

        def model(*args, **kwargs) -> AIMessage:
            """세 번째 응답만 마감 직전에 반환한다.

            Args: args 모델 입력, kwargs 모델 옵션. Returns: 합성 응답.
            Raises: IndexError: 추가 호출 시 테스트 실패. Side Effects: 합성 시계·호출 기록 변경.
            """
            calls.append(kwargs)
            if len(calls) == 3:
                timer.return_value = 69.5
            return replies[len(calls) - 1]

        with self.assertLogs(graph.logger, level="WARNING"):
            result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model,
                                         tool_caller=Mock(return_value=self.observation), clock=timer)
        self.assertEqual((len(calls), result["status"]), (3, "LIMIT_REACHED"))
        self.assertIn("출력 길이 한도", result["answer"])
        self.assertEqual(result["observations"], [self.observation])

    def test_less_than_minimum_budget_prevents_initial_model_call(self) -> None:
        """새 호출에는 최소 실행 여유가 필요하며 계획 전에도 같은 기준을 적용한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 시간 경계에서 모델 호출.
        Side Effects: 합성 시계만 읽는다.
        """
        timer = Mock(side_effect=[0.0, 65.1])
        model = Mock()
        result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model, clock=timer)
        self.assertEqual(result["status"], "LIMIT_REACHED")
        model.assert_not_called()

    def test_truncated_plan_retries_plan_without_accepting_partial_requirements(self) -> None:
        """계획이 잘리면 계획 계약을 유지하고 부분 인자를 계획으로 채택하지 않는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 계획 우회·부분 요구 수락.
        Side Effects: 합성 모델만 실행한다.
        """
        model = Mock(side_effect=[truncated_call(0, "plan_analysis"), native_call("plan_analysis", plan_arguments(), 1),
                                  native_call("finish_answer", missing_arguments(), 2)])
        tool = Mock()
        with self.assertLogs(graph.logger, level="WARNING"):
            result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model, tool_caller=tool)
        self.assertEqual(result["status"], "NEEDS_INPUT")
        schemas = model.call_args_list[1].args[2]
        self.assertEqual([item["function"]["name"] for item in schemas], ["plan_analysis"])
        self.assertEqual(result["brief"]["requirements"][0]["requestQuote"], request_fixture()["message"])
        tool.assert_not_called()

    def test_truncated_repair_keeps_original_proposal_and_only_repair_schema(self) -> None:
        """문장 수정이 잘려도 원래 제안·인용을 고정하고 같은 필드만 다시 수정한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 전체 답변 재작성·인용 변경·수정 범위 확장.
        Side Effects: 합성 모델·조회만 실행한다.
        """
        proposal = selection_arguments()
        proposal["findings"][0]["text"] = "삼십억원의 차이가 있습니다."
        before = deepcopy(proposal)
        model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 0), native_call("company_pnl", {}, 1),
                                  native_call("finish_answer", proposal, 2), truncated_call(3, "repair_answer"),
                                  native_call("repair_answer", repair_arguments(), 4)])
        with self.assertLogs(graph.logger, level="WARNING"):
            result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model,
                                         tool_caller=Mock(return_value=self.observation))
        self.assertEqual(result["status"], "COMPLETED")
        self.assertEqual(proposal, before)
        self.assertEqual([item["function"]["name"] for item in model.call_args.args[2]], ["repair_answer"])
        self.assertEqual(result["citations"][0]["path"], "/data/amountWon")

    def test_sdk_termination_metadata_is_allowlisted_and_optional(self) -> None:
        """공통 SDK 어댑터는 안전한 종료 종류만 전달하며 없는 값은 호환 처리한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 임의 원문 메타데이터 노출·구형 응답 실패.
        Side Effects: SDK 대역만 메모리에서 실행한다.
        """
        for reason in (None, "stop", "length", "content_filter", "tool_calls", "untrusted-private-reason"):
            response = sdk_response("plan_analysis", plan_arguments(), 1)
            if reason is not None:
                response.choices[0].finish_reason = reason
            client = SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=Mock(return_value=response))))
            with patch.object(openai_client, "make_client", return_value=client):
                reply = llm.ask_tools(self.settings, [HumanMessage(content="합성 요청")], tool_schemas([], []), max_tokens=100)
            expected = {} if reason is None or reason == "untrusted-private-reason" else {"finish_reason": reason}
            self.assertEqual(reply.response_metadata, expected)


if __name__ == "__main__":
    unittest.main()
