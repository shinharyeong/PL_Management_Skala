"""설치된 런타임에서 실행하는 무네트워크 DTO·LangGraph 검증.

pytest 전역 DB fixture를 사용하지 않고 unittest로 실행한다. 의존성이 없으면 해당 검증만 생략한다.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

from test_decision_agent import analysis_fixture

sys.path.insert(0, str(Path(__file__).parents[1]))
HAS_PYDANTIC = importlib.util.find_spec("pydantic") is not None
HAS_GRAPH = all(importlib.util.find_spec(name) is not None for name in ("langgraph", "langchain_core", "dotenv", "openai"))


@unittest.skipUnless(HAS_PYDANTIC, "Pydantic이 설치되어 있지 않습니다.")
class DecisionModelTests(unittest.TestCase):
    """원 단위 문자열과 후보·결과 상한의 전달 계약 검증."""

    def test_amounts_and_identifiers_are_not_coerced(self) -> None:
        """JSON 숫자 또는 알 수 없는 필드를 암묵적으로 변환하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 엄격한 계약을 위반한 입력이 수락되면 발생.
        Side Effects: 없음.
        """
        from app.decision_agent.models import DecisionComposeRequest
        from pydantic import ValidationError
        for field, value in (("targetProfitWon", 105000000000), ("analysisId", 1), ("unexpected", "text")):
            payload = analysis_fixture()
            payload[field] = value
            with self.subTest(field=field), self.assertRaises(ValidationError):
                DecisionComposeRequest.model_validate(payload)

    def test_backend_decimal_precision_and_excluded_future_month_are_preserved(self) -> None:
        """백엔드 소수 14자리 결과와 차년도 제외 후보를 그대로 허용한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 백엔드 정밀도나 제외 후보가 손실되면 발생.
        Side Effects: 없음.
        """
        from app.decision_agent.models import DecisionComposeRequest
        payload = analysis_fixture()
        payload["candidates"][0]["recognitionMonth"] = 24
        payload["result"]["effects"][0].update(netEffectWon="3.03333263333333", included=False,
                                                   exclusionReasons=["OUTSIDE_TARGET_YEAR"])
        parsed = DecisionComposeRequest.model_validate(payload).model_dump()
        self.assertEqual(parsed["result"]["effects"][0]["netEffectWon"], "3.03333263333333")
        self.assertEqual(parsed["candidates"][0]["recognitionMonth"], 24)

    def test_nonfinite_and_scientific_amounts_are_rejected(self) -> None:
        """금액 전달 계약의 비정상 값과 지수 표기를 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 비정상 금액이 수락되면 발생.
        Side Effects: 없음.
        """
        from app.decision_agent.models import DecisionComposeRequest
        from pydantic import ValidationError
        for value in ("NaN", "Infinity", "1e10", "1.0000001", "9" * 31):
            payload = analysis_fixture()
            payload["targetProfitWon"] = value
            with self.subTest(value=value), self.assertRaises(ValidationError):
                DecisionComposeRequest.model_validate(payload)

    def test_candidate_limit_is_enforced(self) -> None:
        """전달 후보 100개 상한을 검증한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 과도한 후보가 수락되면 발생.
        Side Effects: 없음.
        """
        from app.decision_agent.models import DecisionComposeRequest
        from pydantic import ValidationError
        payload = analysis_fixture()
        payload["candidates"] *= 101
        with self.assertRaises(ValidationError):
            DecisionComposeRequest.model_validate(payload)


@unittest.skipUnless(HAS_GRAPH, "LangGraph·LangChain 런타임이 설치되어 있지 않습니다.")
class DecisionGraphTests(unittest.TestCase):
    """실제 그래프를 실행하되 모델 요청은 대역으로만 처리한다."""

    def setUp(self) -> None:
        """실제 모델과 파일을 사용하지 않는 독립 실행 설정을 준비한다.

        Args: 없음.
        Returns: 없음.
        Raises: ImportError: 설치된 의존성이 잘못된 경우.
        Side Effects: 프로세스 메모리의 사용량 집계를 초기화한다.
        """
        from app.config import Settings
        from app.decision_agent import graph
        self.graph = graph
        self.settings = Settings(openai_api_key="test-only", output_dir=Path("decision-agent-unit-test"))
        graph.CallLog.clear()

    def test_no_model_uses_honest_template_and_all_five_stages(self) -> None:
        """키가 없으면 모델 호출 없이 실제 템플릿 실행 단계를 반환한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 모델 호출 또는 실행 경로 오표시가 있으면 발생.
        Side Effects: 프로세스 메모리에서 그래프만 실행한다.
        """
        self.settings.openai_api_key = None
        with patch.object(self.graph.llm, "ask_json") as ask:
            response = self.graph.compose_analysis(analysis_fixture(), settings=self.settings)
        ask.assert_not_called()
        self.assertEqual(response["mode"], "TEMPLATE")
        self.assertEqual(len(response["steps"]), 5)
        self.assertEqual(response["steps"][2]["status"], "TEMPLATE")
        self.assertIn("99500000000원", response["summary"])

    def test_permitted_model_selection_is_used_once(self) -> None:
        """유효한 선택만 한 차례 모델 호출 결과로 사용한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 호출 횟수·모드·금액 원문이 달라지면 발생.
        Side Effects: 대역 호출 사용량을 프로세스 메모리에 기록한다.
        """
        from app.services.llm import JsonReply
        payload = analysis_fixture()
        catalog = self.graph.build_catalog(payload)
        selection = {"summaryKey": "reviewed", "decisionKeys": list(reversed(catalog["decisions"]))}
        with patch.object(self.graph.llm, "ask_json", return_value=JsonReply(selection, 12, 8)) as ask:
            response = self.graph.compose_analysis(payload, settings=self.settings)
        ask.assert_called_once()
        self.assertEqual(response["mode"], "MODEL")
        self.assertIn("92000000000원", response["summary"])
        self.assertEqual(self.graph.CallLog(self.settings.output_dir).calls_today(), 1)

    def test_model_failure_or_extra_text_uses_template(self) -> None:
        """모델 장애와 허용되지 않은 설명이 템플릿으로 복구되는지 검증한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 잘못된 응답·오류 원문이 노출되면 발생.
        Side Effects: 대역 호출 사용량을 프로세스 메모리에 기록한다.
        """
        from app.services.llm import JsonReply
        payload = analysis_fixture()
        invalid = {**self.graph.build_catalog(payload)["default"], "summary": "비공개 원문"}
        with patch.object(self.graph.llm, "ask_json", return_value=JsonReply(invalid, 1, 1)):
            invalid_response = self.graph.compose_analysis(payload, settings=self.settings)
        with patch.object(self.graph.llm, "ask_json", side_effect=RuntimeError("비공개 원문")):
            error_response = self.graph.compose_analysis(payload, settings=self.settings)
        for response in (invalid_response, error_response):
            self.assertEqual(response["mode"], "TEMPLATE")
            self.assertNotIn("비공개 원문", str(response))

    def test_disabled_model_and_daily_limit_never_call_provider(self) -> None:
        """호출 금지와 한도 소진 시 제공자 호출을 생략한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 금지된 호출을 시도하면 발생.
        Side Effects: 프로세스 메모리에서 그래프만 실행한다.
        """
        with patch.object(self.graph.llm, "ask_json") as ask:
            disabled = self.graph.compose_analysis(analysis_fixture(), settings=self.settings, allow_model=False)
            self.settings.ai_daily_call_limit = 0
            limited = self.graph.compose_analysis(analysis_fixture(), settings=self.settings)
        ask.assert_not_called()
        self.assertEqual(disabled["mode"], "TEMPLATE")
        self.assertEqual(limited["mode"], "TEMPLATE")


if __name__ == "__main__":
    unittest.main()
