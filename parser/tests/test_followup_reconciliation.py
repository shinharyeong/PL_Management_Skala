"""금액 연결에서 제외값 중복 가산과 ID 기반 의미 보완을 방지한다."""

import unittest
from pathlib import Path
from unittest.mock import patch

from excel_parser.benchmark.followup_reconciliation import (
    inspect_source_contract,
    reconcile_period,
)


def record(domain: str, cell: str, value: str | None, **changes: object) -> dict:
    """원본·마스터에 접근하지 않는 독립 월별 상세 예시를 만든다.

    Args:
        domain: revenue 또는 expense.
        cell: 합성 원본 금액 주소.
        value: 정규금액 문자열 또는 None.
        changes: 검증 사례에 필요한 필드 변경.
    Returns:
        합성 평탄 상세.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    return {"domain": domain, "source_file": "/synthetic/organization.xlsx", "sheet": domain,
            "cell": cell, "source_row": int(cell[1:]), "year": 2025, "month": 1, "scenario": "actual",
            "organization_code": "ORG-A", "submission_owner": "ORG-A", "account_code": "1000",
            "detail_id": "independent", "normalized_value": value, "raw_value": value,
            "included_in_totals": value is not None, "assembly_status": "confirmed" if value is not None else "unresolved", **changes}


def extra(row: dict) -> dict:
    """합성 상세의 출처를 보존한 평가 오탐 예시를 만든다.

    Args:
        row: 합성 상세.
    Returns:
        extra 상태와 출처·기간을 가진 오류.
    Raises:
        KeyError: 합성 계약 위반.
    Side Effects:
        없음.
    """
    return {"status": "extra", "source_file": row["source_file"], "sheet": row["sheet"], "cell": row["cell"], "record_key": row}


class FollowupReconciliationTests(unittest.TestCase):
    """순수 금액 연결과 원본 SUMIFS 근거 조회를 메모리에서 검증한다."""

    def test_excluded_amount_is_separate_from_actual_difference(self) -> None:
        """제외 금액을 이미 발생한 합계 차이에 다시 더하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 포함 extra와 제외 금액의 범위가 섞인 경우.
        Side Effects:
            없음. 합성 메모리 자료만 처리한다.
        """
        matched, surplus, cost = record("revenue", "I5", "100"), record("revenue", "I6", "20"), record("expense", "R5", "60")
        excluded = record("revenue", "I7", "7", included_in_totals=False, exclusion_reason="detail_identity_unresolved")
        totals = [{"scope": "company", "domain": "revenue", "year": 2025, "month": 1, "scenario": "actual", "truth_total": "100", "output_total": "120"},
                  {"scope": "company", "domain": "expense", "year": 2025, "month": 1, "scenario": "actual", "truth_total": "60", "output_total": "60"}]
        result = reconcile_period([matched, surplus, cost], [extra(surplus)], [excluded], totals, year=2025, month=1, scenario="actual")
        revenue = result["bridges"][0]
        self.assertEqual(revenue["difference_won"], "20")
        self.assertEqual(revenue["included_extra_total_won"], "20")
        self.assertEqual(revenue["numeric_residual_won"], "0")
        self.assertEqual(revenue["excluded_known_amount_separate_won"], "7")
        self.assertFalse(revenue["excluded_amount_is_part_of_difference"])

    def test_numeric_residual_and_unexplained_extra_are_different(self) -> None:
        """금액이 전부 연결되어도 업무 원인이 미설명인 extra를 숨기지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 수치 잔차와 업무 미설명 금액이 섞인 경우.
        Side Effects:
            없음.
        """
        revenue, expense = record("revenue", "I5", "10"), record("expense", "R5", "0")
        totals = [{"scope": "company", "domain": "revenue", "year": 2025, "month": 1, "scenario": "actual", "truth_total": "0", "output_total": "10"},
                  {"scope": "company", "domain": "expense", "year": 2025, "month": 1, "scenario": "actual", "truth_total": "0", "output_total": "0"}]
        result = reconcile_period([revenue, expense], [extra(revenue), extra(expense)], [], totals, year=2025, month=1, scenario="actual")
        self.assertEqual(result["bridges"][0]["numeric_residual_won"], "0")
        self.assertEqual(result["bridges"][0]["unexplained_extra_before_source_inspection_won"], "10")
        self.assertEqual(len(result["source_inspection_targets"]), 1)

    def test_source_formula_contract_does_not_use_detail_id(self) -> None:
        """원본 B열과 SUMIFS D라벨만 비교하고 ID를 의미 보완에 쓰지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 직접 수식 근거 없이 분류하거나 ID를 사용한 경우.
        Side Effects:
            추출·해시 경계를 메모리 대역으로 바꾸고 복원한다.
        """
        workbook = {"sheets": [{"name": "revenue", "cells": [
            {"address": "B5", "value": "Unregistered"}, {"address": "C5", "value": "1000 Account"},
            {"address": "D5", "value": "Child label"}, {"address": "I5", "value": "10"}]},
            {"name": "statement", "cells": [{"address": "D10", "value": "Valid category"},
             {"address": "J10", "value": "0", "formula": {"text": "SUMIFS(revenue!I:I,revenue!$B:$B,$D10,revenue!$A:$A,$A$5)"}}]}]}
        request = record("revenue", "I5", "10", detail_id="must-not-drive-meaning")
        with patch("excel_parser.benchmark.followup_reconciliation.extract_workbook", return_value=workbook), \
                patch("excel_parser.benchmark.followup_reconciliation.file_fingerprint", return_value="fixed"), \
                patch.object(Path, "open", side_effect=AssertionError("실제 파일 접근 금지")):
            result = inspect_source_contract(Path("/synthetic/organization.xlsx"), [request], "fixed")
        self.assertFalse(result["rows"][0]["management_class_in_formula_label_set"])
        self.assertFalse(result["rows"][0]["used_detail_id_for_organization_or_account"])
        self.assertTrue(result["source_unchanged"])
        self.assertEqual(result["rows"][0]["formula_bound_management_labels"][0]["label"]["value"], "Valid category")


if __name__ == "__main__":
    unittest.main()
