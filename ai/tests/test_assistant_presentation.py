"""근거 선택 종료·모델용 투영·부분 응답·목표 읽기 계약의 무네트워크 검증."""
from __future__ import annotations

from copy import deepcopy
import json
import unittest
from unittest.mock import Mock

from test_assistant_guards import native_call, observation_fixture, request_fixture, plan_arguments, missing_arguments
from app.config import Settings
from app.decision_agent import assistant_graph as graph
from app.decision_agent.assistant_core import FinishValidationError, normalize_arguments, tool_schemas, validate_finish
from app.decision_agent.assistant_presentation import (MAX_MODEL_VIEW_BYTES, citation_catalog, model_observation,
                                                       partial_finish, resolve_selection)


class AssistantPresentationTests(unittest.TestCase):
    """원장 원문을 선택 ID로 연결하며 모델의 계산·본문 작성을 배제하는 계약."""

    def test_catalog_ids_stay_stable_and_restore_exact_scalars(self) -> None:
        """숫자 조직명·부호·null·boolean·escaped 경로를 실제 원문 인용으로 복원한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: ID 이동·정밀도 손실·인용 변형이 있으면 발생.
        Side Effects: 합성 사전만 메모리에서 사용한다.
        """
        observation = observation_fixture()
        observation["data"]["name"] = "합성사업부1"
        before = deepcopy(observation)
        catalog = citation_catalog([observation])
        later = citation_catalog([observation, observation_fixture("profit_drivers", 2)])
        self.assertEqual(catalog, later[:len(catalog)])
        for option in catalog:
            answer = resolve_selection({"citationIds": [option["id"]]}, [observation])
            self.assertEqual(answer["citations"], [{key: option[key] for key in ("observationId", "path", "quote")}])
            self.assertNotIn("합성사업부1", answer["summary"])
        self.assertEqual(observation, before)

    def test_model_view_omits_large_company_rows_without_changing_public_data(self) -> None:
        """대형 상세 행을 모델에 보내지 않으면서 원장과 비용 유불리 원문을 유지한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 원장 변형·대형 원문 노출·새 판단 생성 시 발생.
        Side Effects: 합성 행과 카탈로그만 메모리에 구성한다.
        """
        observation = observation_fixture()
        observation["data"] = {"dataStatus": "CONFIRMED", "rows": [{"name": "detail-row-marker" * 100} for _ in range(100)],
                               "kpis": [{"key": "COST", "actual": "120", "vsPlanAmount": "20"}],
                               "summary": {"metrics": [{"key": "COST", "actualWon": "120", "planDifferenceWon": "20",
                                                           "direction": "INCREASE", "favorability": "UNFAVORABLE", "missing": False}]}}
        before = deepcopy(observation)
        catalog = citation_catalog([observation])
        view = model_observation(observation, catalog)
        encoded = json.dumps(view, ensure_ascii=False).encode()
        self.assertLessEqual(len(encoded), MAX_MODEL_VIEW_BYTES)
        self.assertNotIn(b"detail-row-marker", encoded)
        unfavorable = next(item for item in catalog if item["path"].endswith("/favorability"))
        answer = resolve_selection({"citationIds": [unfavorable["id"]]}, [observation])
        self.assertEqual(answer["citations"][0]["quote"], "UNFAVORABLE")
        self.assertNotIn("개선", answer["summary"])
        self.assertEqual(observation, before)

    def test_finish_selection_rejects_old_fields_unknown_ids_and_duplicates(self) -> None:
        """자유 본문·직접 pointer·허구 ID·중복 선택은 기존 검증 전에 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 모델이 선택 계약을 우회하면 발생.
        Side Effects: 없음.
        """
        observation = observation_fixture()
        identifier = citation_catalog([observation])[0]["id"]
        for value, code in (({"citationIds": [identifier], "summary": "비용 증가로 개선되었습니다."}, "FINISH_SELECTION_SCHEMA"),
                            ({"citationIds": ["previous-turn-id"]}, "CITATION_OPTION_UNKNOWN"),
                            ({"citationIds": [identifier, identifier]}, "CITATION_DUPLICATE"),
                            ({"citationIds": []}, "CITATION_OPTION_REQUIRED")):
            with self.subTest(code=code), self.assertRaises(FinishValidationError) as caught:
                resolve_selection(value, [observation])
            self.assertEqual(caught.exception.code, code)
        schema = next(item for item in tool_schemas([identifier]) if item["function"]["name"] == "finish_answer")
        self.assertEqual(set(schema["function"]["parameters"]["properties"]), {"summary", "findings", "requirements", "nextActions"})
        self.assertEqual(schema["function"]["parameters"]["$defs"]["GroundedFinding"]["properties"]["citationIds"]["items"]["enum"], [identifier])

    def test_partial_response_uses_successful_current_sources_only(self) -> None:
        """부분 답변은 현재 성공 자료만 포함하고 원래 실패 상태를 대신 결정하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 실패 자료나 허구 인용이 추가되면 발생.
        Side Effects: 없음.
        """
        good, bad = observation_fixture(), observation_fixture("scenario_preview", 2, "ERROR")
        partial = partial_finish([good, bad])
        self.assertIsNotNone(partial)
        self.assertIn("부분 결과", partial["summary"])
        self.assertTrue(all(item["observationId"] == good["id"] for item in partial["citations"]))
        validate_finish(partial, [good, bad])
        self.assertIsNone(partial_finish([bad]))

    def test_empty_success_can_finish_with_context_and_needs_input(self) -> None:
        """빈 성공 응답에서도 실제 비교 기준을 인용하고 근거 부족 상태로 종료한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 빈 자료가 완료로 표시되거나 종료 거절 루프를 만들면 발생.
        Side Effects: 합성 모델·Tool 대역만 호출한다.
        """
        for data in ({}, {"items": []}):
            observation = observation_fixture()
            observation["data"] = data
            catalog = citation_catalog([observation])
            self.assertEqual(catalog[0]["path"], "/basis/comparisonBasis")
            model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 0), native_call("company_pnl", {}, 1), native_call("finish_answer", missing_arguments(), 2)])
            result = graph.run_assistant(request_fixture(), settings=Settings(openai_api_key="synthetic-key", ai_daily_call_limit=1000),
                                         model_caller=model, tool_caller=Mock(return_value=observation))
            self.assertEqual(result["status"], "NEEDS_INPUT")
            self.assertEqual(model.call_count, 3)
            self.assertEqual(result["citations"], [])
            self.assertTrue(result["brief"]["requirements"][0]["followUpQuestion"])

    def test_goal_preview_accepts_won_precision_without_doing_calculation(self) -> None:
        """억 원 목표는 양수·최소 원 단위 문자열만 통과시키고 환산하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 금액 정밀도·단위·형식 검사가 깨지면 발생.
        Side Effects: 없음. 백엔드·DB를 호출하지 않는다.
        """
        for value in ("900", "0.00000001", "9999999999999999.99999999"):
            self.assertEqual(normalize_arguments("goal_preview", {"targetProfitEok": value}), {"targetProfitEok": value})
        for value in ("0", "-1", "+1", "01", "1e3", "1,000", "900억", "0.000000001", " 900", 900):
            with self.subTest(value=value), self.assertRaises(ValueError):
                normalize_arguments("goal_preview", {"targetProfitEok": value})

    def test_timeout_and_limit_keep_valid_partial_evidence(self) -> None:
        """실제 모델 호출 없이 timeout·호출 한도의 부분 근거와 안전한 오류 코드만 확인한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 실패를 완료로 바꾸거나 원문 오류가 노출되면 발생.
        Side Effects: 메모리 모델·도구 대역과 로그 캡처만 사용한다.
        """
        settings = Settings(openai_api_key="synthetic-key", ai_daily_call_limit=1000)
        graph.CallLog.clear()
        model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 0), native_call("company_pnl", {}, 1), TimeoutError("private-provider-detail")])
        with self.assertLogs(graph.logger, level="WARNING") as captured:
            result = graph.run_assistant(request_fixture(), settings=settings, model_caller=model,
                                         tool_caller=Mock(return_value=observation_fixture()))
        self.assertEqual(result["status"], "FAILED")
        self.assertTrue(result["citations"])
        self.assertEqual(captured.records[0].getMessage(), "decision_assistant model_failed code=MODEL_TIMEOUT")
        self.assertNotIn("private-provider-detail", str(result))
        self.assertLessEqual(model.call_args.args[0].ai_timeout_seconds, 40)
        model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 0), native_call("company_pnl", {}, 1)] + [native_call("not_allowed", {}, index) for index in range(2, 7)])
        limited = graph.run_assistant(request_fixture(), settings=settings, model_caller=model,
                                      tool_caller=Mock(return_value=observation_fixture()))
        self.assertEqual(model.call_count, 6)
        self.assertEqual(limited["status"], "LIMIT_REACHED")
        self.assertIn("부분 결과", limited["answer"])
        self.assertTrue(limited["citations"])
