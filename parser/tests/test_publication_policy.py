"""원본 행 발행 정책의 범위·감사 보존·v3 기본 호환을 메모리로 검증한다."""

import copy
import unittest
from pathlib import Path
from unittest.mock import patch

from excel_parser.master import assemble_master
from excel_parser.publication_policy import POLICY_V4, partition_publication_records


def source_row(domain: str = "revenue", *, row_number: int = 5, close: int = 3) -> list[dict]:
    """원본의 네 연간 그룹과 선택적 다음해 추정을 갖춘 합성 물리행을 만든다.

    Args:
        domain: revenue 또는 expense.
        row_number: 같은 파일·시트의 물리행 번호.
        close: actual과 estimate 경계의 기준월.
    Returns:
        금액 0인 48개월 슬롯과 선택적 빈칸 12개.
    Raises:
        없음.
    Side Effects:
        없음. 실제 원본·마스터·임시 파일을 읽거나 만들지 않는다.
    """
    records = []
    for index, (group, year, default_scenario) in enumerate((
            ("prior", 2025, "actual"), ("plan", 2026, "plan"), ("outlook", 2026, "actual"),
            ("next_plan", 2027, "plan"), ("next_projection", 2027, "estimate"))):
        for month in range(1, 13):
            blank = group == "next_projection"
            number, column = index * 12 + month + 8, ""
            while number:
                number, remainder = divmod(number - 1, 26)
                column = chr(65 + remainder) + column
            records.append({
                "domain": domain, "organization_code": "F1", "organization_name": "source organization", "organization_status": "resolved",
                "organization_provenance": [{"sheet": domain, "cell": f"A{row_number}"}],
                "account_code": "40010" if domain == "revenue" else "60010", "detail_id": f"row-{row_number}",
                "revision": 1, "year": year, "month": month, "period_group": group,
                "scenario": "estimate" if group == "outlook" and month > close else default_scenario,
                "aggregation": "month", "role_kind": "detail", "raw_value": None if blank else "0",
                "normalized_value": None if blank else "0", "confirmed": not blank,
                "value_status": "missing" if blank else "confirmed", "value_kind": "amount",
                "semantic_complete": True, "source_file": "synthetic.xlsx", "sheet": domain,
                "source_row": row_number, "cell": f"{column}{row_number}",
                "source_sha256": "a" * 64, "formula": None, "submission_owner": "F1",
                "business_validation": {"status": "valid", "issues": []},
            })
    return records


def source_submission(records: list[dict]) -> dict:
    """원본 선언 조직 근거를 가진 합성 제출 메타데이터를 만든다.

    Args:
        records: 원문을 수정하지 않을 상세 목록.
    Returns:
        F1 소유자·원본 선언 셀·파일 해시·records 사전.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    return {"organization_code": "F1", "source_file": "synthetic.xlsx", "source_sha256": "a" * 64,
            "organization_identity_source": "source_declared_owner",
            "source_declared_owner": {"status": "resolved", "code": "F1", "name": "source owner",
                                      "provenance": [{"sheet": "statement", "cell": "A5"}]},
            "records": records}


def mark_invalid(records: list[dict], field: str = "account_code") -> None:
    """합성 원본행에 근거가 있는 업무 무효 상태를 부여한다.

    Args:
        records: 테스트 소유 합성 원본행.
        field: account_code, detail_id 또는 management_category.
    Returns:
        없음.
    Raises:
        없음.
    Side Effects:
        전달한 테스트용 사전만 변경하며 실제 파일에는 접근하지 않는다.
    """
    observed = "None" if field == "detail_id" else "999999" if field == "account_code" else "unregistered"
    refs = [{"sheet": records[0]["sheet"], "cell": "B5"}]
    value = {"status": "invalid", "observed_value": observed, "provenance": refs}
    if field != "detail_id":
        value.update(contract_digest="b" * 64, contract_ranges=[{"sheet": "registry", "column": 1}])
    for record in records:
        record["semantic_complete"] = False
        if field in {"detail_id", "account_code"}:
            record[field] = observed
        record["business_validation"] = {"status": "invalid", field: copy.deepcopy(value),
            "issues": [{"code": "BUSINESS_" + field.upper() + "_INVALID", "field": field,
                        "observed_value": observed, "provenance": refs}]}


def partition(records: list[dict], *, close: int = 3, submission: dict | None = None) -> dict:
    """합성 원본행을 실제 정책 API로 보내 파일 없는 판정을 수행한다.

    Args:
        records: 합성 선택 상세.
        close: 제출 기준월.
        submission: 소유자 근거를 변경할 선택 제출 사전 또는 None.
    Returns:
        발행·제외 상세와 감사 결과.
    Raises:
        ValueError: 정책 계약 위반.
    Side Effects:
        없음.
    """
    return partition_publication_records(records, closing_month=f"2026-{close:02}",
                                         submissions=[submission or source_submission(records)])


class PublicationPolicyTests(unittest.TestCase):
    """정답 없이 원본 계약만 적용하고 모든 제외 월 슬롯을 보존한다."""

    def test_only_inactive_revenue_is_omitted_and_estimates_are_not_activity(self) -> None:
        """과거·계획·마감 실적이 모두 0이면 미래 추정이 있어도 매출행 전체만 제외한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: estimate가 활성 판정에 포함되거나 월 슬롯이 소실된 경우.
        Side Effects:
            합성 미래 추정값만 변경한다.
        """
        records = source_row()
        future = next(r for r in records if r["period_group"] == "outlook" and r["month"] == 4)
        future.update(normalized_value="13", raw_value="13")
        result = partition(records)
        self.assertEqual(result["records"], [])
        self.assertEqual(len(result["excluded_records"]), 60)
        self.assertEqual(result["counts"]["inactive_revenue_row_count"], 1)
        self.assertEqual(result["issues"][0]["severity"], "info")
        self.assertEqual(next(r for r in result["excluded_records"] if r["cell"] == future["cell"])["normalized_value"], "13")

    def test_active_revenue_keeps_every_real_zero(self) -> None:
        """한 활성기간이 있는 매출행의 나머지 실제 0을 모두 발행한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 개별 0원을 선택적으로 지운 경우.
        Side Effects:
            합성 과거 금액 한 칸만 변경한다.
        """
        records = source_row()
        records[0].update(normalized_value="1", raw_value="1")
        result = partition(records)
        self.assertEqual(result["records"], records)
        self.assertEqual(sum(r["normalized_value"] == "0" for r in result["records"]), 47)

    def test_expense_real_zero_is_never_inactive_revenue(self) -> None:
        """비용의 30개 실제 0과 18개 비영 금액을 모두 보존한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 매출 비활성 규칙을 비용에 적용한 경우.
        Side Effects:
            합성 비용 금액 18개만 변경한다.
        """
        records = source_row("expense")
        for record in records[:18]:
            record.update(normalized_value="7", raw_value="7")
        result = partition(records)
        self.assertEqual(result["records"], records)
        self.assertEqual(sum(r["normalized_value"] == "0" for r in result["records"]), 30)

    def test_missing_january_actual_rejects_whole_row_but_preserves_all_values(self) -> None:
        """명시된 1월 실적 빈칸만 행 전체 제외하고 다른 59개 슬롯도 감사에 남긴다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 누락을 0으로 바꾸거나 일부 월만 제외한 경우.
        Side Effects:
            합성 1월 실적을 빈칸으로 만든다.
        """
        records = source_row("expense")
        january = next(r for r in records if r["period_group"] == "outlook" and r["month"] == 1)
        january.update(raw_value=None, normalized_value=None, confirmed=False, value_status="missing")
        result = partition(records)
        self.assertEqual(result["records"], [])
        self.assertEqual(result["counts"]["required_actual_blank_slots"], 1)
        self.assertEqual(len(result["excluded_records"]), 60)
        for before, after in zip(records, result["excluded_records"]):
            for key in before:
                self.assertEqual(after[key], before[key])

    def test_february_blank_and_optional_projection_do_not_use_january_rule(self) -> None:
        """다른 actual 월과 선택적 다음해 추정 빈칸을 1월 계약으로 제외하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 근거 없는 필수 월 일반화가 발생한 경우.
        Side Effects:
            합성 2월 실적만 비운다.
        """
        records = source_row("expense")
        february = next(r for r in records if r["period_group"] == "outlook" and r["month"] == 2)
        february.update(raw_value=None, normalized_value=None, confirmed=False, value_status="missing")
        result = partition(records)
        self.assertEqual(result["records"], records)
        master = assemble_master([source_submission(records)], ["F1"], "2026-03", "memory", publication_policy=POLICY_V4)
        self.assertEqual(master["status"], "partial")
        self.assertEqual(master["publication_status"], "partial")
        self.assertEqual(master["optional_blank_count"], 12)

    def test_formula_cache_missing_is_not_raw_blank_or_inactive_zero(self) -> None:
        """수식 캐시 미확정을 빈칸이나 실제 0으로 바꾸어 제외하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 수식 미확정 상태를 금액으로 추정한 경우.
        Side Effects:
            합성 1월 실적에 캐시 없는 수식을 설정한다.
        """
        records = source_row()
        january = next(r for r in records if r["period_group"] == "outlook" and r["month"] == 1)
        january.update(raw_value=None, normalized_value=None, confirmed=False, value_status="unresolved",
                       formula={"text": "=A1", "cached_status": "missing"})
        self.assertEqual(partition(records)["records"], records)

    def test_incomplete_activity_scope_is_not_omitted(self) -> None:
        """판정에 필요한 기간 슬롯이 없으면 비활성으로 단정하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 불완전 기간을 모두 0으로 추정한 경우.
        Side Effects:
            합성 과거 슬롯 한 개만 제거한다.
        """
        records = source_row()[1:]
        self.assertEqual(partition(records)["records"], records)

    def test_business_invalid_requires_source_evidence_and_preserves_amounts(self) -> None:
        """세 업무 무효 유형의 근거를 확인하고 숫자·빈칸·ID 원문을 보존한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 무효 값 정정 또는 감사 슬롯 소실이 발생한 경우.
        Side Effects:
            합성 업무 검증만 변경한다.
        """
        for field in ("account_code", "detail_id", "management_category"):
            records = source_row("expense")
            mark_invalid(records, field)
            original = copy.deepcopy(records)
            result = partition(records)
            self.assertEqual(result["records"], [])
            self.assertEqual(records, original)
            self.assertEqual(len(result["excluded_records"]), 60)
            self.assertEqual({r["publication_decision"]["decision_id"] for r in result["excluded_records"]},
                             {result["issues"][0]["publication_decision"]["decision_id"]})
            for before, after in zip(records, result["excluded_records"]):
                for key in before:
                    self.assertEqual(after[key], before[key])

    def test_unsupported_invalid_is_left_unresolved(self) -> None:
        """상태 문자열만 invalid인 경우 실제 근거 없는 발행 제외를 거부한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 근거 없는 invalid를 제거한 경우.
        Side Effects:
            합성 검증 상태만 변경한다.
        """
        for domain in ("revenue", "expense"):
            records = source_row(domain)
            for record in records:
                record.update(semantic_complete=False, business_validation={"status": "invalid", "issues": []})
            self.assertEqual(partition(records)["records"], records)

    def test_owner_scope_requires_declared_source_and_keeps_row_organization(self) -> None:
        """확정된 비용 소유자 범위만 검사하고 이관 수신 조직이나 코드 자체를 바꾸지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 조직 추정·수정 또는 선언 근거 없는 제외가 발생한 경우.
        Side Effects:
            합성 행 조직과 선언 근거만 변경한다.
        """
        records = source_row("expense")
        for record in records:
            record["organization_code"] = "F2"
        result = partition(records)
        self.assertEqual(result["records"], [])
        self.assertTrue(all(r["organization_code"] == "F2" for r in result["excluded_records"]))
        missing = source_submission(records)
        missing["source_declared_owner"]["provenance"] = []
        self.assertEqual(partition(records, submission=missing)["records"], records)
        recipient = source_submission(records)
        recipient["organization_code"] = "F2"
        recipient["source_declared_owner"]["code"] = "F2"
        self.assertEqual(partition(records, submission=recipient)["records"], records)

    def test_opt_in_preserves_legacy_and_separates_publication_from_source_quality(self) -> None:
        """기본 v3는 무효 상세를 보존하고 v4는 원본 품질 partial을 숨기지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 기본 동작 변경 또는 원본 오류를 해결한 것으로 보고한 경우.
        Side Effects:
            파일 접근을 실패 대역으로 바꾸고 복원한다.
        """
        records = source_row("expense")
        mark_invalid(records)
        inputs = [source_submission(records)]
        snapshot = copy.deepcopy(inputs)
        with patch.object(Path, "open", side_effect=AssertionError("파일 읽기 금지")):
            legacy = assemble_master(inputs, ["F1"], "2026-03", "memory")
            published = assemble_master(inputs, ["F1"], "2026-03", "memory", publication_policy=POLICY_V4)
        self.assertEqual(inputs, snapshot)
        self.assertEqual(len(legacy["records"]), 60)
        self.assertNotIn("publication_status", legacy)
        self.assertEqual(published["records"], [])
        self.assertEqual(published["status"], "partial")
        self.assertEqual(published["source_quality"]["status"], "partial")
        self.assertEqual(published["publication_status"], "complete_with_exclusions")
        self.assertTrue(published["generation_complete"])
        self.assertFalse(published["source_quality"]["original_values_corrected"])

    def test_organization_conflict_is_preserved_without_owner_substitution(self) -> None:
        """기존 조직 충돌 감사 전체를 남기고 제출 소유자로 보완하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 조직 충돌 슬롯 소실이나 코드 보완이 발생한 경우.
        Side Effects:
            합성 조직 상태만 변경한다.
        """
        records = source_row("expense")
        for record in records:
            record.update(organization_code=None, organization_status="conflict", semantic_complete=False)
        master = assemble_master([source_submission(records)], ["F1"], "2026-03", "memory", publication_policy=POLICY_V4)
        self.assertEqual(len(master["excluded_records"]), 60)
        self.assertEqual(master["source_quality"]["unresolved_identity_slot_count"], 60)
        self.assertEqual(master["source_quality"]["contract_rejected_identity_slot_count"], 60)
        self.assertEqual(master["status"], "partial")
        self.assertEqual(master["publication_status"], "complete_with_exclusions")
        self.assertTrue(all(r["organization_code"] is None for r in master["excluded_records"]))

    def test_missing_expected_organization_blocks_publication_completeness(self) -> None:
        """기대 조직이 없으면 남은 행이 유효해도 발행 완전으로 표시하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 조직 누락을 완전한 발행으로 표시한 경우.
        Side Effects:
            합성 제출 상태만 조립한다.
        """
        master = assemble_master([source_submission(source_row("expense"))], ["F1", "F2"],
                                 "2026-03", "memory", publication_policy=POLICY_V4)
        self.assertEqual(master["publication_status"], "partial")
        self.assertEqual(master["status"], "partial")

    def test_generic_identity_missing_is_not_known_source_row_rejection(self) -> None:
        """단순 ID·조직 미확정은 원본 조직 충돌 계약으로 숨기지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 일반 미확정을 계약상 제외로 바꾼 경우.
        Side Effects:
            합성 식별 필드만 변경한다.
        """
        for changes in ({"detail_id": None}, {"organization_code": None, "organization_status": "unresolved"},
                        {"organization_code": None, "organization_status": "conflict", "organization_provenance": []}):
            records = source_row("expense")
            for record in records:
                record.update(semantic_complete=False, **changes)
            master = assemble_master([source_submission(records)], ["F1"], "2026-03", "memory", publication_policy=POLICY_V4)
            self.assertEqual(master["publication_status"], "partial")
            self.assertEqual(master["source_quality"]["unresolved_publication_identity_slot_count"], 60)
            self.assertEqual(master["source_quality"]["contract_rejected_identity_slot_count"], 0)

    def test_revision_selection_precedes_policy_and_rerun_is_idempotent(self) -> None:
        """무효 최신행을 제외해도 구수정본을 복구하지 않고 입력 순서와 무관하게 재현한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 구수정본 대체·중복 누적·결과 비결정성이 발생한 경우.
        Side Effects:
            합성 개정행만 조립한다.
        """
        old, latest = source_row("expense", row_number=5), source_row("expense", row_number=8)
        for record in latest:
            record.update(detail_id="row-5", revision=2)
        mark_invalid(latest)
        first = assemble_master([source_submission(old + latest)], ["F1"], "2026-03", "memory", publication_policy=POLICY_V4)
        second = assemble_master([source_submission(list(reversed(old + latest)))], ["F1"], "2026-03", "memory", publication_policy=POLICY_V4)
        self.assertEqual(first, second)
        self.assertEqual(first["records"], [])
        self.assertEqual(sum(r["exclusion_reason"] == "superseded_detail_revision" for r in first["excluded_records"]), 60)
        self.assertEqual(sum(r["exclusion_reason"] == "publication_business_invalid" for r in first["excluded_records"]), 60)


if __name__ == "__main__":
    unittest.main()
