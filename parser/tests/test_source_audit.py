"""원본 감사의 독립 숫자 해석과 누락을 숨기지 않는 분모를 검증한다."""

from copy import deepcopy
from decimal import Decimal
from io import BytesIO
import unittest
from zipfile import ZIP_DEFLATED, ZipFile

from excel_parser.benchmark.source_audit import (
    NativeCell, _amount, _numbers, audit_prediction, expected_rows, read_native,
)


def fixture() -> tuple[dict, dict]:
    """0·빈칸·숫자문자열·반려행을 포함한 독립 원본·manifest를 구성한다.

    Args: 없음.
    Returns: 원본 셀 색인과 평가 전용 manifest 항목.
    Raises: 없음.
    Side Effects: 메모리만 사용하며 Excel·임시 파일을 생성하지 않는다.
    """
    native = {
        "매출": {
            "A5": NativeCell("조직가", "STRING", None, False),
            "A6": NativeCell("조직가", "STRING", None, False),
            "F5": NativeCell("계약 [ID:detail-a; 수정:2]", "STRING", None, False),
            "F6": NativeCell("계약 [ID:detail-b; 수정:1]", "STRING", None, False),
            "I5": NativeCell("10", "NUMBER", None, False),
            "I6": NativeCell("20", "NUMBER", None, False),
            "J5": NativeCell("0", "NUMBER", None, False),
            "K5": NativeCell(None, "BLANK", None, False),
            "L5": NativeCell("(1,234)", "STRING", None, False),
        },
        "조직": {
            "A1": NativeCell("사업부코드", "STRING", None, False),
            "B1": NativeCell("사업부명", "STRING", None, False),
            "A2": NativeCell("ORG-A", "STRING", None, False),
            "B2": NativeCell("조직가", "STRING", None, False),
        },
    }
    entry = {"unit": "천원", "close_month": 3,
             "layout": {"revenue": {"sheet": "매출", "start": 5, "description_column": 6, "annual_columns": [8, 22, 36, 50]}},
             "expected": {"revenue": {"row_count": 2}},
             "records": {"revenue": [{"row": 5, "action": "ACCEPT"}, {"row": 6, "action": "REJECT_ROW"}]}}
    return native, entry


def prediction_fixture(native: dict, entry: dict) -> dict:
    """누락·상쇄 오류를 주입할 테스트 전용 정상 응답을 조립한다.

    Args: native: 합성 원본. entry: 합성 평가 범위.
    Returns: 불일치 위치만 변경할 수 있는 메모리 응답. 실제 파서 검증에 사용하지 않는다.
    Raises: 없음.
    Side Effects: 메모리만 사용한다.
    """
    lines, sources, previews = [], [], []
    for row in expected_rows(native, entry):
        values = []
        for slot in row["values"]:
            value = {key: slot[key] for key in ("year", "month", "scenario", "amount", "valueStatus", "inputPresence", "sourceUnit", "multiplier")}
            value.update(sourceCell=slot["cell"], sourceRecordId="synthetic:" + slot["cell"])
            values.append(value)
            cell = native[row["sheet"]].get(slot["cell"])
            sources.append({"sheet": row["sheet"], "cell": slot["cell"], "rawValue": cell.raw if cell else None, "formula": cell.formula if cell else None, "dataType": cell.kind if cell else "BLANK"})
            if cell and (cell.has_formula or cell.raw not in (None, "")):
                previews.append({"source": {"sheet": row["sheet"], "cell": slot["cell"]}})
        lines.append({"source": {"sheet": row["sheet"], "row": row["row"]},
                      "domain": row["domain"],
                      "detailId": row["detailId"], "detailRevision": row["detailRevision"],
                      "organization": {"code": "ORG-A", "name": "조직가", "status": "RESOLVED"}, "values": values})
    return {"status": "SUCCEEDED", "records": previews, "submissionData": {"lines": lines, "sourceCells": sources}}


class SourceAuditTests(unittest.TestCase):
    """생산 구현과 독립된 원본 범위·정밀도·상태 평가를 검증한다."""

    def test_decimal_text_zero_and_invalid_are_distinct(self) -> None:
        """숫자문자열 부호·정밀도와 빈값·오류·0을 구분한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 기대값 불일치.
        Side Effects: 없음.
        """
        self.assertEqual(_amount(NativeCell("(1,234.125)", "STRING", None, False), Decimal(1000)), Decimal("-1234125"))
        self.assertEqual(_amount(NativeCell("0", "NUMBER", None, False), Decimal(1000)), Decimal(0))
        self.assertIsNone(_amount(NativeCell("-", "STRING", None, False), Decimal(1000)))
        self.assertIsNone(_amount(NativeCell("#N/A", "ERROR", "=LOOKUP()", True), Decimal(1000)))
        self.assertIsNone(_amount(NativeCell("12,34", "STRING", None, False), Decimal(1000)))
        self.assertIsNone(_amount(None, Decimal(1000)))

    def test_periods_and_rejected_rows_remain_in_source_truth(self) -> None:
        """정제 정책과 무관하게 원본 반려행·0·빈칸·기간을 분모에 보존한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 원본 범위·기간 해석이 잘못된 경우.
        Side Effects: 메모리만 사용한다.
        """
        native, entry = fixture()
        rows = expected_rows(native, entry)
        self.assertEqual(len(rows), 2)
        self.assertEqual(sum(len(row["values"]) for row in rows), 96)
        slots = {slot["cell"]: slot for slot in rows[0]["values"]}
        self.assertEqual((slots["AK5"]["year"], slots["AK5"]["scenario"]), (2026, "ACTUAL"))
        self.assertEqual(slots["AN5"]["scenario"], "ESTIMATE")
        self.assertEqual(slots["AY5"]["year"], 2027)
        self.assertEqual(slots["J5"]["amount"], Decimal(0))
        self.assertEqual(slots["K5"]["inputPresence"], "STORED_BLANK")
        self.assertEqual(slots["M5"]["inputPresence"], "ABSENT_XML_CELL")

    def test_failed_parser_does_not_shrink_denominator(self) -> None:
        """실패 응답·행 누락도 원본의 전체 월 슬롯 분모에서 제외하지 않는다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 누락이 분모에서 사라지는 경우.
        Side Effects: 메모리만 사용한다.
        """
        native, entry = fixture()
        result = audit_prediction(native, entry, {"status": "FAILED", "records": []})
        self.assertEqual(result["counts"]["expectedRows"], 2)
        self.assertEqual(result["counts"]["financialSlots"], 96)
        self.assertEqual(result["counts"]["financialNumericSlots"], 4)
        self.assertEqual(result["mismatches"]["ROW_MISSING"], 2)
        self.assertEqual(result["mismatches"]["SLOT_MISSING"], 96)

    def test_offsetting_cell_errors_are_not_hidden_by_same_total(self) -> None:
        """합계가 같아도 두 원본 셀의 금액을 뒤바꾼 오류를 검출한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 상쇄되는 셀 오류가 탐지되지 않는 경우.
        Side Effects: 합성 응답만 변경한다.
        """
        native, entry = fixture()
        prediction = prediction_fixture(native, entry)
        self.assertFalse(audit_prediction(native, entry, prediction)["mismatches"])
        prediction["submissionData"]["lines"][0]["values"][0]["amount"] = Decimal(20000)
        prediction["submissionData"]["lines"][1]["values"][0]["amount"] = Decimal(10000)
        result = audit_prediction(native, entry, prediction)
        self.assertEqual(result["mismatches"]["SLOT_AMOUNT"], 2)
        self.assertEqual(result["counts"]["financialExactAmounts"], 2)

    def test_domain_change_is_detected_with_identical_amounts_and_sources(self) -> None:
        """금액·출처가 같아도 매출/비용 구분만 바뀐 행을 검출한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 도메인 변조를 놓치거나 다른 불일치로 집계하는 경우.
        Side Effects: 합성 응답의 domain만 메모리에서 변경한다.
        """
        native, entry = fixture()
        prediction = prediction_fixture(native, entry)
        self.assertFalse(audit_prediction(native, entry, prediction)["mismatches"])
        prediction["submissionData"]["lines"][0]["domain"] = "COST"
        result = audit_prediction(native, entry, prediction)
        self.assertEqual(result["mismatches"], {"ROW_DOMAIN": 1})
        self.assertEqual(result["counts"]["financialExactAmounts"], 4)

    def test_source_mutation_and_duplicate_slots_are_detected(self) -> None:
        """금액 외에도 원본 근거 변경과 중복 슬롯을 검출한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: 출처 변경·중복이 검출되지 않는 경우.
        Side Effects: 합성 응답만 변경한다.
        """
        native, entry = fixture()
        prediction = prediction_fixture(native, entry)
        line = prediction["submissionData"]["lines"][0]
        line["values"].append(deepcopy(line["values"][0]))
        prediction["submissionData"]["sourceCells"][0]["rawValue"] = "999"
        result = audit_prediction(native, entry, prediction)
        self.assertEqual(result["mismatches"]["SLOT_DUPLICATE"], 1)
        self.assertEqual(result["mismatches"]["SOURCE_RAW_VALUE"], 1)

    def test_native_reader_preserves_blank_formula_and_inline_text(self) -> None:
        """독립 XML 판독기가 실제 빈 셀·수식·문자열을 구분한다.

        Args: 없음.
        Returns: 없음.
        Raises: AssertionError: XML 판독 결과가 원문과 다른 경우.
        Side Effects: ZIP을 메모리에만 생성하며 파일은 만들지 않는다.
        """
        stream = BytesIO()
        with ZipFile(stream, "w", compression=ZIP_DEFLATED) as archive:
            archive.writestr("xl/workbook.xml", '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="합성" sheetId="1" r:id="r1"/></sheets></workbook>')
            archive.writestr("xl/_rels/workbook.xml.rels", '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
            archive.writestr("xl/worksheets/sheet1.xml", '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1"><v>0</v></c><c r="B1"/><c r="C1"><f>A1+1</f><v>1</v></c><c r="D1" t="inlineStr"><is><t>(1,234)</t></is></c></row></sheetData></worksheet>')
        cells = read_native(stream.getvalue())["합성"]
        self.assertEqual(cells["A1"].raw, "0")
        self.assertEqual(cells["B1"].kind, "BLANK")
        self.assertEqual(cells["C1"].formula, "=A1+1")
        self.assertTrue(cells["C1"].has_formula)
        self.assertEqual(cells["D1"].raw, "(1,234)")
        self.assertEqual(_numbers("01,3-5"), {1, 3, 4, 5})


if __name__ == "__main__":
    unittest.main()
