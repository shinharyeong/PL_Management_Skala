"""월별 상세 구조의 조직·기간·식별자 회귀를 메모리 합성 입력으로 검증한다."""

import unittest
from copy import deepcopy
from decimal import Decimal

from excel_parser.mapping import map_workbook
from excel_parser.validation import validate_provenance
from test_mapping import memory_workbook


def append_sheet(workbook: dict, name: str, rows: list[tuple]) -> None:
    """독립 합성 셀 목록을 고유 시트·출처로 추가한다.

    Args:
        workbook: 수정할 메모리 workbook.
        name: 합성 시트명.
        rows: 주소·저장값·자료형 목록.
    Returns:
        없음.
    Raises:
        ValueError: 합성 주소가 잘못된 경우.
    Side Effects:
        전달한 메모리 workbook에만 시트를 추가한다. 파일을 생성하지 않는다.
    """
    sheet = memory_workbook(rows)["sheets"][0]
    identity = str(len(workbook["sheets"]) + 1)
    sheet.update(name=name, sheet_id=identity)
    for cell in sheet["cells"]:
        cell.update(
            sheet=name,
            sheet_id=identity,
            record_id=f"memory/{identity}/{cell['address']}",
        )
    workbook["sheets"].append(sheet)


def monthly_workbook(registry_rows: list[tuple] | None = None) -> dict:
    """코드열 없는 매출·비용과 명시 월별 근거를 독립 상수로 합성한다.

    Args:
        registry_rows: 조직표 데이터. None이면 유일한 조직 대응을 사용한다.
    Returns:
        파일 접근 없이 만든 추출 계약 workbook.
    Raises:
        ValueError: 합성 주소가 잘못된 경우.
    Side Effects:
        없음. 새 메모리 객체만 반환한다.
    """
    workbook = memory_workbook(
        [
            ("A1", "단위: 천원", "string"),
            ("A3", "조직명", "string"),
            ("B3", "관리분류", "string"),
            ("C3", "중분류", "string"),
            ("D3", "소분류", "string"),
            ("E3", "거래내역 및 산출근거", "string"),
            ("F3", "'31년 실적", "string"),
            ("F4", "연간 추정", "string"),
            ("G4", "1월", "string"),
            ("H4", "2월", "string"),
            ("A5", "합성조직", "string"),
            ("B5", "매출", "string"),
            ("C5", "3100 서비스", "string"),
            ("D5", "월정액", "string"),
            ("E5", "계약 근거 [ID:independent-contract; 수정:3]", "string"),
            ("F5", "1200", "number"),
            ("G5", "0", "number"),
            ("H5", "1,200", "string"),
        ]
    )
    append_sheet(
        workbook,
        "비용",
        [
            ("A1", "단위: 천원", "string"),
            ("H3", "'31년 실적", "string"),
            ("A4", "대분류", "string"),
            ("B4", "중분류", "string"),
            ("C4", "소분류", "string"),
            ("D4", "조직명", "string"),
            ("E4", "예산Code", "string"),
            ("F4", "예산명칭", "string"),
            ("G4", "거래내역 및 산출근거", "string"),
            ("H4", "연간 추정", "string"),
            ("I4", "1월", "string"),
            ("J4", "2월", "string"),
            ("A5", "운영비", "string"),
            ("B5", "시설", "string"),
            ("C5", "임대", "string"),
            ("D5", "합성조직", "string"),
            ("E5", "77001", "number"),
            ("F5", "사용료", "string"),
            ("G5", "원본계약 [ID:independent-expense; 수정:2]", "string"),
            ("H5", "-1200", "number"),
            ("I5", "(1,200)", "string"),
            ("J5", "0", "number"),
        ],
    )
    append_sheet(
        workbook,
        "월별 근거",
        [
            ("B2", "2031년", "string"),
            ("C2", "2031년", "string"),
            ("D2", "2031년", "string"),
            ("B3", "실적", "string"),
            ("C3", "실적", "string"),
            ("D3", "추정", "string"),
            ("B4", "연간", "string"),
            ("C4", "1Q", "string"),
            ("D4", "1Q", "string"),
            ("B5", "연간", "string"),
            ("C5", "1월", "string"),
            ("D5", "2월", "string"),
        ],
    )
    if registry_rows is None:
        registry_rows = [("A2", "ORG-1", "string"), ("B2", "합성조직", "string")]
    append_sheet(
        workbook,
        "조직 대응",
        [("A1", "사업부코드", "string"), ("B1", "사업부명", "string"), *registry_rows],
    )
    for sheet in workbook["sheets"]:
        for cell in sheet["cells"]:
            if cell["data_type"] == "string" and cell["value"] in {"1,200", "(1,200)"}:
                cell["number_format"] = "#,##0 ;[Red](#,##0)"
    return workbook


def find_record(mapped: dict, sheet: str, cell: str) -> dict:
    """독립 기대 주소에 해당하는 표준 레코드를 찾는다.

    Args:
        mapped: map_workbook 결과.
        sheet: 합성 시트명.
        cell: 기대 원본 주소.
    Returns:
        해당 표준 레코드.
    Raises:
        StopIteration: 기대 레코드가 없는 경우.
    Side Effects:
        없음.
    """
    return next(
        record
        for record in mapped["records"]
        if record["source"]["sheet"] == sheet and record["source"]["cell"] == cell
    )


class MonthlyMappingTests(unittest.TestCase):
    """정답파일 없이 조직 근거와 월별 의미 보완의 경계를 검증한다."""

    def test_name_only_details_resolve_unique_same_workbook_code(self) -> None:
        """코드열 없는 두 상세표에서 유일한 원본 대응과 식별자를 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 조직·계정·상세 ID·출처 또는 입력 보존 실패.
        Side Effects:
            메모리 객체만 사용한다.
        """
        raw = monthly_workbook()
        before = deepcopy(raw)
        mapped = map_workbook(raw)
        revenue = find_record(mapped, "검증", "G5")
        expense = find_record(mapped, "비용", "I5")
        for record in (revenue, expense):
            self.assertEqual(record["organization"]["code"], "ORG-1")
            self.assertEqual(record["organization"]["status"], "resolved")
            self.assertEqual(record["mapping"]["status"], "mapped")
            self.assertTrue(
                any(
                    ref.get("sheet") == "조직 대응"
                    for ref in record["organization"]["provenance"]
                )
            )
        self.assertEqual(revenue["item"]["code"], "3100")
        self.assertEqual(revenue["item"]["detail_id"], "independent-contract")
        self.assertEqual(revenue["item"]["detail_revision"], 3)
        self.assertEqual(expense["item"]["code"], "77001")
        self.assertEqual(expense["item"]["detail_id"], "independent-expense")
        self.assertTrue(validate_provenance(raw, mapped)["passed"])
        self.assertEqual(raw, before)

    def test_actual_group_uses_month_evidence_and_preserves_values(self) -> None:
        """실적 연도군의 추정월과 실제 0·문자열 금액을 구분한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 월 시나리오 또는 원본 숫자 상태가 달라진 경우.
        Side Effects:
            메모리 객체만 사용한다.
        """
        mapped = map_workbook(monthly_workbook())
        actual = find_record(mapped, "검증", "G5")
        estimate = find_record(mapped, "검증", "H5")
        self.assertEqual(actual["period"]["scenario"], "actual")
        self.assertEqual(actual["value"]["normalized_value"], Decimal(0))
        self.assertEqual(actual["value"]["status"], "confirmed")
        self.assertEqual(estimate["period"]["scenario"], "estimate")
        self.assertEqual(estimate["raw_value"], "1,200")
        self.assertEqual(estimate["value"]["normalized_value"], Decimal("1200000"))
        self.assertEqual(
            find_record(mapped, "검증", "F5")["period"]["scenario"], "mixed"
        )
        self.assertEqual(
            find_record(mapped, "비용", "I5")["value"]["normalized_value"],
            Decimal("-1200000"),
        )

    def test_duplicate_names_and_persistent_code_conflicts_are_unresolved(self) -> None:
        """중복 이름과 다시 등장한 충돌 코드의 의미를 확정하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 모호한 이름이나 충돌 코드가 자동 확정된 경우.
        Side Effects:
            메모리 객체만 사용한다.
        """
        cases = [
            [
                ("A2", "ORG-1", "string"),
                ("B2", "합성조직", "string"),
                ("A3", "ORG-2", "string"),
                ("B3", "합성조직", "string"),
            ],
            [
                ("A2", "ORG-1", "string"),
                ("B2", "합성조직", "string"),
                ("A3", "ORG-1", "string"),
                ("B3", "다른조직", "string"),
                ("A4", "ORG-1", "string"),
                ("B4", "합성조직", "string"),
            ],
        ]
        for rows in cases:
            with self.subTest(rows=rows):
                mapped = map_workbook(monthly_workbook(rows))
                record = find_record(mapped, "검증", "G5")
                self.assertIsNone(record["organization"]["code"])
                self.assertEqual(record["mapping"]["status"], "partial")
                self.assertIn(
                    "ORGANIZATION_NAME_AMBIGUOUS",
                    {x["code"] for x in mapped["diagnostics"]},
                )

    def test_metadata_requires_verified_marker_and_matching_name(self) -> None:
        """검증된 일치 조직 메타데이터만 코드 없는 행을 보완한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 미검증 메타데이터나 다른 조직 이름이 적용된 경우.
        Side Effects:
            메모리 객체만 사용한다.
        """
        raw = monthly_workbook([])
        metadata = {"organization_code": "ORG-M", "organization_name": "합성조직"}
        ignored = map_workbook(raw, metadata)
        self.assertIsNone(find_record(ignored, "검증", "G5")["organization"]["code"])
        self.assertIn(
            "ORGANIZATION_METADATA_UNVERIFIED",
            {x["code"] for x in ignored["diagnostics"]},
        )
        verified = map_workbook(raw, {**metadata, "organization_verified": True})
        self.assertEqual(
            find_record(verified, "비용", "I5")["organization"]["code"], "ORG-M"
        )
        mismatch = map_workbook(
            raw,
            {
                **metadata,
                "organization_name": "다른조직",
                "organization_verified": True,
            },
        )
        self.assertEqual(
            find_record(mismatch, "검증", "G5")["mapping"]["status"], "partial"
        )
        self.assertIn(
            "ORGANIZATION_NAME_CONFLICT", {x["code"] for x in mismatch["diagnostics"]}
        )

    def test_explicit_code_conflict_keeps_source_and_marks_partial(self) -> None:
        """명시 코드와 조직표 이름이 충돌하면 원문을 남기고 진단한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 충돌을 덮어쓰거나 mapped로 확정한 경우.
        Side Effects:
            메모리 객체만 사용한다.
        """
        raw = monthly_workbook(
            [("A2", "ORG-2", "string"), ("B2", "다른조직", "string")]
        )
        extras = memory_workbook(
            [("K3", "사업부코드", "string"), ("K5", "ORG-2", "string")]
        )
        raw["sheets"][0]["cells"].extend(extras["sheets"][0]["cells"])
        mapped = map_workbook(raw)
        record = find_record(mapped, "검증", "G5")
        self.assertEqual(record["organization"]["code"], "ORG-2")
        self.assertEqual(record["organization"]["original_name"], "합성조직")
        self.assertEqual(record["organization"]["status"], "conflict")
        self.assertEqual(record["mapping"]["status"], "partial")

    def test_missing_formula_cache_and_absent_blank_are_not_zero(self) -> None:
        """누락 캐시와 존재하지 않는 빈셀을 실제 0으로 대체하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 누락 수식 값이 확정되거나 없는 원본 셀이 생성된 경우.
        Side Effects:
            메모리 객체만 사용한다.
        """
        raw = monthly_workbook()
        source = next(c for c in raw["sheets"][0]["cells"] if c["address"] == "H5")
        source.update(
            value=None,
            data_type="number",
            formula={"text": "=G5+1", "cached_status": "missing"},
        )
        record = find_record(map_workbook(raw), "검증", "H5")
        self.assertEqual(record["value"]["status"], "unresolved")
        self.assertIsNone(record["value"]["normalized_value"])
        raw["sheets"][0]["cells"].remove(source)
        self.assertFalse(
            any(
                r["source"]["sheet"] == "검증" and r["source"]["cell"] == "H5"
                for r in map_workbook(raw)["records"]
            )
        )

    def test_missing_row_organization_keeps_identified_detail_amount(self) -> None:
        """조직셀이 비어도 항목 근거가 있는 행의 금액 역할을 유지한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 조직 누락 때문에 금액이 식별자로 바뀌거나 행이 누락된 경우.
        Side Effects:
            메모리 객체만 사용한다.
        """
        raw = monthly_workbook()
        for sheet, address in ((raw["sheets"][0], "A5"), (raw["sheets"][1], "D5")):
            for cell in sheet["cells"]:
                if cell["address"] == address:
                    cell.update(value=None, data_type="blank", has_content=False)
        record = find_record(map_workbook(raw), "검증", "H5")
        self.assertEqual(record["role"]["kind"], "detail")
        self.assertEqual(record["value"]["value_kind"], "amount")
        self.assertEqual(record["value"]["normalized_value"], Decimal("1200000"))
        self.assertEqual(record["mapping"]["status"], "partial")
        self.assertIsNone(record["organization"]["code"])

    def test_ambiguous_text_without_integer_grouping_format_stays_unresolved(
        self,
    ) -> None:
        """단위만 있고 그룹 서식이 없으면 단일 쉼표 문자열을 추측하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 구분자 근거 없는 문자열을 확정한 경우.
        Side Effects:
            메모리 객체만 사용한다.
        """
        raw = monthly_workbook()
        source = next(c for c in raw["sheets"][0]["cells"] if c["address"] == "H5")
        for number_format in (
            "General",
            "0.000",
            "#,##0,",
            "_-* #,##0.0,_-",
            "#,##0%",
            "yyyy-mm-dd",
            '#,##0"12"',
            r"#,##0\1",
            '#,##0;"12"',
            "[>=1000]#,##0;0",
        ):
            with self.subTest(number_format=number_format):
                source["number_format"] = number_format
                record = find_record(map_workbook(raw), "검증", "H5")
                expected_status = (
                    "not_numeric" if number_format == "yyyy-mm-dd" else "unresolved"
                )
                self.assertEqual(record["value"]["status"], expected_status)
                self.assertIsNone(record["value"]["normalized_value"])

    def test_accounting_grouping_preserves_source_precision(self) -> None:
        """정수·소수 회계 그룹 서식은 원문 정밀도를 유지하며 기존 정규화로 전달한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 원본 서식 근거를 누락하거나 표시 자릿수로 값을 반올림한 경우.
        Side Effects:
            메모리 객체만 사용한다.
        """
        raw = monthly_workbook()
        source = next(c for c in raw["sheets"][0]["cells"] if c["address"] == "H5")
        accounting = r'_-* #,##0.0_-;\-* #,##0.0_-;_-* "-"_-;_-@_-'
        cases = (
            ("36,083", "#,##0", "36083000"),
            ("36,083", "#,##0.00", "36083000"),
            ("36,083", accounting, "36083000"),
            ("1,234.56789", accounting, "1234567.89"),
            ("(1,234.56789)", accounting, "-1234567.89"),
        )
        for value, number_format, expected in cases:
            with self.subTest(value=value, number_format=number_format):
                source.update(value=value, number_format=number_format)
                record = find_record(map_workbook(raw), "검증", "H5")
                self.assertEqual(record["value"]["status"], "confirmed")
                self.assertEqual(record["value"]["normalized_value"], Decimal(expected))
                self.assertEqual(source["value"], value)

    def test_ambiguous_decimal_locale_is_not_inferred_from_padding(self) -> None:
        """원문 그룹 구조가 없거나 유럽식 서식이면 회계 여백만으로 로케일을 추측하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 단일 소수점·쉼표의 모호한 의미를 임의 확정한 경우.
        Side Effects:
            메모리 객체만 사용한다.
        """
        raw = monthly_workbook()
        source = next(c for c in raw["sheets"][0]["cells"] if c["address"] == "H5")
        for value, number_format in (
            ("36.083", "_-* #,##0.0_-"),
            ("36,083", "_-* #.##0,0_-"),
            ("36,083", "General"),
        ):
            with self.subTest(value=value, number_format=number_format):
                source.update(value=value, number_format=number_format)
                record = find_record(map_workbook(raw), "검증", "H5")
                self.assertEqual(record["value"]["status"], "unresolved")
                self.assertIsNone(record["value"]["normalized_value"])

    def test_conflicting_month_sources_keep_scenario_unknown(self) -> None:
        """동일 연월의 실적·추정 근거 충돌을 월과 연간에 진단한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 상충하는 시나리오를 임의 확정한 경우.
        Side Effects:
            메모리 객체만 사용한다.
        """
        raw = monthly_workbook()
        append_sheet(
            raw,
            "상충 근거",
            [
                ("B2", "2031년", "string"),
                ("B3", "추정", "string"),
                ("B4", "1Q", "string"),
                ("B5", "1월", "string"),
            ],
        )
        mapped = map_workbook(raw)
        self.assertEqual(
            find_record(mapped, "검증", "G5")["period"]["scenario"], "unknown"
        )
        self.assertEqual(
            find_record(mapped, "검증", "F5")["period"]["scenario"], "unknown"
        )
        self.assertIn(
            "PERIOD_CROSS_SHEET_SCENARIO_CONFLICT",
            {x["code"] for x in mapped["diagnostics"]},
        )


if __name__ == "__main__":
    unittest.main()
