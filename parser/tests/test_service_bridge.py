"""디스크 fixture 없이 메모리 OOXML과 내부 프로세스 프로토콜을 검증한다."""

import hashlib
import json
import subprocess
import sys
import unittest
from copy import deepcopy
from dataclasses import replace
from io import BytesIO, StringIO
from pathlib import Path
from unittest.mock import patch
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile

from helpers import extract_memory
from test_business_validation import contract_workbook
from test_mapping_monthly import find_record, inactive_expense_workbook

from excel_parser.extraction import extract_workbook_bytes
from excel_parser.mapping import map_workbook
from excel_parser.service_bridge import (
    BridgeError, BridgeLimits, _encode_response, _review_diagnostics, _validate_package,
    _validate_text_budget, main, parse_bytes,
)

_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
_SHEET = f'''<worksheet xmlns="{_NS}"><sheetData>
<row r="1"><c r="A1" t="inlineStr"><is><t>단위: 원</t></is></c></row>
<row r="3">
<c r="A3" t="inlineStr"><is><t>조직명</t></is></c>
<c r="B3" t="inlineStr"><is><t>관리분류</t></is></c>
<c r="C3" t="inlineStr"><is><t>중분류</t></is></c>
<c r="D3" t="inlineStr"><is><t>소분류</t></is></c>
<c r="E3" t="inlineStr"><is><t>거래내역 및 산출근거</t></is></c>
<c r="F3" t="inlineStr"><is><t>2031년 실적</t></is></c>
<c r="G3" t="inlineStr"><is><t>2031년 실적</t></is></c></row>
<row r="4"><c r="F4" t="inlineStr"><is><t>1월</t></is></c>
<c r="G4" t="inlineStr"><is><t>2월</t></is></c></row>
<row r="5"><c r="A5" t="inlineStr"><is><t>합성조직</t></is></c>
<c r="B5" t="inlineStr"><is><t>매출</t></is></c>
<c r="C5" t="inlineStr"><is><t>3100 서비스</t></is></c>
<c r="D5" t="inlineStr"><is><t>월정액</t></is></c>
<c r="E5" t="inlineStr"><is><t>합성 근거 [ID:memory-row; 수정:1]</t></is></c>
<c r="F5"><v>123.4500</v></c><c r="G5"><v>0</v></c>
</row></sheetData></worksheet>'''
_ORGANIZATIONS = f'''<worksheet xmlns="{_NS}"><sheetData>
<row r="1"><c r="A1" t="inlineStr"><is><t>사업부코드</t></is></c>
<c r="B1" t="inlineStr"><is><t>사업부명</t></is></c></row>
<row r="2"><c r="A2" t="inlineStr"><is><t>ORG-1</t></is></c>
<c r="B2" t="inlineStr"><is><t>합성조직</t></is></c></row>
</sheetData></worksheet>'''


def _package(
    overrides: dict[str, str | bytes] | None = None, *, compression: int = ZIP_STORED
) -> bytes:
    """독립 상수의 최소 재무 원본을 메모리 ZIP으로 구성한다.

    Args:
        overrides: 기본 ZIP 엔트리 내용을 바꿀 XML 또는 바이트.
        compression: ZIP_STORED 또는 ZIP_DEFLATED 압축 방식.
    Returns:
        파일로 저장하지 않은 OOXML 바이트.
    Raises:
        OSError: 메모리 ZIP 작성에 실패한 경우.
    Side Effects:
        BytesIO만 사용한다. Data·샘플·임시 파일은 만들지 않는다.
    """
    entries = {
        "[Content_Types].xml": '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/></Types>',
        "_rels/.rels": f'<Relationships><Relationship Id="book" Type="{_REL}/officeDocument" Target="xl/workbook.xml"/></Relationships>',
        "xl/workbook.xml": f'<workbook xmlns="{_NS}" xmlns:r="{_REL}"><sheets><sheet name="매출" sheetId="1" r:id="r1"/><sheet name="조직" sheetId="2" r:id="r2"/></sheets></workbook>',
        "xl/_rels/workbook.xml.rels": f'<Relationships><Relationship Id="r1" Type="{_REL}/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="r2" Type="{_REL}/worksheet" Target="worksheets/sheet2.xml"/></Relationships>',
        "xl/worksheets/sheet1.xml": _SHEET,
        "xl/worksheets/sheet2.xml": _ORGANIZATIONS,
    }
    entries.update(overrides or {})
    stream = BytesIO()
    with ZipFile(stream, "w", compression=compression) as archive:
        for name, document in entries.items():
            archive.writestr(name, document)
    return stream.getvalue()


def _invoke(
    content: bytes,
    *,
    filename: str = "ORG-1_2031-01.xlsx",
    limits: BridgeLimits | None = None,
) -> tuple[int, dict, str]:
    """메모리 stdin/stdout/stderr로 실제 CLI 함수를 호출한다.

    Args:
        content: 합성 ZIP 또는 실패 검증용 바이트.
        filename: 표시용 파일명.
        limits: 경계 검사에 사용할 선택 한도.
    Returns:
        종료코드, JSON 한 건, stderr 문자열.
    Raises:
        ValueError: stdout이 정확히 하나의 JSON이 아닌 경우.
    Side Effects:
        메모리 스트림만 사용한다.
    """
    output, errors = StringIO(), StringIO()
    code = main(
        ["--filename", filename],
        input_stream=BytesIO(content),
        output_stream=output,
        error_stream=errors,
        limits=limits,
    )
    return code, json.loads(output.getvalue()), errors.getvalue()


def _shared_package(string_item: str, reference: int = 0) -> bytes:
    """시트보다 뒤에 오는 공유 문자열 파트를 메모리에서 구성한다.

    Args:
        string_item: 공유 문자열 si 요소 XML. 리치 텍스트를 포함할 수 있다.
        reference: 상세 설명 셀이 참조할 공유 문자열 인덱스.
    Returns:
        설명 셀을 공유 문자열로 바꾼 합성 OOXML 바이트.
    Raises:
        OSError: 메모리 ZIP 구성에 실패한 경우.
    Side Effects:
        메모리만 사용하며 파일을 생성하지 않는다.
    """
    sheet = _SHEET.replace(
        '<c r="E5" t="inlineStr"><is><t>합성 근거 [ID:memory-row; 수정:1]</t></is></c>',
        f'<c r="E5" t="s"><v>{reference}</v></c>',
    )
    relations = (
        f'<Relationships><Relationship Id="r1" Type="{_REL}/worksheet" '
        'Target="worksheets/sheet1.xml"/>'
        f'<Relationship Id="r2" Type="{_REL}/worksheet" '
        'Target="worksheets/sheet2.xml"/>'
        f'<Relationship Id="r3" Type="{_REL}/sharedStrings" '
        'Target="sharedStrings.xml"/></Relationships>'
    )
    return _package(
        {
            "xl/worksheets/sheet1.xml": sheet,
            "xl/_rels/workbook.xml.rels": relations,
            "xl/sharedStrings.xml": f'<sst xmlns="{_NS}">{string_item}</sst>',
        }
    )


class ServiceBridgeTests(unittest.TestCase):
    """메모리 실제 파서와 실패·자원 제한 프로토콜을 검증한다."""

    def test_drawing_anchor_columns_are_not_worksheet_columns(self) -> None:
        """그림 앵커의 col을 열 서식으로 오인하지 않는지 검증한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 정상 그림을 거부하거나 일반 XML 한도를 생략한 경우.
        Side Effects:
            메모리 ZIP만 사용하며 원본·파일·캐시를 생성하지 않는다.
        """
        drawing = (
            '<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/'
            'drawingml/2006/spreadsheetDrawing"><xdr:twoCellAnchor>'
            '<xdr:from><xdr:col>0</xdr:col><xdr:row>1</xdr:row></xdr:from>'
            '<xdr:to><xdr:col>5</xdr:col><xdr:row>9</xdr:row></xdr:to>'
            '</xdr:twoCellAnchor></xdr:wsDr>'
        )
        content = _package({"xl/drawings/drawing1.xml": drawing})
        code, result, errors = _invoke(content)
        self.assertEqual((code, result["status"], errors), (0, "SUCCEEDED", ""))
        self.assertEqual(result["summary"], parse_bytes(_package(), "sample.xlsx")["summary"])
        with patch("excel_parser.service_bridge.map_workbook") as mapper:
            _, limited, _ = _invoke(
                content, limits=replace(BridgeLimits(), max_xml_nodes=1)
            )
            self.assertEqual(limited["errorCode"], "WORKBOOK_LIMIT_EXCEEDED")
            mapper.assert_not_called()

    def test_invalid_worksheet_column_ranges_remain_rejected(self) -> None:
        """그림 호환성 수정 뒤에도 워크시트 열 범위를 검사하는지 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 누락·역전·범위 밖 열 서식을 허용한 경우.
        Side Effects:
            메모리 ZIP만 사용하며 파일에는 접근하지 않는다.
        """
        for attributes in ('', 'min="0" max="1"', 'min="2" max="1"', 'min="1" max="16385"'):
            with self.subTest(attributes=attributes):
                sheet = _SHEET.replace('<sheetData>', f'<cols><col {attributes}/></cols><sheetData>')
                with patch("excel_parser.service_bridge.map_workbook") as mapper:
                    _, result, _ = _invoke(_package({"xl/worksheets/sheet1.xml": sheet}))
                    self.assertEqual(result["errorCode"], "INVALID_WORKBOOK")
                    mapper.assert_not_called()

    def test_real_mapping_decimal_zero_and_identity(self) -> None:
        """실제 추출·매핑이 십진 정밀도·0·명시 ID를 보존함을 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 원본의 독립 기대값과 응답이 다른 경우.
        Side Effects:
            메모리만 사용한다.
        """
        code, result, errors = _invoke(_package())
        self.assertEqual((code, result["status"], errors), (0, "SUCCEEDED", ""))
        self.assertEqual(result["protocolVersion"], "1.0")
        self.assertEqual(result["summary"]["sheetCount"], 2)
        self.assertEqual(result["summary"]["unresolvedCount"], 0)
        records = {
            record["source"]["cell"]: record
            for record in result["records"]
            if record["source"]["sheet"] == "매출"
        }
        self.assertEqual(records["F5"]["value"]["normalized_value"], "123.4500")
        self.assertEqual(records["G5"]["value"]["normalized_value"], "0")
        self.assertEqual(records["F5"]["item"]["detail_id"], "memory-row")
        self.assertEqual(records["F5"]["item"]["detail_revision"], 1)
        self.assertEqual(records["F5"]["organization"]["code"], "ORG-1")
        self.assertEqual(records["F5"]["source"], {"sheet": "매출", "cell": "F5"})
        self.assertEqual(result["contractVersion"], "preview-records/1.0")

    def test_missing_formula_cache_is_not_zero(self) -> None:
        """캐시 없는 수식은 null과 검토 필요로 보존되는지 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 캐시 누락을 0 또는 성공으로 표시한 경우.
        Side Effects:
            메모리만 사용하며 수식을 실행하지 않는다.
        """
        content = _package(
            {
                "xl/worksheets/sheet1.xml": _SHEET.replace(
                    "<v>123.4500</v>", "<f>SUM(A1:A2)</f>"
                )
            }
        )
        code, result, errors = _invoke(content)
        record = next(
            record for record in result["records"] if record["source"]["cell"] == "F5"
        )
        self.assertEqual((code, result["status"], errors), (0, "NEEDS_REVIEW", ""))
        self.assertIsNone(record["value"]["normalized_value"])
        self.assertEqual(record["formula"]["cached_status"], "missing")
        self.assertEqual(result["summary"]["unresolvedCount"], 1)

    def test_active_organization_lookup_error_keeps_amount_and_location(self) -> None:
        """입력된 행의 조직 수식 오류를 정확한 셀 안내와 검토 상태로 보존한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 실제 오류 행이 미사용으로 제외되거나 위치·금액이 유실된 경우.
        Side Effects:
            합성 원본을 메모리에서만 파싱하며 파일·원본·캐시를 만들지 않는다.
        """
        sheet = _SHEET.replace(
            '<c r="A5" t="inlineStr"><is><t>합성조직</t></is></c>',
            '<c r="A5" t="e"><f>NA()</f><v>#N/A</v></c>',
        )
        _, result, _ = _invoke(_package({"xl/worksheets/sheet1.xml": sheet}))
        self.assertEqual(result["status"], "NEEDS_REVIEW")
        self.assertEqual(result["summary"]["unusedTemplateRowCount"], 0)
        diagnostic = next(item for item in result["diagnostics"]
                          if item["code"] == "ORGANIZATION_LOOKUP_ERROR")
        self.assertEqual(diagnostic["source"], {"sheet": "매출", "cell": "A5"})
        self.assertIn("조직 코드표", diagnostic["message"])
        records = {record["source"]["cell"]: record for record in result["records"]
                   if record["source"]["sheet"] == "매출"}
        self.assertEqual(records["F5"]["value"]["normalized_value"], "123.4500")
        self.assertEqual(records["G5"]["value"]["normalized_value"], "0")
        self.assertEqual(records["G5"]["role"]["kind"], "detail")

    def test_unused_template_summary_keeps_candidates_and_rechecks_input(self) -> None:
        """미사용 수식 여러 개를 한 행으로 세고 0 입력 추가 시 다시 검토한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 원본 후보 손실·행 수 중복·실제 0 입력의 검증 누락.
        Side Effects:
            추출 경계만 독립 메모리 fixture로 대체하고 실제 매핑·브리지를 실행한다.
            원본·임시 XLSX·캐시 파일은 생성하지 않는다.
        """
        workbook = inactive_expense_workbook()
        workbook["diagnostics"] = []
        with patch("excel_parser.service_bridge.extract_workbook_bytes", return_value=workbook):
            _, result, _ = _invoke(_package())
        self.assertEqual(result["status"], "SUCCEEDED")
        self.assertEqual(result["summary"]["recordCount"], 14)
        self.assertEqual(result["summary"]["unusedTemplateRowCount"], 1)
        self.assertEqual(result["summary"]["unresolvedCount"], 0)
        self.assertEqual(len([r for r in result["records"] if r["role"]["kind"] == "inactive_template"]), 11)
        active_zero = next(r for r in result["records"] if r["source"]["cell"] == "R6")
        self.assertEqual(active_zero["value"]["normalized_value"], "0")
        self.assertEqual(active_zero["role"]["kind"], "detail")
        input_cell = next(c for c in workbook["sheets"][0]["cells"] if c["address"] == "R7")
        input_cell.update(value="0", data_type="number", has_content=True)
        with patch("excel_parser.service_bridge.extract_workbook_bytes", return_value=workbook):
            _, changed, _ = _invoke(_package())
        self.assertEqual(changed["status"], "NEEDS_REVIEW")
        self.assertEqual(changed["summary"]["unusedTemplateRowCount"], 0)
        self.assertEqual(changed["summary"]["recordCount"], 15)
        diagnostic = next(d for d in changed["diagnostics"] if d["code"] == "ORGANIZATION_LOOKUP_ERROR")
        self.assertEqual(diagnostic["source"], {"sheet": "검증", "cell": "D7"})

    def test_invalid_business_id_preserves_known_amount(self) -> None:
        """업무키 실패가 원문 ID와 확인된 금액을 삭제하지 않음을 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 업무키·금액 상태가 섞인 경우.
        Side Effects:
            메모리만 사용한다.
        """
        content = _package(
            {"xl/worksheets/sheet1.xml": _SHEET.replace("ID:memory-row", "ID:None")}
        )
        _, result, _ = _invoke(content)
        record = next(
            record for record in result["records"] if record["source"]["cell"] == "F5"
        )
        self.assertEqual(result["status"], "NEEDS_REVIEW")
        self.assertEqual(record["item"]["detail_id"], "None")
        self.assertEqual(record["value"]["normalized_value"], "123.4500")
        self.assertEqual(record["business_validation"]["status"], "invalid")
        diagnostics = [item for item in result["diagnostics"]
                       if item["code"] == "BUSINESS_DETAIL_ID_INVALID"]
        self.assertEqual(len(diagnostics), 1)
        self.assertEqual(diagnostics[0]["source"], {"sheet": "매출", "cell": "E5"})
        self.assertIn("전체 파일을 다시 업로드", diagnostics[0]["message"])
        self.assertNotIn("None", diagnostics[0]["message"])
        self.assertFalse(any(item["code"] == "FINANCIAL_DETAIL_UNRESOLVED"
                             for item in result["diagnostics"]))

    def test_business_diagnostics_locate_fields_and_preserve_candidates(self) -> None:
        """월별 반복 오류를 실제 계정·ID·분류 셀로 묶고 금액과 후보를 보존한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 코드표를 정정 위치로 안내하거나 후보·값·원본이 바뀐 경우.
        Side Effects:
            독립 합성 원본과 실제 매핑을 메모리에서만 사용한다.
        """
        workbook = contract_workbook()
        workbook["diagnostics"] = []
        replacements = {("검증", "B5"): "공개하지않을분류",
                        ("검증", "E5"): "근거 [ID:None; 수정:3]",
                        ("비용", "E5"): "99999"}
        for sheet in workbook["sheets"]:
            for cell in sheet["cells"]:
                if (sheet["name"], cell["address"]) in replacements:
                    cell["value"] = replacements[(sheet["name"], cell["address"])]
        before = deepcopy(workbook)
        standard = map_workbook(workbook)
        with patch("excel_parser.service_bridge.extract_workbook_bytes", return_value=workbook):
            result = parse_bytes(_package(), "sample.xlsx")
        self.assertEqual(result["status"], "NEEDS_REVIEW")
        diagnostics = [item for item in result["diagnostics"] if item["code"].startswith("BUSINESS_")]
        self.assertEqual(len(diagnostics), 3)
        self.assertEqual({(item["code"], item["source"]["sheet"], item["source"]["cell"])
                          for item in diagnostics}, {
            ("BUSINESS_MANAGEMENT_CATEGORY_INVALID", "검증", "B5"),
            ("BUSINESS_DETAIL_ID_INVALID", "검증", "E5"),
            ("BUSINESS_ACCOUNT_CODE_INVALID", "비용", "E5"),
        })
        self.assertTrue(all(set(item) == {"code", "severity", "stage", "message", "source"}
                            for item in diagnostics))
        source_cells = {(cell["sheet"], cell["cell"]): cell
                        for cell in result["submissionData"]["sourceCells"]}
        for item in diagnostics:
            self.assertNotIn("공개하지않을분류", item["message"])
            self.assertNotIn("99999", item["message"])
            self.assertIn("전체 파일을 다시 업로드", item["message"])
            source = (item["source"]["sheet"], item["source"]["cell"])
            self.assertEqual(source_cells[source]["rawValue"], replacements[source])
        self.assertEqual(len(result["records"]), len(standard["records"]))
        for original, projected in zip(standard["records"], result["records"], strict=True):
            for field in ("value", "mapping", "raw_value", "formula", "data_type"):
                self.assertEqual(projected[field], original[field])
        self.assertEqual(workbook, before)

    def test_missing_business_field_keeps_its_observed_source(self) -> None:
        """빈값과 오류값 모두 확인된 계정 셀로 안내하고 값을 추정하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 누락된 계정 대신 코드표 위치를 선택하거나 값을 고친 경우.
        Side Effects:
            메모리 원본만 변경·파싱하며 파일을 만들지 않는다.
        """
        for value, data_type in ((None, "blank"), ("#N/A", "error")):
            with self.subTest(data_type=data_type):
                workbook = contract_workbook()
                workbook["diagnostics"] = []
                cell = next(c for s in workbook["sheets"] if s["name"] == "비용"
                            for c in s["cells"] if c["address"] == "E5")
                cell.update(value=value, data_type=data_type, has_content=value is not None)
                with patch("excel_parser.service_bridge.extract_workbook_bytes", return_value=workbook):
                    result = parse_bytes(_package(), "sample.xlsx")
                diagnostics = [item for item in result["diagnostics"]
                               if item["code"] == "BUSINESS_ACCOUNT_CODE_MISSING"]
                self.assertEqual(len(diagnostics), 1)
                self.assertEqual(diagnostics[0]["source"], {"sheet": "비용", "cell": "E5"})
                self.assertIn("확인할 수 없습니다", diagnostics[0]["message"])
                self.assertEqual((cell["value"], cell["data_type"]), (value, data_type))
                self.assertEqual(result["status"], "NEEDS_REVIEW")

    def test_business_location_requires_matching_field_and_row_evidence(self) -> None:
        """근거 부족·코드표·다른 행의 셀을 정정 위치로 추정하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 확인되지 않은 입력 셀을 만들거나 일반 안내가 사라진 경우.
        Side Effects:
            실제 매핑 결과의 복사본만 메모리에서 변경한다.
        """
        workbook = contract_workbook()
        cell = next(c for s in workbook["sheets"] if s["name"] == "비용"
                    for c in s["cells"] if c["address"] == "E5")
        cell["value"] = "99999"
        original = find_record(map_workbook(workbook), "비용", "H5")
        for source, provenance in (
            ({"sheet": "비용", "cell": "E5"}, [{"sheet": "등록표", "cell": "A2"}]),
            (None, [{"sheet": "비용", "cell": "E5"}]),
            ({"sheet": "비용", "cell": "E6"}, [{"sheet": "비용", "cell": "E6"}]),
        ):
            with self.subTest(source=source, provenance=provenance):
                record = deepcopy(original)
                record["dimensions"]["예산Code"]["source"] = source
                record["business_validation"]["issues"][0]["provenance"] = provenance
                diagnostics = _review_diagnostics([record])
                self.assertEqual([item["code"] for item in diagnostics], ["FINANCIAL_DETAIL_UNRESOLVED"])
                self.assertEqual(diagnostics[0]["source"], {"sheet": "비용", "cell": "H5"})

    def test_business_diagnostics_keep_other_rows_and_unknown_reasons(self) -> None:
        """서로 다른 오류 행을 합치지 않고 알 수 없는 추가 사유는 일반 안내로 남긴다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 다른 행의 오류나 해석하지 않은 문제가 진단에서 사라진 경우.
        Side Effects:
            합성 원본의 실제 매핑 결과 복사본만 메모리에서 사용한다.
        """
        content = _package({"xl/worksheets/sheet1.xml": _SHEET.replace("ID:memory-row", "ID:None")})
        original = next(record for record in map_workbook(extract_workbook_bytes(content, "sample.xlsx"))["records"]
                        if record["source"]["sheet"] == "매출" and record["source"]["cell"] == "F5")
        another_row = deepcopy(original)
        another_row["source"]["cell"] = "F6"
        another_row["dimensions"]["거래내역 및 산출근거"]["source"]["cell"] = "E6"
        another_row["business_validation"]["issues"][0]["provenance"] = [{"sheet": "매출", "cell": "E6"}]
        diagnostics = _review_diagnostics([original, original, another_row])
        self.assertEqual([item["source"] for item in diagnostics], [
            {"sheet": "매출", "cell": "E5"}, {"sheet": "매출", "cell": "E6"},
        ])
        unknown = deepcopy(original)
        unknown["business_validation"]["issues"].append({"code": "NEW_RULE_UNRESOLVED", "field": "unknown"})
        diagnostics = _review_diagnostics([unknown])
        self.assertEqual([item["code"] for item in diagnostics], [
            "BUSINESS_DETAIL_ID_INVALID", "FINANCIAL_DETAIL_UNRESOLVED",
        ])
        self.assertEqual(diagnostics[-1]["source"], {"sheet": "매출", "cell": "F5"})

    def test_business_diagnostic_does_not_hide_missing_amount_cache(self) -> None:
        """업무키 안내를 묶어도 별도 금액 불확실성은 해당 금액 셀에 남긴다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 캐시 누락을 업무키 오류에 합치거나 금액을 보충한 경우.
        Side Effects:
            메모리 OOXML만 사용하며 수식을 실행하지 않는다.
        """
        sheet = _SHEET.replace("ID:memory-row", "ID:None").replace(
            "<v>123.4500</v>", "<f>SUM(A1:A2)</f>"
        )
        result = parse_bytes(_package({"xl/worksheets/sheet1.xml": sheet}), "sample.xlsx")
        targeted = [item for item in result["diagnostics"] if item["code"] == "BUSINESS_DETAIL_ID_INVALID"]
        fallback = [item for item in result["diagnostics"] if item["code"] == "FINANCIAL_DETAIL_UNRESOLVED"]
        self.assertEqual([item["source"] for item in targeted], [{"sheet": "매출", "cell": "E5"}])
        self.assertEqual([item["source"] for item in fallback], [{"sheet": "매출", "cell": "F5"}])
        records = {record["source"]["cell"]: record for record in result["records"]
                   if record["source"]["sheet"] == "매출"}
        self.assertIsNone(records["F5"]["value"]["normalized_value"])
        self.assertEqual(records["G5"]["raw_value"], "0")
        self.assertEqual(result["summary"]["unresolvedCount"], 2)

    def test_file_api_and_byte_api_share_extraction(self) -> None:
        """기존 경로 API와 메모리 API의 시트 결과가 같음을 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 공통 추출 본문 또는 내용 셀 값이 달라진 경우.
        Side Effects:
            기존 경로 API의 파일 경계도 메모리 mock으로 대체한다.
        """
        content = _package()
        memory = extract_workbook_bytes(content, "sample.xlsx")
        legacy = extract_memory(content, name="sample.xlsx")
        self.assertEqual(memory["file"]["file_id"], hashlib.sha256(content).hexdigest())
        for left, right in zip(memory["sheets"], legacy["sheets"], strict=True):
            self.assertEqual(left["counts"], right["counts"])
            self.assertEqual(
                [
                    (cell["address"], cell["value"], cell["data_type"])
                    for cell in left["cells"]
                ],
                [
                    (cell["address"], cell["value"], cell["data_type"])
                    for cell in right["cells"]
                ],
            )

    def test_bridge_does_not_open_files(self) -> None:
        """파일 열기를 금지해도 실제 브리지 파싱이 완료됨을 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 파일을 열거나 정상 메모리 파싱을 실패한 경우.
        Side Effects:
            테스트 동안 파일 열기만 mock으로 차단한다.
        """
        content = _package()
        with (
            patch("builtins.open", side_effect=AssertionError("파일 접근 금지")),
            patch.object(Path, "open", side_effect=AssertionError("파일 접근 금지")),
        ):
            code, result, _ = _invoke(content)
        self.assertEqual((code, result["status"]), (0, "SUCCEEDED"))

    def test_limits_reject_before_mapping(self) -> None:
        """압축·해제·엔트리·셀·XML 한도를 매핑 전에 거부하는지 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 한도 위반을 허용하거나 매핑기를 호출한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        content = _package()
        cases = {
            "max_input_bytes": 1,
            "max_uncompressed_bytes": 1,
            "max_entry_bytes": 1,
            "max_entries": 1,
            "max_cells": 1,
            "max_xml_cells": 1,
            "max_xml_nodes": 1,
            "max_xml_depth": 1,
            "max_cell_context_work": 1,
        }
        for field, value in cases.items():
            with (
                self.subTest(field=field),
                patch("excel_parser.service_bridge.map_workbook") as mapper,
            ):
                code, result, errors = _invoke(
                    content, limits=replace(BridgeLimits(), **{field: value})
                )
                self.assertEqual(code, 1)
                self.assertIn(
                    result["errorCode"], {"FILE_TOO_LARGE", "WORKBOOK_LIMIT_EXCEEDED"}
                )
                self.assertEqual(errors, result["errorCode"] + "\n")
                mapper.assert_not_called()

    def test_sparse_format_cells_have_a_separate_physical_limit(self) -> None:
        """메모가 있는 서식 빈 셀의 보존과 물리 셀·문맥 상한을 검증한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 빈 셀을 금액 후보로 만들거나 물리 상한을 무시한 경우.
        Side Effects:
            합성 메모리 ZIP만 사용한다.
        """
        blanks = ''.join(
            f'<row r="{row}"><c r="A{row}" s="0"/></row>'
            for row in range(10, 90)
        )
        sheet = _SHEET.replace('</sheetData>', blanks + '</sheetData>')
        comments = ''.join(
            f'<comment ref="A{row}" authorId="0"><text><t>합성 메모</t></text></comment>'
            for row in range(10, 90)
        )
        overrides = {
            "xl/worksheets/sheet1.xml": sheet,
            "xl/worksheets/_rels/sheet1.xml.rels": (
                f'<Relationships><Relationship Id="comments" Type="{_REL}/comments" '
                'Target="../comments1.xml"/></Relationships>'
            ),
            "xl/comments1.xml": (
                f'<comments xmlns="{_NS}"><authors><author>합성 작성자</author></authors>'
                f'<commentList>{comments}</commentList></comments>'
            ),
        }
        content = _package(overrides)
        workbook = extract_workbook_bytes(content, "sample.xlsx")
        retained = [cell for cell in workbook["sheets"][0]["cells"] if cell["comments"]]
        self.assertEqual(len(retained), 80)
        self.assertTrue(all(not cell["has_content"] for cell in retained))
        code, result, _ = _invoke(content, limits=replace(BridgeLimits(), max_cells=25))
        self.assertEqual((code, result["status"]), (0, "SUCCEEDED"))
        self.assertEqual(result["summary"], parse_bytes(_package(), "sample.xlsx")["summary"])
        _, limited, _ = _invoke(content, limits=replace(BridgeLimits(), max_xml_cells=25))
        self.assertEqual(limited["errorCode"], "WORKBOOK_LIMIT_EXCEEDED")
        columns = '<cols>' + '<col min="1" max="1"/>' * 100 + '</cols>'
        repeated = sheet.replace('<sheetData>', columns + '<sheetData>')
        with patch("excel_parser.service_bridge.map_workbook") as mapper:
            _, limited, _ = _invoke(
                _package({**overrides, "xl/worksheets/sheet1.xml": repeated}),
                limits=replace(BridgeLimits(), max_cell_context_work=5000),
            )
            self.assertEqual(limited["errorCode"], "WORKBOOK_LIMIT_EXCEEDED")
            mapper.assert_not_called()

    def test_large_financial_cell_boundary_remains_bounded(self) -> None:
        """기존 5만 셀보다 큰 원본을 허용하면서 내용 셀 상한을 지킨다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 새 상한의 정상 원본을 거부하거나 상한 초과를 허용한 경우.
        Side Effects:
            합성 OOXML을 메모리에서만 구성·검사하며 파일을 생성하지 않는다.
        """
        limits = BridgeLimits()
        base_cells = _SHEET.count('<c ') + _ORGANIZATIONS.count('<c ')
        row_count = limits.max_cells - base_cells
        self.assertGreater(row_count, 50_000)
        added = ''.join(
            f'<row r="{row}"><c r="A{row}"><v>0</v></c></row>'
            for row in range(10, 10 + row_count)
        )
        sheet = _SHEET.replace('</sheetData>', added + '</sheetData>')
        content = _package({'xl/worksheets/sheet1.xml': sheet})
        _validate_package(content, limits)
        with self.assertRaisesRegex(BridgeError, '^WORKBOOK_LIMIT_EXCEEDED$'):
            _validate_package(content, replace(limits, max_cells=limits.max_cells - 1))

    def test_large_response_and_text_budget_keep_exact_boundaries(self) -> None:
        """큰 응답·문맥 예산도 UTF-8 바이트의 유한 경계로 검사한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 경계값을 거부하거나 한 바이트 초과를 허용한 경우.
        Side Effects:
            작은 합성 응답과 길이 카운터만 메모리에 만들며 파일을 생성하지 않는다.
        """
        limits = BridgeLimits()
        totals = {'shared_lengths': [], 'row_text': [
            {'direct': limits.max_logical_text_bytes, 'cells': 1,
             'formula_bytes': 0, 'references': {}},
        ]}
        _validate_text_budget(totals, limits)
        totals['row_text'][0]['direct'] += 1
        with self.assertRaisesRegex(BridgeError, '^WORKBOOK_LIMIT_EXCEEDED$'):
            _validate_text_budget(totals, limits)
        response = {'value': '한글'}
        encoded = _encode_response(response, limits.max_output_bytes)
        size = len(encoded.encode('utf-8'))
        self.assertEqual(_encode_response(response, size), encoded)
        with self.assertRaisesRegex(BridgeError, '^PARSER_OUTPUT_LIMIT_EXCEEDED$'):
            _encode_response(response, size - 1)

    def test_cli_streams_only_after_complete_response_validation(self) -> None:
        """CLI가 전체 응답 문자열 없이 출력하고 초과 시 부분 JSON을 남기지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 전체 문자열 생성 경로를 사용하거나 실패 전에 일부 JSON을 출력한 경우.
        Side Effects:
            메모리 입력·출력과 합성 응답만 사용하며 파일을 생성하지 않는다.
        """
        content = _package()
        expected = parse_bytes(content, 'sample.xlsx')
        output, errors = StringIO(), StringIO()
        with (
            patch('excel_parser.service_bridge._encode_response', side_effect=AssertionError),
            patch.object(output, 'write', wraps=output.write) as writes,
        ):
            code = main(['--filename', 'sample.xlsx'], input_stream=BytesIO(content),
                        output_stream=output, error_stream=errors)
            self.assertGreater(writes.call_count, 1)
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(output.getvalue()), json.loads(_encode_response(expected, BridgeLimits().max_output_bytes)))
        output, errors = StringIO(), StringIO()
        with patch.object(output, 'write', wraps=output.write) as writes:
            code = main(['--filename', 'sample.xlsx'], input_stream=BytesIO(content),
                        output_stream=output, error_stream=errors,
                        limits=replace(BridgeLimits(), max_output_bytes=1))
            self.assertEqual(writes.call_count, 1)
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(output.getvalue())['errorCode'], 'PARSER_OUTPUT_LIMIT_EXCEEDED')

    def test_formula_text_is_charged_once_without_disabling_text_limit(self) -> None:
        """행 문맥에 쓰이지 않는 수식을 한 번 계산하면서 총량 상한을 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 수식을 행마다 중복 계산하거나 텍스트 상한을 생략한 경우.
        Side Effects:
            메모리에서 수식 캐시를 읽으며 수식 실행·파일 저장은 하지 않는다.
        """
        formula = 'SUM(' + ','.join(['1'] * 250) + ')'
        cells = ''.join(f'<c r="{column}9"><f>{formula}</f><v>1</v></c>' for column in 'ABCDEFGHIJ')
        sheet = _SHEET.replace('</sheetData>', f'<row r="9">{cells}</row></sheetData>')
        content = _package({"xl/worksheets/sheet1.xml": sheet})
        _, result, _ = _invoke(content, limits=replace(BridgeLimits(), max_logical_text_bytes=12000))
        self.assertNotEqual(result["status"], "FAILED")
        _, limited, _ = _invoke(content, limits=replace(BridgeLimits(), max_logical_text_bytes=5000))
        self.assertEqual(limited["errorCode"], "WORKBOOK_LIMIT_EXCEEDED")

    def test_preview_keeps_every_candidate_value_formula_and_issue(self) -> None:
        """미리보기 투영이 후보·금액·수식·업무 오류 근거를 보존함을 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 미확정·참고 후보나 진단 근거를 없애거나 표준 결과를 바꾼 경우.
        Side Effects:
            메모리 fixture와 실제 표준 매핑을 사용하며 파일은 읽거나 쓰지 않는다.
        """
        sheet = _SHEET.replace('ID:memory-row', 'ID:None').replace(
            '<v>123.4500</v>', '<f>SUM(A1:A2)</f><v>123.4500</v>'
        )
        organizations = _ORGANIZATIONS.replace(
            '</sheetData>', '<row r="3"><c r="C3"><v>7</v></c></row></sheetData>'
        )
        content = _package({
            "xl/worksheets/sheet1.xml": sheet,
            "xl/worksheets/sheet2.xml": organizations,
        })
        standard = map_workbook(extract_workbook_bytes(content, "sample.xlsx"))["records"]
        preview = parse_bytes(content, "sample.xlsx")
        self.assertEqual(preview["summary"]["recordCount"], len(standard))
        self.assertEqual(preview["status"], "NEEDS_REVIEW")
        self.assertTrue(any(record["value"]["value_kind"] != "amount" for record in standard))
        for original, projected in zip(standard, preview["records"]):
            self.assertEqual(projected["source"], {key: original["source"][key] for key in ("sheet", "cell")})
            for field in ("value", "formula", "mapping", "raw_value", "data_type"):
                self.assertEqual(projected[field], original[field])
            self.assertEqual(projected["role"]["kind"], original["role"]["kind"])
            if "business_validation" in original:
                for field in ("status", "issues"):
                    self.assertEqual(projected["business_validation"][field], original["business_validation"][field])
            self.assertIn("provenance", original["item"])
            self.assertNotIn("dimensions", projected)
            self.assertNotIn("path", projected["source"])

    def test_compression_ratio_and_output_limits(self) -> None:
        """압축 비율과 응답 한도를 넘으면 부분 JSON 없이 실패하는지 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 과도한 해제·출력 또는 부분 성공을 허용한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        _, result, _ = _invoke(
            _package(compression=ZIP_DEFLATED),
            limits=replace(BridgeLimits(), max_expansion_ratio=1),
        )
        self.assertEqual(result["errorCode"], "WORKBOOK_LIMIT_EXCEEDED")
        code, result, errors = _invoke(
            _package(), limits=replace(BridgeLimits(), max_output_bytes=1024)
        )
        self.assertEqual(
            (code, result["errorCode"], errors),
            (1, "PARSER_OUTPUT_LIMIT_EXCEEDED", "PARSER_OUTPUT_LIMIT_EXCEEDED\n"),
        )
        self.assertEqual(result["records"], [])

    def test_dtd_blocked_for_utf8_and_utf16(self) -> None:
        """UTF-8·UTF-16 DTD를 엔티티 확장 전에 거부하는지 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: DTD를 허용하거나 비밀 원문을 오류에 노출한 경우.
        Side Effects:
            메모리만 사용하며 외부 엔티티에 접속하지 않는다.
        """
        xml = '<!DOCTYPE worksheet [<!ENTITY secret "private-fixture-value">]>' + _SHEET
        for encoding in ("utf-8", "utf-16"):
            with self.subTest(encoding=encoding):
                code, result, errors = _invoke(
                    _package({"xl/worksheets/sheet1.xml": xml.encode(encoding)})
                )
                self.assertEqual((code, result["errorCode"]), (1, "UNSAFE_XML"))
                self.assertNotIn("private-fixture-value", json.dumps(result) + errors)

    def test_invalid_inputs_return_fixed_codes(self) -> None:
        """손상·파일명·비XML 관계 대상의 오류에서 원문을 숨기는지 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 실패 코드 또는 원문 비노출 계약이 다른 경우.
        Side Effects:
            메모리만 사용한다.
        """
        cases = [
            (b"private-fixture-value", "sample.xlsx", "INVALID_WORKBOOK"),
            (_package(), "../private-fixture-value.xlsx", "INVALID_FILENAME"),
            (_package(), "sample.xlsm", "UNSUPPORTED_FILE_TYPE"),
            (
                _package({"xl/worksheets/sheet1.xml": "<broken>private-fixture-value"}),
                "sample.xlsx",
                "INVALID_WORKBOOK",
            ),
            (
                _package(
                    {
                        "xl/_rels/workbook.xml.rels": f'<Relationships><Relationship Id="r1" Type="{_REL}/worksheet" Target="uninspected.bin"/></Relationships>'
                    }
                ),
                "sample.xlsx",
                "INVALID_WORKBOOK",
            ),
        ]
        for content, filename, expected in cases:
            with self.subTest(expected=expected):
                code, result, errors = _invoke(content, filename=filename)
                self.assertEqual(
                    (code, result["errorCode"], errors), (1, expected, expected + "\n")
                )
                self.assertNotIn("private-fixture-value", json.dumps(result) + errors)

    def test_no_financial_details_require_review(self) -> None:
        """헤더·참고 값만 있는 파일을 재무 해석 성공으로 표시하지 않는다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 재무 상세가 없는데 성공으로 표시한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        result = parse_bytes(
            _package({"xl/worksheets/sheet1.xml": _ORGANIZATIONS}), "sample.xlsx"
        )
        self.assertEqual(result["status"], "NEEDS_REVIEW")
        self.assertTrue(
            any(
                item["code"] == "FINANCIAL_DETAIL_NOT_FOUND"
                for item in result["diagnostics"]
            )
        )

    def test_real_module_process_protocol(self) -> None:
        """-B 모듈 프로세스가 stdout JSON·종료코드 계약을 지키는지 확인한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 실제 모듈의 성공·실패 응답이 다른 경우.
            TimeoutExpired: 합성 자료 처리에 10초를 넘긴 경우.
        Side Effects:
            -B Python 자식 프로세스와 메모리 파이프만 사용한다.
        """
        for content, expected_code, status in (
            (_package(), 0, "SUCCEEDED"),
            (b"invalid", 1, "FAILED"),
        ):
            with self.subTest(status=status):
                result = subprocess.run(
                    [
                        sys.executable,
                        "-B",
                        "-m",
                        "excel_parser.service_bridge",
                        "--filename",
                        "ORG-1_2031-01.xlsx",
                    ],
                    input=content,
                    capture_output=True,
                    timeout=10,
                    check=False,
                )
                self.assertEqual(result.returncode, expected_code)
                payload = json.loads(result.stdout)
                self.assertEqual(payload["status"], status)
                self.assertEqual(
                    result.stderr.decode(),
                    "" if expected_code == 0 else payload["errorCode"] + "\n",
                )

    def test_sheet_metadata_limits_before_extraction(self) -> None:
        """증폭되는 sheetId·시트명은 추출 전에 정수·길이 범위를 검사한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 잘못된 식별자를 추출하거나 경계값을 거부한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        for sheet_id, name in (
            ("0", "매출"),
            ("4294967296", "매출"),
            ("00000000001", "매출"),
            ("x", "매출"),
            ("1", "x" * 32),
        ):
            workbook = f'<workbook xmlns="{_NS}" xmlns:r="{_REL}"><sheets><sheet name="{name}" sheetId="{sheet_id}" r:id="r1"/></sheets></workbook>'
            with (
                self.subTest(sheet_id=sheet_id, name_length=len(name)),
                patch("excel_parser.service_bridge.extract_workbook_bytes") as extract,
            ):
                result = parse_bytes(
                    _package({"xl/workbook.xml": workbook}), "sample.xlsx"
                )
                self.assertEqual(result["errorCode"], "INVALID_WORKBOOK")
                extract.assert_not_called()
        workbook = f'<workbook xmlns="{_NS}" xmlns:r="{_REL}"><sheets><sheet name="{"x" * 31}" sheetId="4294967295" r:id="r1"/><sheet name="조직" sheetId="2" r:id="r2"/></sheets></workbook>'
        result = parse_bytes(_package({"xl/workbook.xml": workbook}), "sample.xlsx")
        self.assertEqual(result["status"], "SUCCEEDED")

    def test_text_and_attribute_limits_before_extraction(self) -> None:
        """큰 XML 속성과 단일 셀 텍스트를 매핑 전에 거부한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 속성·텍스트 한도를 넘는 입력을 추출한 경우.
        Side Effects:
            작은 메모리 fixture만 만들고 파일에는 접근하지 않는다.
        """
        cases = (
            _SHEET.replace("<worksheet ", '<worksheet note="' + "x" * 8193 + '" ', 1),
            _SHEET.replace("합성 근거 [ID:memory-row; 수정:1]", "x" * 32_768),
        )
        for sheet in cases:
            with (
                self.subTest(length=len(sheet)),
                patch("excel_parser.service_bridge.extract_workbook_bytes") as extract,
            ):
                result = parse_bytes(
                    _package({"xl/worksheets/sheet1.xml": sheet}), "sample.xlsx"
                )
                self.assertEqual(result["errorCode"], "WORKBOOK_LIMIT_EXCEEDED")
                extract.assert_not_called()

    def test_shared_text_budget_and_rich_runs(self) -> None:
        """공유 문자열 재사용·리치 텍스트 합계를 실제 확장 전에 제한한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 참조 확장·행 문맥 반복·리치 문자열 한도를 놓친 경우.
        Side Effects:
            메모리 fixture와 작은 테스트용 한도만 사용한다.
        """
        content = _shared_package("<si><t>[ID:" + "x" * 3000 + "; 수정:1]</t></si>")
        self.assertEqual(parse_bytes(content, "sample.xlsx")["status"], "SUCCEEDED")
        with patch("excel_parser.service_bridge.extract_workbook_bytes") as extract:
            result = parse_bytes(
                content,
                "sample.xlsx",
                replace(BridgeLimits(), max_logical_text_bytes=10_000),
            )
            self.assertEqual(result["errorCode"], "WORKBOOK_LIMIT_EXCEEDED")
            extract.assert_not_called()
        rich = (
            "<si><r><t>"
            + "x" * 20_000
            + "</t></r><r><t>"
            + "y" * 20_000
            + "</t></r></si>"
        )
        with patch("excel_parser.service_bridge.extract_workbook_bytes") as extract:
            result = parse_bytes(_shared_package(rich), "sample.xlsx")
            self.assertEqual(result["errorCode"], "WORKBOOK_LIMIT_EXCEEDED")
            extract.assert_not_called()

    def test_missing_shared_reference_rejected_before_extraction(self) -> None:
        """없는 공유 문자열 인덱스는 길이 계산 중에도 안전하게 거부한다.

        Args:
            없음.
        Returns:
            없음.
        Raises:
            AssertionError: 잘못된 참조를 추출 단계로 전달한 경우.
        Side Effects:
            메모리만 사용한다.
        """
        content = _shared_package("<si><t>합성 문자열</t></si>", reference=1)
        with patch("excel_parser.service_bridge.extract_workbook_bytes") as extract:
            result = parse_bytes(content, "sample.xlsx")
            self.assertEqual(result["errorCode"], "INVALID_WORKBOOK")
            extract.assert_not_called()


if __name__ == "__main__":
    unittest.main()
