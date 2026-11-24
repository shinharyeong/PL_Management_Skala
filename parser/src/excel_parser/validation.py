"""생산 파서와 독립적으로 OOXML 원본 및 기존 마스터를 대조한다.

검증 근거는 ZIP 안의 XML에서 직접 읽는다. 원본을 수정하거나 검증 파일을
저장하지 않으며, 호출자가 반환된 검증 결과의 저장 여부를 결정한다.
"""

import posixpath
import re
import xml.etree.ElementTree as ET
from collections import Counter
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any
from zipfile import ZipFile

_NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
_REL_ID = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id"
_SUMIFS = re.compile(
    r"^SUMIFS\((?P<sheet>[^!]+)!\$(?P<value_col>[A-Z]+)\$(?P<start>\d+):"
    r"\$(?P=value_col)\$(?P<end>\d+),(?P=sheet)!\$(?P<code_col>[A-Z]+)"
    r"\$(?P=start):\$(?P=code_col)\$(?P=end),\$(?P<criterion>[A-Z]+)"
    r"(?P<criterion_row>\d+)\)/(?P<divisor>\d+(?:\.\d+)?)$"
)


def _index_sheets(archive: ZipFile) -> list[dict[str, str]]:
    """통합문서 관계를 직접 읽어 모든 시트의 이름과 XML 위치를 얻는다.

    Args:
        archive: 읽기용으로 열린 OOXML ZIP.
    Returns:
        문서 순서의 name, state, part 사전 목록.
    Raises:
        KeyError: 필수 ZIP 부분이나 관계가 없는 경우.
        ET.ParseError: 원본 XML이 손상된 경우.
    Side Effects:
        통합문서와 관계 XML만 읽는다. 파일을 생성하지 않는다.
    """
    relations = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
    targets = {item.attrib["Id"]: item.attrib["Target"] for item in relations}
    workbook = ET.fromstring(archive.read("xl/workbook.xml"))
    result = []
    for sheet in workbook.findall(f"{_NS}sheets/{_NS}sheet"):
        target = targets[sheet.attrib[_REL_ID]]
        part = (
            target.lstrip("/")
            if target.startswith("/")
            else posixpath.normpath(posixpath.join("xl", target))
        )
        result.append(
            {
                "name": sheet.attrib["name"],
                "state": sheet.attrib.get("state", "visible"),
                "part": part,
            }
        )
    return result


def _read_strings(archive: ZipFile) -> list[str]:
    """공유 문자열의 실제 텍스트를 원본 순서대로 읽는다.

    Args:
        archive: 읽기용 OOXML ZIP.
    Returns:
        공유 문자열 목록. 부분이 없으면 빈 목록.
    Raises:
        ET.ParseError: 문자열 XML이 손상된 경우.
    Side Effects:
        공유 문자열 XML을 메모리로 읽으며 쓰지 않는다.
    """
    if "xl/sharedStrings.xml" not in archive.namelist():
        return []
    root = ET.fromstring(archive.read("xl/sharedStrings.xml"))
    return [
        "".join(
            item.text or ""
            for item in [*entry.findall(f"{_NS}t"), *entry.findall(f"{_NS}r/{_NS}t")]
        )
        for entry in root
    ]


def _decode_cell(cell: ET.Element, strings: list[str]) -> dict[str, Any]:
    """개별 원본 셀의 저장값·수식·공유 문자열을 독립 해석한다.

    Args:
        cell: 원본 XML의 c 요소.
        strings: 원본 공유 문자열 목록.
    Returns:
        비교에 필요한 원본 값과 수식·속성의 사전.
    Raises:
        IndexError: 공유 문자열 번호가 유효하지 않은 경우.
        ValueError: 공유 문자열 번호를 정수로 읽을 수 없는 경우.
    Side Effects:
        없음. 제공된 XML 요소만 조회한다.
    """
    value_element = cell.find(f"{_NS}v")
    formula_element = cell.find(f"{_NS}f")
    inline = cell.find(f"{_NS}is")
    lexical = value_element.text if value_element is not None else None
    storage_type = cell.attrib.get("t", "n")
    value: Any = lexical
    if storage_type == "s" and lexical is not None:
        value = strings[int(lexical)]
    elif storage_type == "inlineStr":
        value = "".join(
            t.text or ""
            for t in [
                *cell.findall(f"{_NS}is/{_NS}t"),
                *cell.findall(f"{_NS}is/{_NS}r/{_NS}t"),
            ]
        )
    elif storage_type == "str" and lexical is None:
        value = ""
    elif storage_type == "b" and lexical is not None:
        value = lexical == "1"
    return {
        "value": value,
        "raw_lexeme": lexical,
        "storage_type": storage_type,
        "style_id": cell.attrib.get("s", "0"),
        "formula_text": formula_element.text if formula_element is not None else None,
        "formula_attributes": dict(formula_element.attrib)
        if formula_element is not None
        else None,
        "has_formula": formula_element is not None,
        "has_payload": value_element is not None
        or formula_element is not None
        or inline is not None,
        "has_content": formula_element is not None
        or lexical is not None
        or (inline is not None and bool(value)),
    }


def _scan_sheet(archive: ZipFile, part: str, strings: list[str]) -> dict[str, Any]:
    """한 시트 XML을 순회해 주소·값·행열·병합의 독립 근거를 만든다.

    Args:
        archive: 읽기용 OOXML ZIP.
        part: 검사할 시트 XML 부분 경로.
        strings: 공유 문자열 목록.
    Returns:
        전체 XML 주소 집합, 값 셀, 행열 속성, 병합 범위와 일반·스레드 메모 목록.
    Raises:
        KeyError: 지정된 ZIP 부분이 없는 경우.
        ET.ParseError: 원본 XML이 손상된 경우.
    Side Effects:
        시트와 연결된 메모 XML을 읽으며 원본은 수정하지 않는다.
    """
    result: dict[str, Any] = {
        "addresses": set(),
        "cells": {},
        "rows": [],
        "columns": [],
        "merges": [],
        "comments": {},
        "formula_count": 0,
        "content_count": 0,
        "default_hidden": False,
    }
    with archive.open(part) as stream:
        for event, element in ET.iterparse(stream, events=("start", "end")):
            if event == "start" and element.tag == f"{_NS}row":
                result["rows"].append(dict(element.attrib))
            elif event == "start" and element.tag == f"{_NS}sheetFormatPr":
                result["default_hidden"] = element.get("zeroHeight") in {"1", "true"}
            elif event == "start" and element.tag == f"{_NS}col":
                result["columns"].append(dict(element.attrib))
            elif event == "start" and element.tag == f"{_NS}mergeCell":
                result["merges"].append(element.attrib["ref"])
            elif event == "end" and element.tag == f"{_NS}c":
                address = element.attrib["r"]
                result["addresses"].add(address)
                # c@s가 없으면 OOXML 기본값 0(_decode_cell). 행·열 서식으로 대체하지 않는다.
                decoded = _decode_cell(element, strings)
                if decoded["has_payload"]:
                    result["cells"][address] = decoded
                result["formula_count"] += decoded["has_formula"]
                result["content_count"] += decoded["has_content"]
                element.clear()
            elif event == "end" and element.tag == f"{_NS}row":
                element.clear()
    folder, filename = posixpath.split(part)
    relationships = posixpath.join(folder, "_rels", filename + ".rels")
    if relationships in archive.namelist():
        rel_root = ET.fromstring(archive.read(relationships))
        for relation in rel_root:
            relation_type = relation.attrib.get("Type", "").lower()
            if (not relation_type.endswith(("/comments", "/threadedcomment"))
                    or relation.attrib.get("TargetMode") == "External"):
                continue
            target = relation.attrib["Target"]
            comment_part = (
                target.lstrip("/")
                if target.startswith("/")
                else posixpath.normpath(posixpath.join(folder, target))
            )
            comment_root = ET.fromstring(archive.read(comment_part))
            for comment in comment_root.iter():
                if comment.tag.rsplit("}", 1)[-1] not in {"comment", "threadedComment"}:
                    continue
                text_node = comment.find("{*}text")
                text = "" if text_node is None else (
                    "".join(t.text or "" for t in text_node.iter()
                            if t.tag.rsplit("}", 1)[-1] == "t")
                    if len(text_node) else text_node.text or ""
                )
                result["comments"].setdefault(comment.attrib["ref"], []).append(text)
    return result


def _column_label(index: int) -> str:
    """1부터 시작하는 열 번호를 A1 표기의 열 이름으로 변환한다.

    Args:
        index: 양의 정수 열 번호.
    Returns:
        A, Z, AA 등의 열 이름.
    Raises:
        ValueError: 열 번호가 1보다 작은 경우.
    Side Effects:
        없음. 메모리에서만 처리한다.
    """
    if index < 1:
        raise ValueError("열 번호는 1 이상이어야 합니다.")
    letters = []
    while index:
        index, remainder = divmod(index - 1, 26)
        letters.append(chr(65 + remainder))
    return "".join(reversed(letters))


def _preserved_addresses(sheet: dict[str, Any]) -> set[str]:
    """추출 셀과 압축 빈 구간에서 보존된 주소를 복원한다.

    Args:
        sheet: 생산 파서의 한 시트 결과.
    Returns:
        명시적으로 보존된 모든 셀 주소 집합.
    Raises:
        KeyError: 필수 주소나 빈 구간 필드가 누락된 경우.
        ValueError: 잘못된 열 번호가 전달된 경우.
    Side Effects:
        없음. 메모리에서 주소 집합만 만든다.
    """
    addresses = {cell["address"] for cell in sheet["cells"]}
    for span in sheet.get("blank_spans", []):
        for column in range(span["start_column"], span["end_column"] + 1):
            addresses.add(f"{_column_label(column)}{span['row']}")
    return addresses


def _cell_issues(expected: dict[str, Any], actual: dict[str, Any]) -> list[str]:
    """원본 셀과 추출 셀의 값·수식·스타일 불일치를 분류한다.

    Args:
        expected: 독립 XML 점검으로 읽은 셀.
        actual: 생산 파서에서 반환한 셀.
    Returns:
        불일치한 필드명의 목록. 일치하면 빈 목록.
    Raises:
        없음. 누락 필드는 불일치로 처리한다.
    Side Effects:
        없음. 원본과 결과 사전을 변경하지 않는다.
    """
    issues = []
    for field in ("value", "raw_lexeme"):
        if actual.get(field) != expected[field]:
            issues.append(field)
    if str(actual.get("style_id", "0")) != expected["style_id"]:
        issues.append("style_id")
    formula = actual.get("formula")
    if expected["has_formula"]:
        if not isinstance(formula, dict):
            issues.append("formula_missing")
        else:
            if formula.get("raw_text") != expected["formula_text"]:
                issues.append("formula.raw_text")
            if formula.get("attributes") != expected["formula_attributes"]:
                issues.append("formula.attributes")
            if actual.get("cached_value") != expected["value"]:
                issues.append("cached_value")
    elif formula is not None:
        issues.append("unexpected_formula")
    return issues


_XLSB_ERRORS = {0x00: "#NULL!", 0x07: "#DIV/0!", 0x0F: "#VALUE!", 0x17: "#REF!", 0x1D: "#NAME?",
                0x24: "#NUM!", 0x2A: "#N/A", 0x2B: "#GETTING_DATA"}


def _xlsb_records(data: bytes):
    """추출기와 별개로 BIFF12 레코드를 다시 읽는다(종류 1~2바이트, 길이 1~4바이트 7비트 가변)."""
    position = 0
    while position < len(data):
        kind = data[position]
        position += 1
        if kind & 0x80:
            kind = (kind & 0x7F) | ((data[position] & 0x7F) << 7)
            position += 1
        size = shift = 0
        while True:
            byte = data[position]
            position += 1
            size |= (byte & 0x7F) << shift
            shift += 7
            if not byte & 0x80 or shift >= 28:
                break
        yield kind, data[position:position + size]
        position += size


def _xlsb_wide(body: bytes, offset: int) -> tuple[str, int]:
    count = int.from_bytes(body[offset:offset + 4], "little")
    start = offset + 4
    return body[start:start + count * 2].decode("utf-16-le"), start + count * 2


def _xlsb_number(raw: bytes, rk: bool) -> Decimal:
    """RK·배정밀도 저장값을 비교용 Decimal로."""
    import struct

    if not rk:
        return Decimal(repr(struct.unpack("<d", raw)[0]))
    value = int.from_bytes(raw, "little")
    if value & 0x02:
        number = Decimal((value >> 2) - (1 << 30) if value & 0x80000000 else value >> 2)
    else:
        number = Decimal(repr(struct.unpack("<d", ((value & 0xFFFFFFFC) << 32).to_bytes(8, "little"))[0]))
    return number.scaleb(-2) if value & 0x01 else number


def _validate_xlsb(path: Any, workbook: dict[str, Any]) -> dict[str, Any]:
    """xlsb 원본 레코드와 추출 결과의 시트·셀 주소·저장값·수식 유무를 독립 비교한다.

    Args:
        path: 읽기 전용 원본 xlsb 경로.
        workbook: extract_workbook이 반환한 통합문서 결과.
    Returns:
        validate_extraction과 같은 모양의 보고서(source_format=xlsb).
    Raises:
        OSError, KeyError: 원본을 읽을 수 없거나 필수 파트가 없는 경우.
    Side Effects:
        원본 ZIP을 다시 읽기만 한다. 생산 파서의 xlsb 모듈을 쓰지 않는다.
    """
    report: dict[str, Any] = {"passed": True, "source_file": str(path), "source_format": "xlsb",
                              "sheet_count": 0, "source_xml_cells": 0, "source_content_cells": 0,
                              "source_formula_cells": 0, "preserved_cells": 0, "issues": [], "sheets": []}
    actual_sheets = {sheet["name"]: sheet for sheet in workbook["sheets"]}
    with ZipFile(path) as archive:
        root = ET.fromstring(archive.read("_rels/.rels"))
        book = next(node.get("Target").lstrip("/") for node in root
                    if node.get("Type", "").endswith("/officeDocument"))
        folder = posixpath.dirname(book)
        rels_part = posixpath.join(folder, "_rels", posixpath.basename(book) + ".rels")
        targets = {node.get("Id"): posixpath.normpath(posixpath.join(folder, node.get("Target")))
                   for node in ET.fromstring(archive.read(rels_part))}
        strings_part = next((targets[node.get("Id")] for node in ET.fromstring(archive.read(rels_part))
                             if node.get("Type", "").endswith("/sharedStrings")), None)
        strings = []
        if strings_part:
            for kind, body in _xlsb_records(archive.read(strings_part)):
                if kind == 0x13:
                    strings.append(_xlsb_wide(body, 1)[0])
        expected = []
        for kind, body in _xlsb_records(archive.read(book)):
            if kind == 0x9C:
                relation_count = int.from_bytes(body[8:12], "little")
                offset = 12 + (0 if relation_count == 0xFFFFFFFF else relation_count * 2)
                relation_id = None if relation_count == 0xFFFFFFFF else body[12:offset].decode("utf-16-le")
                name, _ = _xlsb_wide(body, offset)
                expected.append({"name": name, "part": targets.get(relation_id)})
        if [sheet["name"] for sheet in workbook["sheets"]] != [sheet["name"] for sheet in expected]:
            report["issues"].append({"code": "SHEET_LIST_MISMATCH"})
        for entry in expected:
            summary = {"sheet": entry["name"], "source_xml_cells": 0, "source_content_cells": 0,
                       "source_formula_cells": 0, "preserved_cells": 0, "passed": True}
            actual = actual_sheets.get(entry["name"])
            issues = []
            if actual is None:
                issues.append({"code": "MISSING_SHEET"})
            elif entry["part"] and entry["part"].endswith(".bin") and entry["part"] in archive.namelist():
                cells = {cell["address"]: cell for cell in actual["cells"]}
                preserved = _preserved_addresses(actual)
                addresses, row, previous = set(), -1, -1
                for kind, body in _xlsb_records(archive.read(entry["part"])):
                    if kind == 0x00:
                        row, previous = int.from_bytes(body[:4], "little"), -1
                        continue
                    if not 1 <= kind <= 18:
                        continue
                    short = kind >= 12
                    column = previous + 1 if short else int.from_bytes(body[:4], "little")
                    previous = column
                    offset = 4 if short else 8
                    base_kind = kind - 11 if short else kind
                    address = f"{_column_label(column + 1)}{row + 1}"
                    addresses.add(address)
                    summary["source_xml_cells"] += 1
                    if base_kind == 1:
                        continue
                    summary["source_content_cells"] += 1
                    cell = cells.get(address)
                    if cell is None:
                        issues.append({"code": "PAYLOAD_NOT_PRESERVED", "cell": address})
                        continue
                    if 8 <= base_kind <= 11:
                        summary["source_formula_cells"] += 1
                        if cell.get("formula") is None:
                            issues.append({"code": "CELL_MISMATCH", "cell": address, "fields": ["formula"]})
                    if base_kind in {2, 5, 9}:
                        width = 4 if base_kind == 2 else 8
                        try:
                            same = Decimal(str(cell["value"])) == _xlsb_number(body[offset:offset + width],
                                                                              base_kind == 2)
                        except (InvalidOperation, TypeError):
                            same = False
                    elif base_kind == 7:
                        index = int.from_bytes(body[offset:offset + 4], "little")
                        same = index < len(strings) and cell["value"] == strings[index]
                    elif base_kind in {6, 8}:
                        same = cell["value"] == _xlsb_wide(body, offset)[0]
                    elif base_kind in {4, 10}:
                        same = cell["value"] == (body[offset] != 0)
                    else:
                        same = cell["value"] == _XLSB_ERRORS.get(body[offset], "#N/A")
                    if not same:
                        issues.append({"code": "CELL_MISMATCH", "cell": address, "fields": ["value"]})
                summary["preserved_cells"] = len(preserved)
                missing, unexpected = sorted(addresses - preserved), sorted(preserved - addresses)
                if missing:
                    issues.append({"code": "MISSING_CELLS", "count": len(missing), "cells": missing})
                if unexpected:
                    issues.append({"code": "UNEXPECTED_CELLS", "count": len(unexpected), "cells": unexpected})
            summary["passed"] = not issues
            for key in ("source_xml_cells", "source_content_cells", "source_formula_cells", "preserved_cells"):
                report[key] += summary[key]
            report["sheet_count"] += 1
            report["sheets"].append(summary)
            report["issues"].extend({**issue, "sheet": entry["name"]} for issue in issues)
    report["passed"] = not report["issues"]
    return report


def validate_extraction(path: Path, workbook: dict[str, Any]) -> dict[str, Any]:
    """원본 OOXML과 추출 결과의 전체 시트·셀·구조를 독립 비교한다.

    Args:
        path: 읽기 전용 원본 xlsx 경로.
        workbook: extract_workbook이 반환한 통합문서 결과.
    Returns:
        passed, 전체 개수, 시트별 개수 및 위치가 포함된 issues 사전.
    Raises:
        OSError: 원본을 읽을 수 없는 경우.
        KeyError: 필수 OOXML 부분이 없는 경우.
        ET.ParseError: 원본 XML이 손상된 경우.
    Side Effects:
        원본 ZIP을 다시 읽는다. 생산 파서의 reader나 결과값을 정답으로 쓰지
        않으며 파일을 수정·생성하지 않는다.
    """
    report: dict[str, Any] = {
        "passed": True,
        "source_file": str(path),
        "sheet_count": 0,
        "source_xml_cells": 0,
        "source_content_cells": 0,
        "source_formula_cells": 0,
        "preserved_cells": 0,
        "issues": [],
        "sheets": [],
    }
    if (workbook.get("package_metadata") or {}).get("format") == "xlsb":
        return _validate_xlsb(path, workbook)
    actual_sheets = {sheet["name"]: sheet for sheet in workbook["sheets"]}
    with ZipFile(path) as archive:
        expected_sheets = _index_sheets(archive)
        strings = _read_strings(archive)
        if [sheet["name"] for sheet in workbook["sheets"]] != [
            sheet["name"] for sheet in expected_sheets
        ]:
            report["issues"].append({"code": "SHEET_LIST_MISMATCH"})
        for entry in expected_sheets:
            source = _scan_sheet(archive, entry["part"], strings)
            actual = actual_sheets.get(entry["name"])
            summary = {
                "sheet": entry["name"],
                "source_xml_cells": len(source["addresses"]),
                "source_content_cells": source["content_count"],
                "source_formula_cells": source["formula_count"],
                "preserved_cells": 0,
                "passed": True,
            }
            sheet_issues = []
            if actual is None:
                sheet_issues.append({"code": "MISSING_SHEET"})
            else:
                actual_cells = {cell["address"]: cell for cell in actual["cells"]}
                preserved = _preserved_addresses(actual)
                summary["preserved_cells"] = len(preserved)
                expected_addresses = source["addresses"] | set(source["comments"])
                missing = sorted(expected_addresses - preserved)
                unexpected = sorted(preserved - expected_addresses)
                if missing:
                    sheet_issues.append(
                        {
                            "code": "MISSING_CELLS",
                            "count": len(missing),
                            "cells": missing,
                        }
                    )
                if unexpected:
                    sheet_issues.append(
                        {
                            "code": "UNEXPECTED_CELLS",
                            "count": len(unexpected),
                            "cells": unexpected,
                        }
                    )
                for address, cell in source["cells"].items():
                    if address not in actual_cells:
                        sheet_issues.append(
                            {"code": "PAYLOAD_NOT_PRESERVED", "cell": address}
                        )
                        continue
                    mismatches = _cell_issues(cell, actual_cells[address])
                    if mismatches:
                        sheet_issues.append(
                            {
                                "code": "CELL_MISMATCH",
                                "cell": address,
                                "fields": mismatches,
                            }
                        )
                for address, texts in source["comments"].items():
                    comments = actual_cells.get(address, {}).get("comments", [])
                    actual_texts = [comment.get("text") for comment in comments]
                    if actual_texts != texts:
                        sheet_issues.append(
                            {"code": "COMMENT_MISMATCH", "cell": address}
                        )
                for field in ("rows", "columns"):
                    if actual.get(field) != source[field]:
                        sheet_issues.append(
                            {"code": "STRUCTURE_MISMATCH", "field": field}
                        )
                if actual.get("state", "visible") != entry["state"]:
                    sheet_issues.append({"code": "SHEET_STATE_MISMATCH"})
                if [merge["range"] for merge in actual.get("merges", [])] != source[
                    "merges"
                ]:
                    sheet_issues.append({"code": "MERGE_MISMATCH"})
                if len(actual_cells) != len(actual["cells"]):
                    sheet_issues.append({"code": "DUPLICATE_EXTRACTED_ADDRESS"})
            for issue in sheet_issues:
                report["issues"].append({"sheet": entry["name"], **issue})
            summary["passed"] = not sheet_issues
            report["sheets"].append(summary)
            for counter in (
                "source_xml_cells",
                "source_content_cells",
                "source_formula_cells",
                "preserved_cells",
            ):
                report[counter] += summary[counter]
        report["sheet_count"] = len(expected_sheets)
    report["passed"] = not report["issues"]
    return report


def _read_numeric_sources(path: Path, required: set[str]) -> dict[str, dict[str, Any]]:
    """마스터 비교에 필요한 원본 시트를 생산 파서 없이 읽는다.

    Args:
        path: 원본 xlsx 경로.
        required: 비교에 필요한 시트명 집합.
    Returns:
        시트명별 주소·원본 셀 사전.
    Raises:
        OSError: 원본을 읽을 수 없는 경우.
        ET.ParseError: 원본 XML이 손상된 경우.
    Side Effects:
        지정 원본의 필요한 시트만 읽으며 산출물을 만들지 않는다.
    """
    result = {}
    with ZipFile(path) as archive:
        strings = _read_strings(archive)
        for sheet in _index_sheets(archive):
            if sheet["name"] in required:
                result[sheet["name"]] = _scan_sheet(archive, sheet["part"], strings)[
                    "cells"
                ]
    return result


def validate_provenance(
    workbook: dict[str, Any], mapped: dict[str, Any]
) -> dict[str, Any]:
    """표준 레코드가 같은 원본의 숫자·수식과 정확히 연결되는지 확인한다.

    Args:
        workbook: 독립 원본 대조를 수행한 추출 결과.
        mapped: map_workbook의 표준 레코드와 GAP 관계 결과.
    Returns:
        passed, record_count, required_candidate_count, 참조 검사 수와 issues.
        숫자 및 모든 자료형의 수식은 필수 후보이며, 일반 날짜·불리언은
        필수 금액 후보로 간주하지 않는다.
    Raises:
        KeyError: 원본 계약의 record_id 등 필수 필드가 없는 경우.
    Side Effects:
        없음. 메모리에서 전체 후보와 출처·원문·보조 근거를 비교한다.
    """
    raw_cells = {
        cell["record_id"]: cell
        for sheet in workbook["sheets"]
        for cell in sheet["cells"]
    }
    required = {
        identifier
        for identifier, cell in raw_cells.items()
        if cell.get("has_content", True)
        and (cell.get("data_type") == "number" or cell.get("formula") is not None)
    }
    file = workbook["file"]
    records = mapped.get("records", [])
    issues = []
    referenced = Counter()
    standard_ids = Counter()
    reference_count = 0
    for record in records:
        identifier = record.get("record_id")
        standard_ids[identifier] += 1
        source = record.get("source", {})
        raw_id = source.get("raw_record_id")
        referenced[raw_id] += 1
        original = raw_cells.get(raw_id)
        if original is None:
            issues.append(
                {
                    "code": "UNKNOWN_RAW_REFERENCE",
                    "record_id": identifier,
                    "raw_record_id": raw_id,
                }
            )
            continue
        mismatches = []
        expected_source = {
            "file_id": file["file_id"],
            "source_id": file.get("source_id"),
            "path": file["path"],
            "sheet": original["sheet"],
            "cell": original["address"],
        }
        for field, expected in expected_source.items():
            if source.get(field) != expected:
                mismatches.append("source." + field)
        if identifier != "standard:" + raw_id:
            mismatches.append("record_id")
        for field, expected in (
            ("raw_value", original.get("value")),
            ("data_type", original.get("data_type")),
            ("formula", original.get("formula")),
        ):
            actual = record.get(field)
            if actual != expected or type(actual) is not type(expected):
                mismatches.append(field)
        original_value = record.get("value", {}).get("original_value")
        if original_value != original.get("value") or type(original_value) is not type(
            original.get("value")
        ):
            mismatches.append("value.original_value")
        if mismatches:
            issues.append(
                {
                    "code": "PROVENANCE_VALUE_MISMATCH",
                    "record_id": identifier,
                    "fields": mismatches,
                }
            )
        evidence = [("style_reference", record.get("style_reference", {}))]
        for section in ("organization", "item", "period"):
            evidence.extend(
                (section, ref) for ref in record.get(section, {}).get("provenance", [])
            )
        evidence.extend(
            ("unit_provenance", ref) for ref in record.get("unit_provenance", [])
        )
        evidence.extend(
            ("dimensions." + name, dimension.get("source", {}))
            for name, dimension in record.get("dimensions", {}).items()
        )
        for section, reference in evidence:
            if not reference or "raw_record_id" not in reference:
                continue
            reference_count += 1
            linked = raw_cells.get(reference["raw_record_id"])
            if linked is None:
                issues.append(
                    {
                        "code": "UNKNOWN_EVIDENCE_REFERENCE",
                        "record_id": identifier,
                        "section": section,
                        "raw_record_id": reference["raw_record_id"],
                    }
                )
                continue
            if any(
                reference[field] != linked[target]
                for field, target in (("sheet", "sheet"), ("cell", "address"))
                if field in reference
            ):
                issues.append(
                    {
                        "code": "EVIDENCE_LOCATION_MISMATCH",
                        "record_id": identifier,
                        "section": section,
                    }
                )
    missing = sorted(required - set(referenced))
    if missing:
        issues.append(
            {
                "code": "UNMAPPED_NUMERIC_OR_FORMULA_SOURCE",
                "count": len(missing),
                "raw_record_ids": missing,
            }
        )
    duplicate_sources = [
        identifier for identifier, count in referenced.items() if count > 1
    ]
    if duplicate_sources:
        issues.append(
            {"code": "DUPLICATE_STANDARD_SOURCE", "raw_record_ids": duplicate_sources}
        )
    duplicate_ids = [
        identifier for identifier, count in standard_ids.items() if count > 1
    ]
    if duplicate_ids:
        issues.append(
            {"code": "DUPLICATE_STANDARD_RECORD_ID", "record_ids": duplicate_ids}
        )
    for relationship in mapped.get("gap_relationships", []):
        identifiers = [
            *relationship.get("preferred_record_ids", []),
            *(
                identifier
                for values in relationship.get("metric_records", {}).values()
                for identifier in values
            ),
        ]
        for identifier in identifiers:
            if identifier not in standard_ids:
                issues.append(
                    {"code": "UNKNOWN_GAP_RECORD_REFERENCE", "record_id": identifier}
                )
    return {
        "passed": not issues,
        "record_count": len(records),
        "required_candidate_count": len(required),
        "evidence_reference_count": reference_count,
        "covered_candidate_count": len(required & set(referenced)),
        "issues": issues,
    }


def compare_master(paths: list[Path]) -> dict[str, Any]:
    """실제 마스터 수식과 조직 원본 합계를 제한된 비교 범위에서 대조한다.

    Args:
        paths: 이번 입력의 원본 파일 목록. 파일명으로 조직을 확정하지 않는다.
    Returns:
        status, comparison_count, 비교 근거와 issues를 포함한 사전.
        사업부별집계 시트가 없으면 not_applicable로 반환한다.
    Raises:
        OSError: 원본 파일을 읽을 수 없는 경우.
        ET.ParseError: 원본 XML이 손상된 경우.
    Side Effects:
        원본의 집계 수식·캐시와 조직별 상세 셀을 독립 조회한다. 공식 마스터를
        발행하거나 일반 수식 계산 엔진을 실행하지 않으며 원본을 변경하지 않는다.
    """
    result: dict[str, Any] = {
        "status": "not_applicable",
        "comparison_count": 0,
        "comparisons": [],
        "issues": [],
        "scope": "사업부별집계의 관측된 SUMIFS/차감 수식과 조직 원본 값 비교",
    }
    masters = []
    sources = []
    if len({path.resolve() for path in paths}) != len(paths):
        result["status"] = "failed"
        result["issues"].append({"code": "DUPLICATE_INPUT_PATH"})
        return result
    for path in paths:
        with ZipFile(path) as archive:
            names = {sheet["name"] for sheet in _index_sheets(archive)}
        if "사업부별집계" in names:
            masters.append(path)
        else:
            sources.append(path)
    if not masters:
        result["reason"] = "입력에 사업부별집계 시트를 가진 비교용 마스터가 없습니다."
        return result
    if len(masters) != 1:
        result["reason"] = (
            "비교용 마스터가 여러 개여서 기준을 임의 선택하지 않았습니다."
        )
        return result
    master_path = masters[0]
    master = _read_numeric_sources(master_path, {"사업부별집계"})["사업부별집계"]
    specifications = []
    for address, cell in master.items():
        formula = cell.get("formula_text") or ""
        match = _SUMIFS.fullmatch(formula)
        if match:
            specification = match.groupdict()
            specification["address"] = address
            criterion = specification["criterion"] + specification["criterion_row"]
            specification["organization"] = master.get(criterion, {}).get("value")
            specifications.append(specification)
    if not specifications:
        result["reason"] = "지원하는 형태의 독립 비교용 SUMIFS 수식이 없습니다."
        return result
    required = {specification["sheet"].strip("'") for specification in specifications}
    sums: dict[str, Decimal] = {
        specification["address"]: Decimal(0) for specification in specifications
    }
    matched_sources: dict[str, set[str]] = {
        specification["address"]: set() for specification in specifications
    }
    matched_cells: Counter[str] = Counter()
    for path in sources:
        sheets = _read_numeric_sources(path, required)
        for specification in specifications:
            cells = sheets.get(specification["sheet"].strip("'"), {})
            address = specification["address"]
            for row in range(
                int(specification["start"]), int(specification["end"]) + 1
            ):
                code = cells.get(f"{specification['code_col']}{row}", {}).get("value")
                if code != specification["organization"]:
                    continue
                value_cell = cells.get(f"{specification['value_col']}{row}", {})
                value = value_cell.get("raw_lexeme")
                if value is None or value_cell.get("storage_type") not in ("n",):
                    continue
                try:
                    number = Decimal(value)
                except InvalidOperation:
                    result["issues"].append(
                        {
                            "code": "NON_NUMERIC_SOURCE",
                            "file": str(path),
                            "cell": f"{specification['value_col']}{row}",
                        }
                    )
                    continue
                sums[address] += number / Decimal(specification["divisor"])
                matched_cells[address] += 1
                matched_sources[address].add(str(path))
    calculated: dict[str, Decimal] = {}
    for specification in specifications:
        address = specification["address"]
        if not matched_cells[address]:
            result["issues"].append(
                {
                    "code": "NO_SOURCE_FOR_MASTER_ROW",
                    "cell": address,
                    "organization": specification["organization"],
                }
            )
            continue
        if len(matched_sources[address]) > 1:
            result["issues"].append(
                {
                    "code": "AMBIGUOUS_ORGANIZATION_SUBMISSIONS",
                    "cell": address,
                    "organization": specification["organization"],
                    "source_files": sorted(matched_sources[address]),
                }
            )
            continue
        if (
            master[address].get("raw_lexeme") is None
            or master[address].get("storage_type") != "n"
        ):
            result["issues"].append(
                {"code": "UNRESOLVED_MASTER_CACHE", "cell": address}
            )
            continue
        calculated[address] = sums[address]
        expected = Decimal(master[address]["raw_lexeme"])
        comparison = {
            "master_file": str(master_path),
            "sheet": "사업부별집계",
            "cell": address,
            "organization": specification["organization"],
            "formula": master[address]["formula_text"],
            "source_files": sorted(matched_sources[address]),
            "source_cell_count": matched_cells[address],
            "independent_value": str(sums[address]),
            "stored_master_value": str(expected),
            "passed": sums[address] == expected,
        }
        result["comparisons"].append(comparison)
    for address, cell in master.items():
        match = re.fullmatch(r"([A-Z]+\d+)-([A-Z]+\d+)", cell.get("formula_text") or "")
        if not match or not all(
            reference in calculated for reference in match.groups()
        ):
            continue
        left, right = match.groups()
        number = calculated[left] - calculated[right]
        expected = Decimal(cell["raw_lexeme"])
        result["comparisons"].append(
            {
                "master_file": str(master_path),
                "sheet": "사업부별집계",
                "cell": address,
                "formula": cell["formula_text"],
                "source_comparisons": [left, right],
                "independent_value": str(number),
                "stored_master_value": str(expected),
                "passed": number == expected,
            }
        )
    for comparison in result["comparisons"]:
        if not comparison["passed"]:
            result["issues"].append(
                {"code": "MASTER_VALUE_MISMATCH", "cell": comparison["cell"]}
            )
    result["comparison_count"] = len(result["comparisons"])
    result["status"] = (
        "passed" if result["comparison_count"] and not result["issues"] else "failed"
    )
    result["limitations"] = [
        "마스터의 마지막 저장 캐시와 원본 저장값을 비교하며 Excel 전체 재계산은 아닙니다.",
        "지원한 SUMIFS/차감 구조만 검증합니다. 정답 마스터를 조직 추출값으로 사용하지 않습니다.",
        "입력 집합이 전사 제출·승인 완료 목록이라는 의미는 아닙니다.",
    ]
    return result
