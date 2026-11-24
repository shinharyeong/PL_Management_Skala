"""xlsb(Excel 바이너리 통합 문서)를 xlsx 추출과 같은 구조로 읽는다.

xlsb는 xlsx와 같은 ZIP 패키지지만 통합 문서·시트·공유 문자열·서식 파트가 XML이 아니라 BIFF12 레코드다.
이 모듈은 MS-XLSB 명세의 레코드를 표준 라이브러리만으로 읽어 `extraction.extract_workbook`이 xlsx에서
만드는 것과 같은 사전(file, sheets, styles, package_metadata, diagnostics)을 돌려준다. 그래서 매핑·정규화·
제출 어댑터는 원본 형식을 몰라도 된다.

수식은 파싱된 토큰(rgce)으로 저장되어 있어 Excel A1 표기 문장으로 복원한다. 복원할 수 없는 토큰이 있으면
수식 문장을 null로 두고 진단을 남기며, 저장된 계산값(캐시)은 그대로 보존한다. 수식을 계산하지 않는다.
"""

from __future__ import annotations

import posixpath
import struct
from decimal import Decimal
from xml.etree import ElementTree as ET
from zipfile import ZipFile

from .ooxml import (
    BUILTIN_FORMATS,
    is_date_format,
    local_name,
    read_comments,
    relationships,
)

XLSB_WORKBOOK_TYPE = "application/vnd.ms-excel.sheet.binary.macroEnabled.main"
MAX_ROW = 1_048_575
MAX_COLUMN = 16_383

# 시트 셀 레코드(MS-XLSB 2.4) — 열 번호가 있는 일반 셀과 앞 셀의 다음 열인 짧은 셀
_ROW_HEADER = 0x0000
_CELL_BLANK, _CELL_RK, _CELL_ERROR, _CELL_BOOL, _CELL_REAL, _CELL_ST, _CELL_ISST = range(1, 8)
_FMLA_STRING, _FMLA_NUM, _FMLA_BOOL, _FMLA_ERROR = range(8, 12)
_SHORT_BLANK, _SHORT_RK, _SHORT_ERROR, _SHORT_BOOL, _SHORT_REAL, _SHORT_ST, _SHORT_ISST = range(12, 19)
_COL_INFO = 0x003C
_WS_DIM = 0x0094
_MERGE_CELL = 0x00B0
_SHARED_FORMULA = 0x01AB
_ARRAY_FORMULA = 0x01AA
# 통합 문서·문자열·서식·메모 레코드
_BUNDLE_SHEET = 0x009C
_SUP_BOOK_SRC, _SUP_SELF, _SUP_SAME, _SUP_ADDIN = 0x0163, 0x0165, 0x0166, 0x0167
_EXTERN_SHEET = 0x016A
_SST_ITEM = 0x0013
_FORMAT = 0x002C
_XF = 0x002F
_BEGIN_CELL_XFS, _END_CELL_XFS = 0x0269, 0x026A
_COMMENT_AUTHOR, _BEGIN_COMMENT, _END_COMMENT, _COMMENT_TEXT = 0x0278, 0x027B, 0x027C, 0x027D

ERRORS = {
    0x00: "#NULL!", 0x07: "#DIV/0!", 0x0F: "#VALUE!", 0x17: "#REF!", 0x1D: "#NAME?",
    0x24: "#NUM!", 0x2A: "#N/A", 0x2B: "#GETTING_DATA",
}

# 함수 번호(Ftab) → 이름과 고정 인자 수(None이면 가변). 양식에 쓰이는 함수와 흔한 함수만 둔다.
FUNCTIONS: dict[int, tuple[str, int | None]] = {
    0: ("COUNT", None), 1: ("IF", None), 2: ("ISNA", 1), 3: ("ISERROR", 1), 4: ("SUM", None),
    5: ("AVERAGE", None), 6: ("MIN", None), 7: ("MAX", None), 8: ("ROW", None), 9: ("COLUMN", None),
    10: ("NA", 0), 19: ("PI", 0), 20: ("SQRT", 1), 24: ("ABS", 1), 25: ("INT", 1), 26: ("SIGN", 1),
    27: ("ROUND", 2), 28: ("LOOKUP", None), 29: ("INDEX", None), 30: ("REPT", 2), 31: ("MID", 3),
    32: ("LEN", 1), 33: ("VALUE", 1), 34: ("TRUE", 0), 35: ("FALSE", 0), 36: ("AND", None),
    37: ("OR", None), 38: ("NOT", 1), 39: ("MOD", 2), 48: ("TEXT", 2), 63: ("RAND", 0),
    64: ("MATCH", None), 65: ("DATE", 3), 66: ("TIME", 3), 67: ("DAY", 1), 68: ("MONTH", 1),
    69: ("YEAR", 1), 74: ("NOW", 0), 76: ("ROWS", 1), 77: ("COLUMNS", 1), 78: ("OFFSET", None),
    100: ("CHOOSE", None), 101: ("HLOOKUP", None), 102: ("VLOOKUP", None), 111: ("CHAR", 1),
    112: ("LOWER", 1), 113: ("UPPER", 1), 115: ("LEFT", None), 116: ("RIGHT", None), 117: ("EXACT", 2),
    118: ("TRIM", 1), 119: ("REPLACE", 4), 120: ("SUBSTITUTE", None), 124: ("FIND", None),
    148: ("INDIRECT", None), 169: ("COUNTA", None), 183: ("PRODUCT", None), 212: ("ROUNDUP", 2),
    213: ("ROUNDDOWN", 2), 221: ("TODAY", 0), 336: ("CONCATENATE", None), 337: ("POWER", 2),
    344: ("SUBTOTAL", None), 345: ("SUMIF", None), 346: ("COUNTIF", 2), 347: ("COUNTBLANK", 1),
    480: ("IFERROR", 2), 481: ("COUNTIFS", None), 482: ("SUMIFS", None), 483: ("AVERAGEIF", None),
    484: ("AVERAGEIFS", None),
}
_BINARY = {0x03: "+", 0x04: "-", 0x05: "*", 0x06: "/", 0x07: "^", 0x08: "&", 0x09: "<", 0x0A: "<=",
           0x0B: "=", 0x0C: ">=", 0x0D: ">", 0x0E: "<>", 0x0F: " ", 0x10: ",", 0x11: ":"}


class XlsbFormatError(ValueError):
    """레코드 길이·구조가 명세와 맞지 않는 손상된 xlsb."""


class FormulaUnsupported(Exception):
    """복원할 수 없는 수식 토큰. 수식 문장 대신 진단을 남긴다."""


def is_xlsb(archive: ZipFile) -> bool:
    """루트 관계의 통합 문서 파트가 .bin이면 xlsb로 본다(확장자가 아니라 패키지 내용으로 판정)."""
    return workbook_part(archive).endswith(".bin")


def workbook_part(archive: ZipFile) -> str:
    """루트 관계에서 통합 문서 파트 경로를 찾는다. 없으면 xlsx 기본 경로."""
    for entry in relationships(archive, "").values():
        if entry.get("Type", "").endswith("/officeDocument") and "part" in entry:
            return entry["part"]
    return "xl/workbook.xml"


def iter_records(data: bytes):
    """BIFF12 레코드(종류·내용)를 순서대로 돌려준다. 종류는 1~2바이트, 길이는 1~4바이트 7비트 가변 정수다."""
    position, size_total = 0, len(data)
    while position < size_total:
        record_type = data[position]
        position += 1
        if record_type & 0x80:
            if position >= size_total:
                raise XlsbFormatError("레코드 종류가 잘렸습니다.")
            record_type = (record_type & 0x7F) | ((data[position] & 0x7F) << 7)
            position += 1
        length, shift = 0, 0
        for _ in range(4):
            if position >= size_total:
                raise XlsbFormatError("레코드 길이가 잘렸습니다.")
            byte = data[position]
            position += 1
            length |= (byte & 0x7F) << shift
            shift += 7
            if not byte & 0x80:
                break
        if position + length > size_total:
            raise XlsbFormatError("레코드 내용이 잘렸습니다.")
        yield record_type, data[position:position + length]
        position += length


class _Cursor:
    """레코드 내용을 앞에서부터 읽는 작은 도우미."""

    __slots__ = ("data", "position")

    def __init__(self, data: bytes, position: int = 0):
        self.data, self.position = data, position

    def take(self, size: int) -> bytes:
        if self.position + size > len(self.data):
            raise XlsbFormatError("레코드 필드가 잘렸습니다.")
        chunk = self.data[self.position:self.position + size]
        self.position += size
        return chunk

    def u8(self) -> int:
        return self.take(1)[0]

    def u16(self) -> int:
        return struct.unpack("<H", self.take(2))[0]

    def u32(self) -> int:
        return struct.unpack("<I", self.take(4))[0]

    def i32(self) -> int:
        return struct.unpack("<i", self.take(4))[0]

    def f64(self) -> float:
        return struct.unpack("<d", self.take(8))[0]

    def wide(self) -> str:
        count = self.u32()
        return self.take(count * 2).decode("utf-16-le")

    def nullable_wide(self) -> str | None:
        count = self.u32()
        return None if count == 0xFFFFFFFF else self.take(count * 2).decode("utf-16-le")

    def remaining(self) -> int:
        return len(self.data) - self.position


def column_name(index: int) -> str:
    """0부터 시작하는 열 번호 → A, B, …, XFD."""
    name, value = "", index + 1
    while value:
        value, remainder = divmod(value - 1, 26)
        name = chr(65 + remainder) + name
    return name


def address(row: int, column: int) -> str:
    """0부터 시작하는 행·열 → A1."""
    return f"{column_name(column)}{row + 1}"


def decimal_text(value: Decimal) -> str:
    """지수 없이 쓰는 십진 문자열. 뒤쪽 0과 -0을 정리한다."""
    text = format(value, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return "0" if text in {"-0", ""} else text


def float_text(value: float) -> str:
    """저장된 배정밀도 값을 왕복 가능한 가장 짧은 십진 문자열로 쓴다(원본보다 정밀도를 늘리지 않음)."""
    return decimal_text(Decimal(repr(value)))


def rk_text(rk: int) -> str:
    """RK 숫자(30비트 정수 또는 상위 30비트 배정밀도, 선택적으로 ÷100)를 정확한 십진 문자열로."""
    if rk & 0x02:
        number = Decimal((rk >> 2) - (1 << 30) if rk & 0x80000000 else rk >> 2)
    else:
        number = Decimal(repr(struct.unpack("<d", struct.pack("<Q", (rk & 0xFFFFFFFC) << 32))[0]))
    if rk & 0x01:
        number = number.scaleb(-2)
    return decimal_text(number)


def _sheet_label(name: str) -> str:
    """수식의 시트 접두사. 글자·숫자·밑줄·마침표만 있고 숫자로 시작하지 않으면 따옴표 없이 쓴다."""
    plain = name and not name[0].isdigit() and all(ch.isalnum() or ch in "_." for ch in name)
    return name if plain else "'" + name.replace("'", "''") + "'"


class FormulaContext:
    """수식 복원에 필요한 통합 문서 정보: 시트 이름 순서, 외부 시트 표(Xti), 외부 통합 문서 종류."""

    def __init__(self, sheet_names: list[str], xti: list[tuple[int, int, int]], sup_books: list[str]):
        self.sheet_names, self.xti, self.sup_books = sheet_names, xti, sup_books

    def sheet_prefix(self, ixti: int) -> tuple[str, bool]:
        """Xti 번호 → ('시트!' 접두사, 외부 참조 여부)."""
        if ixti >= len(self.xti):
            raise FormulaUnsupported("XTI_OUT_OF_RANGE")
        sup_book, first, last = self.xti[ixti]
        kind = self.sup_books[sup_book] if 0 <= sup_book < len(self.sup_books) else "external"
        if kind not in {"self", "same"}:
            return f"[{sup_book}]!", True
        if first < 0 or first >= len(self.sheet_names):
            raise FormulaUnsupported("XTI_SHEET_INVALID")
        if last != first and 0 <= last < len(self.sheet_names):
            return _sheet_label(self.sheet_names[first] + ":" + self.sheet_names[last]) + "!", False
        return _sheet_label(self.sheet_names[first]) + "!", False


def _cell_ref(row: int, column_field: int, base_row: int, base_column: int, relative_offsets: bool) -> str:
    """행·열 필드를 A1 표기로. 열 필드의 bit14는 열 상대, bit15는 행 상대다.

    relative_offsets가 참이면(PtgRefN·PtgAreaN, 공유 수식) 상대 좌표를 기준 셀로부터의 오프셋으로 읽는다.
    """
    column_relative, row_relative = bool(column_field & 0x4000), bool(column_field & 0x8000)
    column = column_field & 0x3FFF
    if relative_offsets:
        if row_relative:
            row = (base_row + row) & 0xFFFFF
        if column_relative:
            offset = column - 0x4000 if column & 0x2000 else column
            column = (base_column + offset) & 0x3FFF
    return f"{'' if column_relative else '$'}{column_name(column)}{'' if row_relative else '$'}{row + 1}"


def _area_ref(cursor: _Cursor, base_row: int, base_column: int, relative_offsets: bool) -> str:
    """범위 필드(행 시작·끝, 열 시작·끝)를 A1 표기로. 열 전체(A:A)·행 전체(1:1)는 Excel처럼 줄여 쓴다."""
    row_first = cursor.i32() if relative_offsets else cursor.u32()
    row_last = cursor.i32() if relative_offsets else cursor.u32()
    column_first, column_last = cursor.u16(), cursor.u16()
    if not relative_offsets and row_first == 0 and row_last == MAX_ROW:
        first = ("" if column_first & 0x4000 else "$") + column_name(column_first & 0x3FFF)
        last = ("" if column_last & 0x4000 else "$") + column_name(column_last & 0x3FFF)
        return f"{first}:{last}"
    if not relative_offsets and (column_first & 0x3FFF) == 0 and (column_last & 0x3FFF) == MAX_COLUMN:
        first = ("" if column_first & 0x8000 else "$") + str(row_first + 1)
        last = ("" if column_last & 0x8000 else "$") + str(row_last + 1)
        return f"{first}:{last}"
    return (_cell_ref(row_first, column_first, base_row, base_column, relative_offsets) + ":"
            + _cell_ref(row_last, column_last, base_row, base_column, relative_offsets))


def _number_constant(value: float) -> str:
    return float_text(value)


def decompile(rgce: bytes, base_row: int, base_column: int, context: FormulaContext,
              shared: bool = False) -> tuple[str, bool]:
    """파싱된 수식 토큰을 '=' 없는 A1 수식 문장으로 복원한다.

    Args:
        rgce: 수식 토큰 바이트.
        base_row, base_column: 수식이 있는 셀(공유 수식이면 기준 셀). 0부터 시작.
        context: 시트 이름·Xti 표.
        shared: 공유 수식 본문이면 참. 상대 참조 토큰을 기준 셀 오프셋으로 읽는다.
    Returns:
        (수식 문장, 외부 통합 문서 참조 여부)
    Raises:
        FormulaUnsupported: 복원하지 않는 토큰(표 수식, 구조적 참조, 이름 등)을 만난 경우.
    """
    cursor = _Cursor(rgce)
    stack: list[str] = []
    external = False

    def pop(count: int) -> list[str]:
        if count > len(stack):
            raise FormulaUnsupported("STACK_UNDERFLOW")
        values = stack[len(stack) - count:] if count else []
        del stack[len(stack) - count:]
        return values

    while cursor.remaining():
        token = cursor.u8()
        base = (token & 0x1F) | 0x20 if token >= 0x20 else token
        if base in _BINARY:
            right, left = pop(1)[0], pop(1)[0]
            stack.append(f"{left}{_BINARY[base]}{right}")
        elif base == 0x12:
            stack.append("+" + pop(1)[0])
        elif base == 0x13:
            stack.append("-" + pop(1)[0])
        elif base == 0x14:
            stack.append(pop(1)[0] + "%")
        elif base == 0x15:
            stack.append("(" + pop(1)[0] + ")")
        elif base == 0x16:
            stack.append("")
        elif base == 0x17:
            length = cursor.u16()
            text = cursor.take(length * 2).decode("utf-16-le")
            stack.append('"' + text.replace('"', '""') + '"')
        elif base == 0x19:
            kind, data = cursor.u8(), cursor.u16()
            if kind & 0x04:
                cursor.take((data + 1) * 2)
            if kind & 0x10:
                stack.append("SUM(" + pop(1)[0] + ")")
            # 공백·세미콜론·IF/GOTO 점프 등 나머지 속성은 문장에 영향이 없다.
        elif base == 0x1C:
            stack.append(ERRORS.get(cursor.u8(), "#N/A"))
        elif base == 0x1D:
            stack.append("TRUE" if cursor.u8() else "FALSE")
        elif base == 0x1E:
            stack.append(str(cursor.u16()))
        elif base == 0x1F:
            stack.append(_number_constant(cursor.f64()))
        elif base == 0x21:
            number = cursor.u16()
            if number not in FUNCTIONS or FUNCTIONS[number][1] is None:
                raise FormulaUnsupported(f"FUNCTION_{number}")
            name, count = FUNCTIONS[number]
            stack.append(f"{name}({','.join(pop(count))})")
        elif base == 0x22:
            count, tab = cursor.u8(), cursor.u16()
            number = tab & 0x7FFF
            if number not in FUNCTIONS:
                raise FormulaUnsupported(f"FUNCTION_{number}")
            stack.append(f"{FUNCTIONS[number][0]}({','.join(pop(count))})")
        elif base == 0x24:
            row, column = cursor.u32(), cursor.u16()
            stack.append(_cell_ref(row, column, base_row, base_column, False))
        elif base == 0x25:
            stack.append(_area_ref(cursor, base_row, base_column, False))
        elif base == 0x2C:
            row, column = cursor.i32(), cursor.u16()
            stack.append(_cell_ref(row, column, base_row, base_column, True))
        elif base == 0x2D:
            stack.append(_area_ref(cursor, base_row, base_column, True))
        elif base in {0x2A, 0x2B, 0x3C, 0x3D}:
            cursor.take({0x2A: 6, 0x2B: 12, 0x3C: 8, 0x3D: 14}[base])
            stack.append("#REF!")
        elif base == 0x3A:
            prefix, is_external = context.sheet_prefix(cursor.u16())
            external |= is_external
            row, column = cursor.u32(), cursor.u16()
            stack.append(prefix + _cell_ref(row, column, base_row, base_column, False))
        elif base == 0x3B:
            prefix, is_external = context.sheet_prefix(cursor.u16())
            external |= is_external
            stack.append(prefix + _area_ref(cursor, base_row, base_column, False))
        elif base in {0x26, 0x27, 0x28}:
            cursor.take(6)  # 미리 계산한 범위 힌트. 뒤따르는 토큰이 실제 식이다.
        elif base == 0x29:
            cursor.take(2)
        else:
            # 0x01 PtgExp(공유 수식 참조)는 호출자가 처리한다. 0x02 표, 0x18 확장, 0x20 배열, 0x23·0x39 이름 등은 미지원.
            raise FormulaUnsupported(f"TOKEN_{token:#04x}")
    if len(stack) != 1:
        raise FormulaUnsupported("STACK_NOT_SINGLE")
    return stack[0], external


def read_workbook_sheets(archive: ZipFile, part: str) -> tuple[list[dict], FormulaContext]:
    """시트 목록(이름·숨김·관계 ID)과 수식용 외부 시트 표."""
    sheets, sup_books, xti = [], [], []
    for record_type, body in iter_records(archive.read(part)):
        cursor = _Cursor(body)
        if record_type == _BUNDLE_SHEET:
            state, tab_id = cursor.u32(), cursor.u32()
            relation_id = cursor.nullable_wide()
            name = cursor.wide()
            sheets.append({"state": {0: "visible", 1: "hidden", 2: "veryHidden"}.get(state, "visible"),
                           "sheet_id": str(tab_id), "relationship_id": relation_id, "name": name})
        elif record_type == _SUP_SELF:
            sup_books.append("self")
        elif record_type == _SUP_SAME:
            sup_books.append("same")
        elif record_type in {_SUP_BOOK_SRC, _SUP_ADDIN}:
            sup_books.append("external" if record_type == _SUP_BOOK_SRC else "addin")
        elif record_type == _EXTERN_SHEET:
            for _ in range(cursor.u32()):
                xti.append((cursor.i32(), cursor.i32(), cursor.i32()))
    return sheets, FormulaContext([sheet["name"] for sheet in sheets], xti, sup_books)


def read_shared_strings(archive: ZipFile, part: str | None) -> list[str]:
    if not part:
        return []
    strings = []
    for record_type, body in iter_records(archive.read(part)):
        if record_type == _SST_ITEM:
            cursor = _Cursor(body)
            cursor.u8()  # 서식 있는 문자열·발음 표시 여부. 본문만 보존한다.
            strings.append(cursor.wide())
    return strings


def read_styles_xlsb(archive: ZipFile, part: str | None) -> dict:
    """셀 서식(cellXfs)과 사용자 숫자 서식. xlsx `read_styles`와 같은 키를 쓴다(원본 XML은 없음)."""
    if part is None:
        return {"part": None, "custom_formats": {}, "cell_formats": [{"style_id": 0, "number_format": "General"}],
                "raw_xml": None}
    custom, formats, in_cell_xfs = {}, [], False
    for record_type, body in iter_records(archive.read(part)):
        cursor = _Cursor(body)
        if record_type == _FORMAT:
            identifier = cursor.u16()
            custom[identifier] = cursor.wide()
        elif record_type == _BEGIN_CELL_XFS:
            in_cell_xfs = True
        elif record_type == _END_CELL_XFS:
            in_cell_xfs = False
        elif record_type == _XF and in_cell_xfs:
            parent, number_id, font, fill, border = (cursor.u16() for _ in range(5))
            formats.append({
                "style_id": len(formats),
                "num_fmt_id": number_id,
                "number_format": custom.get(number_id, BUILTIN_FORMATS.get(number_id)),
                "attributes": {"numFmtId": str(number_id), "fontId": str(font), "fillId": str(fill),
                               "borderId": str(border), "xfId": str(parent)},
                "raw_xml": None,
            })
    return {"part": part, "custom_formats": custom,
            "cell_formats": formats or [{"style_id": 0, "number_format": "General"}], "raw_xml": None}


def read_comments_xlsb(archive: ZipFile, sheet_part: str) -> dict[str, list[dict]]:
    """바이너리 메모(comments*.bin)와 스레드 메모(XML)를 xlsx와 같은 모양으로 모은다."""
    result = read_comments(archive, sheet_part)  # XML 메모 파트(스레드 메모)
    for relationship in relationships(archive, sheet_part).values():
        part = relationship.get("part", "")
        if "comment" not in relationship.get("Type", "").lower() or not part.endswith(".bin"):
            continue
        authors, current, text = [], None, ""
        for record_type, body in iter_records(archive.read(part)):
            cursor = _Cursor(body)
            if record_type == _COMMENT_AUTHOR:
                authors.append(cursor.wide())
            elif record_type == _BEGIN_COMMENT:
                author = cursor.u32()
                row_first, _row_last, column_first, _column_last = (cursor.u32() for _ in range(4))
                current, text = (address(row_first, column_first), author), ""
            elif record_type == _COMMENT_TEXT:
                cursor.u8()
                text = cursor.wide()
            elif record_type == _END_COMMENT and current:
                reference, author = current
                result.setdefault(reference, []).append({
                    "part": part, "text": text,
                    "author": authors[author] if 0 <= author < len(authors) else None,
                    "attributes": {"ref": reference}, "raw_xml": None,
                })
                current = None
    return result


def _cell_value(record_type: int, cursor: _Cursor, strings: list[str]) -> tuple[object, str, str | None, str]:
    """셀 레코드의 저장값 → (값, 저장 종류, 원문, storage_type). 숫자는 문자열로 유지한다."""
    if record_type in {_CELL_BLANK, _SHORT_BLANK}:
        return None, "blank", None, "n"
    if record_type in {_CELL_RK, _SHORT_RK}:
        text = rk_text(cursor.u32())
        return text, "number", text, "n"
    if record_type in {_CELL_REAL, _SHORT_REAL, _FMLA_NUM}:
        text = float_text(cursor.f64())
        return text, "number", text, "n"
    if record_type in {_CELL_ERROR, _SHORT_ERROR, _FMLA_ERROR}:
        text = ERRORS.get(cursor.u8(), "#N/A")
        return text, "error", text, "e"
    if record_type in {_CELL_BOOL, _SHORT_BOOL, _FMLA_BOOL}:
        flag = cursor.u8() != 0
        return flag, "boolean", "1" if flag else "0", "b"
    if record_type in {_CELL_ISST, _SHORT_ISST}:
        index = cursor.u32()
        if index >= len(strings):
            raise XlsbFormatError("공유 문자열 참조 오류")
        return strings[index], "string", str(index), "s"
    if record_type in {_CELL_ST, _SHORT_ST}:
        return cursor.wide(), "string", None, "inlineStr"
    text = cursor.wide()  # _FMLA_STRING
    return text, "string", text, "str"


def _read_sheet_records(data: bytes, strings: list[str]) -> dict:
    """시트 레코드를 한 번 훑어 행·열·병합·셀·공유 수식을 모은다(수식 복원은 공유 수식이 모인 뒤에)."""
    rows, columns, merges, cells, shared, dimension = [], [], [], [], [], None
    current_row, previous_column = -1, -1
    for record_type, body in iter_records(data):
        cursor = _Cursor(body)
        if record_type == _ROW_HEADER:
            current_row, previous_column = cursor.u32(), -1
            style = cursor.u32()
            cursor.u16()
            cursor.u8()
            flags = cursor.u8()
            row = {"r": str(current_row + 1)}
            if flags & 0x10:
                row["hidden"] = "1"
            if flags & 0x40:
                row["customFormat"], row["s"] = "1", str(style)
            rows.append(row)
        elif record_type <= _SHORT_ISST and record_type != _ROW_HEADER:
            if record_type < _SHORT_BLANK:
                column = cursor.u32()
            else:
                column = previous_column + 1
            style = cursor.u32() & 0x00FFFFFF
            previous_column = column
            value, kind, raw, storage = _cell_value(record_type, cursor, strings)
            formula = None
            if _FMLA_STRING <= record_type <= _FMLA_ERROR:
                cursor.u16()  # 항상 계산·공유 여부 등 계산 플래그
                length = cursor.u32()
                formula = cursor.take(length)
            cells.append({"row": current_row, "column": column, "style": style, "value": value, "kind": kind,
                          "raw": raw, "storage": storage, "rgce": formula,
                          "blank": record_type in {_CELL_BLANK, _SHORT_BLANK}})
        elif record_type == _COL_INFO:
            first, last, width, style = cursor.u32(), cursor.u32(), cursor.u32(), cursor.u32()
            flags = cursor.u16()
            column = {"min": str(first + 1), "max": str(last + 1), "width": str(width / 256), "style": str(style)}
            if flags & 0x01:
                column["hidden"] = "1"
            columns.append(column)
        elif record_type == _WS_DIM:
            row_first, row_last, column_first, column_last = (cursor.u32() for _ in range(4))
            dimension = f"{address(row_first, column_first)}:{address(row_last, column_last)}"
        elif record_type == _MERGE_CELL:
            row_first, row_last, column_first, column_last = (cursor.u32() for _ in range(4))
            anchor = address(row_first, column_first)
            merges.append({"range": f"{anchor}:{address(row_last, column_last)}", "anchor": anchor})
        elif record_type == _SHARED_FORMULA:
            row_first, row_last, column_first, column_last = (cursor.u32() for _ in range(4))
            length = cursor.u32()
            shared.append({"bounds": (row_first, column_first, row_last, column_last),
                           "ref": f"{address(row_first, column_first)}:{address(row_last, column_last)}",
                           "rgce": cursor.take(length), "si": str(len(shared))})
    return {"rows": rows, "columns": columns, "merges": merges, "cells": cells, "shared": shared,
            "dimension": dimension}


def _shared_for(cell: dict, anchor_row: int, shared: list[dict]) -> dict | None:
    """PtgExp가 가리키는 공유 수식: 기준 행이 같고 셀을 범위에 포함하는 것."""
    for entry in shared:
        row_first, column_first, row_last, column_last = entry["bounds"]
        if (row_first == anchor_row and row_first <= cell["row"] <= row_last
                and column_first <= cell["column"] <= column_last):
            return entry
    return None


def _formula(cell: dict, sheet: str, shared: list[dict], context: FormulaContext,
             diagnostics: list[dict]) -> dict | None:
    """xlsx `_formula`와 같은 모양의 수식 정보. 공유 수식은 기준 셀에만 문장을 두고 나머지는 기준을 가리킨다."""
    rgce = cell["rgce"]
    if rgce is None:
        return None
    status = "error" if cell["kind"] == "error" else "missing" if cell["value"] is None else "stored_unverified"
    here = address(cell["row"], cell["column"])
    text, attributes, base, external = None, {}, None, False
    try:
        if rgce[:1] == b"\x01" and len(rgce) == 5:
            anchor_row = struct.unpack("<I", rgce[1:5])[0]
            entry = _shared_for(cell, anchor_row, shared)
            if entry is None:
                raise FormulaUnsupported("SHARED_FORMULA_NOT_FOUND")
            row_first, column_first = entry["bounds"][0], entry["bounds"][1]
            entry.setdefault("text", decompile(entry["rgce"], row_first, column_first, context, shared=True))
            base_text, external = entry["text"]
            base_attributes = {"t": "shared", "ref": entry["ref"], "si": entry["si"]}
            if (cell["row"], cell["column"]) == (row_first, column_first):
                text, attributes = base_text, base_attributes
            else:
                attributes = {"t": "shared", "si": entry["si"]}
                base = {"address": address(row_first, column_first), "raw_text": base_text,
                        "attributes": base_attributes}
        else:
            text, external = decompile(rgce, cell["row"], cell["column"], context)
    except (FormulaUnsupported, XlsbFormatError) as error:
        diagnostics.append({"code": "XLSB_FORMULA_UNRESOLVED", "severity": "warning", "sheet": sheet, "cell": here,
                            "reason": str(error),
                            "message": "수식을 문장으로 복원하지 못해 저장된 계산값만 보존했습니다."})
        text, attributes, base = None, {"unresolved": "1"}, None
    return {"text": "=" + text if text else None, "raw_text": text, "attributes": attributes, "shared_base": base,
            "cached_status": status, "external_reference": external, "recalculated": False}


def _blank_span(spans: list[dict], row: int, column: int, attributes: dict) -> None:
    if spans and spans[-1]["row"] == row and spans[-1]["end_column"] + 1 == column \
            and spans[-1]["attributes"] == attributes:
        spans[-1]["end_column"] = column
    else:
        spans.append({"row": row, "start_column": column, "end_column": column, "attributes": attributes})


def _extract_sheet(archive: ZipFile, info: dict, file: dict, strings: list[str], styles: dict,
                   context: FormulaContext, diagnostics: list[dict]) -> dict:
    raw = _read_sheet_records(archive.read(info["part"]), strings)
    comments = read_comments_xlsb(archive, info["part"])
    merge_bounds = []
    for merge in raw["merges"]:
        first, last = merge["range"].split(":")
        merge_bounds.append((first, last, merge))
    sheet = {
        **{key: info[key] for key in ("sheet_id", "name", "state", "part", "relationship_type")},
        "dimension": raw["dimension"], "rows": raw["rows"], "columns": raw["columns"], "merges": raw["merges"],
        "blank_spans": [], "cells": [], "conditional_formatting": [],
        "relationships": relationships(archive, info["part"]), "structure_xml": [],
        "counts": {"content_cells": 0, "xml_cells": 0, "blank_cells": 0, "formulas": 0},
    }
    hidden_rows = {int(row["r"]) - 1 for row in raw["rows"] if row.get("hidden")}
    column_ranges = [(int(column["min"]) - 1, int(column["max"]) - 1, column) for column in raw["columns"]]
    merge_index = [((_position(first)), (_position(last)), merge) for first, last, merge in merge_bounds]
    for item in raw["cells"]:
        row, column = item["row"], item["column"]
        here = address(row, column)
        sheet["counts"]["xml_cells"] += 1
        style_attributes = {"s": str(item["style"])} if item["style"] else {}
        if item["blank"] and here not in comments:
            _blank_span(sheet["blank_spans"], row + 1, column + 1, style_attributes)
            sheet["counts"]["blank_cells"] += 1
            continue
        style = styles["cell_formats"][item["style"]] if item["style"] < len(styles["cell_formats"]) else {}
        number_format = style.get("number_format")
        kind = "date" if item["kind"] == "number" and is_date_format(number_format) else item["kind"]
        formula = _formula(item, info["name"], raw["shared"], context, diagnostics)
        column_info = next((entry for first, last, entry in column_ranges if first <= column <= last), {})
        merge = next((entry for (r1, c1), (r2, c2), entry in merge_index
                      if r1 <= row <= r2 and c1 <= column <= c2), None)
        has_content = not item["blank"]
        attributes = {"r": here, **style_attributes}
        if item["storage"] != "n":
            attributes["t"] = item["storage"]
        sheet["cells"].append({
            "record_id": f"{file['source_id']}/{info['sheet_id']}/{here}",
            "file_id": file["file_id"], "sheet_id": info["sheet_id"], "sheet": info["name"],
            "address": here, "row": row + 1, "column": column_name(column), "column_index": column + 1,
            "value": item["value"], "data_type": kind, "raw_lexeme": item["raw"], "storage_type": item["storage"],
            "formula": formula, "cached_value": item["value"] if formula is not None else None,
            "style_id": item["style"], "number_format": number_format,
            "row_hidden": row in hidden_rows, "column_hidden": column_info.get("hidden") == "1",
            "sheet_hidden": info["state"] != "visible", "merge": merge, "comments": comments.get(here, []),
            "has_content": has_content, "xml_attributes": attributes,
        })
        sheet["counts"]["content_cells"] += int(has_content)
        sheet["counts"]["formulas"] += int(formula is not None)
    return sheet


def _position(reference: str) -> tuple[int, int]:
    """A1 → 0부터 시작하는 (행, 열)."""
    index = 0
    while index < len(reference) and reference[index].isalpha():
        index += 1
    column = 0
    for char in reference[:index].upper():
        column = column * 26 + ord(char) - 64
    return int(reference[index:]) - 1, column - 1


def extract_xlsb(archive: ZipFile, file: dict) -> dict:
    """열린 xlsb 패키지를 xlsx 추출과 같은 구조로 읽는다.

    Args:
        archive: 읽기 전용 ZIP 패키지.
        file: 호출자가 만든 파일 해시·표시 경로·크기 정보.
    Returns:
        file, sheets, styles, package_metadata, diagnostics. xlsb에는 원본 XML이 없으므로
        workbook_xml·shared_strings_xml·스타일·셀 XML 원문 필드는 비어 있다.
    Raises:
        XlsbFormatError: 레코드 구조가 손상된 경우.
        KeyError: 필수 파트가 없는 경우.
    Side Effects:
        패키지를 읽기만 한다. 수식 계산·파일 생성은 하지 않는다.
    """
    book_part = workbook_part(archive)
    rels = relationships(archive, book_part)
    root_rels = relationships(archive, "")

    def related(kind: str) -> str | None:
        return next((entry.get("part") for entry in rels.values() if entry.get("Type", "").endswith(kind)), None)

    strings_part, styles_part = related("/sharedStrings"), related("/styles")
    sheets_info, context = read_workbook_sheets(archive, book_part)
    strings = read_shared_strings(archive, strings_part)
    styles = read_styles_xlsb(archive, styles_part)
    diagnostics: list[dict] = [{
        "code": "XLSB_SOURCE", "severity": "info",
        "message": "xlsb(바이너리) 원본을 읽었습니다. XML 원문 필드(workbook_xml, raw_xml 등)는 비어 있습니다.",
    }]
    result = {
        "file": file, "sheets": [], "styles": styles,
        "package_metadata": {
            "format": "xlsb", "workbook_part": book_part, "workbook_xml": None, "relationships": rels,
            "root_relationships": root_rels, "shared_strings_part": strings_part, "shared_strings_xml": None,
            "parts": [{"name": item.filename, "size_bytes": item.file_size, "crc": item.CRC}
                      for item in archive.infolist()],
            "supplementary_xml": {
                name: archive.read(name).decode("utf-8-sig") for name in archive.namelist()
                if name.endswith(".xml") and any(part in name for part in (
                    "/externalLinks/", "/drawings/", "/theme/", "/persons/", "docProps/"))
            },
        },
        "diagnostics": diagnostics,
    }
    for sheet in sheets_info:
        relation = rels.get(sheet["relationship_id"] or "", {})
        info = {"sheet_id": sheet["sheet_id"], "name": sheet["name"], "state": sheet["state"],
                "part": relation.get("part"), "relationship_type": relation.get("Type", "")}
        if not info["part"] or not info["relationship_type"].endswith("/worksheet"):
            result["sheets"].append({**info, "cells": [], "raw_xml": None,
                                     "counts": {"content_cells": 0, "xml_cells": 0, "blank_cells": 0, "formulas": 0}})
            diagnostics.append({"code": "NON_WORKSHEET_PART", "sheet": info["name"],
                                "message": "워크시트가 아닌 시트는 셀 없이 시트 정보만 보존했습니다."})
            continue
        result["sheets"].append(_extract_sheet(archive, info, file, strings, styles, context, diagnostics))
    return result


def content_type_of(archive: ZipFile, part: str) -> str | None:
    """[Content_Types].xml에서 파트의 내용 형식(Override 우선, 없으면 확장자 Default)."""
    root = ET.fromstring(archive.read("[Content_Types].xml"))
    extension = posixpath.splitext(part)[1].lstrip(".").lower()
    default = None
    for node in root:
        name = local_name(node.tag)
        if name == "Override" and node.get("PartName", "").lstrip("/") == part:
            return node.get("ContentType")
        if name == "Default" and node.get("Extension", "").lower() == extension:
            default = node.get("ContentType")
    return default
