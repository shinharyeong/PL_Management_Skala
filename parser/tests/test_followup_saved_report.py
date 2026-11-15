"""저장 결과 사후 분석의 금액 기여와 식별·빈칸 구분을 메모리로 검증한다."""

import copy
import json
import unittest
from pathlib import Path
from unittest.mock import patch

from excel_parser.benchmark import followup_saved_report as report
from excel_parser.benchmark.followup_saved_report import analyze_batch


def saved_record(cell: str = "I5", value: str | None = "100", **changes: object) -> dict:
    """실제 원본이나 마스터를 읽지 않는 저장 상세 예시를 만든다.

    Args:
        cell: 서로 다른 합성 원본 셀 주소.
        value: 정규금액 문자열 또는 빈 값 None.
        changes: 검증 사례에 필요한 필드 변경.
    Returns:
        독립된 평탄 상세 사전.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    return {
        "source_file": "/synthetic/organization.xlsx", "sheet": "revenue", "cell": cell,
        "source_row": int(cell[1:]), "domain": "revenue", "organization_code": "F1",
        "organization_status": "resolved", "submission_owner": "F1", "account_code": "40010",
        "detail_id": "source-detail", "year": 2025, "month": 1, "scenario": "actual",
        "period_group": "prior", "semantic_complete": True, "confirmed": value is not None,
        "raw_value": value, "normalized_value": value, "formula": None,
        "assembly_status": "confirmed" if value is not None else "unresolved",
        "included_in_totals": value is not None, **changes,
    }


def saved_error(record: dict, status: str = "extra", truth: str | None = None) -> dict:
    """상세 출처와 정답값을 보존한 합성 평가 오류를 만든다.

    Args:
        record: 합성 저장 상세.
        status: extra, missing, amount_mismatch 등의 평가 상태.
        truth: 정규 정답 금액 또는 None.
    Returns:
        평가 상태를 임의로 변경하지 않은 오류 사전.
    Raises:
        KeyError: 합성 상세에 필요한 필드가 없는 경우.
    Side Effects:
        없음.
    """
    return {
        "source_file": record["source_file"], "sheet": record["sheet"], "cell": record["cell"],
        "record_key": copy.deepcopy(record), "status": status,
        "truth_value": truth, "output_value": record["normalized_value"],
    }


def saved_totals(output: str, truth: str, organization: str | None = "F1") -> list[dict]:
    """수작업으로 지정한 전사·조직 합계 두 개를 만든다.

    Args:
        output: 저장 출력 합계.
        truth: 저장 정답 합계.
        organization: 해당 행 조직이며 제출 소유자에서 추론하지 않는다.
    Returns:
        독립된 동일 기간 합계 두 개.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    return [
        {"scope": scope, "organization_code": code, "domain": "revenue", "period_group": "prior",
         "year": 2025, "month": 1, "scenario": "actual", "output_total": output, "truth_total": truth}
        for scope, code in (("company", None), ("organization", organization))
    ]


def saved_batch(details: list[dict], errors: list[dict], totals: list[dict],
                excluded: list[dict] | None = None, source_zero_count: int = 0) -> dict:
    """분석기가 사용하는 저장 자료를 메모리에서 조합한다.

    Args:
        details: 선택 상세.
        errors: 저장 평가 오류.
        totals: 독립 지정한 저장 합계.
        excluded: 선택에서 제외된 상세.
        source_zero_count: 저장 원본 파서의 확정 0 건수.
    Returns:
        analyze_batch 입력 사전.
    Raises:
        없음.
    Side Effects:
        없음. 실제 파일·네트워크·임시 산출물에 접근하지 않는다.
    """
    return {"details": details, "errors": errors, "totals": totals,
            "excluded": excluded or [], "source_actual_zero_count": source_zero_count}


class FollowupSavedReportTests(unittest.TestCase):
    """순수 사후 분석의 금액·미확정 계약을 독립 합성 사례로 검증한다."""

    def test_included_extra_contributes_to_difference(self) -> None:
        """포함 extra만 실제 합계 차이에 더하고 모든 조직·전사 잔차를 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 포함 출력과 차액의 연결이 잘못된 경우.
        Side Effects:
            없음. 메모리 합성 자료만 사용한다.
        """
        matched, extra = saved_record(value="80"), saved_record("I6", "20")
        data = saved_batch([matched, extra], [saved_error(extra)], saved_totals("100", "80"))
        snapshot = copy.deepcopy(data)
        result = analyze_batch(data, {})
        self.assertEqual(result["contributions"][0]["contribution_won"], "20")
        self.assertEqual(result["contributions"][0]["category"], "included_extra")
        self.assertTrue(all(row["difference_won"] == "20" for row in result["bridges"]))
        self.assertTrue(all(row["numeric_residual_won"] == "0" for row in result["bridges"]))
        self.assertEqual(data, snapshot)

    def test_confirmed_business_invalid_extra_is_separate_potential(self) -> None:
        """숫자 확정 strict FP를 유지하되 업무 무효 출력은 합계 기여에서 제외한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 제외 금액을 중복 가산하거나 기존 오류를 바꾼 경우.
        Side Effects:
            없음.
        """
        record = saved_record(semantic_complete=False, included_in_totals=False, assembly_status="unresolved",
                              business_validation={"status": "invalid", "issues": [{"code": "BAD_CATEGORY"}]})
        error = saved_error(record)
        result = analyze_batch(saved_batch([record], [error], saved_totals("0", "0")), {})
        contribution = result["contributions"][0]
        self.assertEqual(contribution["contribution_won"], "0")
        self.assertEqual(contribution["category"], "extra_excluded_from_totals")
        self.assertEqual(contribution["original_error"], error)
        self.assertEqual(error["status"], "extra")
        self.assertTrue(record["confirmed"])
        self.assertEqual(result["audit"]["business_invalid_known_amount_slots"], 1)
        self.assertTrue(all(row["excluded_known_amount_separate_won"] == "100" for row in result["bridges"]))
        self.assertTrue(all(row["numeric_residual_won"] == "0" for row in result["bridges"]))

    def test_zero_optional_blank_and_mandatory_blank_stay_distinct(self) -> None:
        """확정 0·원본 선택적 빈칸·선언된 기간의 필수 빈칸을 구분한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 실제 0 또는 빈칸 종류를 섞은 경우.
        Side Effects:
            없음.
        """
        zero = saved_record(value="0", raw_value=0)
        optional = saved_record("I6", None, period_group="next_projection", assembly_status="missing_optional")
        mandatory = saved_record("I7", None)
        result = analyze_batch(saved_batch([zero, optional, mandatory], [], saved_totals("0", "0"), source_zero_count=1), {})
        audit = result["audit"]
        self.assertEqual(audit["selected_actual_zero"], 1)
        self.assertEqual(audit["included_actual_zero"], 1)
        self.assertEqual(audit["source_actual_zero"], 1)
        self.assertEqual(audit["selected_raw_optional_blank"], 1)
        self.assertEqual(audit["optional_blank"], 1)
        self.assertEqual(audit["amount_blank_unknown_amount_slots"], 1)
        self.assertEqual(audit["required_unresolved_total"], 1)
        self.assertEqual(audit["selected_formula_cache_missing_or_error"], 0)

    def test_business_invalid_optional_blank_is_not_mandatory_amount_missing(self) -> None:
        """업무 식별 실패와 함께 있는 선택적 빈칸을 필수 금액 누락으로 세지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 식별 미확정과 금액 필요성을 혼동한 경우.
        Side Effects:
            없음.
        """
        invalid = saved_record(value=None, period_group="next_projection", semantic_complete=False,
                               business_validation={"status": "invalid", "issues": []})
        result = analyze_batch(saved_batch([invalid], [], []), {})
        audit = result["audit"]
        self.assertEqual(audit["required_unresolved_total"], 1)
        self.assertEqual(audit["business_invalid_unknown_amount_slots"], 1)
        self.assertEqual(audit["business_invalid_optional_amount_blank"], 1)
        self.assertEqual(audit["selected_raw_optional_blank"], 1)
        self.assertEqual(audit.get("optional_blank", 0), 0)
        self.assertEqual(audit["amount_blank_unknown_amount_slots"], 0)
        self.assertTrue(result["excluded_potential"][0]["raw_optional_amount_blank"])

    def test_unresolved_organization_is_not_replaced_with_submission_owner(self) -> None:
        """확정된 제출 소유자나 ID로 미확정 행 조직을 보완하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 미확정 조직을 추론하거나 알려진 제외 금액을 잃은 경우.
        Side Effects:
            없음.
        """
        excluded = saved_record(value="42", organization_code=None, organization_status="conflict",
                                semantic_complete=False, included_in_totals=False,
                                exclusion_reason="detail_identity_unresolved", detail_id="contains-F1")
        result = analyze_batch(saved_batch([], [], [], [excluded]), {})
        organizations = [row for row in result["bridges"] if row["scope"] == "organization"]
        self.assertEqual(len(organizations), 1)
        self.assertIsNone(organizations[0]["organization_code"])
        self.assertEqual(organizations[0]["excluded_known_amount_separate_won"], "42")
        self.assertIsNone(organizations[0]["numeric_residual_won"])
        self.assertEqual(result["audit"]["organization_invalid_known_amount_slots"], 1)
        self.assertEqual(result["excluded_potential"][0]["record"]["submission_owner"], "F1")

    def test_amount_mismatch_keeps_one_error_and_uses_output_minus_truth(self) -> None:
        """같은 키의 금액 불일치를 오류 1건과 출력−정답 기여로 보존한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 오류를 덮어쓰거나 출력·정답 한쪽 기여를 잃은 경우.
        Side Effects:
            없음. 평가기의 FP 1·FN 1 정의를 다시 계산하거나 바꾸지 않는다.
        """
        record = saved_record(value="100.10")
        error = saved_error(record, "amount_mismatch", "90.05")
        result = analyze_batch(saved_batch([record], [error], saved_totals("100.10", "90.05")), {})
        self.assertEqual(len(result["contributions"]), 1)
        contribution = result["contributions"][0]
        self.assertEqual(contribution["category"], "amount_mismatch")
        self.assertEqual(contribution["included_output_won"], "100.10")
        self.assertEqual(contribution["truth_deduction_won"], "90.05")
        self.assertEqual(contribution["contribution_won"], "10.05")
        self.assertEqual(contribution["original_error"], error)
        self.assertTrue(all(row["numeric_residual_won"] == "0.00" for row in result["bridges"]))

    def test_missing_truth_contributes_negative_without_output(self) -> None:
        """정답에만 있는 금액은 가짜 출력을 만들지 않고 음수 차액으로 연결한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 누락 정답을 빼지 않거나 원본 소유자를 추론한 경우.
        Side Effects:
            없음.
        """
        expected = saved_record(value=None)
        error = saved_error(expected, "missing", "30")
        result = analyze_batch(saved_batch([], [error], saved_totals("0", "30")), {})
        self.assertEqual(result["contributions"][0]["contribution_won"], "-30")
        self.assertEqual(result["contributions"][0]["included_output_won"], "0")
        self.assertTrue(all(row["numeric_residual_won"] == "0" for row in result["bridges"]))

    def test_numeric_residual_is_preserved_when_saved_errors_do_not_explain_total(self) -> None:
        """오류 목록으로 설명되지 않는 합계 차이를 0으로 강제하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 실제 숫자 잔차를 숨긴 경우.
        Side Effects:
            없음.
        """
        record = saved_record()
        result = analyze_batch(saved_batch([record], [], saved_totals("100", "80")), {})
        self.assertTrue(all(row["numeric_residual_won"] == "20" for row in result["bridges"]))

    def test_bad_stored_output_total_is_rejected(self) -> None:
        """실제 포함 상세와 맞지 않는 저장 출력 합계를 즉시 거부한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 손상된 저장 합계를 받아들인 경우.
        Side Effects:
            없음.
        """
        record = saved_record()
        with self.assertRaisesRegex(ValueError, "포함|included_in_totals"):
            analyze_batch(saved_batch([record], [], saved_totals("99", "99")), {})

    def test_read_only_run_never_creates_output(self) -> None:
        """저장 플래그 없는 전체 호출은 분석을 마쳐도 폴더나 파일을 만들지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 읽기 전용 실행이 저장 경계에 진입한 경우.
        Side Effects:
            파일 읽기·쓰기 경계를 메모리 대역으로 바꾸고 복원한다.
        """
        allowed = Path(report.__file__).resolve().parents[3] / "output/comparisons"
        baseline, current = allowed / "old/final", allowed / "new/final"
        batch = {"type": "05_synthetic", "closing_month": "2026-01", "organization_files": ["memory.xlsx"],
                 "organization_count": 1, "master": "reference-not-opened.xlsx"}
        manifest = {"inventory_sha256": "fixed", "batches": [batch]}
        data = saved_batch([saved_record()], [], saved_totals("100", "100"))
        data.update(metadata={"selected_submissions": [{"source_file": "memory.xlsx", "source_sha256": "same"}]},
                    summary={"false_positive": 0}, protected={})
        with patch.object(report, "checked", side_effect=lambda path, roots, **kwargs: path), \
                patch.object(Path, "exists", return_value=False), \
                patch.object(Path, "read_text", return_value=json.dumps(manifest)), \
                patch.object(report, "sha", return_value="memory-hash"), \
                patch.object(report, "load_batch", return_value=data), \
                patch.object(Path, "mkdir", side_effect=AssertionError("폴더 생성 금지")), \
                patch.object(Path, "open", side_effect=AssertionError("파일 저장 금지")), \
                patch.object(report, "save_rows", side_effect=AssertionError("상세 저장 금지")), \
                patch("builtins.print"):
            result = report.run_saved_report(baseline, current, allowed / "new/report")
        self.assertFalse(result["written"])
        self.assertEqual(result["batch_count"], 1)
        self.assertTrue(result["all_numeric_residuals_zero"])

    def test_cli_write_flag_is_explicit(self) -> None:
        """CLI는 명시한 저장 플래그만 실제 분석 함수로 전달한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 기본 실행이 저장 요청으로 바뀐 경우.
        Side Effects:
            분석 함수를 메모리 대역으로 바꾸고 복원한다.
        """
        argv = ["--baseline", "/old/final", "--current", "/new/final", "--output", "/new/report"]
        outcome = {"batch_count": 1, "all_numeric_residuals_zero": True, "written": False, "audits": {}}
        with patch.object(report, "run_saved_report", return_value=outcome) as runner, patch("builtins.print"):
            self.assertEqual(report.main(argv), 0)
            self.assertIs(runner.call_args.kwargs["write_output"], False)
            self.assertEqual(report.main(argv + ["--write-output"]), 0)
            self.assertIs(runner.call_args.kwargs["write_output"], True)

    def test_outside_comparisons_is_rejected_before_artifact_read(self) -> None:
        """비교 산출물 밖의 실제 경로는 JSON을 읽기 전에 거부한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 범위 밖 입력을 읽거나 허용한 경우.
        Side Effects:
            경로 메타데이터와 읽기 경계를 메모리 대역으로 바꾸고 복원한다.
        """
        outside = Path("/outside/reference.xlsx")
        with patch.object(Path, "resolve", autospec=True, side_effect=lambda path, **kwargs: path), \
                patch.object(Path, "read_text", side_effect=AssertionError("JSON 읽기 금지")), \
                patch.object(Path, "open", side_effect=AssertionError("파일 읽기 금지")):
            with self.assertRaisesRegex(ValueError, "실행 산출물"):
                report.run_saved_report(outside, outside, Path("/outside/output"), write_output=True)


if __name__ == "__main__":
    unittest.main()
