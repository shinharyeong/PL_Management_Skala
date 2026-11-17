"""연간 0만 남은 빈 매출 양식과 실제 입력의 경계를 메모리에서 검증한다."""

import unittest
from copy import deepcopy

from excel_parser.mapping import map_workbook
from excel_parser.submission_adapter import build_submission_data
from test_mapping import memory_workbook
from test_submission_adapter import submission_workbook


def revenue_template_workbook(extra: list[tuple] | None = None) -> dict:
    """12개월 입력 아래에 연간 0만 있는 매출 양식 행을 합성한다.

    Args:
        extra: 같은 빈 행에 추가할 독립 원본 셀 주소·값·자료형 목록.
    Returns:
        실제 상세 3개와 미사용 매출 행 1개를 가진 추출 계약.
    Raises:
        ValueError: 합성 셀 주소가 잘못된 경우.
    Side Effects:
        메모리만 사용하며 파일·캐시·원본 데이터를 만들거나 변경하지 않는다.
    """
    workbook = submission_workbook()
    cells = memory_workbook([("F7", "0", "number"), *(extra or [])])["sheets"][0]["cells"]
    workbook["sheets"][0]["cells"].extend(cells)
    return workbook


class RevenueTemplateRowsTests(unittest.TestCase):
    """알려진 빈 양식만 제출 행에서 제외하고 모든 원본 후보를 보존한다."""

    def test_annual_zero_only_row_keeps_candidate_without_submission_line(self) -> None:
        """빈 양식의 연간 0을 삭제하지 않고 비활성 후보로 보존한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 원본 변경·후보 소실·가짜 상세 행이 발생한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        workbook = revenue_template_workbook()
        original = deepcopy(workbook)
        mapped = map_workbook(workbook)
        candidate = next(record for record in mapped["records"] if record["source"]["sheet"] == "검증" and record["source"]["cell"] == "F7")
        self.assertEqual(candidate["raw_value"], "0")
        self.assertEqual(candidate["role"]["kind"], "inactive_template")
        self.assertIn("ROW_INACTIVE_REVENUE_TEMPLATE", candidate["mapping"]["rule_ids"])
        self.assertEqual(len(build_submission_data(workbook, mapped)["lines"]), 3)
        self.assertEqual(workbook, original)

    def test_real_zero_identity_description_and_unknown_content_remain_active(self) -> None:
        """월별 실제 0·설명·조직·주석·미확인 입력은 활성 검증에 남긴다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 실제 입력이나 검토할 근거가 비활성 처리된 경우.
        Side Effects:
            메모리만 사용한다.
        """
        cases = [
            [("G7", "0", "number")],
            [("E7", "수정 근거 [ID:new-input; 수정:1]", "string")],
            [("A7", "합성조직", "string")],
            [("Z7", "추가 확인", "string")],
        ]
        for extra in cases:
            with self.subTest(extra=extra):
                workbook = revenue_template_workbook(extra)
                mapped = map_workbook(workbook)
                self.assertFalse(any(record["role"]["kind"] == "inactive_template" for record in mapped["records"]))
                data = build_submission_data(workbook, mapped)
                self.assertTrue(any(line["source"] == {"sheet": "검증", "row": 7} for line in data["lines"]))
        workbook = revenue_template_workbook()
        workbook["sheets"][0]["cells"][-1]["comments"] = [{"text": "확인할 원본 주석"}]
        mapped = map_workbook(workbook)
        self.assertFalse(any(record["role"]["kind"] == "inactive_template" for record in mapped["records"]))

    def test_nonzero_text_and_unknown_annual_formula_are_not_silently_excluded(self) -> None:
        """연간 비영 금액·숫자 문자열·미확인 수식은 빈 양식으로 추정하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 원본 의미가 확인되지 않은 행을 제외한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        cases = [
            {"value": "1"},
            {"value": "0", "data_type": "string"},
            {"formula": {"text": "=G7+H7", "cached_status": "stored_unverified"}},
            {"formula": {"text": "=NA()", "cached_status": "error"}, "data_type": "error", "value": "#N/A"},
        ]
        for changes in cases:
            with self.subTest(changes=changes):
                workbook = revenue_template_workbook()
                workbook["sheets"][0]["cells"][-1].update(changes)
                mapped = map_workbook(workbook)
                self.assertFalse(any(record["role"]["kind"] == "inactive_template" for record in mapped["records"]))

    def test_verified_annual_sum_and_shared_range_allow_empty_template(self) -> None:
        """같은 행의 12개월 SUM과 검증된 공유 범위만 빈 양식 근거로 인정한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 공유 수식의 근거 확인이나 후보 보존이 실패한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        formulas = [
            {"text": "=SUM(G7:R7)", "cached_status": "stored_unverified"},
            {"text": None, "attributes": {"t": "shared", "si": "1"}, "cached_status": "stored_unverified",
             "shared_base": {"address": "F5", "raw_text": "SUM(G5:R5)", "attributes": {"t": "shared", "si": "1", "ref": "F5:F9"}}},
        ]
        for formula in formulas:
            with self.subTest(formula=formula):
                workbook = revenue_template_workbook()
                workbook["sheets"][0]["cells"][-1]["formula"] = formula
                mapped = map_workbook(workbook)
                self.assertEqual(sum(record["role"]["kind"] == "inactive_template" for record in mapped["records"]), 1)
                self.assertEqual(len(build_submission_data(workbook, mapped)["lines"]), 3)

    def test_broken_shared_range_and_wrong_sum_preserve_review_row(self) -> None:
        """공유 범위 밖·고정 다른 행·잘못된 합계 범위는 비활성으로 확정하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 불완전 수식 근거로 원본 검토 행을 숨긴 경우.
        Side Effects:
            메모리만 사용한다.
        """
        formulas = [
            {"text": "=SUM(G7:Q7)", "cached_status": "stored_unverified"},
            {"text": "=SUM(G6:R6)", "cached_status": "stored_unverified"},
            {"text": "=SUM(G7:R7)", "cached_status": "missing"},
            {"text": None, "attributes": {"t": "shared", "si": "1"}, "cached_status": "stored_unverified",
             "shared_base": {"address": "F5", "raw_text": "SUM(G5:R5)", "attributes": {"t": "shared", "si": "1", "ref": "F5:F6"}}},
            {"text": None, "attributes": {"t": "shared", "si": "1"}, "cached_status": "stored_unverified",
             "shared_base": {"address": "F5", "raw_text": "SUM(G$5:R$5)", "attributes": {"t": "shared", "si": "1", "ref": "F5:F9"}}},
        ]
        for formula in formulas:
            with self.subTest(formula=formula):
                workbook = revenue_template_workbook()
                workbook["sheets"][0]["cells"][-1]["formula"] = formula
                mapped = map_workbook(workbook)
                self.assertFalse(any(record["role"]["kind"] == "inactive_template" for record in mapped["records"]))

    def test_incomplete_month_headers_keep_row_for_review(self) -> None:
        """12개월 소속을 확인하지 못한 연간 0은 비활성으로 확정하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 불완전 양식을 빈 템플릿으로 처리한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        workbook = revenue_template_workbook()
        sheet = workbook["sheets"][0]
        sheet["cells"] = [cell for cell in sheet["cells"] if cell["address"] != "R4"]
        mapped = map_workbook(workbook)
        self.assertFalse(any(record["role"]["kind"] == "inactive_template" for record in mapped["records"]))


if __name__ == "__main__":
    unittest.main()
