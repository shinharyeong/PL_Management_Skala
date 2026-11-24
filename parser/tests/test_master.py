"""정답·파일 접근 없이 기술 마스터의 교체·개정·중복·금액 계약을 검증한다."""

import copy
import unittest
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch

from excel_parser.master import assemble_master, replace_organization


def detail(**changes: object) -> dict:
    """출처가 있는 원화 월별 상세의 독립 메모리 예시를 만든다.

    Args:
        changes: 기본 예시에서 바꿀 필드.
    Returns:
        금액과 의미가 확정된 새 평탄 레코드.
    Raises:
        없음.
    Side Effects:
        없음. 실제 파일이나 정답에 접근하지 않는다.
    """
    return {
        "domain": "revenue", "organization_code": "F10", "account_code": "100",
        "detail_id": "detail-1", "revision": 1, "year": 2026, "month": 1,
        "scenario": "actual", "period_group": "outlook", "aggregation": "month",
        "role_kind": "detail", "normalized_value": "10", "raw_value": "10",
        "value_status": "confirmed", "value_kind": "amount", "semantic_complete": True,
        "source_file": "org.xlsx", "sheet": "details", "cell": "I5", "formula": None,
        **changes,
    }


def submission(records: list[dict] | None = None, **changes: object) -> dict:
    """조직 원본 한 건에 해당하는 기술 제출 상태를 만든다.

    Args:
        records: 상세 목록. None이면 기본 상세 한 건을 사용한다.
        changes: 조직코드·원본해시·확인된 제출 버전 등의 변경 필드.
    Returns:
        독립 메모리 제출 사전.
    Raises:
        없음.
    Side Effects:
        없음. 원본·마스터 파일과 네트워크를 사용하지 않는다.
    """
    return {"organization_code": "F10", "source_file": "org.xlsx", "source_sha256": "hash-1",
            "records": [detail()] if records is None else records, **changes}


class MasterAssemblyTests(unittest.TestCase):
    """전체 교체와 원본 근거 선택이 손실 없는 결정적 조립인지 검사한다."""

    def test_idempotent_assembly_does_not_mutate_or_read_files(self) -> None:
        """동일 입력 재실행의 결과·해시가 같고 입력과 파일에 손대지 않게 한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 입력 변경, 결과 차이 또는 파일 접근이 발생한 경우.
        Side Effects:
            파일 경계를 잠시 실패 Mock으로 대체하며 메모리만 사용한다.
        """
        inputs = [submission()]
        before = copy.deepcopy(inputs)
        with patch.object(Path, "open", side_effect=AssertionError("파일 접근 금지")):
            first = assemble_master(inputs, ["inventory-only"], "2026-01", "sample")
            second = assemble_master(inputs, ["inventory-only"], "2026-01", "sample")
        self.assertEqual(first, second)
        self.assertEqual(inputs, before)
        self.assertEqual(first["status"], "complete")
        self.assertEqual(first["records"][0]["organization_code"], "F10")
        self.assertTrue(first["generation_complete"])
        self.assertFalse(first["business_master_issued"])

    def test_replace_updates_only_one_organization_without_append(self) -> None:
        """선택 조직만 전체 교체하고 재교체해도 상세가 누적되지 않게 한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 다른 조직 변경 또는 중복 누적이 발생한 경우.
        Side Effects:
            메모리 목록만 새로 만든다.
        """
        other = submission([detail(organization_code="F20", source_file="other.xlsx")],
                           organization_code="F20", source_file="other.xlsx", source_sha256="hash-other")
        before = [submission(), other]
        replacement = submission([detail(normalized_value="20", raw_value="20")], source_sha256="hash-new")
        updated = replace_organization(before, replacement)
        repeated = replace_organization(updated, replacement)
        self.assertEqual(updated, repeated)
        self.assertEqual(len(updated), 2)
        self.assertEqual(next(s for s in updated if s["organization_code"] == "F20"), other)
        assembled = assemble_master(updated, ["F10", "F20"], "2026-01", "sample")
        amounts = {r["organization_code"]: r["normalized_value"] for r in assembled["records"]}
        self.assertEqual(amounts, {"F10": "20", "F20": "10"})
        self.assertEqual(before[0]["source_sha256"], "hash-1")

    def test_highest_verified_submission_replaces_older_source(self) -> None:
        """검증된 제출 버전 중 유일한 최신본만 조직 상세로 채택한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 구제출이 누적되거나 미확인 버전을 추정한 경우.
        Side Effects:
            메모리에서 선택과 조립만 수행한다.
        """
        old = submission(submission_version="2026-01-01", version_verified=True)
        new = submission([detail(normalized_value="20")], source_sha256="new",
                         submission_version="2026-01-02", version_verified=True)
        assembled = assemble_master([old, new], ["F10"], "2026-01", "sample")
        self.assertEqual(assembled["detail_count"], 1)
        self.assertEqual(assembled["records"][0]["normalized_value"], "20")
        self.assertEqual(assembled["excluded_submissions"][0]["reason"], "superseded_submission")
        unverified = copy.deepcopy(new)
        unverified["version_verified"] = False
        unresolved = assemble_master([old, unverified], ["F10"], "2026-01", "sample")
        self.assertEqual(unresolved["status"], "partial")
        self.assertEqual(unresolved["records"], [])

    def test_submission_version_tie_is_unresolved(self) -> None:
        """같은 최고 제출 버전의 서로 다른 내용은 선택하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 동률 제출 중 임의 항목을 선택한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        first = submission(submission_version="V2.1", version_verified=True)
        second = submission([detail(normalized_value="30")], source_sha256="different",
                            submission_version="V2.1", version_verified=True)
        result = assemble_master([first, second], ["F10"], "2026-01", "sample")
        self.assertEqual(result["selected_organization_count"], 0)
        self.assertEqual(len(result["excluded_submissions"]), 2)
        self.assertIn("submission_selection_unresolved", {issue["code"] for issue in result["issues"]})

    def test_latest_identical_whole_row_delivery_preserves_all_sources(self) -> None:
        """최고 수정행의 모든 월이 같을 때만 재전송을 한 번 반영한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 구개정 누적, 출처 손실 또는 정확 중복의 이중 합산.
        Side Effects:
            메모리 상세를 조립한다.
        """
        rows = []
        for row, revision, amount in ((5, 2, "20"), (8, 2, "20"), (9, 1, "10")):
            rows.extend([detail(cell=f"I{row}", revision=revision, normalized_value=amount),
                         detail(cell=f"J{row}", revision=revision, normalized_value=amount, month=2)])
        result = assemble_master([submission(rows)], ["F10"], "2026-01", "sample")
        self.assertEqual(result["status"], "complete")
        self.assertEqual(result["detail_count"], 2)
        self.assertEqual(len(result["excluded_records"]), 2)
        for record in result["records"]:
            self.assertEqual(record["selection_reason"], "exact_duplicate_delivery")
            self.assertEqual(record["duplicate_delivery_count"], 1)
            self.assertEqual(len(record["source_occurrences"]), 2)
            self.assertEqual(record["normalized_value"], "20")

    def test_one_month_conflict_blocks_the_entire_tied_detail_row(self) -> None:
        """동률 수정행의 다른 월 하나라도 다르면 전체 행 선택을 미확정으로 둔다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 일부 월의 우연한 일치로 충돌행을 채택한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        rows = [detail(cell="I5", revision=2), detail(cell="J5", revision=2, month=2),
                detail(cell="I8", revision=2), detail(cell="J8", revision=2, month=2, normalized_value="99")]
        result = assemble_master([submission(rows)], ["F10"], "2026-01", "sample")
        self.assertEqual(result["records"], [])
        self.assertEqual(len(result["excluded_records"]), 4)
        self.assertEqual(result["totals"], [])
        self.assertEqual(result["status"], "partial")

    def test_blank_zero_and_formula_cache_states_remain_distinct(self) -> None:
        """실제 0, 빈칸, 저장 수식값, 캐시 없는 수식을 구별하여 합산한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 빈칸·미계산 수식을 0이나 확정 금액으로 채운 경우.
        Side Effects:
            메모리만 사용하고 수식을 재계산하지 않는다.
        """
        rows = [detail(detail_id="zero", normalized_value="0", raw_value="0"),
                detail(detail_id="blank", cell="I6", normalized_value=None, raw_value=None, value_status="missing"),
                detail(detail_id="cached", cell="I7", normalized_value="5", raw_value=None,
                       formula={"cached_status": "stored_unverified"}),
                detail(detail_id="no-cache", cell="I8", normalized_value=None, raw_value=None,
                       value_status="unresolved", formula={"cached_status": "missing"})]
        result = assemble_master([submission(rows)], ["F10"], "2026-01", "sample")
        self.assertEqual(result["detail_count"], 4)
        self.assertEqual(result["confirmed_detail_count"], 2)
        self.assertEqual(result["status"], "partial")
        company = [row for row in result["totals"] if row["scope"] == "company"]
        self.assertEqual(company[0]["normalized_value"], "5")
        self.assertTrue({"blank_amount", "formula_cache_unresolved"} <= {issue["code"] for issue in result["issues"]})

    def test_entire_empty_optional_projection_is_preserved_without_error(self) -> None:
        """전체가 비어 있는 선택 추정 영역만 선택 빈칸으로 보존한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 선택 빈칸이 사라지거나 일부 값이 있는 영역을 선택으로 본 경우.
        Side Effects:
            메모리만 사용한다.
        """
        blank = detail(detail_id="optional", cell="I6", period_group="next_projection",
                       normalized_value=None, raw_value=None, value_status="missing")
        result = assemble_master([submission([detail(), blank])], ["F10"], "2026-01", "sample")
        self.assertEqual(result["status"], "complete")
        self.assertEqual(result["optional_blank_count"], 1)
        filled = detail(detail_id="filled", cell="I7", period_group="next_projection")
        partial = assemble_master([submission([detail(), blank, filled])], ["F10"], "2026-01", "sample")
        self.assertEqual(partial["status"], "partial")
        self.assertEqual(partial["optional_blank_count"], 0)

    def test_decimal_totals_keep_small_value_after_large_cancellation(self) -> None:
        """큰 양수·음수를 상쇄한 작은 금액도 Decimal 기본 정밀도와 무관하게 보존한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 합계 정밀도 손실이 발생한 경우.
        Side Effects:
            메모리만 사용하며 전역 Decimal context를 바꾸지 않는다.
        """
        amounts = ("10000000000000000000000000000000000000000", "0.001",
                   "-10000000000000000000000000000000000000000")
        rows = [detail(detail_id=f"d{i}", cell=f"I{i+5}", normalized_value=amount, raw_value=amount)
                for i, amount in enumerate(amounts)]
        result = assemble_master([submission(rows)], ["F10"], "2026-01", "sample")
        company = next(row for row in result["totals"] if row["scope"] == "company")
        self.assertEqual(Decimal(company["normalized_value"]), Decimal("0.001"))

    def test_optional_blank_never_hides_unresolved_semantics(self) -> None:
        """선택 추정 영역의 빈칸도 조직·계정 의미가 미확정이면 문제를 유지한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 금액이 비었다는 이유로 의미 오류를 숨긴 경우.
        Side Effects:
            메모리만 사용한다.
        """
        blank = detail(period_group="next_projection", account_code=None,
                       semantic_complete=False, normalized_value=None,
                       raw_value=None, value_status="missing")
        result = assemble_master([submission([blank])], ["F10"], "2026-01", "sample")
        self.assertEqual(result["status"], "partial")
        self.assertEqual(result["optional_blank_count"], 0)
        self.assertEqual(result["records"][0]["assembly_status"], "unresolved")
        self.assertIn("semantic_unresolved", {issue["code"] for issue in result["issues"]})

    def test_unselected_submission_order_does_not_change_content_hash(self) -> None:
        """조직 미확정 제출본의 입력 순서가 조립 해시를 바꾸지 않게 한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 같은 미선택 집합의 순서만 달라져 결과가 달라진 경우.
        Side Effects:
            메모리에서 입력 목록 순서만 바꾸어 비교한다.
        """
        known = submission()
        first = submission(organization_code=None, source_file="unknown-a.xlsx")
        second = submission(organization_code=None, source_file="unknown-b.xlsx")
        before = assemble_master([known, first, second], ["F10"], "2026-01", "sample")
        after = assemble_master([second, known, first], ["F10"], "2026-01", "sample")
        self.assertEqual(before["content_sha256"], after["content_sha256"])
        self.assertEqual(before, after)

    def test_annual_subtotal_and_empty_submissions_are_not_complete_detail(self) -> None:
        """연간·소계만 있거나 상세가 없으면 완전한 월별 마스터로 표시하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 범위 밖 값 합산 또는 빈 결과의 성공 표시.
        Side Effects:
            메모리만 사용한다.
        """
        rows = [detail(aggregation="annual"), detail(cell="I6", role_kind="subtotal")]
        result = assemble_master([submission(rows)], ["F10"], "2026-01", "sample")
        self.assertEqual(result["records"], [])
        self.assertEqual(len(result["excluded_records"]), 2)
        self.assertEqual(result["status"], "partial")

    def test_unresolved_raw_values_and_formulas_cannot_be_exact_duplicates(self) -> None:
        """정규값이 모두 미확정이어도 서로 다른 원문·수식은 동일 전달로 보지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 다른 미확정 입력을 하나의 동일 재전송으로 선택한 경우.
        Side Effects:
            메모리에서 원문·수식 차이를 확인하며 수식을 계산하지 않는다.
        """
        variants = [
            ({"raw_value": "badA"}, {"raw_value": "badB"}),
            ({"raw_value": None, "formula": {"cached_status": "missing", "text": "A1+1"}},
             {"raw_value": None, "formula": {"cached_status": "missing", "text": "A1+2"}}),
        ]
        for first, second in variants:
            rows = [detail(cell="I5", revision=2, normalized_value=None,
                           value_status="unresolved", **first),
                    detail(cell="I8", revision=2, normalized_value=None,
                           value_status="unresolved", **second)]
            result = assemble_master([submission(rows)], ["F10"], "2026-01", "sample")
            self.assertEqual(result["records"], [])
            self.assertEqual(len(result["excluded_records"]), 2)
            self.assertIn("detail_revision_conflict", {issue["code"] for issue in result["issues"]})
            self.assertNotIn("exact_duplicate_delivery", {issue["code"] for issue in result["issues"]})


if __name__ == "__main__":
    unittest.main()
