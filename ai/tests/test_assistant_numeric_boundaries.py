"""한국어 숫자 경계와 검증 미완료의 부분 설명 보존을 합성 자료로 검증한다."""
from __future__ import annotations

from copy import deepcopy
import unittest
from unittest.mock import Mock

from test_assistant_guards import native_call, observation_fixture, plan_arguments, request_fixture, selection_arguments
from test_assistant_repair import repair_arguments
from app.config import Settings
from app.decision_agent import assistant_graph as graph
from app.decision_agent.assistant_brief import resolve_brief, validate_plan, validate_prose
from app.decision_agent.assistant_core import FinishValidationError
from app.decision_agent.discovery_core import numeric_claim_kind


class AssistantNumericBoundaryTests(unittest.TestCase):
    """정상 한국어를 막지 않으면서 명시적 수치와 근거 조작은 계속 거부한다."""

    def setUp(self) -> None:
        """실제 설정을 사용하지 않는 합성 환경을 준비한다.

        Args: 없음. Returns: 없음. Raises: 없음.
        Side Effects: 테스트 프로세스의 메모리 호출 집계만 비운다.
        """
        graph.CallLog.clear()
        self.settings = Settings(openai_api_key="synthetic-key", ai_daily_call_limit=1000)

    def test_ordinary_korean_particles_are_not_numeric_claims(self) -> None:
        """일반 명사·조사·가능 표현의 일부를 금액 단위로 오인하지 않는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 정상 문장 거부.
        Side Effects: 합성 문자열만 검증한다.
        """
        for text in ("현재 전망과 목표의 차이만 비교했습니다.", "계획 대비 차이만으로 원인을 판단할 수 없습니다.",
                     "전사만 확인한 결과입니다.", "금액이 아닌 주요 변수만 검토했습니다.",
                     "추가 자료를 확인할 수만 있습니다.", "이만 정리하겠습니다.",
                     "천만다행이라는 회신은 원인 증빙이 아닙니다.", "일조할 가능성은 추가 확인이 필요합니다."):
            with self.subTest(text=text):
                self.assertIsNone(numeric_claim_kind(text))
                validate_prose(text, [], [], "summary")

    def test_explicit_amounts_ratios_and_unicode_numbers_still_fail(self) -> None:
        """공백 없는 명백한 한글금액과 비율·유니코드 수치는 계속 거부한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 수치 검증 약화·원문 오류 노출.
        Side Effects: 합성 문자열만 검증한다.
        """
        cases = {"KOREAN_AMOUNT": ("삼십억 절감", "이 조 원 규모", "삼십억원이 필요", "이조원을 검토", "수억 원 규모",
                                    "십만원 수준", "백만 원 규모", "영업이익 십억원입니다", "비용이삼십억증가", "수만 원", "이만 원"),
                 "NUMERIC_CHARACTER": ("목표 1200억 원", "목표 １２００억 원", "항목 ①", "비율 ½"),
                 "PERCENT_EXPRESSION": ("목표 퍼센트", "비율 %", "percent 기준"),
                 "RATIO_EXPRESSION": ("절반 수준", "두 배", "세배"),
                 "SCALE_EXPRESSION": ("million 규모", "billion 규모")}
        for kind, samples in cases.items():
            for text in samples:
                with self.subTest(kind=kind, text=text), self.assertRaises(FinishValidationError) as caught:
                    validate_prose(text, [], [], "summary")
                self.assertEqual(caught.exception.code, "BRIEF_NUMERIC")
                self.assertEqual(caught.exception.field_issues, ["summary"])
                self.assertEqual(caught.exception.reason_codes, [kind])
                self.assertNotIn(text, str(caught.exception))

    def test_safe_reason_codes_never_echo_unregistered_values(self) -> None:
        """오류 분류에 등록되지 않은 원문을 넣어도 모델 안내·속성에 노출하지 않는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 미등록 원문 노출.
        Side Effects: 메모리 오류 객체만 만든다.
        """
        error = FinishValidationError("BRIEF_NUMERIC", ["summary"], ["synthetic-private", "KOREAN_AMOUNT", "KOREAN_AMOUNT"])
        self.assertEqual(error.reason_codes, ["KOREAN_AMOUNT"])
        self.assertNotIn("synthetic-private", str(error))

    def test_normal_comparison_summary_finishes_without_repair(self) -> None:
        """정상적인 차이 비교 요약이 추가 수정 호출 없이 근거와 함께 완료된다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 정상 답변의 수정 반복.
        Side Effects: 모델·업무 조회 대역만 메모리에서 실행한다.
        """
        proposal = selection_arguments()
        proposal["summary"] = "현재 전망과 목표의 차이만 비교했습니다."
        model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 0), native_call("company_pnl", {}, 1),
                                  native_call("finish_answer", proposal, 2)])
        result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model,
                                     tool_caller=Mock(return_value=observation_fixture()))
        self.assertEqual((result["status"], model.call_count), ("COMPLETED", 3))
        self.assertEqual(result["brief"]["summary"], proposal["summary"])

    def test_summary_only_exhaustion_preserves_verified_findings_without_completion(self) -> None:
        """요약 수정 실패는 전체 완료로 가장하지 않고 재검증한 개별 설명만 보존한다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 거짓 완료·원본 변경·근거 유실.
        Side Effects: 합성 모델·업무 조회와 메모리 로그만 사용한다.
        """
        proposal = selection_arguments()
        proposal["summary"] = "목표 삼십억원 대비 부족합니다."
        before = deepcopy(proposal)
        model = Mock(side_effect=[native_call("plan_analysis", plan_arguments(), 0), native_call("company_pnl", {}, 1),
                                  native_call("finish_answer", proposal, 2)] +
                                 [native_call("repair_answer", repair_arguments("수억 원 부족합니다.", "summary"), index) for index in range(3, 6)])
        with self.assertLogs(graph.logger, level="WARNING") as captured:
            result = graph.run_assistant(request_fixture(), settings=self.settings, model_caller=model,
                                         tool_caller=Mock(return_value=observation_fixture()))
        self.assertEqual((result["status"], result["mode"], model.call_count), ("LIMIT_REACHED", "MODEL", 6))
        self.assertTrue(result["answer"].startswith("시스템 안내:"))
        self.assertEqual(result["answer"], result["brief"]["summary"])
        self.assertEqual(result["brief"]["findings"][0]["text"], before["findings"][0]["text"])
        self.assertEqual(result["citations"][0]["path"], "/data/amountWon")
        self.assertEqual(proposal, before)
        self.assertIn("KOREAN_AMOUNT", str(captured.output))
        self.assertNotIn("삼십억원", str(captured.output))

    def test_partial_summary_cannot_hide_invalid_findings_or_citations(self) -> None:
        """요약 외 오류나 모르는 인용이 있으면 부분 설명도 채택하지 않는다.

        Args: 없음. Returns: 없음. Raises: AssertionError: 부분 반환을 통한 검증 우회.
        Side Effects: 합성 상태만 검사한다.
        """
        proposal = selection_arguments()
        proposal["summary"] = "목표 삼십억원 대비 부족합니다."
        state = {"repair_proposal": proposal, "repair_fields": ["summary"], "observations": [observation_fixture()],
                 "requirements": validate_plan(plan_arguments(), request_fixture()["message"])}
        proposal["findings"][0]["text"] = "수억 원 차이입니다."
        self.assertIsNone(graph.verified_summary_partial(state))
        proposal["findings"][0]["text"] = "선택 기준의 차이가 조회되었습니다."
        proposal["findings"][0]["citationIds"] = ["synthetic-missing"]
        self.assertIsNone(graph.verified_summary_partial(state))


if __name__ == "__main__":
    unittest.main()
