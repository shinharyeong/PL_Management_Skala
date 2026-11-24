"""파일·원본·정답 접근 없이 생성 그룹 합성과 회귀 지표를 검증한다."""

import unittest
from copy import deepcopy
from pathlib import Path
from unittest.mock import patch

from test_reconstruction_selection import generation_fixture, select_fixture

from excel_parser.benchmark.regression import (
    _aggregate_matrix,
    compare_evaluation_rows,
    compare_source_records,
    join_manifest_data,
    metric_snapshot,
)


def regression_inputs() -> tuple[dict, list[dict]]:
    """v2 복합 기준과 같은 새 구현의 9+36+36 그룹을 메모리에 준비한다.

    Args: 없음.
    Returns: 기준 manifest와 새 그룹 목록.
    Raises: ValueError - 합성 v2 선택 계약 오류.
    Side Effects: 메모리만 사용한다.
    """
    original = generation_fixture()
    baseline = select_fixture(original, generation_fixture(replacement=True, replacement_types=(5, 7)), replace_types=("05", "07"))
    groups = []
    for number, types in enumerate(({"05"}, {"01", "02", "03", "04"}, {"06", "07", "08", "09"})):
        group = deepcopy(original)
        group["implementation_sha256"] = "9" * 64
        group["batches"] = [batch for batch in group["batches"] if batch["type"][:2] in types]
        for batch in group["batches"]:
            batch["directory"] = batch["directory"].replace("/run/full/", f"/v3/group-{number}/")
        groups.append(group)
    return baseline, groups


def join_fixture(baseline: dict, groups: list[dict]) -> dict:
    """순수 join에 합성 경로·해시 근거를 전달한다.

    Args: baseline - 기준 목록. groups - 새 그룹 목록.
    Returns: join 결과.
    Raises: ValueError - 잘못된 합성 계약.
    Side Effects: 없음.
    """
    return join_manifest_data(groups, baseline,
                              group_paths=[f"/artifacts/v3/group-{i}/generation.json" for i in range(len(groups))],
                              group_hashes=["e" * 64] * len(groups),
                              baseline_path="/artifacts/v2/final/generation.json", baseline_hash="f" * 64)


def source_record(**changes: object) -> dict:
    """셀과 의미·원금액이 있는 독립 파서 출력 예시를 만든다.

    Args: changes - 바꿀 합성 필드.
    Returns: 원본 파일을 참조하지 않는 사전.
    Raises: 없음.
    Side Effects: 없음.
    """
    return {"source_file": "org.xlsx", "sheet": "details", "cell": "I5", "domain": "revenue",
            "organization_code": "F10", "organization_status": "resolved", "account_code": "1100",
            "detail_id": "detail-1", "year": 2026, "month": 1, "scenario": "actual",
            "aggregation": "month", "period_group": "outlook", "normalized_value": "10",
            "raw_value": 10, "value_kind": "amount", "value_status": "confirmed",
            "mapping_status": "mapped", "confirmed": True, "semantic_complete": True, **changes}


class RegressionTests(unittest.TestCase):
    """재파싱 범위·계보·셀 다중집합·보고 지표를 검증한다."""

    def test_join_preserves_explicit_policy_and_rejects_mixed_publication(self) -> None:
        """같은 구현이라도 발행 정책이 다르면 최종 81개로 혼합하지 않는다.

        Args:
            없음. 독립 메모리 생성 목록을 사용한다.
        Returns:
            없음.
        Raises:
            AssertionError: 정책이 손실되거나 혼합 실행이 통과한 경우.
        Side Effects:
            없음. 원본·기준 마스터·파일에 접근하지 않는다.
        """
        baseline, groups = regression_inputs()
        for group in groups:
            group["publication_policy"] = "source-contract-v4"
        self.assertEqual(join_fixture(baseline, groups)["publication_policy"], "source-contract-v4")
        groups[0].pop("publication_policy")
        with self.assertRaises(ValueError):
            join_fixture(baseline, groups)

    def test_join_exact_groups_accepts_composite_v2_without_mutation(self) -> None:
        """복합 v2 기준을 읽어 새 동일 구현 81개를 입력 변경 없이 합친다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 범위·계보·입력 불변 위반.
        Side Effects: 실제 파일 접근은 실패하도록 대체한다.
        """
        baseline, groups = regression_inputs()
        before = deepcopy((baseline, groups))
        with patch.object(Path, "open", side_effect=AssertionError("파일 접근 금지")):
            result = join_fixture(baseline, groups)
        self.assertEqual((baseline, groups), before)
        self.assertEqual(len(result["batches"]), 81)
        self.assertEqual(result["implementation_sha256"], "9" * 64)
        self.assertEqual(result["reference_masters_read"], 0)
        self.assertEqual([len(group["batches"]) for group in groups], [9, 36, 36])
        self.assertTrue(all(batch["generation_implementation_sha256"] == "9" * 64 for batch in result["batches"]))
        self.assertTrue(all("/v3/group-" in batch["directory"] for batch in result["batches"]))

    def test_join_rejects_overlap_missing_and_implementation_drift(self) -> None:
        """그룹 중복·누락·서로 다른 파서 구현을 거부한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 잘못된 그룹이 통과한 경우.
        Side Effects: 합성 사전만 변경한다.
        """
        baseline, groups = regression_inputs()
        groups[1]["batches"].append(deepcopy(groups[0]["batches"][0]))
        with self.assertRaises(ValueError):
            join_fixture(baseline, groups)
        baseline, groups = regression_inputs()
        groups[0]["batches"].pop()
        with self.assertRaises(ValueError):
            join_fixture(baseline, groups)
        baseline, groups = regression_inputs()
        groups[2]["implementation_sha256"] = "8" * 64
        with self.assertRaises(ValueError):
            join_fixture(baseline, groups)

    def test_join_rejects_inventory_and_original_correspondence_changes(self) -> None:
        """동일 원본 목록·기준 마스터 대응과 inventory 해시를 유지해야 한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 원본 변경이 통과한 경우.
        Side Effects: 합성 사전만 변경한다.
        """
        baseline, groups = regression_inputs()
        groups[0]["inventory_sha256"] = "0" * 64
        with self.assertRaises(ValueError):
            join_fixture(baseline, groups)
        baseline, groups = regression_inputs()
        groups[0]["batches"][0]["organization_files"].reverse()
        with self.assertRaises(ValueError):
            join_fixture(baseline, groups)
        baseline, groups = regression_inputs()
        groups[0]["batches"][0]["master"] = "/reference/other.xlsx"
        with self.assertRaises(ValueError):
            join_fixture(baseline, groups)

    def test_source_comparison_preserves_duplicate_multiplicity(self) -> None:
        """같은 셀의 중복 수량과 의미 변화는 삭제·합산 없이 드러난다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 중복 또는 변경 셀 집계 오류.
        Side Effects: 메모리만 사용한다.
        """
        before = [source_record(), source_record()]
        after = [source_record()]
        result = compare_source_records(before, after)
        self.assertFalse(result["identical"])
        self.assertEqual(result["same_record_count"], 1)
        self.assertEqual(result["removed_record_count"], 1)
        self.assertEqual(result["added_record_count"], 0)
        self.assertEqual(result["changes"][0]["before"][0]["count"], 2)
        changed = compare_source_records([source_record()], [source_record(semantic_complete=False)])
        self.assertEqual((changed["removed_record_count"], changed["added_record_count"]), (1, 1))

    def test_source_comparison_ignores_diagnostics_but_distinguishes_blank_zero(self) -> None:
        """추가 진단은 비교 의미를 바꾸지 않고 실제 빈값과 0은 구별한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 정규금액 또는 진단 비교 오류.
        Side Effects: 메모리만 사용한다.
        """
        unchanged = compare_source_records([source_record()], [source_record(normalized_value="10.000", business_validation={"status": "valid"})])
        self.assertTrue(unchanged["identical"])
        changed = compare_source_records([source_record(normalized_value=None, raw_value=None)], [source_record(normalized_value="0", raw_value=0)])
        self.assertFalse(changed["identical"])
        self.assertEqual(changed["changed_cell_count"], 1)

    def test_nonzero_fp_includes_amount_mismatch_and_excludes_zero(self) -> None:
        """비0 FP는 extra와 금액 불일치를 모두 포함하며 0·미확정은 제외한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 비0 FP 정의가 틀린 경우.
        Side Effects: 메모리만 사용한다.
        """
        errors = [{"status": "extra", "output_value": "0"}, {"status": "extra", "output_value": "10"},
                  {"status": "amount_mismatch", "output_value": "0"}, {"status": "amount_mismatch", "output_value": "-20"},
                  {"status": "unresolved", "output_value": "30"}, {"status": "missing", "output_value": None}]
        metrics = metric_snapshot({"false_positive": 4, "amount_mismatch": 2, "unresolved_required": 1}, errors)
        self.assertEqual(metrics["nonzero_fp"], 2)
        self.assertEqual(metrics["amount_mismatch"], 2)
        self.assertEqual(metrics["unresolved_required"], 1)

    def test_matrix_uses_record_micro_counts(self) -> None:
        """월별 비율 평균 대신 전체 레코드 건수로 P/R/F1을 계산한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 마이크로 집계 오류.
        Side Effects: 메모리만 사용한다.
        """
        rows = []
        for expected, matched in ((1, 1), (9, 0)):
            summary = metric_snapshot({"truth_count": expected, "confirmed_output_count": expected,
                                       "match_count": matched, "all_totals_match": matched == expected}, [])
            rows.append({"type": "01_test", "before": summary, "after": deepcopy(summary),
                         "source_comparison": {"changed_cell_count": 0}, "nonregression_required": True, "nonregression_pass": True})
        overall = next(row for row in _aggregate_matrix(rows) if row["type"] == "ALL")
        self.assertEqual(overall["after_recall"], 0.1)
        self.assertEqual(overall["after_precision"], 0.1)
        self.assertEqual(overall["after_f1"], 0.1)

    def test_evaluation_comparison_detects_changed_key_with_same_counts(self) -> None:
        """오류 건수가 같아도 업무키·금액·출처 변화는 회귀 차이로 남긴다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 다른 오류를 동일한 것으로 집계한 경우.
        Side Effects: 메모리만 사용한다.
        """
        previous = [{"status": "extra", "source_file": "a.xlsx", "cell": "I5", "output_value": "10", "record_key": {"account_code": "1100"}}]
        same = [{**previous[0], "output_value": "10.0", "mapping_reasons": ["새 진단 문구"]}]
        self.assertTrue(compare_evaluation_rows(previous, same)["identical"])
        changed = [{**previous[0], "record_key": {"account_code": "1200"}}]
        result = compare_evaluation_rows(previous, changed)
        self.assertFalse(result["identical"])
        self.assertEqual((result["removed_count"], result["added_count"]), (1, 1))

    def test_total_comparison_keeps_period_group_and_value(self) -> None:
        """합계 일치 여부가 같아도 기간 블록 또는 실제 합계가 바뀌면 구별한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError - 다른 합계 레코드를 같다고 판단한 경우.
        Side Effects: 메모리만 사용한다.
        """
        row = {"scope": "company", "domain": "revenue", "period_group": "plan", "year": 2026,
               "month": 1, "scenario": "plan", "truth_total": "10", "output_total": "10", "matching": True}
        self.assertFalse(compare_evaluation_rows([row], [{**row, "period_group": "outlook"}], totals=True)["identical"])
        self.assertFalse(compare_evaluation_rows([row], [{**row, "truth_total": "20", "output_total": "20"}], totals=True)["identical"])


if __name__ == "__main__":
    unittest.main()
