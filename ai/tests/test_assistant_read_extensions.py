"""기준월·보고 항목 읽기 도구의 범위 인자와 원문 인용을 합성 관찰로 검증한다."""
from __future__ import annotations

from copy import deepcopy
import unittest
from unittest.mock import Mock

from test_assistant_guards import native_call, observation_fixture, plan_arguments, request_fixture, selection_arguments
from app.config import Settings
from app.decision_agent import assistant_graph as graph
from app.decision_agent.assistant_core import normalize_arguments, tool_schemas
from app.decision_agent.assistant_presentation import citation_catalog, model_observation, projected_data


def extended_observation(tool: str) -> dict:
    """기존 callback 계약을 따르는 월간·보고 항목 합성 응답을 만든다.

    Args: tool 신규 허용 읽기 도구명.
    Returns: 동일 고정 기준과 정밀 문자열 금액을 가진 합성 관찰.
    Raises: 없음. Side Effects: 메모리 사전만 생성한다.
    """
    observation = observation_fixture(tool)
    if tool == "monthly_pnl":
        observation["data"] = {"period": "MONTH", "year": 2026, "month": 11, "unit": "KRW",
            "companyScope": "ALL_MASTER_ORGANIZATIONS", "selectedOrganizationId": "7",
            "company": {"kpis": [{"key": "OP", "actual": "105.125", "vsPlanAmount": "-10.5"}],
                        "rows": [{"syntheticLongRow": "not-for-model"}]},
            "organizations": {"summary": {"rankedByOpPlanDifference": [{"organization": {"id": "7", "name": "합성 조직"},
                              "planDifferenceWon": "-10.5"}]}, "total": {"syntheticGlobalTotal": "999"}}}
    else:
        observation["data"] = {"period": "YEAR", "year": 2026, "baseMonth": 11, "unit": "KRW",
            "comparison": "SAME_SUBMISSION_PLAN_VS_ANNUAL_OUTLOOK", "availableCount": 1, "sourceLimit": 100,
            "sourceLimitMayApply": True, "limit": 20, "capturedAt": None,
            "items": [{"refId": "pnl-7-9", "kind": "PNL", "organizationId": "7", "label": "합성 조직 · 외주비",
                       "text": "동일 제출본 계획과 전망의 차이이며 개선 가능액이 아닙니다.",
                       "facts": {"reportItemId": "9", "kind": "COST", "forecastWon": "30.125", "planWon": "20",
                                 "varianceWon": "10.125", "profitImpactWon": "-10.125", "sourceSubmissionIds": ["11"],
                                 "comparison": "SAME_SUBMISSION_PLAN_VS_ANNUAL_OUTLOOK"}}]}
    return observation


class AssistantReadExtensionTests(unittest.TestCase):
    """기존 업무 계산을 재사용하며 새로운 읽기 범위를 원문 그대로 전달하는지 확인한다."""

    def test_arguments_reject_period_scope_and_invalid_identifiers(self) -> None:
        """기간·사용자 scope를 도구 인자로 주입하거나 잘못된 ID·상한을 입력할 수 없다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 범위 주입·잘못된 인자 수락.
        Side Effects: 합성 인자와 스키마만 검사한다.
        """
        self.assertEqual(normalize_arguments("monthly_pnl", {}), {})
        self.assertEqual(normalize_arguments("report_item_breakdown", {"organizationId": "7", "reportItemId": "9"}),
                         {"organizationId": "7", "reportItemId": "9", "limit": 20})
        for tool in ("monthly_pnl", "report_item_breakdown"):
            for args in ({"year": 2027}, {"month": 1}, {"masterId": "2"}, {"organizationId": "001"}, {"organizationId": 7}):
                with self.subTest(tool=tool, args=args), self.assertRaises(ValueError):
                    normalize_arguments(tool, args)
        for args in ({"reportItemId": "0"}, {"reportItemId": "account-name"}, {"limit": 51}, {"limit": True}):
            with self.subTest(args=args), self.assertRaises(ValueError):
                normalize_arguments("report_item_breakdown", args)
        names = {item["function"]["name"] for item in tool_schemas([], plan_arguments()["requirements"])}
        self.assertTrue({"monthly_pnl", "report_item_breakdown"} <= names)

    def test_monthly_projection_preserves_scope_and_original_paths(self) -> None:
        """큰 상세행을 생략해도 월간·전사 범위와 선택 조직·원문 경로는 보존한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 연간 혼합·원본 변경·전사 합계의 조직값 오표시.
        Side Effects: 합성 사전의 모델용 투영만 생성한다.
        """
        observation = extended_observation("monthly_pnl")
        before = deepcopy(observation)
        projected = projected_data(observation)
        self.assertEqual((projected["period"], projected["companyScope"], projected["selectedOrganizationId"]),
                         ("MONTH", "ALL_MASTER_ORGANIZATIONS", "7"))
        self.assertNotIn("rows", projected["company"])
        self.assertNotIn("total", projected["organizations"])
        paths = {item["path"] for item in citation_catalog([observation])}
        self.assertIn("/data/company/kpis/0/actual", paths)
        self.assertIn("/data/organizations/summary/rankedByOpPlanDifference/0/planDifferenceWon", paths)
        self.assertEqual(observation, before)

    def test_report_item_limits_basis_and_precision_reach_model(self) -> None:
        """보고 항목 조회의 상한과 계획 기준·원 단위 정밀도가 모델 문맥에 유지된다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 제한·비교 기준 유실 또는 금액 변환.
        Side Effects: 합성 카탈로그와 모델 문맥만 메모리에서 생성한다.
        """
        observation = extended_observation("report_item_breakdown")
        catalog = citation_catalog([observation])
        view = model_observation(observation, catalog)
        self.assertEqual(view["context"]["comparison"], "SAME_SUBMISSION_PLAN_VS_ANNUAL_OUTLOOK")
        self.assertTrue(view["context"]["sourceLimitMayApply"])
        self.assertEqual(view["context"]["sourceLimit"], 100)
        amount = next(item for item in catalog if item["path"] == "/data/items/0/facts/profitImpactWon")
        self.assertEqual(amount["quote"], "-10.125")

    def test_model_selected_extensions_use_callback_and_actual_citations(self) -> None:
        """신규 도구도 모델 선택→기존 callback→실제 원문 인용 종료 경로를 사용한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 고정 조회·추가 계산·인용 발명.
        Side Effects: 합성 모델과 업무 callback 대역만 실행한다.
        """
        for tool, path in (("monthly_pnl", "/data/company/kpis/0/actual"),
                           ("report_item_breakdown", "/data/items/0/facts/profitImpactWon")):
            graph.CallLog.clear()
            observation = extended_observation(tool)
            option = next(item for item in citation_catalog([observation]) if item["path"] == path)
            proposal = selection_arguments(option["id"])
            proposal["findings"][0]["text"] = "선택한 범위의 비교 자료를 확인했습니다."
            model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 0), native_call(tool, {}, 1),
                                      native_call("finish_answer", proposal, 2)])
            callback = Mock(return_value=observation)
            result = graph.run_assistant(request_fixture(), settings=Settings(openai_api_key="synthetic-key", ai_daily_call_limit=1000),
                                         model_caller=model, tool_caller=callback)
            self.assertEqual(result["status"], "COMPLETED")
            self.assertEqual(result["citations"][0]["path"], path)
            self.assertEqual(callback.call_args.args[0], tool)
            callback.assert_called_once()


if __name__ == "__main__":
    unittest.main()
