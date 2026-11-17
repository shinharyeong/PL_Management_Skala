"""표준 후보를 바꾸지 않고 제출 검증용 행·월·원본 근거를 메모리로 구성한다.

요청 연도 선택·승인·개정본 선택·DB 저장은 수행하지 않는다. 알려진 직접
SUMIFS와 같은 열의 합계 수식만 보고 항목 근거로 읽으며 수식을 실행하지 않는다.
"""

import hashlib
import json
import re
from collections import defaultdict
from decimal import Decimal

from .business_validation import (
    _column_range, _criterion, _sumifs_arguments,
    _text as _safe_text, build_business_contracts, validate_business_row,
)
from .mapping import (
    _index, _layout, _organization_registry, _periods_with_cross_evidence,
    _row_context, _statement_items, _unit, _workbook_organization,
)
from .ooxml import cell_position, range_bounds

ADAPTER_VERSION = "financial-submission/1.0"
_DOMAINS = {"revenue_detail": "REVENUE", "expense_detail": "COST"}
_UNIT_MULTIPLIERS = {"원": Decimal(1), "천원": Decimal(1000), "백만원": Decimal(1000000), "억원": Decimal(100000000)}
_MAX_REPORT_REFERENCE_VISITS = 200_000


def _column_name(number: int) -> str:
    """양수 열 번호를 원본 주소의 열 문자로 변환한다.

    Args:
        number: 추출 계약의 1 이상 열 번호.
    Returns:
        A부터 XFD 사이의 열 문자.
    Raises:
        없음. 추출 계약의 유효 범위를 전제로 한다.
    Side Effects:
        없음.
    """
    result = ""
    while number:
        number, remainder = divmod(number - 1, 26)
        result = chr(65 + remainder) + result
    return result


def _refs(references: list[dict | None]) -> list[dict]:
    """원본 근거를 경로 없는 고유 시트·셀 쌍으로 줄인다.

    Args:
        references: 표준 provenance 또는 source 참조 목록.
    Returns:
        최초 순서를 유지한 sheet/cell 사전 목록.
    Raises:
        없음. 불완전 참조는 제외한다.
    Side Effects:
        없음. 원본을 수정하지 않는다.
    """
    result, seen = [], set()
    for reference in references:
        if not reference or not reference.get("sheet") or not reference.get("cell"):
            continue
        key = (reference["sheet"], reference["cell"])
        if key not in seen:
            result.append({"sheet": key[0], "cell": key[1]})
            seen.add(key)
    return result


def _remember(context: dict, references: list[dict | None]) -> list[dict]:
    """응답에 필요한 원본 셀 주소를 중복 없이 모은다.

    Args:
        context: 현재 메모리 어댑터 색인.
        references: 보존할 원본 참조 목록.
    Returns:
        정리한 sheet/cell 참조 목록.
    Raises:
        없음.
    Side Effects:
        context의 주소 집합만 갱신한다. 파일을 읽거나 쓰지 않는다.
    """
    references = _refs(references)
    context["source_keys"].update((ref["sheet"], ref["cell"]) for ref in references)
    return references


def _problem(context: dict, code: str, message: str, source: dict | None = None, **scope: object) -> None:
    """요청 범위 선택 후 사용할 제출 검증 문제와 근거를 기록한다.

    Args:
        context: 현재 어댑터 색인.
        code: 고정 문제 코드.
        message: 원문 값이 없는 한국어 조치 안내.
        source: 원본 sheet/cell 또는 None.
        scope: lineKey/year/month/scenario 등의 적용 범위.
    Returns:
        없음.
    Raises:
        없음.
    Side Effects:
        메모리 진단과 원본 조회 주소만 추가한다.
    """
    refs = _remember(context, [source])
    context["diagnostics"].append({
        "code": code, "severity": "ERROR", "stage": "COMPLETENESS",
        "message": message, "source": refs[0] if refs else None, **scope,
    })


def _source_cell(context: dict, sheet: str, address: str) -> dict:
    """원본 셀 또는 확인 가능한 빈 월 슬롯을 공개 SourceCell 형태로 만든다.

    Args:
        context: 추출·표준 후보 색인.
        sheet: 현재 워크북의 시트 이름.
        address: 같은 시트의 원본 또는 확인된 월 슬롯 주소.
    Returns:
        API 계약의 camelCase 스칼라 필드. 수식 공유 follower의 원문은 None일 수 있다.
    Raises:
        KeyError: 현재 워크북에 없는 시트를 전달한 경우.
    Side Effects:
        없음. 수식을 재계산하거나 빈칸을 0으로 보충하지 않는다.
    """
    cell = context["indices"][sheet]["addresses"].get(address)
    record = context["records"].get((sheet, address))
    value = context.get("control_normalization", {}).get((sheet, address), (record or {}).get("value", {}))
    formula = (cell or {}).get("formula")
    raw = (cell or {}).get("value")
    present = formula is not None or raw not in (None, "")
    is_amount = value.get("value_kind") == "amount"
    status = value.get("status", "missing" if not present else "not_numeric")
    return {
        "sheet": sheet, "cell": address, "presence": "PRESENT" if present else "EMPTY",
        "dataType": ((cell or {}).get("data_type") or "blank").upper(),
        "rawValue": raw, "formula": (formula or {}).get("text"),
        "cachedValue": raw if formula else None,
        "cacheStatus": (formula.get("cached_status", "missing").upper() if formula else "NOT_FORMULA"),
        "normalizationStatus": status.upper(),
        "normalizedAmount": value.get("normalized_value") if is_amount else None,
        "sourceUnit": value.get("source_unit") if is_amount else None,
        "multiplier": value.get("multiplier") if is_amount else None,
    }


def _sumifs_contract(expression: str) -> tuple[list[str], Decimal] | None:
    """단일 직접 SUMIFS와 명시한 양수 표시 단위 배율만 한정해 읽는다.

    Args:
        expression: 저장된 수식 문자열.
    Returns:
        SUMIFS 인자와 배율. 추가 연산·다중 합계·미지원 식이면 None.
    Raises:
        없음. 지원하지 않는 식은 미확정으로 남긴다.
    Side Effects:
        없음. 조건이나 수식의 결과를 계산하지 않는다.
    """
    expression = expression.removeprefix("=").strip()
    wrapped = expression.startswith("(")
    if wrapped:
        expression = expression[1:].lstrip()
    if not expression.upper().startswith("SUMIFS("):
        return None
    depth, quote, end = 0, None, None
    for position, character in enumerate(expression[6:], 6):
        if quote:
            if character == quote:
                quote = None
        elif character in {"'", '"'}:
            quote = character
        elif character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
            if depth == 0:
                end = position + 1
                break
    if end is None:
        return None
    suffix = expression[end:].strip()
    if wrapped:
        if not suffix.startswith(")"):
            return None
        suffix = suffix[1:].strip()
    match = re.fullmatch(r"(?:([*/])\s*(\d+(?:\.\d+)?))?", suffix)
    arguments = _sumifs_arguments(expression[:end])
    if not match or len(arguments) != 1 or len(arguments[0]) != 5:
        return None
    factor = Decimal(match.group(2) or "1")
    if factor <= 0:
        return None
    return arguments[0], (Decimal(1) / factor if match.group(1) == "/" else factor)


def _report_criterion(context: dict, expression: str, sheet: str, row: int, domain: str) -> tuple[str, list[dict]]:
    """직접 기준값과 현재 비용 양식의 같은 행 전체열 기준만 해석한다.

    Args:
        context: 현재 워크북의 원본 셀 색인.
        expression: SUMIFS criterion 원문.
        sheet: 보고 수식이 저장된 시트.
        row: 보고 수식의 원본 행.
        domain: REVENUE 또는 COST.
    Returns:
        확인된 기준값과 근거 셀. 비용 E:E는 같은 보고행 E셀로만 한정한다.
    Raises:
        없음.
    Side Effects:
        없음. 임의 범위·동적 수식·다른 시트의 열을 계산하지 않는다.
    """
    cells = {name: index["addresses"] for name, index in context["indices"].items()}
    value, ref = _criterion(expression, sheet, cells)
    if value:
        return value, _refs([ref])
    reference = _column_range(expression, sheet)
    if domain == "COST" and reference == (sheet, 5):
        cell = cells[sheet].get("E" + str(row))
        value = _safe_text(cell)
        if value and not any(character in value for character in "*?~"):
            return value, [{"sheet": sheet, "cell": "E" + str(row)}]
    return "", []


def _control_value(context: dict, sheet: str, cell: dict | None, address: str, period: dict, **identity: str) -> dict:
    """확인된 보고 셀의 저장 캐시를 원 단위 대조값으로 노출한다.

    Args:
        context: 표준 후보와 원본 색인.
        sheet: 보고 시트.
        cell: 실제 보고 셀 또는 None.
        address: 보고 항목과 기간 구조로 확인한 기대 주소.
        period: 대상 월의 원본 기간 근거.
        identity: reportItemKey 또는 calculationCode.
    Returns:
        AVAILABLE/MISSING/UNRESOLVED와 정확한 원본 셀을 가진 대조값.
    Raises:
        없음.
    Side Effects:
        원본 조회 주소만 메모리에 추가한다. 저장 캐시를 재계산 결과로 표시하지 않는다.
    """
    record = context["records"].get((sheet, address), {})
    value = record.get("value", {})
    present = cell is not None and (cell.get("value") not in (None, "") or cell.get("formula"))
    unit = context.get("control_units", {}).get((sheet, address))
    available = value.get("status") == "confirmed" and value.get("value_kind") == "amount" and unit is not None
    amount = None
    if available:
        amount = value["normalized_value"] / value.get("multiplier", Decimal(1)) * _UNIT_MULTIPLIERS[unit]
        context["control_normalization"][(sheet, address)] = {
            **value, "normalized_value": amount, "source_unit": unit,
            "multiplier": _UNIT_MULTIPLIERS[unit],
        }
    _remember(context, [{"sheet": sheet, "cell": address}, *period.get("provenance", [])])
    return {
        **identity, "year": period.get("year"), "month": period.get("month"),
        "scenario": str(period.get("scenario", "unknown")).upper(),
        "amount": amount,
        "valueStatus": "AVAILABLE" if available else "MISSING" if not present else "UNRESOLVED",
        "sourceSheet": sheet, "sourceCell": address,
        "cacheStatus": ((cell or {}).get("formula") or {}).get("cached_status", "missing").upper(),
    }


def _additive_references(expression: str, column: str) -> list[str] | None:
    """같은 열의 양수 합계 참조를 수식 평가 없이 펼친다.

    Args:
        expression: 보고 총계의 원문 SUM 또는 더하기 식.
        column: 해당 월의 보고 열 문자.
    Returns:
        참조 셀 목록. 다른 열·임의 연산·너무 긴 범위면 None.
    Raises:
        없음.
    Side Effects:
        없음. 캐시를 바꾸거나 수식을 실행하지 않는다.
    """
    expression = expression.removeprefix("=").replace("$", "").strip().upper()
    if expression.startswith("SUM(") and expression.endswith(")"):
        parts = expression[4:-1].split(",")
    else:
        parts = expression.split("+")
    refs = []
    for part in parts:
        match = re.fullmatch(rf"{column}(\d+)(?::{column}(\d+))?", part.strip())
        if not match:
            return None
        first, last = int(match.group(1)), int(match.group(2) or match.group(1))
        if not 1 <= first <= last or last - first > 1000:
            return None
        refs.extend(f"{column}{row}" for row in range(first, last + 1))
    return refs


def _report_expression(cell: dict | None) -> str | None:
    """원문 또는 범위가 확인된 가로 공유 합계 수식의 주소만 해석한다.

    Args:
        cell: 보고 셀. 원문을 가진 공유 base 또는 follower일 수 있다.
    Returns:
        직접 원문이나 같은 행·열 상대참조만 옮긴 합계/차감 식. 미지원이면 None.
    Raises:
        없음. 잘못된 공유 범위는 미확정으로 남긴다.
    Side Effects:
        없음. 원본 formula.text·캐시를 변경하거나 수식을 실행하지 않는다.
    """
    formula = (cell or {}).get("formula") or {}
    if formula.get("text"):
        return formula["text"]
    base = formula.get("shared_base") or {}
    attributes, base_attributes = formula.get("attributes", {}), base.get("attributes", {})
    if attributes.get("t") != "shared" or attributes.get("si") != base_attributes.get("si"):
        return None
    expression = base.get("raw_text") or ""
    if not expression or "$" in expression or not base.get("address") or not base_attributes.get("ref"):
        return None
    try:
        base_row, base_column, _ = cell_position(base["address"])
        bounds = range_bounds(base_attributes["ref"])
    except ValueError:
        return None
    if base_row != cell["row"] or not (bounds[0] <= cell["row"] <= bounds[2] and bounds[1] <= cell["column_index"] <= bounds[3]):
        return None
    base_letter, target_letter = _column_name(base_column), cell["column"]
    references = _additive_references(expression, base_letter)
    if references:
        return "+".join(target_letter + str(cell_position(reference)[0]) for reference in references)
    match = re.fullmatch(rf"{base_letter}(\d+)-{base_letter}(\d+)", expression.strip(), re.IGNORECASE)
    return f"{target_letter}{match.group(1)}-{target_letter}{match.group(2)}" if match else None


def _total_leaves(context: dict, sheet: str, address: str, leaf_cells: dict, visited: set[str]) -> list[str] | None:
    """총계의 덧셈 경로가 확인된 같은 월 SUMIFS 말단으로 끝나는지 검사한다.

    Args:
        context: 원본 색인.
        sheet: 현재 보고 시트.
        address: 검사할 총계 셀.
        leaf_cells: 검증된 SUMIFS 주소와 보고 항목 키 대응.
        visited: 현재 재귀 경로의 주소 집합.
    Returns:
        중복 개수를 보존한 말단 키 목록. 순환·미지원 경로·방문 상한 초과면 None.
    Raises:
        없음.
    Side Effects:
        context의 방문 횟수만 증가한다. 합계를 계산하거나 원본을 변경하지 않는다.
    """
    context["reference_visits"] = context.get("reference_visits", 0) + 1
    if context["reference_visits"] > _MAX_REPORT_REFERENCE_VISITS:
        return None
    if address in leaf_cells:
        return [leaf_cells[address]]
    if address in visited or len(visited) > 32:
        return None
    cell = context["indices"][sheet]["addresses"].get(address)
    expression = _report_expression(cell)
    if not expression:
        return None
    refs = _additive_references(expression, cell["column"])
    if not refs:
        return None
    result = []
    for reference in refs:
        leaves = _total_leaves(context, sheet, reference, leaf_cells, visited | {address})
        if leaves is None:
            return None
        result.extend(leaves)
        if len(result) > len(leaf_cells):
            return None
    return result


def _period_key(period: dict) -> tuple:
    """원본 기간의 비교 키를 만든다.

    Args:
        period: 표준 기간 근거.
    Returns:
        연도·월·시나리오·집계 단위의 튜플.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    return tuple(period.get(key) for key in ("year", "month", "scenario", "aggregation"))


def _report_binding(context: dict, sheet: str, cell: dict, period: dict) -> dict | None:
    """단일 조직·분류의 직접 SUMIFS를 상세 열과 보고 항목에 연결한다.

    Args:
        context: 원본·레이아웃·조직 색인.
        sheet: 보고 시트 이름.
        cell: 보고 금액 수식 셀.
        period: 보고 열의 기간 근거.
    Returns:
        조건·단위·기간을 모두 확인한 분류 연결 또는 None.
    Raises:
        없음. 미지원 수식은 추측하지 않는다.
    Side Effects:
        없음. 수식을 계산하지 않는다.
    """
    expression = (cell.get("formula") or {}).get("text") or ""
    contract = _sumifs_contract(expression)
    if not contract:
        return None
    arguments, factor = contract
    source_range = _column_range(arguments[0], sheet)
    if not source_range or source_range[0] not in context["layouts"]:
        return None
    source_sheet, source_column = source_range
    layout = context["layouts"][source_sheet]
    domain = _DOMAINS.get(layout["kind"])
    if not domain or _period_key(layout["periods"].get(source_column, {})) != _period_key(period):
        return None
    source_unit, unit_refs = _unit(context["indices"][source_sheet])
    target_unit, target_refs = _unit(context["indices"][sheet])
    if source_unit not in _UNIT_MULTIPLIERS:
        return None
    target_multiplier = _UNIT_MULTIPLIERS[source_unit] / factor
    inferred_unit = next((unit for unit, multiplier in _UNIT_MULTIPLIERS.items() if multiplier == target_multiplier), None)
    if not inferred_unit or (target_unit is not None and target_unit != inferred_unit):
        return None
    classification_column = layout["columns"].get("관리분류" if domain == "REVENUE" else "실적집계기준")
    organization_column = layout["columns"].get("조직명")
    if not classification_column or not organization_column:
        return None
    conditions = {}
    evidence = [*unit_refs, *target_refs]
    for offset in (1, 3):
        reference = _column_range(arguments[offset], sheet)
        if not reference or reference[0] != source_sheet or reference[1] in conditions:
            return None
        value, refs = _report_criterion(context, arguments[offset + 1], sheet, cell["row"], domain)
        if not value:
            return None
        conditions[reference[1]] = value
        evidence.extend(refs)
    if set(conditions) != {organization_column, classification_column}:
        return None
    if context["owner"].get("status") != "RESOLVED" or conditions[organization_column] != context["owner"].get("name"):
        return None
    item = context["statement_items"][sheet].get(cell["row"], {})
    if not item.get("name") or not item.get("header_path") or conditions[classification_column] != item["name"]:
        return None
    key = json.dumps([domain, item["header_path"]], ensure_ascii=False, separators=(",", ":"))
    return {
        "key": key, "kind": domain, "name": item["name"], "path": item["header_path"],
        "status": "RESOLVED", "sourceSheet": source_sheet,
        "classification": conditions[classification_column], "evidence": _refs(evidence),
        "formulaCell": {"sheet": sheet, "cell": cell["address"]}, "displayUnit": inferred_unit,
    }


def _reports(context: dict) -> tuple[list[dict], dict, list[dict]]:
    """보고 항목과 상세 분류의 유일 대응 및 월별 원본 대조값을 모은다.

    Args:
        context: 레이아웃과 원본 셀 색인.
    Returns:
        보고 항목 목록, 분류별 키 집합, 말단·총계·OP 대조값 목록.
    Raises:
        없음. 미확정 구조는 문제와 UNRESOLVED 대조값으로 보존한다.
    Side Effects:
        메모리 원본 조회 주소와 진단을 추가한다.
    """
    items, classifications, controls = {}, defaultdict(set), []
    for sheet, layout in context["layouts"].items():
        if layout["kind"] != "financial_statement":
            continue
        index, bindings, report_rows = context["indices"][sheet], {}, {}
        for cell in index["addresses"].values():
            period = layout["periods"].get(cell["column_index"])
            if not period or cell["row"] <= layout["header_row"]:
                continue
            binding = _report_binding(context, sheet, cell, period)
            if not binding:
                continue
            key = binding["key"]
            classifications[(binding["kind"], binding["sourceSheet"], binding["classification"])].add(key)
            bindings[cell["address"]] = key
            context["control_units"][(sheet, cell["address"])] = binding["displayUnit"]
            report_rows[cell["row"]] = key
            if key not in items:
                items[key] = {field: binding[field] for field in ("key", "kind", "name", "path", "status", "formulaCell")}
                items[key]["evidence"] = _remember(context, [*binding["evidence"], binding["formulaCell"]])
        monthly = [(column, period) for column, period in layout["periods"].items() if period.get("aggregation") == "month"]
        for row, key in report_rows.items():
            for column, period in monthly:
                address = f"{_column_name(column)}{row}"
                cell = index["addresses"].get(address)
                control = _control_value(context, sheet, cell, address, period, reportItemKey=key)
                if bindings.get(address) != key:
                    control.update(amount=None, valueStatus="MISSING" if not cell or not cell.get("has_content") else "UNRESOLVED")
                controls.append(control)
        totals = {}
        for row, item in context["statement_items"][sheet].items():
            code = "REVENUE_TOTAL" if item.get("name") == "매출" else "COST_TOTAL" if item.get("name") == "영업비용" else "OP" if item.get("code") == "OP" else None
            if code:
                totals.setdefault(code, []).append(row)
        for column, period in monthly:
            letter, verified_totals = _column_name(column), {}
            for code, domain in (("REVENUE_TOTAL", "REVENUE"), ("COST_TOTAL", "COST")):
                rows = totals.get(code, [])
                if len(rows) != 1:
                    continue
                address = f"{letter}{rows[0]}"
                leaves = _total_leaves(context, sheet, address, bindings, set())
                expected = {key for key in report_rows.values() if items[key]["kind"] == domain}
                verified = bool(leaves) and len(leaves) == len(set(leaves)) and set(leaves) == expected
                leaf_units = {context["control_units"].get((sheet, ref)) for ref, key in bindings.items() if ref.startswith(letter) and cell_position(ref)[1] == column and key in expected}
                verified = verified and len(leaf_units) == 1 and None not in leaf_units
                if verified:
                    context["control_units"][(sheet, address)] = next(iter(leaf_units))
                control = _control_value(context, sheet, index["addresses"].get(address), address, period, calculationCode=code)
                if not verified:
                    control.update(amount=None, valueStatus="UNRESOLVED")
                else:
                    verified_totals[code] = address
                controls.append(control)
            rows = totals.get("OP", [])
            if len(rows) == 1:
                address = f"{letter}{rows[0]}"
                cell = index["addresses"].get(address)
                expression = (_report_expression(cell) or "").removeprefix("=").replace("$", "").strip().upper()
                expected = f"{verified_totals.get('REVENUE_TOTAL')}-{verified_totals.get('COST_TOTAL')}"
                total_units = {context["control_units"].get((sheet, ref)) for ref in verified_totals.values()}
                verified = len(verified_totals) == 2 and expression == expected and len(total_units) == 1 and None not in total_units
                if verified:
                    context["control_units"][(sheet, address)] = next(iter(total_units))
                control = _control_value(context, sheet, cell, address, period, calculationCode="OP")
                if not verified:
                    control.update(amount=None, valueStatus="UNRESOLVED")
                controls.append(control)
    if not items:
        _problem(context, "REPORT_ITEMS_UNRESOLVED", "원본 SUMIFS의 조직·보고 분류·기간·단위 근거를 확인해야 합니다.")
    if context.get("reference_visits", 0) > _MAX_REPORT_REFERENCE_VISITS:
        _problem(context, "REPORT_REFERENCE_LIMIT", "보고 합계 참조의 허용 작업량을 초과하여 집계 근거를 확인하지 못했습니다.")
    return list(items.values()), classifications, controls


def _line_value(context: dict, sheet: str, row: int, column: int, period: dict) -> dict:
    """확인한 상세 월 열의 원본 값과 빈칸을 서로 다른 슬롯으로 보존한다.

    Args:
        context: 원본·표준 후보 색인.
        sheet: 상세 시트.
        row: 활성 상세 행.
        column: 확인된 월 열 번호.
        period: 해당 열의 기간 근거.
    Returns:
        금액·상태·원본 주소·레코드 ID가 있는 월 슬롯.
    Raises:
        없음.
    Side Effects:
        원본 조회 주소를 메모리에 추가한다. 빈칸을 0으로 채우지 않는다.
    """
    address = f"{_column_name(column)}{row}"
    cell = context["indices"][sheet]["addresses"].get(address)
    record = context["records"].get((sheet, address), {})
    value, formula = record.get("value", {}), (cell or {}).get("formula")
    present = formula is not None or (cell or {}).get("value") not in (None, "")
    stored_blank = any(start <= column <= end for start, end in context["blank_ranges"][sheet].get(row, []))
    available = (value.get("status") == "confirmed" and value.get("value_kind") == "amount"
                 and value.get("source_unit") in _UNIT_MULTIPLIERS
                 and value.get("multiplier") == _UNIT_MULTIPLIERS[value["source_unit"]])
    _remember(context, [{"sheet": sheet, "cell": address}, *period.get("provenance", [])])
    source_id = record.get("record_id") or "slot:" + hashlib.sha256((context["source_sha256"] + "\0" + sheet + "\0" + address).encode()).hexdigest()
    return {
        "year": period.get("year"), "month": period.get("month"),
        "scenario": str(period.get("scenario", "unknown")).upper(),
        "amount": value.get("normalized_value") if available else None,
        "valueStatus": "AVAILABLE" if available else "MISSING" if not present else "UNRESOLVED",
        "inputPresence": "PRESENT" if present else "STORED_BLANK" if cell or stored_blank else "ABSENT_XML_CELL",
        "sourceCell": address, "sourceRecordId": source_id,
        "sourceUnit": value.get("source_unit"), "multiplier": value.get("multiplier"),
        "formulaCacheStatus": formula.get("cached_status", "missing").upper() if formula else "NOT_FORMULA",
    }


def _lines(context: dict, classifications: dict) -> list[dict]:
    """활성 상세 행의 차원과 확인된 월 슬롯을 제출 검증 단위로 구성한다.

    Args:
        context: 워크북·조직·표준 후보 색인.
        classifications: SUMIFS로 증명한 상세 분류별 보고 키 집합.
    Returns:
        개정·중복을 제거하지 않은 원본 행 목록.
    Raises:
        없음. 필수 정보 누락은 미확정 상태로 보존한다.
    Side Effects:
        메모리 진단과 원본 조회 주소를 추가한다. 후보·원본·금액을 변경하지 않는다.
    """
    result = []
    dimension_fields = {
        "managementCategory": "관리분류", "actualBasis": "실적집계기준", "costType": "비용구분",
        "teamCode": "팀Code", "taskCode": "실행과제Code", "description": "거래내역 및 산출근거",
        "personInCharge": "담당자", "teamName": "팀명", "taskName": "실행과제명", "headquartersName": "본부명",
    }
    for sheet, layout in context["layouts"].items():
        domain = _DOMAINS.get(layout["kind"])
        if not domain:
            continue
        index = context["indices"][sheet]
        monthly = [(column, period) for column, period in layout["periods"].items() if period.get("aggregation") == "month"]
        for row_number, row in sorted(index["rows"].items()):
            if row_number <= layout["header_row"] or (sheet, row_number) in context["inactive_rows"]:
                continue
            money = [row[column] for column, _ in monthly if column in row and (row[column].get("formula") is not None or row[column].get("value") not in (None, ""))]
            meaningful = [row[column] for column in layout["columns"].values() if column in row and _safe_text(row[column])]
            detail_records = context["detail_rows"].get((sheet, row_number), [])
            if not meaningful and not detail_records:
                # 소계와 입력 없는 조회 전용 영역은 금융 상세로 만들지 않는다.
                if not money or all("SUBTOTAL(" in ((cell.get("formula") or {}).get("text") or "").upper() for cell in money):
                    continue
            if not money and not meaningful and not detail_records:
                continue
            seed = next(iter(row.values()))
            row_context = _row_context(seed, index, layout, context["common_org"], context["registry"], {}, inactive_template=False)
            item, organization, dimensions = row_context["item"], row_context["organization"], row_context["dimensions"]
            key = f"{context['sheet_ids'][sheet]}:{row_number}"
            fields, field_sources = {}, {}
            for field, label in dimension_fields.items():
                dimension = dimensions.get(label, {})
                ref = dimension.get("source")
                fields[field] = _safe_text(index["addresses"].get((ref or {}).get("cell", ""))) or None
                field_sources[field] = _remember(context, [ref])
            account_refs = _remember(context, item.get("provenance", []))
            field_sources["account"] = account_refs
            field_sources["detailId"] = field_sources["description"]
            field_sources["organization"] = _remember(context, organization.get("provenance", []))
            classification = fields["managementCategory" if domain == "REVENUE" else "actualBasis"]
            matches = classifications.get((domain, sheet, classification), set())
            report_key = next(iter(matches)) if len(matches) == 1 else None
            validation = validate_business_row(row, layout, context["business_contracts"])
            line = {
                "key": key, "domain": domain, "source": {"sheet": sheet, "row": row_number},
                "organization": {"code": organization.get("code"), "name": organization.get("name"), "status": organization.get("status", "unresolved").upper(), "evidence": field_sources["organization"]},
                "account": {"code": item.get("code"), "name": item.get("name")},
                "detailId": item.get("detail_id"), "detailRevision": item.get("detail_revision"),
                "reportItemKey": report_key, "reportMappingStatus": "RESOLVED" if report_key else "UNRESOLVED",
                **fields, "fieldSources": field_sources, "businessValidation": validation,
                "values": [_line_value(context, sheet, row_number, column, period) for column, period in monthly],
            }
            if not report_key:
                refs = field_sources["managementCategory" if domain == "REVENUE" else "actualBasis"]
                _problem(context, "REPORT_MAPPING_UNRESOLVED", "상세 분류에 유일하게 대응하는 보고 항목을 원본 수식에서 확인해야 합니다.", refs[0] if refs else {"sheet": sheet, "cell": seed["address"]}, lineKey=key)
            result.append(line)
    if not result:
        _problem(context, "ACTIVE_FINANCIAL_LINES_NOT_FOUND", "제출 검증에 사용할 활성 재무 상세 행을 확인하지 못했습니다.")
    return result


def build_submission_data(workbook: dict, mapped: dict) -> dict:
    """표준 추출 결과에서 제출용 행·보고 분류·월 완전성 근거를 분리한다.

    Args:
        workbook: extract_workbook_bytes의 메모리 원본 추출 결과.
        mapped: 같은 바이트에 대한 map_workbook 결과.
    Returns:
        승인 상태를 갖지 않는 financial-submission/1.0 메타데이터.
        모든 원본 기간을 유지하며 요청 연도·유형의 선택과 필수 검증은 백엔드가 맡는다.
    Raises:
        KeyError: 내부 추출·매핑 계약이 손상된 경우.
    Side Effects:
        메모리 자료만 만든다. 입력·파일·DB를 변경하거나 수식을 재계산하지 않는다.
    """
    indices = {sheet["name"]: _index(sheet) for sheet in workbook["sheets"]}
    layouts = {sheet["name"]: _layout(sheet, indices[sheet["name"]]) for sheet in workbook["sheets"]}
    _periods_with_cross_evidence(list(layouts.values()))
    registry = _organization_registry(list(indices.values()))
    common_org = _workbook_organization(list(layouts.values()), list(indices.values()), registry)
    records = {(record["source"]["sheet"], record["source"]["cell"]): record for record in mapped["records"]}
    blank_ranges = {sheet["name"]: defaultdict(list) for sheet in workbook["sheets"]}
    for sheet in workbook["sheets"]:
        for span in sheet.get("blank_spans", []):
            blank_ranges[sheet["name"]][span["row"]].append((span["start_column"], span["end_column"]))
    context = {
        "indices": indices, "layouts": layouts, "records": records, "registry": registry,
        "blank_ranges": blank_ranges,
        "common_org": common_org, "source_sha256": workbook["file"]["file_id"],
        "sheet_ids": {sheet["name"]: str(sheet.get("sheet_id", sheet["name"])) for sheet in workbook["sheets"]},
        "source_keys": set(), "diagnostics": [], "detail_rows": defaultdict(list),
        "control_units": {}, "control_normalization": {},
        "inactive_rows": set(), "business_contracts": build_business_contracts(workbook),
        "statement_items": {name: _statement_items(indices[name], layout) for name, layout in layouts.items() if layout["kind"] == "financial_statement"},
    }
    for record in mapped["records"]:
        row_key = (record["source"]["sheet"], cell_position(record["source"]["cell"])[0])
        if record["role"]["kind"] == "inactive_template":
            context["inactive_rows"].add(row_key)
        elif record["role"]["kind"] == "detail" and record["value"]["value_kind"] == "amount":
            context["detail_rows"][row_key].append(record)
    owner = {"code": common_org.get("code"), "name": common_org.get("name"), "status": common_org.get("status", "unresolved").upper(), "evidence": _remember(context, common_org.get("provenance", []))}
    context["owner"] = owner
    if owner["status"] != "RESOLVED":
        _problem(context, "WORKBOOK_OWNER_UNRESOLVED", "워크북의 단일 소유 조직을 원본 코드표와 상세 행에서 확인해야 합니다.", owner["evidence"][0] if owner["evidence"] else None)
    sheets = []
    for name, layout in layouts.items():
        domain = _DOMAINS.get(layout["kind"])
        if not domain:
            continue
        periods = []
        for column, period in layout["periods"].items():
            if period.get("aggregation") != "month":
                continue
            resolved = bool(period.get("year") and period.get("month") and period.get("scenario") in {"plan", "actual", "estimate"})
            periods.append({"column": _column_name(column), "year": period.get("year"), "month": period.get("month"), "scenario": str(period.get("scenario", "unknown")).upper(), "status": "RESOLVED" if resolved else "UNRESOLVED", "evidence": _remember(context, period.get("provenance", []))})
        sheets.append({"name": name, "domain": domain, "headerRow": layout["header_row"], "periodColumns": periods})
    report_items, classifications, controls = _reports(context)
    lines = _lines(context, classifications)
    return {
        "adapterVersion": ADAPTER_VERSION, "sourceSha256": context["source_sha256"],
        "owner": owner, "sheets": sheets, "reportItems": report_items, "lines": lines,
        "controlValues": controls, "diagnostics": context["diagnostics"],
        "sourceCells": [_source_cell(context, sheet, address) for sheet, address in sorted(context["source_keys"])],
    }
