"""stdin의 xlsx를 파일 생성 없이 해석하는 내부 프로세스 JSON 브리지다.

공개 HTTP API·전체 제출 검증·승인·공식 마스터를 구현하지 않는다.
호출자는 python3 -B와 실행 시간·출력 크기 제한을 함께 적용해야 한다.
"""

import json
import sys
from dataclasses import dataclass
from decimal import Decimal
from io import BytesIO, StringIO
from itertools import chain
from pathlib import PurePosixPath
from typing import BinaryIO, Iterator, TextIO
from xml.etree import ElementTree as ET
from xml.parsers import expat
from zipfile import ZIP_DEFLATED, ZIP_STORED, BadZipFile, ZipFile

from . import xlsb as xlsb_reader
from .extraction import extract_workbook_bytes
from .mapping import map_workbook
from .ooxml import cell_position, local_name, range_bounds, relationships
from .submission_adapter import build_submission_data
from .xlsb import XLSB_WORKBOOK_TYPE, content_type_of, is_xlsb, workbook_part

PROTOCOL_VERSION = "1.0"
PREVIEW_CONTRACT_VERSION = "preview-records/1.0"
BRIDGE_VERSION = "stdlib-bridge/1.4"
_WORKBOOK_TYPE = (
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
)


@dataclass(frozen=True)
class BridgeLimits:
    """프로세스 입력의 자원 한도다. 생성 시 별도 검증은 수행하지 않는다.

    Attributes:
        max_input_bytes: 압축된 stdin의 최대 바이트 수.
        max_uncompressed_bytes: 모든 ZIP 엔트리의 해제 크기 합계 한도.
        max_entry_bytes: 한 엔트리의 해제 크기 한도.
        max_entries: ZIP 엔트리 개수 한도.
        max_cells: 내용 또는 자식 요소가 있는 워크시트 셀 개수 한도.
        max_xml_cells: 서식만 있는 빈 셀을 포함한 워크시트 셀 개수 한도.
        max_xml_nodes: 전체 XML 요소 개수 한도.
        max_xml_depth: 한 XML의 중첩 깊이 한도.
        max_xml_attribute_chars: XML 속성 값·이름의 최대 문자 수.
        max_text_chars: 셀 텍스트·공유 문자열·수식의 최대 문자 수.
        max_logical_text_bytes: 행 문맥 재사용을 포함한 텍스트 작업량 한도.
        max_expansion_ratio: 한 ZIP 엔트리의 최대 압축 해제 배율.
        max_cell_context_work: 물리 셀 수와 병합·열 서식 개수 곱의 합계 한도.
        max_output_bytes: UTF-8 응답의 최대 바이트 수.
    """

    max_input_bytes: int = 16 * 1024 * 1024
    max_uncompressed_bytes: int = 64 * 1024 * 1024
    max_entry_bytes: int = 16 * 1024 * 1024
    max_entries: int = 1024
    max_cells: int = 80_000
    max_xml_cells: int = 250_000
    max_xml_nodes: int = 600_000
    max_xml_depth: int = 64
    max_xml_attribute_chars: int = 8192
    max_text_chars: int = 32_767
    max_logical_text_bytes: int = 40 * 1024 * 1024
    max_expansion_ratio: int = 200
    max_cell_context_work: int = 6_000_000
    max_output_bytes: int = 96 * 1024 * 1024


class BridgeError(ValueError):
    """원본 내용이 없는 고정 오류코드만 인자로 받는 브리지 거부 예외다."""


def _check_filename(filename: str) -> None:
    """내부 경로와 제어 문자 없이 xlsx 이름만 허용한다.

    Args:
        filename: Java가 검증해 전달한 표시용 원본 파일명.
    Returns:
        없음.
    Raises:
        BridgeError: 파일명이 없거나 형식·길이·문자가 허용되지 않은 경우.
    Side Effects:
        없음. 파일명에서 조직이나 제출 회차를 추론하지 않는다.
    """
    if (
        not filename
        or len(filename) > 255
        or any(character in filename for character in "/\\")
        or any(ord(character) < 32 or ord(character) == 127 for character in filename)
    ):
        raise BridgeError("INVALID_FILENAME")
    if PurePosixPath(filename).suffix.lower() not in {".xlsx", ".xlsb"}:
        raise BridgeError("UNSUPPORTED_FILE_TYPE")


def _reject_doctype(*arguments: object) -> None:
    """XML 인코딩과 무관하게 DTD 및 사용자 정의 엔티티를 차단한다.

    Args:
        arguments: Expat이 전달하는 DTD 이벤트 인자. 내용은 사용하지 않는다.
    Returns:
        반환하지 않는다.
    Raises:
        BridgeError: DTD 선언이 발견된 경우 항상 발생한다.
    Side Effects:
        없음. 외부 자원을 조회하지 않는다.
    """
    raise BridgeError("UNSAFE_XML")


def _inspect_xml(document: bytes, totals: dict, limits: BridgeLimits) -> None:
    """전체 XML 자원과 워크시트의 셀·주소·반복 작업량을 제한한다.

    그림 앵커의 col 등 다른 XML 파트의 동명 요소에는 셀 주소 규칙을
    적용하지 않는다. 요소·깊이·속성·텍스트 한도는 모든 XML에 적용한다.

    Args:
        document: 해제 크기를 확인한 한 XML 엔트리.
        totals: 전체 XML 카운터·공유 문자열 길이·행 텍스트 작업량 자료.
        limits: 허용하는 자원 한도.
    Returns:
        없음. totals의 누적 카운터를 갱신한다.
    Raises:
        BridgeError: XML의 자원 한도 또는 주소 범위가 잘못된 경우.
        ExpatError: XML이 손상된 경우.
    Side Effects:
        메모리에서만 검사하며 원본·파일·네트워크에 접근하지 않는다.
    """
    depth = physical_cells = contexts = 0
    stack = []
    shared_lengths = []
    row_text = {}
    current_cell = None
    current_string = None
    parser = expat.ParserCreate(namespace_separator="}")

    def start(name: str, attributes: dict) -> None:
        """시작 요소의 자원 사용과 Excel 주소 범위를 검사한다.

        Args:
            name: 네임스페이스를 포함한 요소 이름.
            attributes: 원본 속성 사전.
        Returns:
            없음.
        Raises:
            BridgeError: 요소·셀·깊이 한도 또는 주소가 잘못된 경우.
            ValueError: 셀·병합 주소 구문이 잘못된 경우.
        Side Effects:
            검사 카운터만 갱신한다.
        """
        nonlocal depth, physical_cells, contexts, current_cell, current_string
        depth += 1
        totals["nodes"] += 1
        if depth > limits.max_xml_depth or totals["nodes"] > limits.max_xml_nodes:
            raise BridgeError("WORKBOOK_LIMIT_EXCEEDED")
        if len(name) > limits.max_xml_attribute_chars or any(
            len(key) > limits.max_xml_attribute_chars
            or len(value) > limits.max_xml_attribute_chars
            for key, value in attributes.items()
        ):
            raise BridgeError("WORKBOOK_LIMIT_EXCEEDED")
        tag = local_name(name)
        stack.append({"tag": tag, "chars": 0})
        root = stack[0]["tag"]
        if root == "workbook" and tag == "sheet":
            sheet_id = attributes.get("sheetId", "")
            if (
                not sheet_id.isascii()
                or not sheet_id.isdecimal()
                or len(sheet_id) > 10
                or not 1 <= int(sheet_id) <= 4_294_967_295
                or not 1 <= len(attributes.get("name", "")) <= 31
            ):
                raise BridgeError("INVALID_WORKBOOK")
        if (root == "sst" and tag == "si") or (root == "worksheet" and tag == "is"):
            if current_string is not None:
                raise BridgeError("INVALID_WORKBOOK")
            current_string = {"tag": tag, "chars": 0, "bytes": 0}
        if current_cell is not None and len(stack) > 1 and stack[-2]["tag"] == "c":
            current_cell["has_content"] = True
        if root == "worksheet" and tag == "c":
            physical_cells += 1
            totals["xml_cells"] += 1
            if totals["xml_cells"] > limits.max_xml_cells:
                raise BridgeError("WORKBOOK_LIMIT_EXCEEDED")
            address = attributes.get("r", "")
            if len(address) > 16:
                raise BridgeError("INVALID_WORKBOOK")
            row, column, _ = cell_position(address)
            if row > 1_048_576 or column > 16_384:
                raise BridgeError("INVALID_WORKBOOK")
            if current_cell is not None:
                raise BridgeError("INVALID_WORKBOOK")
            current_cell = {
                "row": row,
                "kind": attributes.get("t", "n"),
                "bytes": 0,
                "formula_bytes": 0,
                "value": "",
                "has_content": False,
            }
        elif root == "worksheet" and tag == "mergeCell":
            contexts += 1
            reference = attributes.get("ref", "")
            if len(reference) > 33:
                raise BridgeError("INVALID_WORKBOOK")
            r1, c1, r2, c2 = range_bounds(reference)
            if not (r1 <= r2 <= 1_048_576 and c1 <= c2 <= 16_384):
                raise BridgeError("INVALID_WORKBOOK")
        elif root == "worksheet" and tag == "col":
            contexts += 1
            if not 1 <= int(attributes["min"]) <= int(attributes["max"]) <= 16_384:
                raise BridgeError("INVALID_WORKBOOK")
        elif tag == "Relationship" and attributes.get("TargetMode") != "External":
            relation_type = attributes.get("Type", "").lower()
            xml_target = (
                relation_type.endswith(
                    ("/officedocument", "/worksheet", "/styles", "/sharedstrings")
                )
                or "comment" in relation_type
            )
            target = attributes.get("Target", "")
            if xml_target and target.endswith(".bin"):
                # xlsb의 바이너리 파트. 패키지가 실제 xlsb인지는 _validate_package에서 확인한다.
                totals["binary_targets"] = True
            elif xml_target and not target.endswith(".xml"):
                raise BridgeError("INVALID_WORKBOOK")

    def end(name: str) -> None:
        """문자열·셀의 누적 길이를 기록하고 닫힌 요소의 깊이를 되돌린다.

        Args:
            name: 닫힌 요소 이름. 내용은 사용하지 않는다.
        Returns:
            없음.
        Raises:
            BridgeError: 공유 문자열 인덱스가 유효하지 않은 경우.
        Side Effects:
            깊이·공유 문자열 길이·행별 작업량 카운터만 변경한다.
        """
        nonlocal depth, current_cell, current_string
        tag = local_name(name)
        if current_string is not None and tag == current_string["tag"]:
            if tag == "si":
                shared_lengths.append(current_string["bytes"])
            current_string = None
        if tag == "c" and current_cell is not None:
            if current_cell["has_content"]:
                totals["cells"] += 1
                if totals["cells"] > limits.max_cells:
                    raise BridgeError("WORKBOOK_LIMIT_EXCEEDED")
                row = row_text.setdefault(
                    current_cell["row"],
                    {"cells": 0, "direct": 0, "formula_bytes": 0, "references": {}},
                )
                row["cells"] += 1
                row["formula_bytes"] += current_cell["formula_bytes"]
                if current_cell["kind"] == "s":
                    value = current_cell["value"]
                    if not value.isascii() or not value.isdecimal() or len(value) > 10:
                        raise BridgeError("INVALID_WORKBOOK")
                    index = int(value)
                    row["references"][index] = row["references"].get(index, 0) + 1
                else:
                    row["direct"] += current_cell["bytes"]
            current_cell = None
        stack.pop()
        depth -= 1

    def characters(value: str) -> None:
        """표시 문자열·값·수식의 실제 길이를 분할 이벤트에 걸쳐 제한한다.

        Args:
            value: Expat이 전달한 XML 텍스트 조각.
        Returns:
            없음.
        Raises:
            BridgeError: 단일 텍스트 또는 리치 문자열의 길이를 초과한 경우.
        Side Effects:
            문자·UTF-8 길이와 공유 문자열 인덱스만 메모리에 기록한다.
        """
        if not stack or stack[-1]["tag"] not in {"t", "v", "f"}:
            return
        stack[-1]["chars"] += len(value)
        if stack[-1]["chars"] > limits.max_text_chars:
            raise BridgeError("WORKBOOK_LIMIT_EXCEEDED")
        length = len(value.encode("utf-8"))
        if current_string is not None:
            current_string["chars"] += len(value)
            current_string["bytes"] += length
            if current_string["chars"] > limits.max_text_chars:
                raise BridgeError("WORKBOOK_LIMIT_EXCEEDED")
        if current_cell is not None:
            if stack[-1]["tag"] == "f":
                current_cell["formula_bytes"] += length
            else:
                current_cell["bytes"] += length
            if stack[-1]["tag"] == "v" and current_cell["kind"] == "s":
                current_cell["value"] += value

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = characters
    parser.StartDoctypeDeclHandler = _reject_doctype
    parser.Parse(document, True)
    # 주석이 있는 빈 셀은 추출에서 보존되므로 서식 탐색 비용은 물리 셀로 제한한다.
    totals["work"] += physical_cells * (contexts + 1)
    if totals["work"] > limits.max_cell_context_work:
        raise BridgeError("WORKBOOK_LIMIT_EXCEEDED")
    for index, length in enumerate(shared_lengths):
        if index == len(totals["shared_lengths"]):
            totals["shared_lengths"].append(length)
        else:
            totals["shared_lengths"][index] = max(
                totals["shared_lengths"][index], length
            )
    totals["row_text"].extend(row_text.values())


def _validate_text_budget(totals: dict, limits: BridgeLimits) -> None:
    """공유 문자열 확장과 행 문맥 반복의 텍스트 작업량을 제한한다.

    Args:
        totals: 전체 XML에서 얻은 공유 문자열 길이와 논리 행별 참조 자료.
        limits: UTF-8 바이트 기준의 논리 텍스트 작업량 한도.
    Returns:
        없음. 문자열 내용을 실제로 반복 생성하지 않고 길이만 계산한다.
    Raises:
        BridgeError: 공유 문자열 참조가 없거나 누적 작업량이 한도를 넘는 경우.
    Side Effects:
        메모리 길이 연산만 수행한다. 원본·추출·매핑 결과를 만들지 않는다.
    """
    shared = totals["shared_lengths"]
    work = sum(shared)
    for row in totals["row_text"]:
        text_size = row["direct"]
        for index, count in row["references"].items():
            if index >= len(shared):
                raise BridgeError("INVALID_WORKBOOK")
            text_size += shared[index] * count
        # 빈 서식 셀은 추출에서 희소 구간으로 보존되며 수식은 행 설명으로 복제되지 않는다.
        work += text_size * row["cells"] + row["formula_bytes"]
        if work > limits.max_logical_text_bytes:
            raise BridgeError("WORKBOOK_LIMIT_EXCEEDED")
    if work > limits.max_logical_text_bytes:
        raise BridgeError("WORKBOOK_LIMIT_EXCEEDED")


def _validate_package(content: bytes, limits: BridgeLimits) -> None:
    """ZIP 해제와 OOXML 매핑 전에 패키지 크기·형식·XML을 검사한다.

    Args:
        content: stdin에서 상한 내로 읽은 압축 바이트.
        limits: 압축·해제·엔트리·XML 한도.
    Returns:
        없음.
    Raises:
        BridgeError: 위험한 패키지·미지원 형식·자원 상한 위반인 경우.
        BadZipFile, ExpatError, ET.ParseError: 손상된 ZIP 또는 XML인 경우.
        ValueError, KeyError: 잘못된 OOXML 주소·구조인 경우.
    Side Effects:
        BytesIO만 해제·읽는다. 경로로 추출하거나 원본을 저장하지 않는다.
    """
    if not content or len(content) > limits.max_input_bytes:
        raise BridgeError("FILE_TOO_LARGE" if content else "INVALID_WORKBOOK")
    with ZipFile(BytesIO(content)) as archive:
        entries = archive.infolist()
        if len(entries) > limits.max_entries:
            raise BridgeError("WORKBOOK_LIMIT_EXCEEDED")
        names = set()
        total_size = 0
        for entry in entries:
            name = entry.filename
            if (
                name in names
                or name.startswith("/")
                or "\\" in name
                or ".." in PurePosixPath(name).parts
                or any(ord(character) < 32 for character in name)
                or entry.flag_bits & 1
                or entry.compress_type not in {ZIP_STORED, ZIP_DEFLATED}
            ):
                raise BridgeError("INVALID_WORKBOOK")
            names.add(name)
            total_size += entry.file_size
            if (
                entry.file_size > limits.max_entry_bytes
                or total_size > limits.max_uncompressed_bytes
                or entry.file_size
                > max(entry.compress_size, 1) * limits.max_expansion_ratio
            ):
                raise BridgeError("WORKBOOK_LIMIT_EXCEEDED")
            if "vbaproject" in name.lower():
                raise BridgeError("UNSUPPORTED_FILE_TYPE")
        if "[Content_Types].xml" not in names:
            raise BridgeError("INVALID_WORKBOOK")
        totals = {
            "nodes": 0,
            "cells": 0,
            "xml_cells": 0,
            "work": 0,
            "shared_lengths": [],
            "row_text": [],
        }
        workbook_parts = []
        for entry in entries:
            if not entry.filename.endswith((".xml", ".rels")):
                continue
            document = archive.read(entry)
            if len(document) != entry.file_size:
                raise BridgeError("INVALID_WORKBOOK")
            _inspect_xml(document, totals, limits)
            if entry.filename == "[Content_Types].xml":
                for node in ET.fromstring(document):
                    content_type = node.get("ContentType", "")
                    # xlsb 통합 문서 형식 이름에는 macroEnabled가 들어 있지만 매크로가 아니다.
                    # 실제 매크로(vbaProject)는 위 엔트리 이름 검사에서 막는다.
                    if content_type != XLSB_WORKBOOK_TYPE and (
                        "macroenabled" in content_type.lower()
                        or "vba" in content_type.lower()
                    ):
                        raise BridgeError("UNSUPPORTED_FILE_TYPE")
                    if content_type == _WORKBOOK_TYPE:
                        workbook_parts.append(node.get("PartName", "").lstrip("/"))
        if not is_xlsb(archive) and totals.get("binary_targets"):
            raise BridgeError("INVALID_WORKBOOK")
        if is_xlsb(archive):
            book_part = workbook_part(archive)
            if book_part not in names or content_type_of(archive, book_part) != XLSB_WORKBOOK_TYPE:
                raise BridgeError("INVALID_WORKBOOK")
            _inspect_xlsb(archive, book_part, limits)
            return
        if (
            len(workbook_parts) != 1
            or workbook_parts[0] not in names
            or not workbook_parts[0].endswith(".xml")
        ):
            raise BridgeError("INVALID_WORKBOOK")
        _validate_text_budget(totals, limits)


def _inspect_xlsb(archive: ZipFile, book_part: str, limits: BridgeLimits) -> None:
    """xlsb 바이너리 파트에 XML 검사와 같은 자원 한도를 적용한다.

    Args:
        archive: 크기·경로 검사를 마친 ZIP 패키지.
        book_part: 통합 문서(workbook.bin) 파트 경로.
        limits: 셀·텍스트 한도.
    Returns:
        없음.
    Raises:
        BridgeError: 레코드가 손상됐거나(INVALID_WORKBOOK) 셀·텍스트 한도를 넘은 경우.
    Side Effects:
        메모리에서 레코드를 훑기만 한다.
    """
    try:
        sheets, _ = xlsb_reader.read_workbook_sheets(archive, book_part)
        if not sheets or any(not 1 <= len(sheet["name"]) <= 31 for sheet in sheets):
            raise BridgeError("INVALID_WORKBOOK")
        rels = relationships(archive, book_part)
        text_bytes = 0
        strings_part = next((entry.get("part") for entry in rels.values()
                             if entry.get("Type", "").endswith("/sharedStrings")), None)
        for text in xlsb_reader.read_shared_strings(archive, strings_part):
            if len(text) > limits.max_text_chars:
                raise BridgeError("WORKBOOK_LIMIT_EXCEEDED")
            text_bytes += len(text.encode("utf-8"))
        cells = physical = 0
        for sheet in sheets:
            relation = rels.get(sheet["relationship_id"] or "", {})
            part = relation.get("part")
            if not part or not relation.get("Type", "").endswith("/worksheet"):
                continue
            for record_type, body in xlsb_reader.iter_records(archive.read(part)):
                if record_type in range(1, 19):
                    physical += 1
                    if record_type not in {1, 12}:
                        cells += 1
                    if record_type < 12 and len(body) >= 4:
                        column = int.from_bytes(body[:4], "little")
                        if column > xlsb_reader.MAX_COLUMN:
                            raise BridgeError("INVALID_WORKBOOK")
                elif record_type == 0 and len(body) >= 4:
                    if int.from_bytes(body[:4], "little") > xlsb_reader.MAX_ROW:
                        raise BridgeError("INVALID_WORKBOOK")
                if physical > limits.max_xml_cells or cells > limits.max_cells:
                    raise BridgeError("WORKBOOK_LIMIT_EXCEEDED")
        if text_bytes > limits.max_logical_text_bytes:
            raise BridgeError("WORKBOOK_LIMIT_EXCEEDED")
    except xlsb_reader.XlsbFormatError as error:
        raise BridgeError("INVALID_WORKBOOK") from error


def _failure(code: str) -> dict:
    """예외 원문 없이 실패 응답을 일정한 형태로 구성한다.

    Args:
        code: 내부에서 선택한 고정 오류코드.
    Returns:
        빈 records와 0 카운트, 안전한 진단 하나를 가진 FAILED 응답.
    Raises:
        없음.
    Side Effects:
        없음. 원본 값·파일 경로·예외 메시지를 포함하지 않는다.
    """
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "parserVersion": BRIDGE_VERSION,
        "contractVersion": PREVIEW_CONTRACT_VERSION,
        "status": "FAILED",
        "errorCode": code,
        "summary": {
            "sheetCount": 0,
            "recordCount": 0,
            "mappedCount": 0,
            "unresolvedCount": 0,
            "unusedTemplateRowCount": 0,
        },
        "diagnostics": [
            {
                "code": code,
                "severity": "ERROR",
                "stage": "PARSING",
                "message": "파일을 안전하게 해석하지 못했습니다.",
                "source": None,
            }
        ],
        "records": [],
    }


def _preview_record(record: dict) -> dict:
    """모든 후보를 유지하며 내부 미리보기에 필요한 필드로 투영한다.

    표준 map_workbook 결과는 변경하지 않는다. 원본 파일·중복 행 문맥은
    응답마다 복제하지 않으며 저장된 원본으로 다시 추출할 수 있다.

    Args:
        record: 표준 매핑 후보 한 건.
    Returns:
        원본 셀, 조직·항목·기간, 금액·수식, 역할·매핑 상태와 업무 오류 근거.
        Decimal은 유지하며 정상 후보·미확정 후보·참고 후보를 삭제하지 않는다.
    Raises:
        KeyError: 내부 표준 매핑 결과의 필수 필드가 없는 경우.
    Side Effects:
        입력을 변경하지 않는다. 결과는 읽기 전용 용도의 중첩 값 참조를 공유한다.
    """
    result = {
        "source": {key: record["source"].get(key) for key in ("sheet", "cell")},
        "organization": {
            key: record["organization"].get(key) for key in ("code", "name", "status")
        },
        "item": {
            key: record["item"].get(key)
            for key in ("code", "name", "detail_id", "detail_revision", "metric_code")
        },
        "period": {
            key: record["period"].get(key)
            for key in ("year", "month", "quarter", "aggregation", "scenario")
        },
        "role": {
            key: record["role"].get(key) for key in ("kind", "aggregatable")
        },
        "value": record["value"],
        "mapping": record["mapping"],
        "formula": record["formula"],
        "raw_value": record["raw_value"],
        "data_type": record["data_type"],
    }
    validation = record.get("business_validation")
    if validation is not None:
        result["business_validation"] = {
            "status": validation["status"],
            "issues": validation["issues"],
        }
    return result


def _public_diagnostic(item: dict) -> dict:
    """원본 내용 대신 고정된 조치 안내와 확인된 셀 위치를 전달한다.

    Args:
        item: 추출·매핑 단계의 진단. code와 선택 sheet·cell·provenance를 사용한다.
    Returns:
        공개 가능한 MAPPING 진단. 조직 오류는 원인별 안내로 구분한다.
    Raises:
        KeyError: 내부 진단에 필수 code가 없는 경우.
    Side Effects:
        없음. 원본 값·경로·예외 메시지를 복제하거나 셀 위치를 추측하지 않는다.
    """
    messages = {
        "ORGANIZATION_LOOKUP_ERROR": (
            "조직 조회 수식의 결과를 확인할 수 없습니다. "
            "해당 행의 팀코드와 조직 코드표를 확인해 주세요."
        ),
        "ORGANIZATION_VALUE_ERROR": (
            "조직코드 또는 조직명 셀에 오류 값이 있습니다. "
            "해당 셀의 입력과 조직 코드표를 확인해 주세요."
        ),
        "ORGANIZATION_NAME_CONFLICT": (
            "행의 조직명과 파일에서 확인된 조직명이 다릅니다. "
            "조직명과 팀코드 연결을 확인해 주세요."
        ),
        "ORGANIZATION_CODE_NAME_CONFLICT": (
            "조직코드와 조직명이 코드표의 대응과 다릅니다. "
            "해당 행과 조직 코드표를 확인해 주세요."
        ),
        "ORGANIZATION_NAME_AMBIGUOUS": (
            "같은 조직명에 여러 조직코드 또는 충돌하는 이름이 연결됩니다. "
            "조직 코드표의 중복 대응을 확인해 주세요."
        ),
        "ORGANIZATION_CODE_UNRESOLVED": (
            "조직명에 대응하는 조직코드를 확인하지 못했습니다. "
            "조직명과 조직 코드표를 확인해 주세요."
        ),
    }
    sheet, cell = item.get("sheet"), item.get("cell")
    if sheet and not cell:
        cell = next((ref.get("cell") for ref in item.get("provenance", [])
                     if ref.get("sheet") == sheet and ref.get("cell")), None)
    return {
        "code": item["code"],
        "severity": "WARNING",
        "stage": "MAPPING",
        "message": messages.get(item["code"], "원본 구조와 의미 근거의 확인이 필요합니다."),
        "source": {"sheet": sheet, "cell": cell} if sheet else None,
    }


def _business_diagnostic(record: dict, issue: dict) -> dict | None:
    """업무 오류의 실제 입력 셀이 확인될 때 원인별 정정 안내를 만든다.

    Args:
        record: dimensions와 원본 금액 셀을 가진 표준 매핑 후보.
        issue: business_validation.issues의 field·code·provenance 문제 한 건.
    Returns:
        같은 행의 필드 출처와 오류 근거가 일치하면 공개 진단을 반환한다.
        지원하지 않는 문제이거나 위치가 불확실하면 None을 반환한다.
    Raises:
        KeyError: 표준 매핑 후보에 필수 source가 없는 경우.
        주소를 해석할 수 없으면 위치를 추정하지 않고 None을 반환한다.
    Side Effects:
        없음. 원본 값·후보·상태를 변경하거나 관측값을 메시지에 복제하지 않는다.
    """
    fields = {
        "account_code": ("예산Code", "예산 계정코드", "같은 파일의 계정 코드표에 등록되지 않았습니다."),
        "detail_id": ("거래내역 및 산출근거", "상세 ID", "유효한 식별자가 아닙니다."),
        "management_category": ("관리분류", "관리분류", "같은 파일의 집계 기준과 일치하지 않습니다."),
    }
    field = issue.get("field")
    if field not in fields:
        return None
    header, label, invalid_reason = fields[field]
    code = issue.get("code")
    if code not in {f"BUSINESS_{field.upper()}_INVALID", f"BUSINESS_{field.upper()}_MISSING"}:
        return None
    source = record.get("dimensions", {}).get(header, {}).get("source") or {}
    amount_source = record["source"]
    if not source.get("sheet") or source.get("sheet") != amount_source.get("sheet"):
        return None
    try:
        if cell_position(source.get("cell"))[0] != cell_position(amount_source.get("cell"))[0]:
            return None
    except (TypeError, ValueError):
        return None
    if not any(ref.get("sheet") == source["sheet"] and ref.get("cell") == source["cell"]
               for ref in issue.get("provenance", [])):
        return None
    reason = invalid_reason if code.endswith("_INVALID") else "확인할 수 없습니다."
    return {
        "code": code,
        "severity": "WARNING",
        "stage": "MAPPING",
        "message": (
            f"{label}: {reason} 담당자가 원본의 해당 셀을 확인·수정한 뒤 "
            "전체 파일을 다시 업로드해 주세요."
        ),
        "source": {"sheet": source["sheet"], "cell": source["cell"]},
    }


def _review_diagnostics(unresolved: list[dict]) -> list[dict]:
    """반복 업무 오류는 원본 필드별로 묶고 별도 불확실성은 금액 셀에 남긴다.

    Args:
        unresolved: 기존 상태 판정으로 선택된 미확정 재무 상세 후보 전체.
    Returns:
        업무 오류의 code·sheet·cell별 고유 진단과 일반 불확실성 안내 목록.
        원인 위치를 확정하지 못하거나 금액·매핑도 미확정이면 기존 안내를 유지한다.
    Raises:
        KeyError: 표준 매핑 후보의 필수 필드가 없는 경우.
    Side Effects:
        없음. 진단만 구성하며 후보·금액·검토 수와 입력 객체를 변경하지 않는다.
    """
    diagnostics, seen = [], set()
    for record in unresolved:
        issues = record.get("business_validation", {}).get("issues", [])
        located = 0
        for issue in issues:
            diagnostic = _business_diagnostic(record, issue)
            if diagnostic is None:
                continue
            located += 1
            source = diagnostic["source"]
            key = (diagnostic["code"], source["sheet"], source["cell"])
            if key not in seen:
                seen.add(key)
                diagnostics.append(diagnostic)
        if (not issues or located != len(issues)
                or record["mapping"]["status"] != "mapped"
                or record["value"]["status"] != "confirmed"):
            diagnostics.append({
                "code": "FINANCIAL_DETAIL_UNRESOLVED",
                "severity": "WARNING",
                "stage": "MAPPING",
                "message": "재무 상세의 조직·기간·금액·업무키를 확인해야 합니다.",
                "source": {"sheet": record["source"]["sheet"], "cell": record["source"]["cell"]},
            })
    return diagnostics


def parse_bytes(
    content: bytes, filename: str, limits: BridgeLimits | None = None
) -> dict:
    """한 xlsx 바이트를 기존 추출·매핑기로 처리해 내부 프로토콜을 만든다.

    Args:
        content: xlsx 원본 바이트.
        filename: 경로 없는 표시용 파일명. 조직·회차 정보로 사용하지 않는다.
        limits: 생략하면 BridgeLimits의 보수적인 기본 자원 한도.
    Returns:
        SUCCEEDED/NEEDS_REVIEW/FAILED와 내부 미리보기 records를 가진 사전.
        records의 Decimal은 메모리에 유지하며 main이 십진 문자열로 직렬화한다.
        SUCCEEDED는 관측한 재무 상세의 해석 상태이며 전체 제출 검증이 아니다.
        미사용 양식 후보는 보존하되 업무 검토와 분리하고 고유 행 수를 알린다.
        submissionData는 별도의 행·월·보고 수식·원본 셀 근거이며 요청 범위의
        검증 통과나 승인 상태를 의미하지 않는다.
    Raises:
        없음. 일반 예외를 원문 없는 고정 오류코드로 변환한다.
    Side Effects:
        메모리에서만 해제·추출·매핑한다. 파일·DB·수식 실행·승인은 없다.
    """
    limits = limits or BridgeLimits()
    try:
        _check_filename(filename)
        _validate_package(content, limits)
        workbook = extract_workbook_bytes(content, filename)
        mapped = map_workbook(workbook)
        records = mapped["records"]
        unused_rows = {
            (record["source"]["sheet"], cell_position(record["source"]["cell"])[0])
            for record in records
            if record["role"]["kind"] == "inactive_template"
        }
        details = [
            record
            for record in records
            if record["role"]["kind"] == "detail"
            and record["value"]["value_kind"] == "amount"
        ]
        unresolved = [
            record
            for record in details
            if record["mapping"]["status"] != "mapped"
            or record["value"]["status"] != "confirmed"
            or record.get("business_validation", {}).get("status")
            in {"invalid", "unresolved"}
        ]
        diagnostics = []
        for item in [*workbook["diagnostics"], *mapped["diagnostics"]]:
            if item.get("severity") == "info":
                continue
            diagnostics.append(_public_diagnostic(item))
        diagnostics.extend(_review_diagnostics(unresolved))
        if not details:
            diagnostics.append(
                {
                    "code": "FINANCIAL_DETAIL_NOT_FOUND",
                    "severity": "WARNING",
                    "stage": "MAPPING",
                    "message": "지원하는 재무 상세를 확인하지 못했습니다.",
                    "source": None,
                }
            )
        needs_review = not details or bool(unresolved)
        return {
            "protocolVersion": PROTOCOL_VERSION,
            "parserVersion": BRIDGE_VERSION,
            "contractVersion": PREVIEW_CONTRACT_VERSION,
            "status": "NEEDS_REVIEW" if needs_review else "SUCCEEDED",
            "errorCode": "PARSER_REVIEW_REQUIRED" if needs_review else None,
            "summary": {
                "sheetCount": len(workbook["sheets"]),
                "recordCount": len(records),
                "mappedCount": mapped["summary"]["mapped"],
                "unresolvedCount": len(unresolved),
                "unusedTemplateRowCount": len(unused_rows),
            },
            "diagnostics": diagnostics,
            "records": [_preview_record(record) for record in records],
            "submissionData": build_submission_data(workbook, mapped),
        }
    except BridgeError as error:
        return _failure(str(error))
    except (
        BadZipFile,
        ET.ParseError,
        expat.ExpatError,
        UnicodeError,
        ValueError,
        KeyError,
        IndexError,
        StopIteration,
    ):
        return _failure("INVALID_WORKBOOK")
    except Exception:
        return _failure("PARSER_INTERNAL_ERROR")


def _decimal_json(value: object) -> str:
    """정밀 Decimal만 유한 십진 문자열로 JSON에 연결한다.

    Args:
        value: 기본 JSON 직렬화기가 지원하지 않는 값.
    Returns:
        Decimal의 지수 없는 십진 문자열.
    Raises:
        ValueError: Decimal이 NaN 또는 무한대인 경우.
        TypeError: 허용하지 않은 객체 타입인 경우.
    Side Effects:
        없음.
    """
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise ValueError("유한 십진값만 허용합니다.")
        return format(value, "f")
    raise TypeError("지원하지 않는 프로토콜 타입입니다.")


def _response_chunks(response: dict, limit: int) -> Iterator[str]:
    """응답 전체를 검증한 뒤 같은 JSON을 작은 조각으로 다시 직렬화한다.

    Args:
        response: 브리지 응답 사전.
        limit: 허용할 UTF-8 응답 바이트 수. 마지막 줄바꿈도 포함한다.
    Returns:
        JSON 조각과 마지막 줄바꿈을 순서대로 반환하는 반복자.
    Raises:
        BridgeError: 반환 전에 확인한 응답 크기가 한도를 넘는 경우.
        TypeError, ValueError: 반환 전 검사에서 지원하지 않는 값이나 비유한 숫자가 있는 경우.
    Side Effects:
        메모리만 사용한다. 첫 순회는 크기·형식만 검사하고 응답을 저장하지 않는다.
        호출자는 반환 후 순회가 끝날 때까지 response를 변경하지 않아야 한다.
    """
    size = 1
    encoder = json.JSONEncoder(
        ensure_ascii=False, allow_nan=False, default=_decimal_json, separators=(",", ":")
    )
    for piece in encoder.iterencode(response):
        size += len(piece.encode("utf-8"))
        if size > limit:
            raise BridgeError("PARSER_OUTPUT_LIMIT_EXCEEDED")
    return chain(encoder.iterencode(response), ("\n",))


def _encode_response(response: dict, limit: int) -> str:
    """검증된 JSON 조각을 문자열이 필요한 내부 호출자에게 모아 반환한다.

    Args:
        response: 브리지 응답 사전.
        limit: 마지막 줄바꿈을 포함한 UTF-8 응답 바이트 상한.
    Returns:
        줄바꿈 한 개로 끝나는 JSON 문자열.
    Raises:
        BridgeError: 응답 크기가 상한을 넘는 경우.
        TypeError, ValueError: 응답 값이 JSON으로 표현할 수 없는 경우.
    Side Effects:
        메모리 문자열을 만든다. 실제 CLI는 이 함수를 쓰지 않고 조각을 직접 출력한다.
    """
    output = StringIO()
    output.writelines(_response_chunks(response, limit))
    return output.getvalue()


def main(
    argv: list[str] | None = None,
    *,
    input_stream: BinaryIO | None = None,
    output_stream: TextIO | None = None,
    error_stream: TextIO | None = None,
    limits: BridgeLimits | None = None,
) -> int:
    """stdin xlsx 한 건을 stdout JSON 한 건으로 바꾸는 내부 CLI다.

    Args:
        argv: --filename 파일명 두 인자. 생략하면 sys.argv[1:]을 읽는다.
        input_stream: 테스트용 바이너리 입력. 생략하면 sys.stdin.buffer.
        output_stream: 테스트용 텍스트 출력. 생략하면 sys.stdout.
        error_stream: 고정 실패코드용 출력. 생략하면 sys.stderr.
        limits: 생략하면 BridgeLimits 기본 한도.
    Returns:
        SUCCEEDED/NEEDS_REVIEW는 0, FAILED는 1.
    Raises:
        OSError: 호출자가 닫은 stdout/stderr에 결과를 쓸 수 없는 경우.
    Side Effects:
        stdin을 상한까지만 읽고 stdout JSON 및 실패 시 stderr 코드만 쓴다.
        파일·캐시·임시 폴더를 생성하지 않는다. -B는 호출자가 적용해야 한다.
    """
    arguments = sys.argv[1:] if argv is None else argv
    source = sys.stdin.buffer if input_stream is None else input_stream
    output = sys.stdout if output_stream is None else output_stream
    errors = sys.stderr if error_stream is None else error_stream
    limits = limits or BridgeLimits()
    try:
        if len(arguments) != 2 or arguments[0] != "--filename":
            response = _failure("INVALID_ARGUMENTS")
        else:
            _check_filename(arguments[1])
            response = parse_bytes(
                source.read(limits.max_input_bytes + 1), arguments[1], limits
            )
        chunks = _response_chunks(response, limits.max_output_bytes)
    except BridgeError as error:
        response = _failure(str(error))
        chunks = iter((json.dumps(response, ensure_ascii=False) + "\n",))
    except Exception:
        response = _failure("PARSER_INTERNAL_ERROR")
        chunks = iter((json.dumps(response, ensure_ascii=False) + "\n",))
    for piece in chunks:
        output.write(piece)
    output.flush()
    if response["status"] == "FAILED":
        errors.write(response["errorCode"] + "\n")
        errors.flush()
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
