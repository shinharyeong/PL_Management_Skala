"""파일·임시 디렉터리·API 없이 월별 상세 평가의 핵심 계약을 검증한다."""

from copy import deepcopy
from decimal import Decimal
from pathlib import Path
import unittest
from unittest.mock import patch

from excel_parser.benchmark.monthly import _run_source, run_monthly
from excel_parser.benchmark.monthly_evaluation import score_monthly_records
from excel_parser.benchmark.monthly_truth import (
    _master_organization,
    build_master_truth,
    decimal_amount,
)


def expected_record(**changes: object) -> dict:
    """특정 원본에 의존하지 않는 독립 월별 정답 한 건을 선언한다.

    Args:
        changes: 개별 경계 사례의 필드 변경값.
    Returns:
        새 평탄 정답 사전.
    Raises:
        없음.
    Side Effects:
        메모리 객체만 생성한다.
    """
    return {
        "type": "synthetic", "closing_month": "2026-01", "domain": "revenue",
        "organization_code": "F10", "account_code": "1200", "year": 2026,
        "month": 1, "scenario": "actual", "period_group": "outlook",
        "detail_id": "synthetic-detail-1", "normalized_value": "1000",
        "source_file": "/memory/master.xlsx", "organization_file": "/memory/org.xlsx",
        "sheet": "매출", "cell": "AK5", **changes,
    }


def predicted_record(**changes: object) -> dict:
    """정답 생성기를 조회하지 않고 파서 출력 사례를 별도로 선언한다.

    Args:
        changes: 개별 경계 사례의 필드 변경값.
    Returns:
        금액과 의미가 확정된 새 평탄 예측 사전.
    Raises:
        없음.
    Side Effects:
        메모리 객체만 생성한다.
    """
    return {
        "type": "synthetic", "closing_month": "2026-01", "domain": "revenue",
        "organization_code": "F10", "account_code": "1200", "year": 2026,
        "month": 1, "scenario": "actual", "period_group": "outlook",
        "detail_id": "synthetic-detail-1", "normalized_value": "1000.00",
        "source_file": "/memory/org.xlsx", "sheet": "매출", "cell": "AK5",
        "confirmed": True, "semantic_complete": True, **changes,
    }


def memory_cell(row: int, column: int, value: object) -> dict:
    """고정 마스터 변환 검증에 필요한 최소 추출 셀을 선언한다.

    Args:
        row: 1부터 시작하는 원본 행 번호.
        column: 1부터 시작하는 원본 열 번호.
        value: 원본 저장값. None은 실제 빈 셀을 뜻한다.
    Returns:
        행·열·값을 가진 새 메모리 셀.
    Raises:
        없음.
    Side Effects:
        없음. OOXML이나 실제 파일을 생성하지 않는다.
    """
    return {"row": row, "column_index": column, "value": value}


class MonthlyEvaluationTests(unittest.TestCase):
    """월별 상세 업무키와 금액의 일대일 대조·분모 보존을 확인한다."""

    def test_exact_decimal_match_preserves_inputs(self) -> None:
        """정확한 Decimal 일치가 인정되고 입력 객체가 유지되는지 검사한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 일치 수·지표 또는 입력 보존이 잘못된 경우.
        Side Effects:
            메모리 객체만 생성한다.
        """
        truth, predictions = [expected_record()], [predicted_record()]
        before = deepcopy((truth, predictions))
        report = score_monthly_records(predictions, truth)
        self.assertEqual(report["summary"]["match_count"], 1)
        self.assertEqual(report["summary"]["precision"], 1.0)
        self.assertEqual(report["summary"]["recall"], 1.0)
        self.assertEqual(report["summary"]["f1"], 1.0)
        self.assertEqual(report["details"], [])
        self.assertEqual((truth, predictions), before)

    def test_amount_mismatch_is_one_detail_and_one_fp_one_fn(self) -> None:
        """동일 키의 금액 오류가 상세 한 건과 FP·FN 각 한 건인지 검사한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 금액 오류가 누락되거나 상세에서 중복 계수된 경우.
        Side Effects:
            메모리만 사용한다.
        """
        report = score_monthly_records(
            [predicted_record(normalized_value="999")], [expected_record()]
        )
        self.assertEqual(report["summary"]["false_positive"], 1)
        self.assertEqual(report["summary"]["false_negative"], 1)
        self.assertEqual(report["summary"]["match_count"], 0)
        self.assertEqual(len(report["details"]), 1)
        self.assertEqual(report["details"][0]["status"], "amount_mismatch")
        self.assertEqual(report["details"][0]["truth_value"], "1000")
        self.assertEqual(report["details"][0]["output_value"], "999")

    def test_duplicate_predictions_and_truth_keep_multiplicity(self) -> None:
        """양쪽 중복을 삭제·합산하지 않고 일대일 연결하는지 검사한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 중복 개수가 사라지거나 하나의 정답이 재사용된 경우.
        Side Effects:
            메모리만 사용한다.
        """
        report = score_monthly_records(
            [predicted_record(), predicted_record(cell="AK6"), predicted_record(cell="AK7")],
            [expected_record(), expected_record(cell="AK6")],
        )
        summary = report["summary"]
        self.assertEqual(summary["truth_count"], 2)
        self.assertEqual(summary["confirmed_output_count"], 3)
        self.assertEqual(summary["match_count"], 2)
        self.assertEqual(summary["false_positive"], 1)
        self.assertEqual(summary["false_negative"], 0)
        self.assertEqual(summary["duplicate_output_excess"], 2)
        self.assertEqual(summary["duplicate_truth_groups"], 1)

    def test_duplicate_truth_missing_copy_stays_in_recall(self) -> None:
        """한 개 출력으로 중복 정답 두 개를 모두 맞힐 수 없는지 검사한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 중복 정답의 누락이 Recall 분모에서 빠진 경우.
        Side Effects:
            메모리만 사용한다.
        """
        report = score_monthly_records(
            [predicted_record()], [expected_record(), expected_record(cell="AK6")]
        )
        self.assertEqual(report["summary"]["match_count"], 1)
        self.assertEqual(report["summary"]["false_negative"], 1)
        self.assertEqual(report["summary"]["recall"], 0.5)

    def test_zero_and_blank_are_distinct(self) -> None:
        """실제 0은 일치시키고 빈 출력은 확정 금액으로 세지 않는지 검사한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 빈값을 0으로 채우거나 0 정답을 누락한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        self.assertIsNone(decimal_amount(""))
        self.assertIsNone(decimal_amount(None))
        self.assertEqual(decimal_amount("0"), Decimal(0))
        truth = [expected_record(normalized_value="0")]
        exact = score_monthly_records([predicted_record(normalized_value="0")], truth)
        blank = score_monthly_records(
            [predicted_record(normalized_value=None, confirmed=False)], truth
        )
        self.assertEqual(exact["summary"]["match_count"], 1)
        self.assertEqual(blank["summary"]["match_count"], 0)
        self.assertEqual(blank["summary"]["confirmed_output_count"], 0)
        self.assertEqual(blank["summary"]["false_negative"], 1)

    def test_missing_and_unresolved_keep_all_truth_in_recall(self) -> None:
        """생략과 금액 미확정 모두 원래 정답 분모를 유지하는지 검사한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 생략·미확정 정답을 분모에서 제거한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        truth = [expected_record(), expected_record(detail_id="synthetic-detail-2")]
        report = score_monthly_records(
            [predicted_record(confirmed=False, normalized_value=None)], truth
        )
        self.assertEqual(report["summary"]["truth_count"], 2)
        self.assertEqual(report["summary"]["false_negative"], 2)
        self.assertEqual(report["summary"]["recall"], 0)
        self.assertEqual(report["summary"]["error_counts"], {"unresolved": 1, "missing": 1})

    def test_different_detail_ids_are_not_merged(self) -> None:
        """동일 조직·계정·기간이라도 다른 상세 ID를 구분하는지 검사한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 다른 상세를 합치거나 금액 일치만으로 정답 처리한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        report = score_monthly_records(
            [predicted_record(detail_id="synthetic-detail-other")], [expected_record()]
        )
        self.assertEqual(report["summary"]["match_count"], 0)
        self.assertEqual(report["summary"]["false_positive"], 1)
        self.assertEqual(report["summary"]["false_negative"], 1)
        self.assertEqual(report["summary"]["error_counts"], {"missing": 1, "extra": 1})

    def test_closing_snapshots_are_evaluated_separately(self) -> None:
        """같은 실적 레코드라도 마감월 스냅샷을 섞지 않는지 검사한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 다른 마감월의 출력으로 누락을 메운 경우.
        Side Effects:
            메모리만 사용한다.
        """
        report = score_monthly_records(
            [predicted_record(closing_month="2026-02")], [expected_record()]
        )
        self.assertEqual(report["summary"]["match_count"], 0)
        self.assertEqual(report["summary"]["false_positive"], 1)
        self.assertEqual(report["summary"]["false_negative"], 1)

    def test_master_blank_is_unmeasured_while_zero_is_truth(self) -> None:
        """독립 마스터 변환에서 빈 셀을 미측정으로 남기고 실제 0만 정답에 넣는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 빈 셀이 0으로 변환되거나 미측정 근거가 없는 경우.
        Side Effects:
            추출·해시·파일 목록 조회를 메모리 대역으로 바꿨다가 복원한다.
            원본·임시·결과 파일과 폴더는 생성하지 않는다.
        """
        workbook = {"sheets": [
            {"name": "매출_세부_IFRS15後", "cells": [
                memory_cell(1, 1, "매출 (천원)"), memory_cell(5, 1, "테스트조직"),
                memory_cell(5, 3, "1200 기본료"), memory_cell(5, 5, "담당자_F10"),
                memory_cell(5, 6, "[ID:SYN-F10-REV-001; 수정:1]"),
                memory_cell(5, 9, "0"), memory_cell(5, 10, None),
            ]},
            {"name": "답안지_IFRS15後", "cells": [
                memory_cell(6, 37 + month, "실적" if month == 1 else "추정")
                for month in range(1, 13)
            ]},
        ]}
        with patch("excel_parser.benchmark.monthly_truth.extract_workbook", return_value=workbook), \
             patch("excel_parser.benchmark.monthly_truth.file_fingerprint", return_value="memory-hash"), \
             patch.object(Path, "resolve", return_value=Path("/memory/master.xlsx")), \
             patch.object(Path, "glob", return_value=[]):
            result = build_master_truth(Path("master.xlsx"), type_name="synthetic", closing_month=1)
        self.assertEqual(len(result["records"]), 1)
        self.assertEqual(result["records"][0]["normalized_value"], "0")
        self.assertEqual(result["records"][0]["cell"], "I5")
        self.assertEqual(result["metadata"]["unmeasured_cells"], 47)
        self.assertTrue(all(row["reason"] == "master_blank" for row in result["issues"]))
        self.assertTrue(result["metadata"]["closing_boundary_matches"])

    def test_missing_location_points_to_master_evidence(self) -> None:
        """누락 오류의 파일·시트·셀이 같은 마스터 출처를 가리키는지 검사한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 조직 파일명과 마스터 셀 좌표가 혼합된 경우.
        Side Effects:
            메모리만 사용한다.
        """
        detail = score_monthly_records([], [expected_record()])["details"][0]
        self.assertEqual(detail["source_file"], "/memory/master.xlsx")
        self.assertEqual(detail["cell"], "AK5")
        self.assertEqual(detail["organization_file"], "/memory/org.xlsx")

    def test_master_transfer_uses_recipient_without_rewriting_donor(self) -> None:
        """이관 수신 접미사와 담당자를 확인하고 원조직 행은 그대로 유지한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 수신 행을 원조직으로 연결하거나 실제 충돌을 숨긴 경우.
        Side Effects:
            메모리 원본 셀만 사용한다. 파일과 파서 결과를 조회하지 않는다.
        """
        layout = {"person": 14}
        donor = {14: {"value": "담당자_F20"}}
        recipient = {14: {"value": "담당자_F13"}}
        unrelated = {14: {"value": "담당자_F39"}}
        self.assertEqual(_master_organization(donor, layout, "SYN-F20-OPEX-022"), "F20")
        self.assertEqual(
            _master_organization(recipient, layout, "SYN-F20-OPEX-022-IN-F13"), "F13"
        )
        self.assertIsNone(
            _master_organization(unrelated, layout, "SYN-F20-OPEX-022-IN-F13")
        )


class MonthlyExecutionTests(unittest.TestCase):
    """규칙 기반 실행과 정답 읽기의 분리·원본 해시 감사를 메모리에서 확인한다."""

    def test_source_worker_reads_only_organization_via_rule_adapter(self) -> None:
        """조직 워커가 규칙 어댑터의 전체 후보를 평가기에 전달하는지 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 입력 파일·후보 전달 또는 반환 축약이 잘못된 경우.
        Side Effects:
            어댑터·평가 함수를 메모리 대역으로 바꾸며 파일·프로세스를 만들지 않는다.
        """
        mapped = {"records": [{"candidate": "original"}]}
        workbook = {"sheets": [{"name": "원본"}]}
        parsed = {"parser": "rule", "mapped": mapped, "workbook": workbook,
                  "completed": True, "validation_passed": True,
                  "source_integrity": {"sha256_before": "same", "sha256_after": "same"}}
        before = deepcopy(parsed)
        with patch("excel_parser.benchmark.adapters.run_rule_adapter", return_value=parsed) as adapter, \
             patch("excel_parser.benchmark.monthly_evaluation.project_predictions", return_value=[predicted_record()]) as project:
            result = _run_source(("/memory/org.xlsx", "synthetic", "2026-01"))
        adapter.assert_called_once_with(Path("/memory/org.xlsx"))
        project.assert_called_once_with(mapped["records"], workbook,
                                        type_name="synthetic", closing_month="2026-01")
        self.assertEqual(result["trial"]["parser"], "rule")
        self.assertEqual(result["trial"]["source_file"], "/memory/org.xlsx")
        self.assertNotIn("mapped", result["trial"])
        self.assertNotIn("workbook", result["trial"])
        self.assertEqual(result["predictions"], [predicted_record()])
        self.assertEqual(parsed, before)

    def test_monthly_run_keeps_independent_scoring_and_source_integrity(self) -> None:
        """원본과 독립 마스터를 각각 처리하고 해시 차이를 숨기지 않는지 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 독립 대조·실행 건수·원본 변경 감지가 잘못된 경우.
        Side Effects:
            모든 파일·폴더 저장과 프로세스 풀을 메모리 대역으로 바꾼다.
            실제 원본과 결과 파일을 읽거나 생성하지 않는다.
        """
        dataset = Path("/memory/dataset")
        output = Path("/memory/output")
        repo = Path("/memory/repo")
        inventory = {"selected_types": ["synthetic"], "organization_count": 1,
                     "master_count": 1, "batches": [{"type": "synthetic",
                     "closing_month": "2026-01", "organization_files": ["/memory/org.xlsx"],
                     "master": "/memory/master.xlsx", "organization_count": 1}]}
        truth = {"records": [expected_record()], "metadata": {"unmeasured_cells": 0}, "issues": []}
        for after, unchanged in (("same", True), ("changed", False)):
            trial = {"parser": "rule", "type": "synthetic", "closing_month": "2026-01",
                     "source_file": "/memory/org.xlsx", "completed": True,
                     "validation_passed": True,
                     "source_integrity": {"sha256_before": "same", "sha256_after": after}}
            with self.subTest(after=after), \
                 patch("excel_parser.benchmark.monthly.validate_output_path", return_value=output), \
                 patch("excel_parser.benchmark.monthly.inventory_dataset", return_value=inventory), \
                 patch("excel_parser.benchmark.monthly.file_fingerprint", return_value="same"), \
                 patch("excel_parser.benchmark.runner.implementation_fingerprint", return_value="implementation"), \
                 patch.object(Path, "mkdir"), \
                 patch("excel_parser.benchmark.monthly.ProcessPoolExecutor") as pool_factory, \
                 patch("excel_parser.benchmark.monthly_truth.build_master_truth", return_value=truth) as master, \
                 patch("excel_parser.benchmark.monthly.write_json") as write_json, \
                 patch("excel_parser.benchmark.monthly._append_rows"), \
                 patch("excel_parser.benchmark.monthly.write_summary"), \
                 patch("builtins.print"):
                pool = pool_factory.return_value.__enter__.return_value
                pool.map.return_value = [{"trial": trial, "predictions": [predicted_record()]}]
                report = run_monthly(dataset, ["01"], output, repo, 1)
                pool.map.assert_called_once_with(_run_source, [("/memory/org.xlsx", "synthetic", "2026-01")])
                master.assert_called_once_with(Path("/memory/master.xlsx"), type_name="synthetic", closing_month="2026-01")
                self.assertEqual(report["trial_count"], 1)
                self.assertEqual(report["sources_unchanged"], unchanged)
                self.assertEqual({row["parser"] for row in report["summary"]}, {"rule"})
                self.assertTrue(all(row["match_count"] == 1 for row in report["summary"]))
                self.assertEqual(report["conditions"]["parser"], "rule")
                self.assertTrue(report["execution_by_type"][0]["evaluation_complete"])
                self.assertEqual(write_json.call_args_list[0].args, (output / "inventory.json", inventory))

    def test_invalid_worker_count_does_not_create_output(self) -> None:
        """잘못된 프로세스 수는 결과 폴더를 만들기 전에 거절하는지 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 입력 오류가 허용되거나 결과 폴더를 만드는 경우.
        Side Effects:
            폴더 생성 호출을 감시하는 메모리 대역만 사용한다.
        """
        with patch.object(Path, "mkdir") as mkdir:
            with self.assertRaisesRegex(ValueError, "프로세스 수"):
                run_monthly(Path("/memory/input"), ["01"], Path("/memory/output"), Path("/memory"), 0)
            mkdir.assert_not_called()


if __name__ == "__main__":
    unittest.main()
