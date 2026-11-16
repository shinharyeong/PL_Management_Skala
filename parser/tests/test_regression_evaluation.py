"""기존 평가 합성의 범위·원본 대응·완료 계수·덮어쓰기 금지를 메모리에서 검증한다."""

import unittest
from copy import deepcopy
from pathlib import Path
from unittest.mock import patch

from test_regression import join_fixture, regression_inputs

from excel_parser.benchmark import regression_evaluation as merger


def evaluation_fixture() -> tuple[dict, list[dict], list[dict], list[str]]:
    """같은 새 구현 81개 생성과 9+36+36 완료 평가를 메모리에 만든다.

    Args: 없음.
    Returns: final, groups, reports, group generation 경로.
    Raises: ValueError - 합성 생성 계약 오류.
    Side Effects: 메모리만 사용하며 파일을 열지 않는다.
    """
    baseline, groups = regression_inputs()
    final = join_fixture(baseline, groups)
    reports = []
    paths = [f"/artifacts/v3/group-{i}/generation.json" for i in range(len(groups))]
    for group in groups:
        rows = []
        for batch in group["batches"]:
            matched = batch["closing_month"].endswith("-01")
            rows.append({"type": batch["type"], "closing_month": batch["closing_month"],
                         "generation_status": batch["status"], "detailed_and_total_compared": True,
                         "reference_unchanged": True, "stored_totals_verified": True,
                         "truth_count": 4, "confirmed_output_count": 4 if matched else 5,
                         "match_count": 4 if matched else 3, "false_positive": 0 if matched else 2,
                         "false_negative": 0 if matched else 1, "total_groups": 10,
                         "total_matching_groups": 10 if matched else 8, "all_totals_match": matched,
                         "strict_detail_and_total_match": matched, "reconciled_detail_and_total_match": matched,
                         "error_counts": {} if matched else {"extra": 2, "missing": 1}})
        reports.append({"command": ["synthetic", "evaluate"], "rows": rows, "generated_count": len(rows),
                        "detailed_and_total_compared_count": len(rows)})
    return final, groups, reports, paths


class RegressionEvaluationTests(unittest.TestCase):
    """같은 완료 평가만 합치고 점수와 원본 경계를 보존한다."""

    def test_exact_evaluations_preserve_rows_inputs_and_aggregate_flags(self) -> None:
        """81개 원래 행·입력을 바꾸지 않고 완료·일치 계수를 합친다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 점수 변경 또는 집계 오류.
        Side Effects: 실제 파일 접근은 실패하도록 대체한다.
        """
        final, groups, reports, paths = evaluation_fixture()
        before = deepcopy((final, groups, reports))
        with patch.object(Path, "open", side_effect=AssertionError("파일 접근 금지")):
            result = merger.combine_evaluation_data(final, groups, reports, group_paths=paths)
        self.assertEqual((final, groups, reports), before)
        self.assertEqual(result["generated_count"], 81)
        self.assertEqual(result["strict_detail_and_total_match_count"], 9)
        self.assertEqual(result["reconciled_detail_and_total_match_count"], 9)
        self.assertEqual(result["all_totals_match_count"], 9)
        originals = {(row["type"], row["closing_month"]): row for report in reports for row in report["rows"]}
        self.assertEqual(result["rows"], [originals[(batch["type"], batch["closing_month"])] for batch in final["batches"]])

    def test_duplicate_or_missing_evaluation_rows_are_rejected(self) -> None:
        """중복 또는 누락된 월 평가를 최종 완료로 합성하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 잘못된 평가가 통과한 경우.
        Side Effects: 합성 사전만 변경한다.
        """
        final, groups, reports, paths = evaluation_fixture()
        reports[0]["rows"][1] = deepcopy(reports[0]["rows"][0])
        with self.assertRaises(ValueError):
            merger.combine_evaluation_data(final, groups, reports, group_paths=paths)
        final, groups, reports, paths = evaluation_fixture()
        reports[0]["rows"].pop()
        reports[0]["generated_count"] -= 1
        reports[0]["detailed_and_total_compared_count"] -= 1
        with self.assertRaises(ValueError):
            merger.combine_evaluation_data(final, groups, reports, group_paths=paths)

    def test_overlapping_groups_and_incomplete_union_are_rejected(self) -> None:
        """그룹 자체 중복 또는 81개 미달도 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 범위 계약 위반이 통과한 경우.
        Side Effects: 합성 목록만 사용한다.
        """
        final, groups, reports, paths = evaluation_fixture()
        with self.assertRaises(ValueError):
            merger.combine_evaluation_data(final, groups + [groups[0]], reports + [reports[0]], group_paths=paths + [paths[0]])
        with self.assertRaises(ValueError):
            merger.combine_evaluation_data(final, groups[:-1], reports[:-1], group_paths=paths[:-1])

    def test_batch_source_hash_directory_and_lineage_must_match(self) -> None:
        """생성 상세 해시·원본 대응·실제 그룹 경로의 차이를 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 다른 생성물의 평가가 선택된 경우.
        Side Effects: 합성 사전만 변경한다.
        """
        for field, value in (("master_details_sha256", "8" * 64),
                             ("directory", "/artifacts/other/batch"),
                             ("master", "/reference/other.xlsx")):
            final, groups, reports, paths = evaluation_fixture()
            groups[0]["batches"][0][field] = value
            with self.assertRaises(ValueError):
                merger.combine_evaluation_data(final, groups, reports, group_paths=paths)
        final, groups, reports, paths = evaluation_fixture()
        final["batches"][0]["lineage"]["manifest"] = "/artifacts/other/generation.json"
        with self.assertRaises(ValueError):
            merger.combine_evaluation_data(final, groups, reports, group_paths=paths)

    def test_incomplete_or_changed_reference_evaluation_is_rejected(self) -> None:
        """평가 미완료 또는 기준 원본 변경 기록을 숨겨 합성하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 불완전 평가가 통과한 경우.
        Side Effects: 합성 사전만 변경한다.
        """
        for field in ("detailed_and_total_compared", "reference_unchanged", "stored_totals_verified"):
            final, groups, reports, paths = evaluation_fixture()
            reports[0]["rows"][0][field] = False
            with self.assertRaises(ValueError):
                merger.combine_evaluation_data(final, groups, reports, group_paths=paths)

    def test_existing_output_is_rejected_before_read_or_write(self) -> None:
        """기존 evaluation은 입력 읽기나 복사에 앞서 즉시 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 기존 파일에 접근하거나 저장한 경우.
        Side Effects: 파일 경계를 메모리 Mock으로 대체한 뒤 복원한다.
        """
        current = Path("/artifacts/final")
        with patch.object(merger, "_checked_path", side_effect=[current, current / "evaluation"]), \
                patch.object(Path, "exists", return_value=True), \
                patch.object(merger, "_read_json", side_effect=AssertionError("입력 읽기 금지")), \
                patch.object(Path, "mkdir", side_effect=AssertionError("쓰기 금지")), \
                patch.object(merger.shutil, "copyfile", side_effect=AssertionError("복사 금지")):
            with self.assertRaises(ValueError):
                merger.merge_evaluations([Path("/artifacts/a")], current, current / "evaluation", write_output=True)


if __name__ == "__main__":
    unittest.main()
