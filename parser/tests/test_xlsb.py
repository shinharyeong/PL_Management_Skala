"""xlsb(바이너리 통합 문서) 읽기를 메모리에서 만든 합성 패키지로 검증한다. 디스크에 파일을 만들지 않는다."""

import struct
import unittest
from io import BytesIO
from zipfile import ZipFile

from excel_parser.extraction import extract_workbook_bytes
from excel_parser.service_bridge import (
    BridgeError,
    BridgeLimits,
    _validate_package,
    parse_bytes,
)
from excel_parser.validation import validate_extraction
from excel_parser.xlsb import XLSB_WORKBOOK_TYPE, XlsbFormatError, rk_text

_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"


def _record(kind: int, body: bytes = b"") -> bytes:
    """BIFF12 레코드: 종류(7비트 가변 1~2바이트) + 길이(7비트 가변) + 내용."""
    head = bytes([kind]) if kind < 0x80 else bytes([(kind & 0x7F) | 0x80, kind >> 7])
    size, length = len(body), b""
    while True:
        byte = size & 0x7F
        size >>= 7
        length += bytes([byte | (0x80 if size else 0)])
        if not size:
            break
    return head + length + body


def _wide(text: str) -> bytes:
    return struct.pack("<I", len(text)) + text.encode("utf-16-le")


def _cell(column: int, style: int = 0) -> bytes:
    return struct.pack("<II", column, style)


def _formula(rgce: bytes) -> bytes:
    return struct.pack("<H", 0) + struct.pack("<I", len(rgce)) + rgce + struct.pack("<I", 0)


def _row(row: int, hidden: bool = False) -> bytes:
    return _record(0, struct.pack("<IIH", row, 0, 300) + bytes([0, 0x10 if hidden else 0, 0]) + struct.pack("<I", 0))


def _rk_int(value: int) -> int:
    return ((value << 2) | 0x02) & 0xFFFFFFFF


# 수식 토큰
def _ptg_ref(row: int, column: int, row_rel: bool = True, col_rel: bool = True, kind: int = 0x24) -> bytes:
    return bytes([kind]) + struct.pack("<IH", row, column | (0x4000 if col_rel else 0) | (0x8000 if row_rel else 0))


def _ptg_area3d(ixti: int, column: int, col_rel: bool) -> bytes:
    """열 전체 범위(행 0~1048575)를 다른 시트에서 참조."""
    flags = 0x4000 if col_rel else 0
    return bytes([0x3B]) + struct.pack("<HIIHH", ixti, 0, 1_048_575, column | flags, column | flags)


def _package(sheet_records: bytes, *, styles: bytes | None = None, strings: tuple[str, ...] = ("매출", "본부"),
             second_sheet: bytes | None = None) -> bytes:
    """최소 xlsb 패키지: 시트 1~2개, 공유 문자열, 서식, 외부 시트 표(자기 통합 문서)."""
    sheets = [("rId1", "답안지"), ("rId2", "Detail")]
    workbook = b"".join(_record(0x9C, struct.pack("<II", 0, index + 1) + _wide(rid) + _wide(name))
                        for index, (rid, name) in enumerate(sheets))
    workbook += _record(0x0165) + _record(0x016A, struct.pack("<I", 2) + struct.pack("<iii", 0, 0, 0)
                                          + struct.pack("<iii", 0, 1, 1))
    if styles is None:
        styles = (_record(0x002C, struct.pack("<H", 164) + _wide("yyyy-mm-dd"))
                  + _record(0x0269, struct.pack("<I", 2))
                  + _record(0x002F, struct.pack("<HHHHH", 0xFFFF, 0, 0, 0, 0) + b"\x00" * 6)
                  + _record(0x002F, struct.pack("<HHHHH", 0, 164, 0, 0, 0) + b"\x00" * 6)
                  + _record(0x026A))
    sst = b"".join(_record(0x13, b"\x00" + _wide(text)) for text in strings)
    content_types = (
        '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        f'<Default Extension="bin" ContentType="{XLSB_WORKBOOK_TYPE}"/>'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Override PartName="/xl/worksheets/sheet1.bin" ContentType="application/vnd.ms-excel.worksheet"/>'
        '<Override PartName="/xl/worksheets/sheet2.bin" ContentType="application/vnd.ms-excel.worksheet"/>'
        '<Override PartName="/xl/styles.bin" ContentType="application/vnd.ms-excel.styles"/>'
        '<Override PartName="/xl/sharedStrings.bin" ContentType="application/vnd.ms-excel.sharedStrings"/></Types>')
    root_rels = ('<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                 f'<Relationship Id="rId1" Type="{_REL}/officeDocument" Target="xl/workbook.bin"/></Relationships>')
    book_rels = ('<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                 f'<Relationship Id="rId1" Type="{_REL}/worksheet" Target="worksheets/sheet1.bin"/>'
                 f'<Relationship Id="rId2" Type="{_REL}/worksheet" Target="worksheets/sheet2.bin"/>'
                 f'<Relationship Id="rId3" Type="{_REL}/styles" Target="styles.bin"/>'
                 f'<Relationship Id="rId4" Type="{_REL}/sharedStrings" Target="sharedStrings.bin"/></Relationships>')
    buffer = BytesIO()
    with ZipFile(buffer, "w") as archive:
        archive.writestr("[Content_Types].xml", content_types)
        archive.writestr("_rels/.rels", root_rels)
        archive.writestr("xl/_rels/workbook.bin.rels", book_rels)
        archive.writestr("xl/workbook.bin", workbook)
        archive.writestr("xl/styles.bin", styles)
        archive.writestr("xl/sharedStrings.bin", sst)
        archive.writestr("xl/worksheets/sheet1.bin", sheet_records)
        archive.writestr("xl/worksheets/sheet2.bin", second_sheet or _row(0))
    return buffer.getvalue()


def _sheet(*records: bytes) -> bytes:
    return _record(0x91) + b"".join(records) + _record(0x92)


class XlsbValueTests(unittest.TestCase):
    """셀 값·형식·구조를 xlsx 추출과 같은 키로 돌려주는지."""

    def setUp(self) -> None:
        records = _sheet(
            _row(0),
            _record(2, _cell(0) + struct.pack("<I", _rk_int(100))),            # A1 RK 정수
            _record(2, _cell(1) + struct.pack("<I", _rk_int(1234) | 0x01)),     # B1 RK ÷100 → 12.34
            _record(5, _cell(2) + struct.pack("<d", 0.1)),                       # C1 실수
            _record(7, _cell(3) + struct.pack("<I", 0)),                         # D1 공유 문자열
            _record(6, _cell(4) + _wide("인라인")),                               # E1 인라인 문자열
            _record(4, _cell(5) + b"\x01"),                                      # F1 불리언
            _record(3, _cell(6) + b"\x2A"),                                      # G1 오류 #N/A
            _record(1, _cell(7, 1)),                                             # H1 서식만 있는 빈칸
            _record(2, _cell(8, 1) + struct.pack("<I", _rk_int(45000))),         # I1 날짜 서식 숫자
            _row(1, hidden=True),
            _record(2, _cell(0) + struct.pack("<I", _rk_int(-7))),               # A2 숨김 행, 음수
            _record(0x003C, struct.pack("<IIII", 1, 1, 2560, 0) + struct.pack("<H", 1)),  # B열 숨김
            _record(0x00B1, struct.pack("<I", 1)), _record(0x00B0, struct.pack("<IIII", 2, 2, 0, 1)),
            _record(0x00B2),
        )
        self.content = _package(records)
        self.workbook = extract_workbook_bytes(self.content, "D100_2027-PLAN.xlsb")
        self.sheet = self.workbook["sheets"][0]
        self.cells = {cell["address"]: cell for cell in self.sheet["cells"]}

    def test_values_and_types(self) -> None:
        expect = {
            "A1": ("100", "number", "n"), "B1": ("12.34", "number", "n"), "C1": ("0.1", "number", "n"),
            "D1": ("매출", "string", "s"), "E1": ("인라인", "string", "inlineStr"), "F1": (True, "boolean", "b"),
            "G1": ("#N/A", "error", "e"), "I1": ("45000", "date", "n"), "A2": ("-7", "number", "n"),
        }
        for address, (value, kind, storage) in expect.items():
            cell = self.cells[address]
            self.assertEqual((cell["value"], cell["data_type"], cell["storage_type"]), (value, kind, storage), address)
        self.assertEqual(self.cells["D1"]["raw_lexeme"], "0")
        self.assertEqual(self.cells["I1"]["number_format"], "yyyy-mm-dd")
        self.assertEqual(self.workbook["package_metadata"]["format"], "xlsb")

    def test_structure(self) -> None:
        self.assertNotIn("H1", self.cells)
        self.assertEqual(self.sheet["blank_spans"], [{"row": 1, "start_column": 8, "end_column": 8,
                                                      "attributes": {"s": "1"}}])
        self.assertTrue(self.cells["A2"]["row_hidden"])
        self.assertTrue(self.cells["B1"]["column_hidden"])
        self.assertEqual(self.sheet["merges"], [{"range": "A3:B3", "anchor": "A3"}])
        self.assertEqual(self.sheet["counts"], {"content_cells": 9, "xml_cells": 10, "blank_cells": 1, "formulas": 0})

    def test_independent_validation_passes(self) -> None:
        report = validate_extraction(BytesIO(self.content), self.workbook)
        self.assertTrue(report["passed"], report["issues"])
        self.assertEqual(report["source_format"], "xlsb")
        self.assertEqual(report["source_content_cells"], 9)


class XlsbFormulaTests(unittest.TestCase):
    """수식 토큰을 Excel A1 문장으로 복원하는지."""

    def _extract(self, *records: bytes) -> dict:
        content = _package(_sheet(*records))
        workbook = extract_workbook_bytes(content, "D100_2027-PLAN.xlsb")
        return {"cells": {cell["address"]: cell for cell in workbook["sheets"][0]["cells"]},
                "diagnostics": workbook["diagnostics"]}

    def test_sumifs_with_whole_columns_and_scale(self) -> None:
        # =(SUMIFS(Detail!I:I,Detail!$B:$B,$D12,Detail!$A:$A,$A$5))*0.00001
        rgce = (_ptg_area3d(1, 8, True) + _ptg_area3d(1, 1, False) + _ptg_ref(11, 3, row_rel=True, col_rel=False)
                + _ptg_area3d(1, 0, False) + _ptg_ref(4, 0, row_rel=False, col_rel=False)
                + bytes([0x42, 5]) + struct.pack("<H", 482) + b"\x15" + b"\x1F" + struct.pack("<d", 0.00001) + b"\x05")
        cells = self._extract(_row(11), _record(9, _cell(9) + struct.pack("<d", 12.5) + _formula(rgce)))["cells"]
        formula = cells["J12"]["formula"]
        self.assertEqual(formula["text"], "=(SUMIFS(Detail!I:I,Detail!$B:$B,$D12,Detail!$A:$A,$A$5))*0.00001")
        self.assertEqual(formula["raw_text"], formula["text"][1:])
        self.assertEqual(formula["cached_status"], "stored_unverified")
        self.assertEqual(cells["J12"]["cached_value"], "12.5")
        self.assertFalse(formula["external_reference"])

    def test_attr_sum_strings_and_operators(self) -> None:
        area = bytes([0x25]) + struct.pack("<IIHH", 9, 9, 9 | 0xC000, 20 | 0xC000)          # J10:U10
        sum_rgce = area + bytes([0x19, 0x10]) + struct.pack("<H", 0)                        # =SUM(J10:U10)
        text_rgce = b"\x17" + struct.pack("<H", 2) + '원"'.encode("utf-16-le") + b"\x1E" + struct.pack("<H", 3) \
            + b"\x1E" + struct.pack("<H", 4) + b"\x04" + b"\x08"                            # ="원"""&3-4
        cells = self._extract(
            _row(9),
            _record(9, _cell(8) + struct.pack("<d", 0) + _formula(sum_rgce)),
            _record(8, _cell(9) + _wide("원\"-1") + _formula(text_rgce)),
        )["cells"]
        self.assertEqual(cells["I10"]["formula"]["text"], "=SUM(J10:U10)")
        self.assertEqual(cells["J10"]["formula"]["text"], '="원"""&3-4')
        self.assertEqual(cells["J10"]["storage_type"], "str")

    def test_shared_formula_anchor_and_follower(self) -> None:
        # 기준 C2 = A2+B2 (상대 참조 오프셋 열 -2, -1), 범위 C2:D2. D2는 같은 공유 수식을 가리킨다.
        shared_rgce = (b"\x2C" + struct.pack("<iH", 0, (0x4000 - 2) & 0x3FFF | 0xC000)
                       + b"\x2C" + struct.pack("<iH", 0, (0x4000 - 1) & 0x3FFF | 0xC000) + b"\x03")
        exp = b"\x01" + struct.pack("<I", 1)
        cells = self._extract(
            _row(1),
            _record(9, _cell(2) + struct.pack("<d", 3) + _formula(exp)),
            _record(0x01AB, struct.pack("<IIII", 1, 1, 2, 3) + struct.pack("<I", len(shared_rgce)) + shared_rgce
                    + struct.pack("<I", 0)),
            _record(9, _cell(3) + struct.pack("<d", 5) + _formula(exp)),
        )["cells"]
        anchor, follower = cells["C2"]["formula"], cells["D2"]["formula"]
        self.assertEqual(anchor["text"], "=A2+B2")
        self.assertEqual(anchor["attributes"], {"t": "shared", "ref": "C2:D2", "si": "0"})
        self.assertIsNone(follower["text"])
        self.assertEqual(follower["attributes"], {"t": "shared", "si": "0"})
        self.assertEqual(follower["shared_base"], {"address": "C2", "raw_text": "A2+B2",
                                                   "attributes": anchor["attributes"]})

    def test_unsupported_token_keeps_cache_and_reports(self) -> None:
        name_rgce = b"\x23" + struct.pack("<I", 1)  # 정의된 이름은 복원하지 않는다
        result = self._extract(_row(0), _record(9, _cell(0) + struct.pack("<d", 42) + _formula(name_rgce)))
        cell = result["cells"]["A1"]
        self.assertIsNone(cell["formula"]["text"])
        self.assertEqual(cell["cached_value"], "42")
        self.assertIn("XLSB_FORMULA_UNRESOLVED", [d["code"] for d in result["diagnostics"]])


class XlsbBridgeTests(unittest.TestCase):
    """서비스 브리지가 xlsb를 받고 손상·위장 패키지는 거부하는지."""

    def test_bridge_accepts_xlsb(self) -> None:
        content = _package(_sheet(_row(0), _record(7, _cell(0) + struct.pack("<I", 0))))
        response = parse_bytes(content, "D100_2027-PLAN.xlsb")
        self.assertNotEqual(response["status"], "FAILED", response.get("errorCode"))
        self.assertEqual(response["submissionData"]["adapterVersion"], "financial-submission/1.0")

    def test_bridge_rejects_corrupted_records(self) -> None:
        content = _package(b"\x02\x05\x00")  # 길이 5인데 내용이 없는 레코드
        with self.assertRaises(BridgeError):
            _validate_package(content, BridgeLimits())
        with self.assertRaises(XlsbFormatError):
            extract_workbook_bytes(content, "D100_2027-PLAN.xlsb")

    def test_bridge_enforces_cell_limit(self) -> None:
        records = _sheet(_row(0), *(_record(2, _cell(column) + struct.pack("<I", _rk_int(column)))
                                    for column in range(5)))
        with self.assertRaises(BridgeError) as error:
            _validate_package(_package(records), BridgeLimits(max_cells=3))
        self.assertEqual(str(error.exception), "WORKBOOK_LIMIT_EXCEEDED")

    def test_xlsx_package_with_binary_parts_is_rejected(self) -> None:
        content = _package(_sheet(_row(0)))
        buffer = BytesIO()
        with ZipFile(BytesIO(content)) as source, ZipFile(buffer, "w") as target:
            for item in source.infolist():
                data = source.read(item)
                if item.filename == "_rels/.rels":
                    data = data.replace(b"xl/workbook.bin", b"xl/workbook.xml")
                target.writestr(item, data)
            target.writestr("xl/workbook.xml", "<workbook/>")
        with self.assertRaises(BridgeError):
            _validate_package(buffer.getvalue(), BridgeLimits())


class RkTests(unittest.TestCase):

    def test_rk_numbers(self) -> None:
        self.assertEqual(rk_text(_rk_int(1240)), "1240")
        self.assertEqual(rk_text(_rk_int(-5)), "-5")
        self.assertEqual(rk_text(_rk_int(1234) | 0x01), "12.34")
        high = struct.unpack("<Q", struct.pack("<d", 1.5))[0] >> 32
        self.assertEqual(rk_text(high << 0 & 0xFFFFFFFC), "1.5")


if __name__ == "__main__":
    unittest.main()
