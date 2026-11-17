"""발행 비교 후처리의 실제 Excel 경계·감사 분류·보존 검증을 메모리에서 검사한다."""

import copy
import gzip
import hashlib
import io
import unittest
from pathlib import Path
from unittest.mock import patch

from excel_parser.benchmark import publication_report as report


def excluded_record(**changes: object) -> dict:
    """원본 수정 없이 구분할 제외 감사의 독립 예시를 만든다.

    Args:
        changes: 검증에 필요한 원문·키·정책 상태 변경.
    Returns:
        완전한 출처와 실제 0을 가진 합성 레코드.
    Raises:
        없음.
    Side Effects:
        없음. 실제 파일·마스터에 접근하지 않는다.
    """
    return {"domain": "revenue", "organization_code": "F1", "organization_status": "resolved",
            "account_code": "A1", "detail_id": "D1", "source_file": "source.xlsx", "sheet": "source",
            "cell": "I5", "source_row": 5, "year": 2026, "closing_month": "2026-01", "month": 1,
            "period_group": "outlook", "scenario": "actual", "raw_value": 0, "normalized_value": "0",
            "confirmed": True, "formula": None, "exclusion_reason": "publication_inactive_revenue",
            "publication_decision": {"reasons": ["inactive_revenue"], "decision_id": "memory"}, **changes}


def evaluation_fixture(keys: set[tuple[str, str]], *, actual_excel: bool) -> dict:
    """정답 파일과 별개의 완료 평가 요약 예시를 만든다.

    Args:
        keys: 유형·제출월 키.
        actual_excel: 실제 Excel 평가 출처 필드를 넣을지 여부.
    Returns:
        건수가 일치하는 독립 저장 validation 사전.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    rows = [{"type": kind, "closing_month": month, "truth_count": 1, "confirmed_output_count": 1,
             "match_count": 1, "false_positive": 0, "false_negative": 0, "amount_mismatch": 0,
             "unresolved_required": 0, "total_groups": 1, "total_matching_groups": 1,
             "all_totals_match": True, "precision": 1.0, "recall": 1.0, "f1": 1.0,
             "generation_status": "complete", "detailed_and_total_compared": True, "reference_unchanged": True,
             "strict_detail_and_total_match": True,
             **({"prediction_source": "saved_excel", "metadata_json_value_reads": 0,
                 "strict_excel_detail_and_total_match": True} if actual_excel else {})}
            for kind, month in sorted(keys)]
    return {"rows": rows, "generated_count": len(rows), "detailed_and_total_compared_count": len(rows)}


def report_fixture() -> dict:
    """81개 비교를 파일 없이 구동할 manifest·요약·감사 대역을 만든다.

    Args:
        없음.
    Returns:
        이전/현재 실행 경로, 저장 JSON 대응과 실제 합계표 실패 1건.
    Raises:
        없음.
    Side Effects:
        없음. 실제 Excel·원본·정답·임시 파일을 만들거나 읽지 않는다.
    """
    old, new = Path("/artifacts/v3/final"), Path("/artifacts/v4/final")
    keys = {(f"{kind:02}_synthetic", f"2026-{month:02}") for kind in range(1, 10) for month in range(1, 10)}
    manifests = []
    for version in ("v3", "v4"):
        batches = [{"type": kind, "closing_month": month, "directory": f"/artifacts/{version}/full/{kind}/{month}",
                    "organization_files": ["source.xlsx"], "organization_count": 1, "master": "reference.xlsx"}
                   for kind, month in sorted(keys)]
        manifests.append({"inventory_sha256": "same", "batches": batches})
    before, after = evaluation_fixture(keys, actual_excel=False), evaluation_fixture(keys, actual_excel=True)
    after["rows"][0]["strict_excel_detail_and_total_match"] = False
    saved = {str(old / "generation.json"): manifests[0], str(new / "generation.json"): manifests[1],
             str(old / "evaluation/validation.json"): before, str(new / "evaluation/validation.json"): after}
    return {"old": old, "new": new, "keys": keys, "saved": saved}


def diagnostic_lines(path: Path) -> list[dict]:
    """요청된 저장 경로에 대응하는 합성 감사·합계표 확인을 반환한다.

    Args:
        path: 파일을 열지 않고 분기만 할 합성 경로.
    Returns:
        조직 충돌·1월 필수 빈칸 또는 실제 합계표 실패 예시.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    if "05_synthetic/2026-01" in str(path) and path.name == "excluded_records.jsonl.gz":
        return [excluded_record(organization_code=None, organization_status="conflict",
                                exclusion_reason="detail_identity_unresolved", publication_decision=None),
                excluded_record(raw_value=None, normalized_value=None, confirmed=False,
                                exclusion_reason="publication_missing_required_actual",
                                publication_decision={"reasons": ["missing_required_actual"]})]
    if "01_synthetic/2026-01" in str(path) and path.name == "excel_total_checks.jsonl.gz":
        return [{"basis": "excel_details", "scope": "company", "matching": False, "fully_matching": False,
                 "expected_value": "1", "output_value": "2", "sheet": "전사월별합계", "cell": "E6"}]
    return []


class PublicationReportTests(unittest.TestCase):
    """재채점 없이 저장 결과의 근거·완료 범위·출처 차이를 검증한다."""

    def test_zero_blank_formula_and_optional_amount_are_distinct(self) -> None:
        """실제 0·선택적 빈칸·수식 미확정의 상태와 정정 요구를 섞지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 금액 상태 또는 필요한 원본이 잘못 분류된 경우.
        Side Effects:
            없음.
        """
        zero = excluded_record()
        original = copy.deepcopy(zero)
        self.assertEqual(report.audit_exclusion(zero)["amount_kind"], "zero")
        self.assertEqual(zero, original)
        optional = excluded_record(period_group="next_projection", year=2027, scenario="estimate",
                                   normalized_value=None, raw_value=None, confirmed=False,
                                   publication_decision={"reasons": ["missing_required_actual"]})
        self.assertIsNone(report.audit_exclusion(optional)["needed_source_correction"])
        formula = excluded_record(normalized_value=None, raw_value=None, confirmed=False,
                                  formula={"text": "=A1", "cached_status": "missing"})
        self.assertEqual(report.audit_exclusion(formula)["source_correction_kind"], "formula_cache")

    def test_identity_correction_names_actual_missing_fields(self) -> None:
        """상세 ID 누락을 조직 오류로 오분류하지 않고 실제 미확정 필드를 표시한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 관측된 미확정 필드와 설명이 다른 경우.
        Side Effects:
            없음.
        """
        identity = excluded_record(detail_id=None, exclusion_reason="detail_identity_unresolved", publication_decision=None)
        audit = report.audit_exclusion(identity)
        self.assertEqual(audit["source_correction_fields"], ["detail_id"])
        self.assertNotIn("조직", audit["needed_source_correction"])
        organization = excluded_record(organization_code=None, organization_status="conflict",
                                       exclusion_reason="detail_identity_unresolved", publication_decision=None)
        self.assertEqual(report.audit_exclusion(organization)["source_correction_kind"], "organization_identity")

    def test_only_current_year_january_actual_blank_requires_amount_correction(self) -> None:
        """행 전체 제외 중 당해 1월의 진짜 빈칸만 필수 금액 정정 슬롯으로 센다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 다른 기간·0·원값 미제공을 필수 빈칸으로 센 경우.
        Side Effects:
            합성 사전만 변경한다.
        """
        missing = excluded_record(normalized_value=None, raw_value=None, confirmed=False,
                                  publication_decision={"reasons": ["missing_required_actual"]})
        self.assertEqual(report.audit_exclusion(missing)["source_correction_kind"], "required_january_actual")
        for changes in ({"year": 2025}, {"month": 2}, {"scenario": "estimate"}, {"normalized_value": "0", "raw_value": 0}):
            self.assertIsNone(report.audit_exclusion({**missing, **changes})["needed_source_correction"])
        missing.pop("raw_value")
        self.assertIsNone(report.audit_exclusion(missing)["needed_source_correction"])

    def test_duplicate_missing_or_incomplete_evaluations_are_rejected(self) -> None:
        """중복 덮어쓰기·누락·미완료 평가를 완료된 81개 비교로 인정하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 잘못된 완료 행이 색인으로 통과한 경우.
        Side Effects:
            합성 목록만 변경한다.
        """
        keys = {("01", "2026-01"), ("01", "2026-02")}
        valid = evaluation_fixture(keys, actual_excel=True)
        self.assertEqual(set(report._evaluation_index(valid, keys, actual_excel=True)), keys)
        duplicate = copy.deepcopy(valid)
        duplicate["rows"][1] = copy.deepcopy(duplicate["rows"][0])
        with self.assertRaises(ValueError):
            report._evaluation_index(duplicate, keys, actual_excel=True)
        missing = copy.deepcopy(valid)
        missing["rows"].pop()
        with self.assertRaises(ValueError):
            report._evaluation_index(missing, keys, actual_excel=True)
        incomplete = copy.deepcopy(valid)
        incomplete["rows"][0]["detailed_and_total_compared"] = False
        with self.assertRaises(ValueError):
            report._evaluation_index(incomplete, keys, actual_excel=True)

    def test_actual_excel_source_and_no_json_substitution_are_required(self) -> None:
        """현재 평가는 실제 Excel 출처와 JSON 값 보충 0을 모두 요구한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 생성 JSON 평가를 실제 Excel 비교로 표시한 경우.
        Side Effects:
            합성 요약만 변경한다.
        """
        keys = {("01", "2026-01")}
        for change in ({"prediction_source": "source_json"}, {"metadata_json_value_reads": 1}, {"reference_unchanged": False}):
            data = evaluation_fixture(keys, actual_excel=True)
            data["rows"][0].update(change)
            with self.assertRaises(ValueError):
                report._evaluation_index(data, keys, actual_excel=True)

    def test_artifact_guard_rejects_excel_and_detects_changed_hash(self) -> None:
        """후처리 파일형식 경계를 지키고 같은 입력의 변경을 즉시 검출한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: Excel 읽기 또는 입력 변경이 통과한 경우.
        Side Effects:
            경로·해시 함수를 메모리 대역으로 바꾸고 복원한다.
        """
        with patch.object(report, "_checked_path", side_effect=lambda path, allowed, **kwargs: path), \
                patch.object(report, "_sha256", return_value="new") as reader:
            with self.assertRaises(ValueError):
                report._artifact(Path("/artifacts/reference.xlsx"), {})
            reader.assert_not_called()
            with self.assertRaises(ValueError):
                report._artifact(Path("/artifacts/saved.json"), {"/artifacts/saved.json": "old"})

    def test_uncompressed_hash_ignores_gzip_timestamp_only(self) -> None:
        """같은 원본 파서 바이트는 압축 시각이 달라도 같고 금액 변경은 다르다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 압축 메타데이터와 실제 내용 변경을 혼동한 경우.
        Side Effects:
            gzip 입력만 메모리 BytesIO로 바꾸고 파일을 만들지 않는다.
        """
        payload = b'{"normalized_value":"0"}\n'
        for stamp in (1, 2):
            compressed = gzip.compress(payload, mtime=stamp)
            with patch.object(report.gzip, "open", return_value=gzip.GzipFile(fileobj=io.BytesIO(compressed))):
                self.assertEqual(report._uncompressed_hash(Path("memory.gz")), hashlib.sha256(payload).hexdigest())
        self.assertNotEqual(hashlib.sha256(payload).hexdigest(), hashlib.sha256(payload.replace(b'"0"', b'"1"')).hexdigest())

    def test_actual_total_table_failure_is_reported_even_when_detail_totals_match(self) -> None:
        """상세 점수가 100%여도 실제 합계표 실패를 별도 잔차로 보존한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 실제 Excel 합계표 문제를 숨기거나 기존 점수를 바꾼 경우.
        Side Effects:
            모든 읽기·쓰기 경계를 합성 메모리로 대체하고 복원한다.
        """
        fixture = report_fixture()
        before = copy.deepcopy(fixture["saved"])
        with patch.object(report, "_checked_path", side_effect=lambda path, allowed, **kwargs: path), \
                patch.object(Path, "exists", return_value=False), \
                patch.object(report, "_read_json", side_effect=lambda path: (fixture["saved"][str(path)], "stable")), \
                patch.object(report, "_saved_index", side_effect=lambda manifest, label: {(b["type"], b["closing_month"]): b for b in manifest["batches"]}), \
                patch.object(report, "_sha256", return_value="stable"), \
                patch.object(report, "_uncompressed_hash", return_value="same-source-payload"), \
                patch.object(report, "_lines", side_effect=diagnostic_lines), \
                patch.object(Path, "read_text", return_value="[]"), \
                patch.object(Path, "open", side_effect=AssertionError("실제 파일 접근 금지")), \
                patch.object(Path, "mkdir", side_effect=AssertionError("실제 폴더 생성 금지")), \
                patch("builtins.print"):
            result = report.create_publication_report(fixture["old"], fixture["new"], Path("/artifacts/v4/report"))
        self.assertEqual(fixture["saved"], before)
        self.assertEqual(result["after_strict_complete"], 80)
        self.assertEqual(result["remaining_total_difference_groups"], 0)
        self.assertEqual(result["remaining_actual_excel_total_checks"], 1)
        self.assertEqual(result["source_correction_kind_counts"], {"organization_identity": 1, "required_january_actual": 1})
        self.assertEqual(result["source_records_unchanged_batches"], 81)
        self.assertEqual(len(result["input_sha256"]), 652)
        self.assertEqual(next(row for row in result["matrix"] if row["type"] == "ALL")["after_f1"], 1.0)
        self.assertFalse(result["written"])

    def test_source_record_change_stops_before_score_comparison(self) -> None:
        """발행 정책 이외에 원본 파서 바이트가 바뀌면 점수 비교 전에 거부한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 실제 파서 출력 변경을 동일 원본으로 보고한 경우.
        Side Effects:
            입력·해시·평가 읽기를 메모리 대역으로 바꾸고 복원한다.
        """
        fixture = report_fixture()
        with patch.object(report, "_checked_path", side_effect=lambda path, allowed, **kwargs: path), \
                patch.object(Path, "exists", return_value=False), \
                patch.object(report, "_read_json", side_effect=lambda path: (fixture["saved"][str(path)], "stable")), \
                patch.object(report, "_saved_index", side_effect=lambda manifest, label: {(b["type"], b["closing_month"]): b for b in manifest["batches"]}), \
                patch.object(report, "_sha256", return_value="stable"), \
                patch.object(report, "_uncompressed_hash", side_effect=["old-source", "changed-source"]), \
                patch.object(report, "_lines", side_effect=AssertionError("점수·감사 읽기 금지")), \
                patch.object(Path, "open", side_effect=AssertionError("파일 읽기 금지")), \
                patch.object(Path, "mkdir", side_effect=AssertionError("폴더 생성 금지")):
            with self.assertRaisesRegex(ValueError, "원본 파서 출력"):
                report.create_publication_report(fixture["old"], fixture["new"], Path("/artifacts/v4/report"))

    def test_cli_exit_uses_strict_excel_complete_count(self) -> None:
        """F1이나 JSON 상세 완료가 아니라 실제 Excel 81개 완료로 종료코드를 정한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 실제 Excel 미완료를 성공으로 표시한 경우.
        Side Effects:
            보고서 생성 함수를 메모리 대역으로 바꾸고 복원한다.
        """
        args = ["--baseline", "/old", "--current", "/new", "--output", "/report"]
        data = {"compared_batches": 81, "before_strict_complete": 9, "after_strict_complete": 80, "source_correction_slots": 0}
        with patch.object(report, "create_publication_report", return_value=data), patch("builtins.print"):
            self.assertEqual(report.main(args), 2)
            data["after_strict_complete"] = 81
            self.assertEqual(report.main(args), 0)


if __name__ == "__main__":
    unittest.main()
