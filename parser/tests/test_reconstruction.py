"""원본 상세 투영의 조직 상태·빈 슬롯·기간 분리를 메모리에서 검증한다.

실제 원본, 마스터, 정답, 생성 결과를 읽지 않고 파일·캐시를 만들지 않는다.
"""

import unittest
from copy import deepcopy
from unittest.mock import patch

from excel_parser.benchmark.reconstruction import (
    _flat,
    _source_declared_owner,
    parse_submission,
    project_source_details,
)
from excel_parser.mapping import map_workbook
from test_mapping import memory_workbook
from test_mapping_monthly import append_sheet, find_record, monthly_workbook


def projected_cell(records: list[dict], sheet: str, address: str) -> dict:
    """독립적으로 지정한 원본 좌표의 평탄화 레코드를 찾는다.

    Args:
        records: project_source_details 결과.
        sheet: 합성 입력의 시트명.
        address: 확인할 원본 주소.
    Returns:
        해당 좌표의 상세 사전.
    Raises:
        StopIteration: 기대 좌표가 누락된 경우.
    Side Effects:
        없음. 메모리 목록만 조회한다.
    """
    return next(
        record
        for record in records
        if record["sheet"] == sheet and record["cell"] == address
    )


def owner_workbook(
    declared_name: str = "합성조직",
    independent_name: str = "합성조직",
    *,
    foreign_row: bool = True,
) -> dict:
    """상단 제출 조직과 행 조직을 독립적으로 지정한 메모리 원본을 만든다.

    Args:
        declared_name: 재무표 상단 제목에 명시할 조직명.
        independent_name: 제목과 별도로 기재할 조직명.
        foreign_row: 비용 행을 다른 조직에 소속시킬지 여부.
    Returns:
        원본 조직표·상단 조직 선언·상세 행을 가진 합성 workbook.
    Raises:
        ValueError: 합성 셀 주소가 잘못된 경우.
    Side Effects:
        메모리만 사용한다. 원본·마스터·정답·결과 파일을 읽지 않는다.
    """
    raw = monthly_workbook()
    registry = raw["sheets"][-1]
    cells = memory_workbook(
        [
            ("A3", "ORG-2", "string"),
            ("B3", "다른조직", "string"),
        ]
    )["sheets"][0]["cells"]
    for cell in cells:
        cell.update(
            sheet=registry["name"],
            sheet_id=registry["sheet_id"],
            record_id=f"memory/{registry['sheet_id']}/{cell['address']}",
        )
    registry["cells"].extend(cells)
    statement = raw["sheets"][2]
    declarations = memory_workbook(
        [
            ("A1", "▣ " + declared_name, "string"),
            ("A2", independent_name, "string"),
        ]
    )["sheets"][0]["cells"]
    for cell in declarations:
        cell.update(
            sheet=statement["name"],
            sheet_id=statement["sheet_id"],
            record_id=f"memory/{statement['sheet_id']}/{cell['address']}",
        )
    statement["cells"].extend(declarations)
    if foreign_row:
        next(cell for cell in raw["sheets"][1]["cells"] if cell["address"] == "D5")[
            "value"
        ] = "다른조직"
    return raw


class ReconstructionProjectionTests(unittest.TestCase):
    """원본만 사용하는 투영 경로의 보존 및 미확정 경계를 확인한다."""

    def test_conflicting_organization_prevents_semantic_completion(self) -> None:
        """금액이 확정되어도 조직 충돌이 있으면 의미 완료로 표시하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 조직 충돌을 완전한 상세 의미로 인정한 경우.
        Side Effects:
            메모리 레코드 사본만 수정한다.
        """
        record = deepcopy(find_record(map_workbook(monthly_workbook()), "검증", "G5"))
        complete = _flat(record, "synthetic", "2031-09")
        self.assertTrue(complete["confirmed"])
        self.assertTrue(complete["semantic_complete"])
        record["organization"]["status"] = "conflict"
        record["mapping"]["status"] = "mapped"
        conflicted = _flat(record, "synthetic", "2031-09")
        self.assertEqual(conflicted["organization_code"], "ORG-1")
        self.assertEqual(conflicted["organization_status"], "conflict")
        self.assertTrue(conflicted["confirmed"])
        self.assertFalse(conflicted["semantic_complete"])

    def test_all_amounts_blank_retains_source_identified_monthly_row(self) -> None:
        """전체 금액이 비어도 원본 항목과 ID가 있는 행의 월 슬롯을 남긴다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 빈 행이 누락되거나 금액·원본 셀이 임의 생성된 경우.
        Side Effects:
            합성 workbook의 메모리만 변경하며 외부 파일을 읽지 않는다.
        """
        raw = monthly_workbook()
        sheet = raw["sheets"][0]
        sheet["cells"] = [
            cell for cell in sheet["cells"] if cell["address"] not in {"F5", "H5"}
        ]
        stored_blank = next(cell for cell in sheet["cells"] if cell["address"] == "G5")
        stored_blank.update(value=None, data_type="blank", has_content=False)
        mapped = map_workbook(raw)
        self.assertFalse(
            any(record["source"]["sheet"] == "검증" for record in mapped["records"])
        )
        before_raw, before_mapped = deepcopy(raw), deepcopy(mapped)
        projected = project_source_details(mapped, raw, "synthetic", "2031-09")
        revenue = [record for record in projected if record["sheet"] == "검증"]
        self.assertEqual({record["cell"] for record in revenue}, {"G5", "H5"})
        for record in revenue:
            self.assertEqual(record["detail_id"], "independent-contract")
            self.assertEqual(record["account_code"], "3100")
            self.assertEqual(record["role_kind"], "detail")
            self.assertEqual(record["value_status"], "missing")
            self.assertIsNone(record["normalized_value"])
            self.assertFalse(record["confirmed"])
        stored = projected_cell(projected, "검증", "G5")
        absent = projected_cell(projected, "검증", "H5")
        self.assertEqual(stored["input_presence"], "stored_blank")
        self.assertEqual(stored["raw_record_id"], stored_blank["record_id"])
        self.assertEqual(absent["input_presence"], "absent_xml_cell")
        self.assertIsNone(absent["raw_record_id"])
        self.assertEqual(raw, before_raw)
        self.assertEqual(mapped, before_mapped)

    def test_zero_blank_and_missing_formula_cache_remain_distinct(self) -> None:
        """실제 0·저장 빈셀·캐시 없는 수식을 서로 다른 상태로 보존한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 빈값이나 누락 캐시가 0으로 바뀌거나 출처가 달라진 경우.
        Side Effects:
            합성 메모리만 사용한다.
        """
        raw = monthly_workbook()
        blank = next(
            cell for cell in raw["sheets"][0]["cells"] if cell["address"] == "H5"
        )
        blank.update(value=None, data_type="blank", has_content=False)
        formula = next(
            cell for cell in raw["sheets"][1]["cells"] if cell["address"] == "I5"
        )
        formula.update(
            value=None,
            data_type="number",
            formula={"text": "=J5+1", "cached_status": "missing"},
        )
        projected = project_source_details(
            map_workbook(raw), raw, "synthetic", "2031-09"
        )
        zero = projected_cell(projected, "검증", "G5")
        missing = projected_cell(projected, "검증", "H5")
        uncached = projected_cell(projected, "비용", "I5")
        self.assertEqual(zero["value_status"], "confirmed")
        self.assertEqual(zero["raw_value"], "0")
        self.assertEqual(zero["normalized_value"], "0")
        self.assertTrue(zero["confirmed"])
        self.assertEqual(missing["value_status"], "missing")
        self.assertIsNone(missing["normalized_value"])
        self.assertIsNone(missing["formula"])
        self.assertEqual(uncached["value_status"], "unresolved")
        self.assertIsNone(uncached["normalized_value"])
        self.assertEqual(uncached["formula"], formula["formula"])
        self.assertEqual(uncached["raw_record_id"], formula["record_id"])
        self.assertFalse(uncached["confirmed"])

    def test_file_path_and_closing_month_do_not_fill_source_semantics(self) -> None:
        """파일명 조직코드와 제출월을 원본 조직·대상 연월로 사용하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 경로 또는 제출 기준월로 누락 의미를 추측한 경우.
        Side Effects:
            메모리의 경로 문자열만 변경한다. 해당 경로에 접근하지 않는다.
        """
        raw = monthly_workbook([])
        raw["file"]["path"] = "memory/99_F999_가짜조직_2099_12월마감.xlsx"
        projected = project_source_details(
            map_workbook(raw), raw, "synthetic", "2042-09"
        )
        record = projected_cell(projected, "검증", "G5")
        self.assertIsNone(record["organization_code"])
        self.assertEqual(record["organization_name"], "합성조직")
        self.assertEqual(record["source_file"], raw["file"]["path"])
        self.assertEqual(record["closing_month"], "2042-09")
        self.assertEqual(record["year"], 2031)
        self.assertEqual(record["month"], 1)
        self.assertEqual(record["scenario"], "actual")
        self.assertFalse(record["semantic_complete"])
        source_record = deepcopy(find_record(map_workbook(raw), "검증", "G5"))
        source_record["period"].update(year=None, month=None)
        unknown = _flat(source_record, "synthetic", "2042-09")
        self.assertIsNone(unknown["year"])
        self.assertIsNone(unknown["month"])
        self.assertEqual(unknown["period_group"], "unknown")

    def test_unprojected_source_number_is_not_silently_promoted(self) -> None:
        """생산 파서에서 빠진 실제 숫자도 투영 단계가 임의 확정하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 누락된 생산 레코드가 정상 확정 금액으로 대체된 경우.
        Side Effects:
            메모리의 파서 결과 사본만 변경한다.
        """
        raw = monthly_workbook()
        mapped = map_workbook(raw)
        mapped["records"] = [
            record
            for record in mapped["records"]
            if not (
                record["source"]["sheet"] == "검증" and record["source"]["cell"] == "G5"
            )
        ]
        record = projected_cell(
            project_source_details(mapped, raw, "synthetic", "2031-09"), "검증", "G5"
        )
        self.assertEqual(record["raw_value"], "0")
        self.assertEqual(record["value_status"], "unresolved")
        self.assertIsNone(record["normalized_value"])
        self.assertFalse(record["confirmed"])
        self.assertEqual(record["mapping_status"], "partial")
        self.assertEqual(record["input_presence"], "stored_nonblank")
        source = next(
            cell for cell in raw["sheets"][0]["cells"] if cell["address"] == "G5"
        )
        source.update(
            value=None,
            formula={"text": "=H5+1", "cached_status": "missing"},
        )
        uncached = projected_cell(
            project_source_details(mapped, raw, "synthetic", "2031-09"), "검증", "G5"
        )
        self.assertEqual(uncached["input_presence"], "stored_nonblank")
        self.assertEqual(uncached["value_status"], "unresolved")
        self.assertEqual(uncached["formula"], source["formula"])
        self.assertIsNone(uncached["normalized_value"])

    def test_error_placeholders_do_not_create_missing_monthly_details(self) -> None:
        """항목·코드·ID 없이 오류 표시만 있는 양식 행의 빈 월을 만들지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 오류 자리표시가 상세 행이 되거나 원본 후보가 제거된 경우.
        Side Effects:
            합성 메모리만 사용한다. 원본·마스터·결과 파일을 읽지 않는다.
        """
        for data_type in ("error", "string"):
            with self.subTest(data_type=data_type):
                raw = monthly_workbook()
                expense = raw["sheets"][1]
                placeholders = memory_workbook(
                    [
                        ("A100", "#N/A", data_type),
                        ("B100", "#N/A", data_type),
                        ("C100", "#N/A", data_type),
                        ("D100", "#N/A", data_type),
                        ("F100", "#N/A", data_type),
                        ("H100", "0", "number"),
                    ]
                )["sheets"][0]["cells"]
                for cell in placeholders:
                    cell.update(
                        sheet=expense["name"],
                        sheet_id=expense["sheet_id"],
                        record_id=f"memory/{expense['sheet_id']}/{cell['address']}",
                    )
                    if data_type == "error" and cell["data_type"] == "error":
                        cell["formula"] = {"text": "=NA()", "cached_status": "error"}
                expense["cells"].extend(placeholders)
                before = deepcopy(raw)
                mapped = map_workbook(raw)
                self.assertTrue(
                    any(
                        record["source"]["cell"] == "H100"
                        for record in mapped["records"]
                    )
                )
                projected = project_source_details(mapped, raw, "synthetic", "2031-09")
                self.assertFalse(
                    any(
                        record["sheet"] == "비용" and record["source_row"] == 100
                        for record in projected
                    )
                )
                self.assertEqual(raw, before)

    def test_real_item_label_retains_blank_row_without_code_or_detail_id(self) -> None:
        """계정코드와 ID가 없어도 실제 항목명 근거가 있는 빈 상세 행은 남긴다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 의미 있는 항목 행을 오류 자리표시와 함께 제외한 경우.
        Side Effects:
            합성 메모리만 사용한다.
        """
        raw = monthly_workbook()
        expense = raw["sheets"][1]
        expense["cells"] = [
            cell
            for cell in expense["cells"]
            if cell["address"] not in {"E5", "G5", "H5", "I5", "J5"}
        ]
        label = next(cell for cell in expense["cells"] if cell["address"] == "F5")
        label["formula"] = {
            "text": '=CONCAT("사용", "료")',
            "cached_status": "stored_unverified",
        }
        projected = project_source_details(
            map_workbook(raw), raw, "synthetic", "2031-09"
        )
        retained = [record for record in projected if record["sheet"] == "비용"]
        self.assertEqual({record["cell"] for record in retained}, {"I5", "J5"})
        for record in retained:
            self.assertEqual(record["account_name"], "사용료")
            self.assertIsNone(record["account_code"])
            self.assertIsNone(record["detail_id"])
            self.assertEqual(record["value_status"], "missing")
            self.assertFalse(record["semantic_complete"])


class SubmissionOwnerTests(unittest.TestCase):
    """상단 제출 소유조직과 상세 행 소속을 분리하는 보완 경로를 확인한다."""

    def test_source_owner_resolves_multiple_row_organizations_without_rewriting(
        self,
    ) -> None:
        """명시 상단 근거로 제출 소유자만 정하고 다른 소속의 행을 보존한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 소유자 근거가 누락되거나 행 조직·값이 변경된 경우.
        Side Effects:
            어댑터 파일 읽기를 메모리 대역으로 대체한다.
        """
        raw = owner_workbook()
        mapped = map_workbook(raw)
        before_raw, before_mapped = deepcopy(raw), deepcopy(mapped)
        original = project_source_details(mapped, raw, "synthetic", "2031-09")
        adapter = {
            "workbook": raw,
            "mapped": mapped,
            "source_integrity": {"sha256_before": "memory-source", "unchanged": True},
        }
        with patch(
            "excel_parser.benchmark.reconstruction.run_rule_adapter",
            return_value=adapter,
        ):
            parsed = parse_submission(
                ("memory/filename-owner-is-not-used.xlsx", "synthetic", "2031-09")
            )
        submission = parsed["submission"]
        self.assertEqual(submission["organization_code"], "ORG-1")
        self.assertEqual(
            submission["organization_identity_source"], "source_declared_owner"
        )
        self.assertEqual(submission["source_declared_owner"]["status"], "resolved")
        self.assertEqual(submission["records"], original)
        self.assertEqual(
            {record["organization_code"] for record in submission["records"]},
            {"ORG-1", "ORG-2"},
        )
        self.assertEqual(
            projected_cell(submission["records"], "비용", "I5")["organization_code"],
            "ORG-2",
        )
        evidence = {
            (ref["sheet"], ref["cell"])
            for ref in submission["source_declared_owner"]["provenance"]
        }
        self.assertTrue(
            {
                ("월별 근거", "A1"),
                ("월별 근거", "A2"),
                ("조직 대응", "A2"),
                ("조직 대응", "B2"),
            }
            <= evidence
        )
        self.assertEqual(
            parsed["trial"]["submission_owner"]["source_declared_owner"],
            submission["source_declared_owner"],
        )
        self.assertEqual(raw, before_raw)
        self.assertEqual(mapped, before_mapped)

    def test_mismatched_or_conflicting_owner_declarations_remain_unresolved(
        self,
    ) -> None:
        """제목과 독립 이름이 다르거나 재무표끼리 충돌하면 소유자를 보충하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 불명확하거나 충돌한 조직 선언을 임의 확정한 경우.
        Side Effects:
            메모리만 사용하고 파일 어댑터를 대역으로 교체한다.
        """
        mismatch = owner_workbook(independent_name="다른조직")
        conflicting = owner_workbook()
        append_sheet(
            conflicting,
            "상충 소유자",
            [
                ("A1", "▣ 다른조직", "string"),
                ("A2", "다른조직", "string"),
                ("B2", "2031년", "string"),
                ("B3", "실적", "string"),
                ("B4", "1Q", "string"),
                ("B5", "1월", "string"),
            ],
        )
        for raw in (mismatch, conflicting):
            with self.subTest(declarations=_source_declared_owner(raw)):
                adapter = {
                    "workbook": raw,
                    "mapped": map_workbook(raw),
                    "source_integrity": {"sha256_before": "memory-source"},
                }
                with patch(
                    "excel_parser.benchmark.reconstruction.run_rule_adapter",
                    return_value=adapter,
                ):
                    parsed = parse_submission(
                        ("memory/ORG-1.xlsx", "synthetic", "2031-09")
                    )
                self.assertIsNone(parsed["submission"]["organization_code"])
                self.assertNotEqual(
                    parsed["submission"]["source_declared_owner"]["status"], "resolved"
                )
                self.assertEqual(
                    {
                        record["organization_code"]
                        for record in parsed["submission"]["records"]
                    },
                    {"ORG-1", "ORG-2"},
                )

    def test_unique_row_owner_keeps_existing_path_without_title_fallback(self) -> None:
        """행 조직이 유일하면 기존 소유자 결정을 유지하고 상단 보완을 호출하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 유일 행 조직을 다른 상단 선언으로 덮어쓴 경우.
        Side Effects:
            메모리 대역만 사용한다.
        """
        raw = owner_workbook("다른조직", "다른조직", foreign_row=False)
        adapter = {
            "workbook": raw,
            "mapped": map_workbook(raw),
            "source_integrity": {"sha256_before": "memory-source"},
        }
        with patch(
            "excel_parser.benchmark.reconstruction.run_rule_adapter",
            return_value=adapter,
        ):
            with patch(
                "excel_parser.benchmark.reconstruction._source_declared_owner",
                side_effect=AssertionError("유일 행 조직에서 보완 호출 금지"),
            ):
                parsed = parse_submission(("memory/ORG-2.xlsx", "synthetic", "2031-09"))
        self.assertEqual(parsed["submission"]["organization_code"], "ORG-1")
        self.assertEqual(
            parsed["submission"]["organization_identity_source"],
            "unique_row_organization",
        )
        self.assertEqual(
            parsed["submission"]["source_declared_owner"]["status"], "not_attempted"
        )

    def test_owner_name_requires_unique_source_registry_code(self) -> None:
        """동일 조직명이 둘 이상의 원본 코드에 대응하면 상단 이름만으로 확정하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 중복 이름을 하나의 조직 코드로 임의 연결한 경우.
        Side Effects:
            합성 메모리만 사용한다.
        """
        raw = owner_workbook()
        registry = raw["sheets"][-1]
        next(cell for cell in registry["cells"] if cell["address"] == "B3")["value"] = (
            "합성조직"
        )
        declared = _source_declared_owner(raw)
        self.assertIsNone(declared["code"])
        self.assertNotEqual(declared["status"], "resolved")


if __name__ == "__main__":
    unittest.main()
