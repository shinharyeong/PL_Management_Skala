"""요청 인용의 재작성 방지와 마지막 호출의 정직한 미해결 종료를 검증한다."""
from __future__ import annotations

import unittest
from unittest.mock import Mock

from test_assistant_guards import native_call, observation_fixture, plan_arguments, request_fixture, selection_arguments
from app.config import Settings
from app.decision_agent import assistant_graph as graph
from app.decision_agent.assistant_brief import validate_plan
from app.decision_agent.assistant_core import FinishValidationError, tool_schemas
from app.decision_agent.assistant_presentation import citation_catalog


class AssistantPlanningBudgetTests(unittest.TestCase):
    """계획 범위를 유지하며 종료할 예산을 조회로 소진하지 않도록 검사한다."""

    def setUp(self) -> None:
        """합성 실행의 사용량과 설정을 준비한다.

        Args: 없음. Returns: 없음. Raises: 없음.
        Side Effects: 테스트 프로세스의 메모리 사용량만 초기화한다.
        """
        graph.CallLog.clear()
        self.settings = Settings(openai_api_key="synthetic-key", ai_daily_call_limit=1000)

    def test_short_request_quote_enum_preserves_literal_full_request_and_distinct_plan(self) -> None:
        """짧은 복합 요청의 원문을 그대로 선택하되 모델이 서로 다른 요구를 구분한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 원문 변경·계획 항목 소실·인용 상한 변경.
        Side Effects: 합성 스키마와 계획만 검사한다.
        """
        message = "목표와 전망을 비교하고, 조직별 계획 차이와 먼저 확인할 점을 정리해줘."
        schema = tool_schemas([], [], request_message=message)[0]["function"]["parameters"]
        quote = schema["$defs"]["PlannedRequirement"]["properties"]["requestQuote"]
        self.assertEqual(quote["enum"], [message])
        self.assertEqual(quote["maxLength"], 500)
        plan = {"requirements": [{"id": f"r{index}", "requestQuote": message, "question": question} for index, question in enumerate(
            ("목표와 전망의 차이는 무엇인가?", "조직별 계획 차이는 무엇인가?", "무엇을 먼저 확인해야 하는가?"), 1)]}
        self.assertEqual(validate_plan(plan, message), plan["requirements"])
        plan["requirements"] = [dict(plan["requirements"][0], requestQuote="목표와 전망을 비교하고")]
        with self.assertRaises(FinishValidationError) as caught:
            validate_plan(plan, message)
        self.assertEqual(caught.exception.code, "PLAN_COVERAGE")

    def test_long_request_retains_partial_quote_coverage_without_invalid_enum(self) -> None:
        """긴 질문에 상한을 초과하는 enum을 넣지 않고 기존 전체 인용 범위 검사를 유지한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 긴 질문 유실·인용 상한 우회.
        Side Effects: 합성 문자열과 스키마만 검사한다.
        """
        first = "가" * 300
        second = "나" * 300
        message = first + ". " + second
        schema = tool_schemas([], [], request_message=message)[0]["function"]["parameters"]
        self.assertNotIn("enum", schema["$defs"]["PlannedRequirement"]["properties"]["requestQuote"])
        plan = {"requirements": [{"id": "r1", "requestQuote": first, "question": "앞선 요구는 무엇인가?"},
                                 {"id": "r2", "requestQuote": second, "question": "뒤의 요구는 무엇인가?"}]}
        self.assertEqual(validate_plan(plan, message), plan["requirements"])
        with self.assertRaises(FinishValidationError):
            validate_plan({"requirements": plan["requirements"][:1]}, message)

    def test_final_call_reports_unresolved_requirements_instead_of_more_lookup(self) -> None:
        """계획 재시도 후에도 마지막 호출에서 전체 요구와 부족 자료를 정직하게 반환한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 마지막 조회·가짜 완료·요구 누락.
        Side Effects: 합성 모델·업무 조회와 메모리 로그만 실행한다.
        """
        request = request_fixture()
        request["message"] = "목표와 전망을 비교하고 조직 차이와 먼저 확인할 점을 정리해줘."
        plan = {"requirements": [{"id": f"r{index}", "requestQuote": request["message"], "question": question} for index, question in enumerate(
            ("목표와 전망의 차이는 무엇인가?", "조직별 차이는 무엇인가?", "무엇을 먼저 확인해야 하는가?"), 1)]}
        bad_plan = plan_arguments("목표와 전망을 비교하고")
        observations = [observation_fixture("analysis_context", 1), observation_fixture("goal_preview", 2)]
        option = next(item for item in citation_catalog(observations) if item["observationId"] == "obs-2" and item["path"] == "/data/amountWon")
        proposal = selection_arguments(option["id"])
        proposal["requirements"] += [{"id": identifier, "status": "NEEDS_INPUT", "findingIds": [],
                                       "missingInformation": "조직별 차이와 연결된 소명을 아직 조회하지 못했습니다.",
                                       "followUpQuestion": "조직별 차이와 승인 소명을 확인하는 후속 조회를 요청해 주세요."} for identifier in ("r2", "r3")]
        model = Mock(side_effect=[native_call("plan_analysis", bad_plan, 0), native_call("plan_analysis", bad_plan, 1),
                                  native_call("plan_analysis", plan, 2), native_call("analysis_context", {}, 3),
                                  native_call("goal_preview", {"targetProfitEok": "1200", "targetYear": 2026}, 4),
                                  native_call("finish_answer", proposal, 5)])
        tool = Mock(side_effect=observations)
        with self.assertLogs(graph.logger, level="WARNING"):
            result = graph.run_assistant(request, settings=self.settings, model_caller=model, tool_caller=tool)
        self.assertEqual((model.call_count, tool.call_count, result["status"]), (6, 2, "NEEDS_INPUT"))
        self.assertEqual([item["id"] for item in result["brief"]["requirements"]], ["r1", "r2", "r3"])
        self.assertEqual([item["status"] for item in result["brief"]["requirements"]], ["ANSWERED", "NEEDS_INPUT", "NEEDS_INPUT"])
        self.assertEqual([item["function"]["name"] for item in model.call_args.args[2]], ["finish_answer"])
        self.assertEqual(result["observations"], observations)

    def test_last_call_cannot_execute_read_tool_even_if_model_ignores_schema(self) -> None:
        """마지막 호출의 조회 요청은 callback 전에 거부하고 완료로 표시하지 않는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 종료 예산의 조회 사용·거짓 완료.
        Side Effects: 합성 모델만 실행하며 업무 조회는 실행하지 않는다.
        """
        model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 0)] +
                                 [native_call("not_allowed", {}, index) for index in range(1, 5)] +
                                 [native_call("company_pnl", {}, 5)])
        tool = Mock()
        result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model, tool_caller=tool)
        self.assertEqual((model.call_count, result["status"]), (6, "LIMIT_REACHED"))
        tool.assert_not_called()
        self.assertEqual(result["observations"], [])


if __name__ == "__main__":
    unittest.main()
