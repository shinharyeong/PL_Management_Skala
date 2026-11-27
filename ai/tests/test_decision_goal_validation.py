"""목표 제안의 명시적 보호 명령과 안전한 검증 실패를 무네트워크로 확인한다."""
from __future__ import annotations

from pathlib import Path
import unittest
from unittest.mock import patch

from app.config import Settings
from app.decision_agent import discovery_core as core
from app.decision_agent import discovery_graph as graph
from app.services.llm import JsonReply


def goal_fixture(phrase: str = "인건비와 R&D는 줄이지 마.") -> tuple[str, dict]:
    """두 보호 범주가 같은 원문 문장을 인용하는 합성 목표를 만든다.

    Args: phrase 목표 요청에 붙일 보호 조건 문장.
    Returns: 합성 요청 원문과 모델 제안 사전.
    Raises: 없음.
    Side Effects: 없음. 실제 업무 자료와 모델은 사용하지 않는다.
    """
    return "2026년 영업이익 목표 1200억원. " + phrase, {
        "targetProfitWon": "120000000000", "targetYear": 2026,
        "targetQuote": "영업이익 목표 1200억원", "yearQuote": "2026년",
        "protectedQuotes": [{"category": category, "quote": phrase}
                            for category in ("HEADCOUNT", "RND")],
        "uncertainties": [],
    }


class GoalValidationTests(unittest.TestCase):
    """원문·금액 대조를 유지하면서 명시적 감축 금지와 모호한 요청을 구분한다."""

    def setUp(self) -> None:
        """파일을 만들지 않는 합성 모델 설정과 메모리 사용량을 준비한다.

        Args: 없음.
        Returns: 없음.
        Raises: 없음.
        Side Effects: 메모리 모델 호출 집계만 초기화한다.
        """
        graph.CallLog.clear()
        self.settings = Settings(openai_api_key="synthetic-key",
                                 output_dir=Path("/private/tmp/pl-agent-verification/usage-namespace"))

    def test_explicit_reduction_prohibitions_preserve_both_categories(self) -> None:
        """명시적 감축 금지를 정확한 동일 원문 인용으로 두 범주에 제안한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 보호 범주 누락이나 사용자 확인 생략이 발생하면.
        Side Effects: 합성 제안을 메모리에서 검증한다.
        """
        for phrase in ("인건비와 R&D는 줄이지 마.", "인건비와 R&D는 줄이지 마세요.",
                       "인건비와 R&D를 감축하지 말아줘.", "인건비와 R&D를 축소하지 말아 주세요."):
            prompt, proposal = goal_fixture(phrase)
            with self.subTest(phrase=phrase):
                result = core.validate_goal(proposal, prompt, 2026)
                self.assertEqual(result["protectedCategories"], ["HEADCOUNT", "RND"])
                self.assertEqual(result["targetProfitWon"], "120000000000")
                self.assertTrue(result["needsConfirmation"])
                self.assertEqual(proposal["protectedQuotes"][0]["quote"], phrase)

    def test_conditional_cancelled_and_reversed_protection_remains_unselected(self) -> None:
        """조건·취소·반대 의미와 단순 언급을 명시적 보호로 확대하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 불명확한 조건을 자동 보호로 해석하면.
        Side Effects: 합성 제안을 메모리에서 검증한다.
        """
        for phrase in ("가능하면 인건비와 R&D는 줄이지 마.",
                       "인건비와 R&D는 줄이지 마. 앞의 요청은 취소.",
                       "인건비와 R&D는 줄이지 않아도 된다.",
                       "인건비와 R&D는 유지하지 않아도 된다.",
                       "인건비와 R&D 축소 가능.",
                       "인건비와 R&D 유지 말고 축소 허용.",
                       "인건비와 R&D 내용을 검토해 줘."):
            prompt, proposal = goal_fixture(phrase)
            with self.subTest(phrase=phrase):
                result = core.validate_goal(proposal, prompt, 2026)
                self.assertEqual(result["protectedCategories"], [])
                self.assertTrue(result["needsConfirmation"])

    def test_existing_protection_is_not_removed_by_ambiguous_new_request(self) -> None:
        """보류한 신규 보호 제안 때문에 사용자가 선택한 기존 보호를 해제하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 기존 보호 범주가 제거되면.
        Side Effects: 합성 제안을 메모리에서 검증한다.
        """
        prompt, proposal = goal_fixture("가능하면 인건비와 R&D는 줄이지 마.")
        result = core.validate_goal(proposal, prompt, 2026, ["HEADCOUNT"])
        self.assertEqual(result["protectedCategories"], ["HEADCOUNT"])

    def test_confirmation_uncertainty_is_allowed_but_claims_are_rejected(self) -> None:
        """일반 확인 질문은 허용하고 수치 추가와 명사형·서술형 달성 단정은 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 확인 질문과 결과 단정을 잘못 구분하면.
        Side Effects: 합성 제안을 메모리에서 검증한다.
        """
        prompt, proposal = goal_fixture()
        proposal["uncertainties"] = ["목표와 보호 조건을 적용하기 전에 확인해 주세요."]
        self.assertIn(proposal["uncertainties"][0], core.validate_goal(proposal, prompt, 2026)["uncertainties"])
        for text in ("목표를 반드시 달성합니다.", "효과를 보장합니다.", "추가 10억원이 필요합니다.",
                     "목표 달성 확정", "실현은 확정입니다", "성공 보장"):
            proposal["uncertainties"] = [text]
            with self.subTest(text=text), self.assertRaises(core.GoalValidationError) as caught:
                core.validate_goal(proposal, prompt, 2026)
            self.assertEqual(caught.exception.code, "UNCERTAINTY_CLAIM")

    def test_goal_value_quote_and_year_validation_stays_strict(self) -> None:
        """금액·연도·원문 없는 보호 인용은 각각의 안전한 실패 코드로 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 검증 완화나 잘못된 오류 구분이 발생하면.
        Side Effects: 합성 제안을 메모리에서 검증한다.
        """
        cases = (("targetProfitWon", "120000000001", "TARGET_VALUE"),
                 ("targetQuote", "영업이익 목표 1300억원", "TARGET_QUOTE"),
                 ("targetYear", 2027, "YEAR_QUOTE"),
                 ("protectedQuotes", [{"category": "HEADCOUNT", "quote": "인건비는 줄이지 마."}], "PROTECTED_QUOTE"))
        for field, value, code in cases:
            prompt, proposal = goal_fixture()
            proposal[field] = value
            with self.subTest(field=field), self.assertRaises(core.GoalValidationError) as caught:
                core.validate_goal(proposal, prompt, 2026)
            self.assertEqual(caught.exception.code, code)

    def test_goal_runtime_retains_model_mode_and_protection(self) -> None:
        """목표 API 로직이 정확한 모델 인용을 보호 조건과 함께 전달한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 정확한 모델 응답이 기본 처리로 바뀌거나 보호가 누락되면.
        Side Effects: 대역 모델만 한 번 호출한다. 네트워크·DB·파일 접근은 없다.
        """
        prompt, proposal = goal_fixture()
        with patch.object(graph.llm, "ask_json", return_value=JsonReply({"goalProposal": proposal}, 1, 1)) as ask:
            result = graph.interpret_goal({"prompt": prompt, "year": 2026}, settings=self.settings)
        self.assertEqual(ask.call_count, 1)
        self.assertEqual(result["mode"], "MODEL")
        self.assertEqual(result["goalProposal"]["protectedCategories"], ["HEADCOUNT", "RND"])
        self.assertTrue(result["goalProposal"]["needsConfirmation"])

    def test_validation_failure_logs_only_safe_code(self) -> None:
        """모델 인용 변조와 계약 위반을 원문 없는 코드로만 진단한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 모델 원문이 로그에 노출되거나 실패 상태를 성공으로 표시하면.
        Side Effects: 대역 모델·메모리 로그 캡처만 사용한다.
        """
        prompt, proposal = goal_fixture()
        proposal["protectedQuotes"][0]["quote"] = "외부에서만 존재하는 합성 인용"
        for value, code in (({"goalProposal": proposal}, "PROTECTED_QUOTE"),
                            ({"untrusted-model-key": "untrusted-model-value"}, "GOAL_SCHEMA_OR_CONTRACT")):
            with patch.object(graph.llm, "ask_json", return_value=JsonReply(value, 1, 1)), self.assertLogs(graph.logger, "WARNING") as logs:
                result = graph.interpret_goal({"prompt": prompt, "year": 2026}, settings=self.settings)
            self.assertEqual(result["mode"], "TEMPLATE")
            self.assertEqual(logs.records[0].getMessage(), "decision_goal validation_rejected code=" + code)
            self.assertEqual(result["goalProposal"]["protectedCategories"], [])
            self.assertTrue(result["goalProposal"]["needsConfirmation"])
            self.assertNotIn("외부에서만", str(result))
