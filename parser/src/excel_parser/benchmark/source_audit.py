"""월별 검증 원본과 서비스 파서의 행·월 슬롯을 독립 OOXML로 대조한다.

manifest는 평가 대상과 양식의 정답 범위만 정한다. 생산 파서에는 원본
바이트와 파일명만 전달하며, 정제 마스터의 중복·반려 선택은 평가하지 않는다.
원본과 출력 파일을 수정하지 않고 JSON 요약을 콘솔에만 기록한다.
"""

import argparse
from collections import Counter, defaultdict
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation, localcontext
import hashlib
from io import BytesIO
import json
from pathlib import Path
import posixpath
import re
import time
from typing import Any
from xml.etree import ElementTree as ET
from zipfile import ZipFile

from ..service_bridge import parse_bytes


_PERIODS = ((2025, "ACTUAL"), (2026, "PLAN"), (2026, "BOUNDARY"), (2027, "PLAN"))
_UNITS = {"원": Decimal(1), "천원": Decimal(1000), "백만원": Decimal(1000000), "억원": Decimal(100000000)}
_NUMBER = re.compile(r"^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$")
_GROUPED = re.compile(r"^[+-]?\d{1,3}(?:,\d{3})+(?:\.\d+)?$")


@dataclass(frozen=True, slots=True)
class NativeCell:
    """독립 XML 판독기의 원본 셀 값과 수식 존재 정보를 보존한다."""

    raw: Any
    kind: str
    formula: str | None
    has_formula: bool


def _column(number: int) -> str:
    """양수 열 번호를 Excel 주소의 문자 부분으로 변환한다.

    Args: number: 1부터 시작하는 열 번호.
    Returns: Excel 열 문자.
    Raises: ValueError: 양수가 아닌 경우.
    Side Effects: 없음.
    """
    if number < 1:
        raise ValueError("열 번호는 양수여야 합니다")
    value = ""
    while number:
        number, digit = divmod(number - 1, 26)
        value = chr(65 + digit) + value
    return value


def _row(address: str) -> int:
    """Excel 셀 주소에서 행 번호를 독립적으로 읽는다.

    Args: address: A1 형식 주소.
    Returns: 양수 행 번호.
    Raises: ValueError: 유효한 주소가 아닌 경우.
    Side Effects: 없음.
    """
    match = re.fullmatch(r"[A-Z]+([1-9]\d*)", address)
    if not match:
        raise ValueError("원본 셀 주소가 잘못되었습니다")
    return int(match.group(1))


def _decode(node: ET.Element, strings: list[str]) -> NativeCell:
    """생산 추출기를 사용하지 않고 XML 셀 타입과 저장값을 읽는다.

    Args: node: c 요소. strings: 원본 공유 문자열.
    Returns: 숫자 렉심·문자열·빈값·수식을 보존한 NativeCell.
    Raises: ValueError, IndexError: 공유 문자열 참조가 잘못된 경우.
    Side Effects: 없음. 수식을 실행하지 않는다.
    """
    token = node.get("t", "n")
    raw = node.findtext("{*}v")
    if token == "s":
        raw, kind = strings[int(raw)], "STRING"
    elif token == "inlineStr":
        raw, kind = "".join(part.text or "" for part in node.findall("./{*}is//{*}t")), "STRING"
    elif token == "str":
        raw, kind = raw if raw is not None else "", "STRING"
    elif token == "b":
        raw, kind = raw == "1" if raw is not None else None, "BOOLEAN"
    else:
        kind = {"e": "ERROR", "d": "DATE"}.get(token, "NUMBER" if raw is not None else "BLANK")
    formula = node.find("{*}f")
    return NativeCell(raw, kind, "=" + formula.text if formula is not None and formula.text else None, formula is not None)


def read_native(content: bytes) -> dict[str, dict[str, NativeCell]]:
    """XLSX의 실제 관계표를 따라 모든 워크시트 셀을 독립 판독한다.

    Args: content: 변경하지 않는 XLSX 원본 바이트.
    Returns: 시트명별 주소·NativeCell 색인. 저장된 빈 셀도 포함한다.
    Raises: ZIP·XML·키 오류: 읽을 수 없거나 손상된 원본인 경우.
    Side Effects: 메모리만 사용하며 파일·캐시·외부 관계를 생성하거나 조회하지 않는다.
    """
    with ZipFile(BytesIO(content)) as archive:
        root = ET.fromstring(archive.read("xl/workbook.xml"))
        relationships = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
        parts = {}
        for relation in relationships:
            if relation.get("TargetMode") == "External":
                continue
            target = relation.attrib["Target"]
            parts[relation.attrib["Id"]] = target.lstrip("/") if target.startswith("/") else posixpath.normpath("xl/" + target)
        strings_part = next((parts[relation.attrib["Id"]] for relation in relationships if relation.get("Type", "").endswith("/sharedStrings")), None)
        strings = []
        if strings_part:
            strings = ["".join(node.text or "" for node in item.iter() if node.tag.endswith("}t")) for item in ET.fromstring(archive.read(strings_part))]
        result = {}
        for sheet in root.findall("./{*}sheets/{*}sheet"):
            relationship_id = next(value for key, value in sheet.attrib.items() if key.endswith("}id"))
            part = parts[relationship_id]
            cells = {}
            for _, node in ET.iterparse(BytesIO(archive.read(part)), events=("end",)):
                if node.tag.endswith("}c"):
                    cells[node.attrib["r"]] = _decode(node, strings)
                    node.clear()
            result[sheet.attrib["name"]] = cells
        return result


def _amount(cell: NativeCell | None, multiplier: Decimal) -> Decimal | None:
    """검증 양식의 숫자·괄호·한국식 그룹 문자열을 독립적으로 해석한다.

    Args: cell: 원본 셀 또는 부재. multiplier: manifest가 명시한 원 단위 배율.
    Returns: 유한한 원 금액 또는 값이 없거나 해석할 수 없으면 None.
    Raises: 없음. 오류·불리언·날짜·미지원 표기는 숫자로 추정하지 않는다.
    Side Effects: 없음. 생산 정규화 함수를 호출하지 않는다.
    """
    if cell is None or cell.kind not in {"NUMBER", "STRING"} or cell.raw in (None, ""):
        return None
    text = str(cell.raw).strip()
    if text.startswith("(") and text.endswith(")"):
        text = "-" + text[1:-1].strip()
    if _GROUPED.fullmatch(text):
        text = text.replace(",", "")
    if not _NUMBER.fullmatch(text):
        return None
    try:
        value = Decimal(text)
        if not value.is_finite():
            return None
        with localcontext() as context:
            context.prec = max(50, len(value.as_tuple().digits) + len(multiplier.as_tuple().digits) + 10)
            return value * multiplier
    except InvalidOperation:
        return None


def _present(cell: NativeCell | None) -> bool:
    """수식 또는 실제 값이 있는 원본 셀인지 판단한다.

    Args: cell: 원본 셀 또는 부재.
    Returns: 내용 존재 여부. 직접 입력한 0은 True다.
    Raises: 없음.
    Side Effects: 없음.
    """
    return cell is not None and (cell.has_formula or cell.raw not in (None, ""))


def _registry(native: dict) -> dict[str, set[str]]:
    """원본의 조직 코드표에서 이름별 코드 집합을 독립적으로 읽는다.

    Args: native: 시트별 원본 셀 색인.
    Returns: 조직명과 명시된 코드 집합. 동명 코드는 합치지 않는다.
    Raises: ValueError: 셀 주소가 유효하지 않은 경우.
    Side Effects: 없음. manifest 조직·파일명·ID로 조직을 보충하지 않는다.
    """
    result = defaultdict(set)
    for cells in native.values():
        headers = defaultdict(dict)
        for address, cell in cells.items():
            if cell.raw in ("사업부코드", "사업부명"):
                headers[_row(address)][cell.raw] = re.sub(r"\d+$", "", address)
        for row, columns in headers.items():
            if set(columns) != {"사업부코드", "사업부명"}:
                continue
            for address, name in cells.items():
                if not address.startswith(columns["사업부명"]) or re.sub(r"\d+$", "", address) != columns["사업부명"] or _row(address) <= row:
                    continue
                code = cells.get(columns["사업부코드"] + str(_row(address)))
                if code and name.raw and code.raw and name.kind != "ERROR" and code.kind != "ERROR":
                    result[str(name.raw).strip()].add(str(code.raw).strip())
    return dict(result)


def expected_rows(native: dict, entry: dict) -> list[dict]:
    """평가용 명시 행과 실제 입력으로 독립 정답 행·월 슬롯을 구성한다.

    Args: native: 독립 원본 색인. entry: manifest 한 조직 파일의 범위·단위·기간.
    Returns: 오류행·중복행·0원행을 제거하지 않은 정답 목록. next_projection은 별도 그룹이다.
    Raises: ValueError, KeyError: 정답 단위·행 수·양식 계약이 맞지 않는 경우.
    Side Effects: 메모리만 사용하며 manifest를 생산 파서에 전달하지 않는다.
    """
    multiplier = _UNITS.get(entry["unit"])
    if multiplier is None:
        raise ValueError("평가 단위가 지원되지 않습니다")
    registry, result = _registry(native), []
    for domain, layout in entry["layout"].items():
        cells, sheet = native[layout["sheet"]], layout["sheet"]
        count = entry["expected"][domain]["row_count"]
        manifest_rows = {record["row"] for record in entry.get("records", {}).get(domain, [])}
        targets = manifest_rows | set(range(layout["start"], layout["start"] + count))
        description_column = _column(layout["description_column"])
        for address, cell in cells.items():
            if re.sub(r"\d+$", "", address) == description_column and "[ID:" in str(cell.raw):
                targets.add(_row(address))
        for row in sorted(targets):
            description = cells.get(f"{description_column}{row}")
            text = str(description.raw or "") if description else ""
            identity = re.search(r"\[ID:([^;\]]+);\s*수정:(\d+)\]", text)
            organization_cell = cells.get(f"{'A' if domain == 'revenue' else 'D'}{row}")
            organization_name = str(organization_cell.raw).strip() if organization_cell and organization_cell.raw is not None and organization_cell.kind != "ERROR" else None
            codes = registry.get(organization_name, set())
            organization_code = next(iter(codes)) if len(codes) == 1 else None
            periods = [(column, f"{year}:{scenario}", year, scenario) for column, (year, scenario) in zip(layout["annual_columns"], _PERIODS)]
            if domain == "cost":
                periods.append((73, "next_projection", 2027, "ESTIMATE"))
            slots = []
            for annual_column, group, year, scenario in periods:
                for month in range(1, 13):
                    address = f"{_column(annual_column + month)}{row}"
                    cell = cells.get(address)
                    resolved_scenario = ("ACTUAL" if month <= entry["close_month"] else "ESTIMATE") if scenario == "BOUNDARY" else scenario
                    value = _amount(cell, multiplier)
                    slots.append({"cell": address, "group": group, "year": year, "month": month,
                                  "scenario": resolved_scenario, "amount": value,
                                  "valueStatus": "AVAILABLE" if value is not None else "UNRESOLVED" if _present(cell) else "MISSING",
                                  "inputPresence": "PRESENT" if _present(cell) else "STORED_BLANK" if cell else "ABSENT_XML_CELL",
                                  "sourceUnit": entry["unit"], "multiplier": multiplier})
            result.append({"sheet": sheet, "row": row, "domain": "REVENUE" if domain == "revenue" else "COST",
                           "detailId": identity.group(1).strip() if identity else None,
                           "detailRevision": int(identity.group(2)) if identity else None,
                           "organizationName": organization_name, "organizationCode": organization_code,
                           "organizationError": bool(organization_cell and (organization_cell.kind == "ERROR" or organization_cell.has_formula and organization_cell.raw in (None, ""))),
                           "values": slots, "manifestRow": row in manifest_rows})
    return result


def _mismatch(result: dict, code: str, sheet: str, cell: str | int, expected: Any = None, actual: Any = None) -> None:
    """불일치 총수를 누적하고 제한된 원인·좌표 예시만 보존한다.

    Args: result: 평가 결과. code: 분류. sheet: 시트. cell: 주소 또는 행. expected: 기대값. actual: 실제값.
    Returns: 없음.
    Raises: 없음.
    Side Effects: 메모리 카운터·최대 sampleLimit개 예시만 갱신한다.
    """
    result["mismatches"][code] += 1
    if len(result["samples"]) < result["sampleLimit"]:
        result["samples"].append({"code": code, "sheet": sheet, "cell": cell, "expected": expected, "actual": actual})


def audit_prediction(native: dict, entry: dict, prediction: dict, sample_limit: int = 8) -> dict:
    """원본 기반 분모를 유지하며 bridge의 행·월·값·근거를 전수 대조한다.

    Args: native: 독립 원본. entry: 평가용 manifest 항목. prediction: 원본만 받은 parse_bytes 결과. sample_limit: 불일치 예시 한도.
    Returns: 행·매출/비용 구분·슬롯·값·출처·상태별 평가. 정제 마스터 선택 정확도는 포함하지 않는다.
    Raises: ValueError, KeyError: 평가 원본·manifest 계약이 유효하지 않은 경우.
    Side Effects: 메모리만 사용하며 원본·파서 결과를 바꾸지 않는다.
    """
    result = {"counts": Counter(), "mismatches": Counter(), "samples": [], "sampleLimit": sample_limit}
    expected = expected_rows(native, entry)
    data = prediction.get("submissionData", {})
    indexed = defaultdict(list)
    for line in data.get("lines", []):
        source = line.get("source", {})
        indexed[(source.get("sheet"), source.get("row"))].append(line)
    previews = defaultdict(list)
    for record in prediction.get("records", []):
        previews[(record["source"].get("sheet"), record["source"].get("cell"))].append(record)
    sources = defaultdict(list)
    for cell in data.get("sourceCells", []):
        sources[(cell.get("sheet"), cell.get("cell"))].append(cell)
    expected_keys = set()
    for row in expected:
        sheet, number = row["sheet"], row["row"]
        expected_keys.add((sheet, number))
        result["counts"]["expectedRows"] += 1
        candidates = indexed.get((sheet, number), [])
        if len(candidates) != 1:
            _mismatch(result, "ROW_MISSING" if not candidates else "ROW_DUPLICATE", sheet, number, 1, len(candidates))
        line = candidates[0] if len(candidates) == 1 else {}
        if line:
            result["counts"]["matchedRows"] += 1
        for key in ("domain", "detailId", "detailRevision"):
            if line and line.get(key) != row[key]:
                _mismatch(result, "ROW_" + key.upper(), sheet, number, row[key], line.get(key))
        organization = line.get("organization", {})
        if line and row["organizationCode"] is not None:
            if organization.get("code") != row["organizationCode"] or organization.get("name") != row["organizationName"]:
                _mismatch(result, "ORGANIZATION_MISMATCH", sheet, number, row["organizationCode"], organization.get("code"))
        elif line and row["organizationError"] and organization.get("status") == "RESOLVED":
            _mismatch(result, "ORGANIZATION_ERROR_RESOLVED", sheet, number, "UNRESOLVED", "RESOLVED")
        elif line and organization.get("status") == "RESOLVED":
            result["counts"]["organizationFallbackRows"] += 1
        slots = defaultdict(list)
        for value in line.get("values", []):
            slots[value.get("sourceCell")].append(value)
        for slot in row["values"]:
            address, amount = slot["cell"], slot["amount"]
            projection = slot["group"] == "next_projection"
            prefix = "projection" if projection else "financial"
            result["counts"][prefix + "Slots"] += 1
            if amount is not None:
                result["counts"][prefix + "NumericSlots"] += 1
                if amount == 0:
                    result["counts"]["zeroSlots"] += 1
                if native[sheet].get(address).kind == "STRING":
                    result["counts"]["numericTextSlots"] += 1
            else:
                result["counts"][prefix + ("MissingSlots" if slot["valueStatus"] == "MISSING" else "UnresolvedSlots")] += 1
            values = slots.get(address, [])
            if len(values) != 1:
                _mismatch(result, "SLOT_MISSING" if not values else "SLOT_DUPLICATE", sheet, address, 1, len(values))
                continue
            value = values[0]
            correct = True
            for field in ("year", "month", "scenario", "valueStatus", "inputPresence", "amount"):
                if value.get(field) != slot[field]:
                    _mismatch(result, "SLOT_" + field.upper(), sheet, address, slot[field], value.get(field))
                    correct = False
            if amount is not None:
                for field in ("sourceUnit", "multiplier"):
                    if value.get(field) != slot[field]:
                        _mismatch(result, "SLOT_" + field.upper(), sheet, address, slot[field], value.get(field))
                        correct = False
                if value.get("amount") == amount and value.get("valueStatus") == "AVAILABLE":
                    result["counts"][prefix + "ExactAmounts"] += 1
            if not value.get("sourceRecordId"):
                _mismatch(result, "SOURCE_RECORD_ID_MISSING", sheet, address)
                correct = False
            source_cells = sources.get((sheet, address), [])
            if len(source_cells) != 1:
                _mismatch(result, "SOURCE_CELL_MISSING" if not source_cells else "SOURCE_CELL_DUPLICATE", sheet, address, 1, len(source_cells))
                correct = False
            elif native[sheet].get(address) and source_cells[0].get("dataType") != native[sheet][address].kind:
                _mismatch(result, "SOURCE_DATA_TYPE", sheet, address, native[sheet][address].kind, source_cells[0].get("dataType"))
                correct = False
            if correct:
                result["counts"][prefix + "ExactSlots"] += 1
            if _present(native[sheet].get(address)) and not previews.get((sheet, address)):
                _mismatch(result, "MAPPING_CANDIDATE_MISSING", sheet, address)
        expected_addresses = {slot["cell"] for slot in row["values"]}
        for address in slots.keys() - expected_addresses:
            _mismatch(result, "UNEXPECTED_SLOT", sheet, str(address))
    for sheet, row in indexed.keys() - expected_keys:
        _mismatch(result, "UNEXPECTED_LINE", str(sheet), row)
    for (sheet, address), candidates in sources.items():
        if len(candidates) != 1:
            continue
        source = native.get(sheet, {}).get(address)
        actual = candidates[0]
        result["counts"]["sourceCellsChecked"] += 1
        if actual.get("rawValue") != (source.raw if source else None):
            _mismatch(result, "SOURCE_RAW_VALUE", str(sheet), str(address), source.raw if source else None, actual.get("rawValue"))
        if actual.get("formula") != (source.formula if source else None):
            _mismatch(result, "SOURCE_FORMULA", str(sheet), str(address), source.formula if source else None, actual.get("formula"))
    result["counts"]["returnedRows"] = len(data.get("lines", []))
    result["bridgeStatus"] = prediction.get("status")
    result["bridgeErrorCode"] = prediction.get("errorCode")
    result["diagnostics"] = dict(Counter(item.get("code", "UNKNOWN") for item in prediction.get("diagnostics", [])))
    result["adapterDiagnostics"] = dict(Counter(item.get("code", "UNKNOWN") for item in data.get("diagnostics", [])))
    result.pop("sampleLimit")
    return result


def audit_file(job: tuple[str, dict, int]) -> dict:
    """파일 한 개를 읽고 생산 parse_bytes 결과와 독립 원본을 비교한다.

    Args: job: 데이터 루트, manifest 항목, 예시 한도 튜플.
    Returns: 해시 전후·유형·월·조직·실행시간 및 원본 대조 결과.
    Raises: OSError, ValueError: 파일 접근·평가 정답 오류.
    Side Effects: 원본을 읽고 CPU·메모리를 사용한다. 파일·로그·캐시는 생성하지 않는다.
    """
    dataset, entry, sample_limit = job
    root = Path(dataset).resolve()
    path = (root / entry["path"]).resolve()
    if not path.is_relative_to(root) or path.suffix.lower() != ".xlsx":
        raise ValueError("평가 대상은 데이터 루트 내부 XLSX여야 합니다")
    started = time.monotonic()
    content = path.read_bytes()
    before = hashlib.sha256(content).hexdigest()
    native = read_native(content)
    prediction = parse_bytes(content, path.name)
    result = audit_prediction(native, entry, prediction, sample_limit)
    after = hashlib.sha256(path.read_bytes()).hexdigest()
    result.update(type=f"{entry['scenario']:02d}", month=entry["close_month"], organization=entry["org_code"],
                  sourceHash=before, sourceUnchanged=before == after, elapsedSeconds=round(time.monotonic() - started, 3))
    if before != after:
        result["mismatches"]["SOURCE_CHANGED"] += 1
    if prediction.get("submissionData", {}).get("sourceSha256") not in (None, before):
        result["mismatches"]["SOURCE_HASH_MISMATCH"] += 1
    return result


def load_jobs(dataset: Path, types: set[int], months: set[int], sample_limit: int = 8) -> list[tuple[str, dict, int]]:
    """manifest에서 마스터를 제외한 조직 파일 평가 대상을 읽는다.

    Args: dataset: 읽기 전용 데이터 루트. types: 유형 번호. months: 마감월. sample_limit: 파일별 예시 한도.
    Returns: 중복 없는 audit_file 입력 목록.
    Raises: OSError, ValueError: manifest 누락·계약 오류·중복 경로.
    Side Effects: manifest JSON만 읽으며 원본이나 파서 입력을 변경하지 않는다.
    """
    jobs, seen = [], set()
    for scenario in sorted(types):
        manifest = json.loads((dataset / "검증기준" / f"manifest_{scenario:02d}.json").read_text(encoding="utf-8"))
        if manifest.get("scenario") != scenario:
            raise ValueError("manifest 유형 번호가 일치하지 않습니다")
        for entry in manifest["files"]:
            if entry["is_master"] or entry["close_month"] not in months:
                continue
            if entry["path"] in seen:
                raise ValueError("중복 평가 파일 경로입니다")
            seen.add(entry["path"])
            jobs.append((str(dataset), entry, sample_limit))
    return jobs


def _numbers(value: str) -> set[int]:
    """쉼표와 범위로 전달한 유형·월 선택을 정수 집합으로 읽는다.

    Args: value: 1,3 또는 1-9 문자열.
    Returns: 1~9 사이의 번호 집합.
    Raises: argparse.ArgumentTypeError: 잘못된 선택식.
    Side Effects: 없음.
    """
    result = set()
    try:
        for part in value.split(","):
            bounds = part.split("-")
            if len(bounds) == 1:
                result.add(int(bounds[0]))
            elif len(bounds) == 2 and int(bounds[0]) <= int(bounds[1]):
                result.update(range(int(bounds[0]), int(bounds[1]) + 1))
            else:
                raise ValueError
        if not result or not result <= set(range(1, 10)):
            raise ValueError
    except ValueError as error:
        raise argparse.ArgumentTypeError("1~9의 쉼표 목록 또는 범위를 입력하세요") from error
    return result


def _summary(results: list[dict], sample_limit: int = 12) -> dict:
    """파일 결과를 유형·마감월 단위의 분모와 불일치 요약으로 합산한다.

    Args: results: 파일별 평가 결과. sample_limit: 합산 예시 한도.
    Returns: 정밀도·포괄성 카운터, bridge 상태, 진단, 제한된 불일치 예시.
    Raises: 없음.
    Side Effects: 메모리만 사용한다.
    """
    counts, mismatches, diagnostics, adapter = Counter(), Counter(), Counter(), Counter()
    samples = []
    for result in results:
        counts.update(result["counts"])
        mismatches.update(result["mismatches"])
        diagnostics.update(result["diagnostics"])
        adapter.update(result["adapterDiagnostics"])
        for sample in result["samples"]:
            if len(samples) < sample_limit:
                samples.append({"type": result["type"], "month": result["month"], "organization": result["organization"], **sample})
    return {"files": len(results), "sourceUnchanged": all(result["sourceUnchanged"] for result in results),
            "bridgeStatuses": dict(Counter(result["bridgeStatus"] for result in results)),
            "bridgeErrors": dict(Counter(result["bridgeErrorCode"] for result in results if result["bridgeErrorCode"])),
            "counts": dict(counts), "mismatches": dict(mismatches), "diagnostics": dict(diagnostics),
            "adapterDiagnostics": dict(adapter), "samples": samples}


def main(argv: list[str] | None = None) -> int:
    """원본 전수 대조를 제한된 병렬 프로세스로 실행하고 콘솔 JSON을 출력한다.

    Args: argv: 선택 CLI 인자. 생략하면 프로세스 인자.
    Returns: 불일치 없으면 0, 대조 불일치 있으면 1.
    Raises: OSError, ValueError: 평가 입력 오류. argparse 종료: 잘못된 CLI 인자.
    Side Effects: 원본 읽기·최대 workers개 프로세스·콘솔 출력만 수행한다. 파일 산출물은 만들지 않는다.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", required=True, type=Path)
    parser.add_argument("--types", type=_numbers, default=set(range(1, 10)))
    parser.add_argument("--months", type=_numbers, default=set(range(1, 10)))
    parser.add_argument("--workers", type=int, choices=range(1, 5), default=2)
    parser.add_argument("--limit", type=int, help="대상 목록의 앞 N개만 진단한다. 생략하면 전수 실행한다.")
    arguments = parser.parse_args(argv)
    if arguments.limit is not None and arguments.limit < 1:
        parser.error("--limit은 양수여야 합니다")
    jobs = load_jobs(arguments.dataset.resolve(), arguments.types, arguments.months)
    if arguments.limit:
        jobs = jobs[:arguments.limit]
    print(json.dumps({"event": "started", "files": len(jobs), "workers": arguments.workers, "scope": "source_preservation_not_clean_master"}, ensure_ascii=False), flush=True)
    results, groups = [], defaultdict(list)
    with ProcessPoolExecutor(max_workers=arguments.workers) as executor:
        for result in executor.map(audit_file, jobs, chunksize=1):
            results.append(result)
            groups[(result["type"], result["month"])].append(result)
            if len(results) % 17 == 0 or len(results) == len(jobs):
                values = groups[(result["type"], result["month"])]
                print(json.dumps({"event": "group", "completed": len(results), "total": len(jobs), "type": result["type"], "month": result["month"], **_summary(values)}, ensure_ascii=False, default=str), flush=True)
    summary = _summary(results)
    print(json.dumps({"event": "summary", **summary}, ensure_ascii=False, default=str), flush=True)
    return 1 if summary["mismatches"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
