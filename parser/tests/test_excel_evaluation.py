"""실제 Excel 셀의 엄격 점수·빈칸·0·중복·합계표와 기준 읽기 경계를 메모리에서 검증한다."""

import json
import unittest
from copy import deepcopy
from pathlib import Path
from unittest.mock import patch

from excel_parser.benchmark import excel_evaluation as evaluator


def workbook_fixture(*, amount: object = "100", domain: str = "revenue", legacy: bool = False) -> dict:
    """파일 없이 실제 추출기와 같은 네 시트 셀 사전을 만든다.

    Args: amount - 실제 저장 금액 또는 빈칸. domain - 매출·비용. legacy - 이전 Z 상태.
    Returns: extract_workbook 모양의 독립 메모리 자료.
    Raises: 없음.
    Side Effects: 파일·정답·JSON 자료를 읽거나 쓰지 않는다.
    """
    sheets = []
    numeric = amount is not None
    state = {"value": "confirmed" if numeric else "missing", "assembly": "confirmed" if numeric else "unresolved", "included": numeric}
    if not legacy:
        state.update(confirmed=numeric, semantic_complete=True, value_kind="amount", mapping_status="confirmed", mapping_reasons=[])
    for name in (*evaluator.DETAIL_SHEETS, *evaluator.TOTAL_SHEETS):
        detail = name in evaluator.DETAIL_SHEETS
        organization = name == "조직월별합계"
        headers = evaluator.DETAIL_HEADERS if detail else (("조직코드",) if organization else ()) + ("영역", "계정코드", "연도", "구분", *evaluator.MONTH_HEADERS, "합산 상세 수") + (() if legacy else ("월별 상세 수",))
        values = {3: ["01_테스트 · 마감 2026-01 · 금액 단위 원"],
                  4: ["출력 상태 complete · 원본 품질 complete · 조립 상태 complete · 기대 조직 1 · 반영 조직 1"], 5: list(headers)}
        if detail and evaluator.DETAIL_SHEETS[name] == domain:
            values[6] = ["F01", "A01", "D01", 2026, "실적", "당년 실적·추정", 0, amount, *([None] * 11),
                         "조직", "계정", "/source/01_F01_org.xlsx", "원본", 8, json.dumps({"1": "H8"}), json.dumps({"1": state})]
        elif not detail and numeric:
            values[6] = (["F01"] if organization else []) + ["매출" if domain == "revenue" else "비용", "A01", 2026, "실적", amount, *([None] * 11), 1] + ([] if legacy else [json.dumps({"1": 1})])
        cells = []
        for row, row_values in values.items():
            for column, value in enumerate(row_values, 1):
                if value is None:
                    continue
                money_column = row >= 6 and (8 <= column <= 19 if detail else (6 if organization else 5) <= column <= (17 if organization else 16))
                cells.append({"row": row, "column_index": column, "address": evaluator._address(row, column),
                              "value": value, "data_type": "number" if money_column or isinstance(value, int) else "string",
                              "number_format": "#,##0" if money_column else "General", "formula": None})
        sheets.append({"name": name, "cells": cells})
    return {"file": {"path": "/generated/master.xlsx"}, "sheets": sheets}


def fixture_cell(workbook: dict, name: str, row: int, column: int) -> dict:
    """메모리 통합문서의 지정 실제 셀을 찾는다.

    Args: workbook - 합성 통합문서. name/row/column - 실제 위치.
    Returns: 수정 가능한 합성 셀 사전.
    Raises: StopIteration - 셀이 없는 경우.
    Side Effects: 실제 파일에 접근하지 않는다.
    """
    return next(cell for sheet in workbook["sheets"] if sheet["name"] == name for cell in sheet["cells"]
                if cell["row"] == row and cell["column_index"] == column)


def truth_fixture(*, amount: str = "100", domain: str = "revenue") -> dict:
    """생성물 판독 결과와 별개로 작은 독립 정답을 만든다.

    Args: amount - 알려진 정답 금액. domain - 영역.
    Returns: 독립 정답 레코드 한 건.
    Raises: 없음.
    Side Effects: 메모리만 사용한다.
    """
    return {"records": [{"type": "01_테스트", "closing_month": "2026-01", "domain": domain,
                         "organization_code": "F01", "account_code": "A01", "detail_id": "D01", "year": 2026,
                         "month": 1, "scenario": "actual", "period_group": "outlook", "normalized_value": amount,
                         "confirmed": True, "semantic_complete": True, "source_file": "/reference/master.xlsx",
                         "sheet": "정답", "cell": "M9"}]}


def score_fixture(workbook: dict, *, truth: dict | None = None, legacy: bool = False) -> dict:
    """메모리 실제 Excel을 기존 엄격 채점으로 대조한다.

    Args: workbook - 실제 셀 모양 자료. truth - 정답 또는 기본 정답. legacy - 명시 호환.
    Returns: Excel 상세·합계 검증 결과.
    Raises: ValueError - 잘못된 합성 자료.
    Side Effects: 파일·외부 API 없이 판독·채점한다.
    """
    return evaluator.evaluate_excel_data(evaluator.parse_generated_workbook(workbook, legacy_compatible=legacy),
                                         truth or truth_fixture(), {"type": "01_테스트", "closing_month": "2026-01", "organization_count": 1})


class ExcelEvaluationTests(unittest.TestCase):
    """실측 Excel 값과 기존 엄격 평가 계약의 연결을 검증한다."""

    def test_exact_actual_excel_values_and_provenance(self) -> None:
        """실제 값과 키가 같으면 엄격 상세·합계표 모두 통과한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 실제 셀 채점 오류.
        Side Effects: 메모리만 사용한다.
        """
        workbook = workbook_fixture()
        before = deepcopy(workbook)
        result = score_fixture(workbook)
        self.assertEqual(workbook, before)
        self.assertEqual(result["summary"]["match_count"], 1)
        self.assertTrue(result["summary"]["strict_excel_detail_and_total_match"])
        self.assertEqual(result["summary"]["metadata_json_value_reads"], 0)

    def test_amount_change_is_one_mismatch_and_fp_fn(self) -> None:
        """실제 금액 셀 변경은 불일치 하나와 FP·FN 각각 하나가 된다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 금액 변경을 무시한 경우.
        Side Effects: 합성 셀만 변경한다.
        """
        workbook = workbook_fixture()
        fixture_cell(workbook, "매출상세", 6, 8)["value"] = "101"
        result = score_fixture(workbook)
        self.assertEqual((result["summary"]["amount_mismatch"], result["summary"]["false_positive"], result["summary"]["false_negative"]), (1, 1, 1))
        self.assertEqual(result["errors"][0]["cell"], "H6")
        self.assertEqual(result["errors"][0]["organization_source_cell"], "H8")
        self.assertFalse(result["summary"]["stored_totals_verified"])

    def test_actual_key_change_cannot_be_filled_from_json(self) -> None:
        """실제 업무키 변경은 저장 JSON 없이 오탐·누락으로 드러난다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 키 보충 또는 잘못된 일치.
        Side Effects: 합성 셀만 변경한다.
        """
        workbook = workbook_fixture()
        fixture_cell(workbook, "매출상세", 6, 2)["value"] = "A99"
        result = score_fixture(workbook)["summary"]
        self.assertEqual((result["match_count"], result["false_positive"], result["false_negative"]), (0, 1, 1))

    def test_blank_is_not_zero_and_zero_expense_is_preserved(self) -> None:
        """실제 비용 0원은 확정 출력으로 보존하고 빈칸은 정답 누락을 유지한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 빈칸·0원 혼동.
        Side Effects: 메모리만 사용한다.
        """
        zero = score_fixture(workbook_fixture(amount="0", domain="expense"), truth=truth_fixture(amount="0", domain="expense"))["summary"]
        self.assertEqual((zero["match_count"], zero["actual_zero_expense"]), (1, 1))
        blank = score_fixture(workbook_fixture(amount=None, domain="expense"), truth=truth_fixture(amount="0", domain="expense"))["summary"]
        self.assertEqual((blank["confirmed_output_count"], blank["false_negative"], blank["actual_zero_amounts"]), (0, 1, 0))

    def test_missing_sheet_retains_truth_denominator(self) -> None:
        """상세 시트가 없어도 정답은 Recall 분모에 남는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 생략 정답 분모 감소.
        Side Effects: 합성 목록만 변경한다.
        """
        workbook = workbook_fixture()
        workbook["sheets"] = [sheet for sheet in workbook["sheets"] if sheet["name"] != "매출상세"]
        result = score_fixture(workbook)
        self.assertEqual((result["summary"]["truth_count"], result["summary"]["false_negative"]), (1, 1))
        self.assertIn("sheet_missing", {issue["code"] for issue in result["excel_issues"]})

    def test_physical_duplicate_rows_are_not_merged(self) -> None:
        """같은 키의 물리행 두 개를 각각 세어 초과 출력을 보존한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 중복 제거·합산.
        Side Effects: 메모리 행만 복제한다.
        """
        workbook = workbook_fixture()
        sheet = workbook["sheets"][0]
        duplicate = [{**cell, "row": 7, "address": evaluator._address(7, cell["column_index"])} for cell in sheet["cells"] if cell["row"] == 6]
        sheet["cells"].extend(duplicate)
        result = score_fixture(workbook)["summary"]
        self.assertEqual((result["confirmed_output_count"], result["match_count"], result["false_positive"], result["duplicate_prediction"]), (2, 1, 1, 1))

    def test_uncertain_key_keeps_numeric_fp_but_excludes_totals(self) -> None:
        """미확정 업무키의 숫자는 FP로 남기고 합계에는 넣지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 숫자 손실 또는 잘못된 합계 포함.
        Side Effects: 메모리 상태만 변경한다.
        """
        workbook = workbook_fixture()
        state_cell = fixture_cell(workbook, "매출상세", 6, 26)
        state = json.loads(state_cell["value"])
        state["1"]["semantic_complete"] = False
        state_cell["value"] = json.dumps(state)
        parsed = evaluator.parse_generated_workbook(workbook)
        self.assertEqual(parsed["records"][0]["normalized_value"], "100")
        self.assertFalse(parsed["records"][0]["included_in_totals"])
        result = score_fixture(workbook)["summary"]
        self.assertEqual((result["false_positive"], result["false_negative"], result["nonzero_false_positive"]), (1, 1, 1))

    def test_legacy_requires_explicit_option_and_counts_unmeasured(self) -> None:
        """이전 상태 양식은 명시 호환에서만 해석하고 월별 건수는 미측정이다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 암묵 상태 보충.
        Side Effects: 메모리만 사용한다.
        """
        workbook = workbook_fixture(legacy=True)
        self.assertEqual(score_fixture(workbook)["summary"]["match_count"], 0)
        result = score_fixture(workbook, legacy=True)["summary"]
        self.assertEqual((result["match_count"], result["legacy_derived_slots"]), (1, 1))
        self.assertFalse(result["stored_totals_verified"])

    def test_actual_summary_amount_and_count_changes_are_detected(self) -> None:
        """상세가 같아도 실제 합계표의 금액·월별 건수 변경을 별도 검출한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 합계표를 읽지 않은 경우.
        Side Effects: 합성 합계 셀만 변경한다.
        """
        workbook = workbook_fixture()
        fixture_cell(workbook, "전사월별합계", 6, 5)["value"] = "99"
        fixture_cell(workbook, "조직월별합계", 6, 19)["value"] = json.dumps({"1": 2})
        result = score_fixture(workbook)
        self.assertEqual(result["summary"]["match_count"], 1)
        self.assertTrue(result["summary"]["strict_detail_and_total_match"])
        self.assertFalse(result["summary"]["strict_excel_detail_and_total_match"])
        self.assertFalse(result["summary"]["actual_sheet_totals_match_reference"])
        self.assertIn("total_row_count_mismatch", {issue["code"] for issue in result["excel_issues"]})

    def test_duplicate_total_cells_are_not_summed(self) -> None:
        """중복 합계 물리행은 정상 합계에 합산하지 않고 초과로 남긴다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 중복 합계 제거.
        Side Effects: 메모리만 사용한다.
        """
        parsed = evaluator.parse_generated_workbook(workbook_fixture())
        observed = parsed["actual_totals"] + [dict(parsed["actual_totals"][0])]
        checks = evaluator.compare_total_cells(observed, evaluator._expected_totals(parsed["records"]), basis="excel_details")
        self.assertEqual(sum(row["status"] == "extra" for row in checks), 1)
        self.assertTrue(any(row["duplicate_actual_key"] for row in checks))

    def test_duplicate_json_months_and_formula_without_cache_fail_closed(self) -> None:
        """중복 상태 키와 계산되지 않은 수식 캐시는 확정 일치를 만들지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 손상 상태·수식 자동 보충.
        Side Effects: 합성 셀만 변경한다.
        """
        workbook = workbook_fixture()
        fixture_cell(workbook, "매출상세", 6, 26)["value"] = '{"1":{},"1":{}}'
        self.assertEqual(score_fixture(workbook)["summary"]["match_count"], 0)
        workbook = workbook_fixture()
        fixture_cell(workbook, "매출상세", 6, 8)["formula"] = {"cached_status": "missing"}
        self.assertEqual(score_fixture(workbook)["summary"]["false_negative"], 1)

    def test_a4_counts_are_measured_from_excel(self) -> None:
        """기대·반영 조직 표기가 실측 출처 수와 다르면 구조 문제를 남긴다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - A4 숫자를 외부 JSON으로 대체한 경우.
        Side Effects: 합성 A4만 변경한다.
        """
        workbook = workbook_fixture()
        fixture_cell(workbook, "매출상세", 4, 1)["value"] = "출력 상태 complete_with_exclusions · 원본 품질 partial · 조립 상태 partial · 기대 조직 17 · 반영 조직 16"
        result = score_fixture(workbook)
        self.assertEqual((result["summary"]["expected_organizations"], result["summary"]["included_organizations"]), (17, 16))
        self.assertEqual(result["summary"]["measured_source_file_count"], 1)
        self.assertFalse(result["summary"]["excel_structure_complete"])

    def test_preflight_failure_never_reads_reference_or_writes(self) -> None:
        """생성·Excel 완료 검증 실패 때 기준 원본과 출력에 접근하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 조기 기준 읽기·쓰기.
        Side Effects: 모든 파일 동작을 메모리 Mock으로 차단한다.
        """
        with patch.object(evaluator, "_preflight_run", side_effect=ValueError("missing Excel")), \
                patch.object(evaluator, "build_master_truth", side_effect=AssertionError("기준 읽기 금지")), \
                patch.object(Path, "mkdir", side_effect=AssertionError("쓰기 금지")):
            with self.assertRaises(ValueError):
                evaluator.evaluate_excel_run(Path("/run"), Path("/new"), write_output=True)

    def test_consistent_but_incomplete_organization_count_cannot_pass(self) -> None:
        """반영 조직 표기와 실측이 같아도 기대 조직이 부족하면 완료로 보지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 부분 제출의 최종 완료 오판.
        Side Effects: 메모리 문맥만 변경한다.
        """
        workbook = workbook_fixture()
        for name in (*evaluator.DETAIL_SHEETS, *evaluator.TOTAL_SHEETS):
            cell = fixture_cell(workbook, name, 4, 1)
            cell["value"] = cell["value"].replace("기대 조직 1", "기대 조직 2")
        parsed = evaluator.parse_generated_workbook(workbook)
        result = evaluator.evaluate_excel_data(parsed, truth_fixture(), {"type": "01_테스트", "closing_month": "2026-01", "organization_count": 2})
        self.assertTrue(result["summary"]["strict_detail_and_total_match"])
        self.assertFalse(result["summary"]["strict_excel_detail_and_total_match"])
        self.assertEqual(result["summary"]["measured_source_file_count"], 1)

    def test_no_write_flag_does_not_read_any_inputs(self) -> None:
        """명시 저장 플래그 없이 입력 읽기나 결과 생성을 시작하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 미승인 읽기·쓰기.
        Side Effects: 경계 확인을 메모리 Mock으로 차단한다.
        """
        with patch.object(evaluator, "_preflight_run", side_effect=AssertionError("읽기 금지")):
            with self.assertRaises(ValueError):
                evaluator.evaluate_excel_run(Path("/run"), Path("/new"))


if __name__ == "__main__":
    unittest.main()
