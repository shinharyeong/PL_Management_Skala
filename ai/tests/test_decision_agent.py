"""파일·DB·네트워크 없이 실행하는 의사결정 문장 계약 회귀 검증.

python3 -B -m unittest discover -s ai/tests -p 'test_decision_agent.py'
"""
from __future__ import annotations

import copy
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    "decision_agent_core", Path(__file__).parents[1] / "app" / "decision_agent" / "core.py")
core = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(core)


def analysis_fixture() -> dict:
    """외부 원본과 무관한 고정 분석 입력을 메모리에 만든다.

    Args:
        없음.
    Returns:
        기준 920억·목표 1050억·후보 75억·잔여 55억의 전달 계약.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    return {
        "analysisId": "analysis-1", "title": "연말 목표 검토", "prompt": "인력 감축 없이 목표를 검토해 줘",
        "targetProfitWon": "105000000000", "protectedCategories": ["HEADCOUNT", "RND"],
        "snapshot": {"masterId": "master-1", "masterLabel": "11월 확정 기준", "year": 2026,
                     "baseMonth": 11, "asOf": "2026-11-25T09:00:00+09:00", "sourceSubmissionIds": ["s-1"],
                     "organizations": [{"id": "org-1", "code": "A", "name": "A사업부",
                                        "revenueWon": "200000000000", "costWon": "108000000000",
                                        "profitWon": "92000000000"}], "baselineProfitWon": "92000000000"},
        "candidates": [{"id": "candidate-1", "organizationId": "org-1", "title": "납품 일정 검토",
                        "type": "NET_PROFIT", "amountWon": "7500000000", "contributionMarginPercent": None,
                        "implementationCostWon": "0", "embeddedEffectWon": "0", "recognitionMonth": 12,
                        "category": "OPERATIONS", "duplicateGroup": "", "evidenceStatus": "ASSUMPTION",
                        "evidenceNote": "담당자 회신 전", "evidenceRef": ""}],
        "result": {"baselineProfitWon": "92000000000", "targetProfitWon": "105000000000",
                   "initialGapWon": "13000000000", "reviewedEffectWon": "0",
                   "conditionalEffectWon": "7500000000", "reviewedOutlookWon": "92000000000",
                   "conditionalOutlookWon": "99500000000", "reviewedGapWon": "13000000000",
                   "conditionalGapWon": "5500000000", "targetCoveragePercent": "94.761905",
                   "effects": [{"candidateId": "candidate-1", "netEffectWon": "7500000000",
                                "included": True, "reviewed": False, "exclusionReasons": []}],
                   "warnings": ["후보 효과는 아직 실현되지 않았습니다."]},
    }


class DecisionCoreTests(unittest.TestCase):
    """계산 권위와 모델 출력 경계를 지키는 순수 규칙 검증."""

    def test_baseline_and_goal_must_match(self) -> None:
        """기준·목표 불일치를 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 잘못된 결과가 수락되면 발생.
        Side Effects: 없음.
        """
        for field in ("baselineProfitWon", "targetProfitWon"):
            with self.subTest(field=field):
                payload = analysis_fixture()
                payload["result"][field] = "1"
                with self.assertRaises(ValueError):
                    core.validate_context(payload)

    def test_candidate_snapshot_and_effect_links_must_match(self) -> None:
        """조직·후보·효과 연결의 누락과 중복을 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 잘못된 연결이 통과하면 발생.
        Side Effects: 없음.
        """
        cases = []
        payload = analysis_fixture()
        payload["candidates"][0]["organizationId"] = "another-org"
        cases.append(payload)
        payload = analysis_fixture()
        payload["result"]["effects"] = []
        cases.append(payload)
        payload = analysis_fixture()
        payload["result"]["effects"] *= 2
        cases.append(payload)
        payload = analysis_fixture()
        payload["snapshot"]["sourceSubmissionIds"] *= 2
        cases.append(payload)
        payload = analysis_fixture()
        payload["snapshot"]["organizations"] *= 2
        cases.append(payload)
        payload = analysis_fixture()
        payload["candidates"] *= 2
        cases.append(payload)
        payload = analysis_fixture()
        payload["result"]["effects"][0]["netEffectWon"] = None
        cases.append(payload)
        for index, payload in enumerate(cases):
            with self.subTest(index=index), self.assertRaises(ValueError):
                core.validate_context(payload)

    def test_as_of_requires_offset(self) -> None:
        """재현 가능한 기준일의 시간대가 없으면 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 모호한 기준일이 통과하면 발생.
        Side Effects: 없음.
        """
        for value in ("2026-11-25", "2026-11-25T09:00:00", "invalid"):
            payload = analysis_fixture()
            payload["snapshot"]["asOf"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                core.validate_context(payload)

    def test_backend_decimal_text_is_kept_without_recalculation(self) -> None:
        """정밀 계산 결과 원문을 반올림·재계산 없이 설명에 유지한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 원문 숫자 또는 입력값이 변경되면 발생.
        Side Effects: 없음.
        """
        payload = analysis_fixture()
        payload["result"]["conditionalEffectWon"] = "3.03333263333333"
        payload["result"]["conditionalOutlookWon"] = "92000000003.03333263333333"
        original = copy.deepcopy(payload)
        core.validate_context(payload)
        catalog = core.build_catalog(payload)
        response = core.render_narrative(payload, catalog, catalog["default"], "TEMPLATE", "NO_MODEL", [])
        self.assertIn("3.03333263333333원", response["summary"])
        self.assertIn("92000000003.03333263333333원", response["summary"])
        self.assertEqual(payload, original)

    def test_model_cannot_add_numbers_text_or_omit_reviews(self) -> None:
        """모델 자유 문장·새 금액·미지 ID·안건 누락·중복을 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 허용하지 않은 설명이 통과하면 발생.
        Side Effects: 없음.
        """
        catalog = core.build_catalog(analysis_fixture())
        valid = catalog["default"]
        invalid = [None, {**valid, "newProfitWon": "200000000000"}, {**valid, "summary": "추진 승인 완료"},
                   {**valid, "summaryKey": "승인 완료"}, {**valid, "decisionKeys": []},
                   {**valid, "decisionKeys": [*valid["decisionKeys"], valid["decisionKeys"][0]]},
                   {**valid, "decisionKeys": ["invented"]}, {**valid, "decisionKeys": [1]}]
        for index, value in enumerate(invalid):
            with self.subTest(index=index), self.assertRaises(ValueError):
                core.validate_selection(value, catalog)

    def test_model_may_only_change_allowed_summary_and_order(self) -> None:
        """모델의 허용된 순서 선택만 수락한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 허용된 순서가 변경되면 발생.
        Side Effects: 없음.
        """
        catalog = core.build_catalog(analysis_fixture())
        selected = {"summaryKey": "reviewed", "decisionKeys": list(reversed(catalog["decisions"]))}
        self.assertEqual(core.validate_selection(selected, catalog), selected)

    def test_template_explains_unverified_constraints_and_effects(self) -> None:
        """템플릿이 미검증 근거·제약·효과와 모델 생략을 설명한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 필수 한계가 누락되면 발생.
        Side Effects: 없음.
        """
        payload = analysis_fixture()
        catalog = core.build_catalog(payload)
        response = core.render_narrative(payload, catalog, catalog["default"], "TEMPLATE", "NO_MODEL", [])
        text = " ".join(response["limitations"])
        self.assertEqual(response["mode"], "TEMPLATE")
        for expected in ("미실현 조건부", "외부 증빙", "추가 제약", "확률이 아닙니다", "모델이 설정되지 않아"):
            self.assertIn(expected, text)

    def test_mode_and_fallback_reason_must_be_consistent(self) -> None:
        """모델 실행 여부와 설명 사유의 모순을 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 모순된 모드가 통과하면 발생.
        Side Effects: 없음.
        """
        payload = analysis_fixture()
        catalog = core.build_catalog(payload)
        for mode, reason in (("TEMPLATE", None), ("MODEL", "NO_MODEL"), ("UNKNOWN", None)):
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                core.render_narrative(payload, catalog, catalog["default"], mode, reason, [])

    def test_excluded_candidate_is_never_presented_as_executable(self) -> None:
        """모든 후보가 제외되면 실행 검토 대신 제외 사유 검토를 요청한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 제외 후보의 추진 안건이 생성되면 발생.
        Side Effects: 없음.
        """
        payload = analysis_fixture()
        payload["result"]["effects"][0].update(included=False, exclusionReasons=["PROTECTED_CATEGORY"])
        catalog = core.build_catalog(payload)
        self.assertNotIn("review_execution", catalog["decisions"])
        self.assertNotIn("review_assumptions", catalog["decisions"])
        self.assertIn("review_exclusions", catalog["decisions"])


if __name__ == "__main__":
    unittest.main()
