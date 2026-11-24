"""같은 원본의 코드표·집계 수식으로 행 업무필드만 검증한다.

금액 정규화, 조직 추론, 원본 필드 수정과 상세행 선택은 수행하지 않는다.
"""

from __future__ import annotations

import hashlib
import json
import re
from collections import defaultdict


def _text(cell: dict | None) -> str:
    """오류가 아닌 원본 저장값을 비교용 문자열로 읽는다.

    Args:
        cell: 추출 셀 또는 None.
    Returns:
        유효 저장값의 양끝 공백을 제거한 문자열. 빈값·오류·캐시 누락은 빈 문자열.
    Raises:
        없음. 추출 계약을 전제로 한다.
    Side Effects:
        없음. 원문이나 수식을 변경하지 않는다.
    """
    if not cell or cell.get("data_type") == "error":
        return ""
    if (cell.get("formula") or {}).get("cached_status") in {"missing", "error"}:
        return ""
    value = cell.get("value")
    return "" if value is None else str(value).strip()


def _ref(cell: dict | None) -> dict | None:
    """검증 근거를 원본 셀의 최소 참조로 표현한다.

    Args:
        cell: 추출된 근거 셀 또는 None.
    Returns:
        시트·주소·원본 레코드 참조 또는 None.
    Raises:
        없음.
    Side Effects:
        없음. 파일이나 원본 객체를 변경하지 않는다.
    """
    if cell is None:
        return None
    return {
        "sheet": cell.get("sheet"),
        "cell": cell.get("address"),
        "raw_record_id": cell.get("record_id"),
    }


def _unique_refs(refs: list[dict | None]) -> list[dict]:
    """같은 근거 셀의 반복을 제거하여 행 검증 출처를 작게 유지한다.

    Args:
        refs: 원본 참조 또는 None 목록.
    Returns:
        최초 순서를 유지한 고유 참조 목록.
    Raises:
        없음.
    Side Effects:
        없음. 입력 목록을 변경하지 않는다.
    """
    result, seen = [], set()
    for ref in refs:
        if ref is None:
            continue
        key = (ref.get("sheet"), ref.get("cell"), ref.get("raw_record_id"))
        if key not in seen:
            seen.add(key)
            result.append(ref)
    return result


def _sumifs_arguments(formula: str) -> list[list[str]]:
    """SUMIFS의 직접 인자만 따옴표·괄호 경계를 보존해 분리한다.

    Args:
        formula: 원본 Excel 수식 문자열.
    Returns:
        닫는 괄호가 확인된 SUMIFS별 인자 목록. 인자 식은 계산하지 않는다.
    Raises:
        없음. 닫히지 않은 호출은 해석하지 않는다.
    Side Effects:
        없음. 수식 실행이나 파일 접근을 하지 않는다.
    """
    calls = []
    for match in re.finditer(r"\bSUMIFS\s*\(", formula, re.IGNORECASE):
        args, current, quote, depth = [], "", None, 1
        position = match.end()
        while position < len(formula):
            char = formula[position]
            if quote:
                current += char
                if char == quote:
                    if position + 1 < len(formula) and formula[position + 1] == quote:
                        current += formula[position + 1]
                        position += 1
                    else:
                        quote = None
            elif char in {'"', "'"}:
                quote = char
                current += char
            elif char == "(":
                depth += 1
                current += char
            elif char == ")":
                depth -= 1
                if depth == 0:
                    args.append(current.strip())
                    calls.append(args)
                    break
                current += char
            elif char == "," and depth == 1:
                args.append(current.strip())
                current = ""
            else:
                current += char
            position += 1
    return calls


def _sheet_reference(expression: str, default_sheet: str) -> tuple[str, str] | None:
    """외부 파일·계산식을 제외한 직접 시트 참조를 분리한다.

    Args:
        expression: 원본 범위 또는 셀 참조 인자.
        default_sheet: 시트 접두사가 없을 때의 원본 수식 시트.
    Returns:
        시트명과 주소 문자열. 외부 참조이면 None.
    Raises:
        없음.
    Side Effects:
        없음. 참조를 조회하거나 계산하지 않는다.
    """
    if "!" not in expression:
        return default_sheet, expression
    sheet, address = expression.rsplit("!", 1)
    if sheet.startswith("'") and sheet.endswith("'"):
        sheet = sheet[1:-1].replace("''", "'")
    if not sheet or any(char in sheet for char in "[]"):
        return None
    return sheet, address


def _column_range(expression: str, default_sheet: str) -> tuple[str, int] | None:
    """직접 전체 열 범위를 시트와 열 번호로 한정한다.

    Args:
        expression: SUMIFS criteria_range 원문.
        default_sheet: 수식이 저장된 시트.
    Returns:
        동일 열 전체 범위의 시트·열 번호. 부분 범위나 계산식이면 None.
    Raises:
        없음.
    Side Effects:
        없음. 부분 범위로 전체 열의 계약을 추정하지 않는다.
    """
    reference = _sheet_reference(expression, default_sheet)
    if reference is None:
        return None
    sheet, address = reference
    match = re.fullmatch(r"\$?([A-Z]+):\$?\1", address, re.IGNORECASE)
    if match is None:
        return None
    column = 0
    for char in match.group(1).upper():
        column = column * 26 + ord(char) - ord("A") + 1
    return sheet, column


def _criterion(expression: str, sheet: str, cells: dict) -> tuple[str, dict | None]:
    """직접 기준 셀 또는 명시 문자열만 집계 조건의 근거로 읽는다.

    Args:
        expression: SUMIFS criterion 원문.
        sheet: 수식이 저장된 시트.
        cells: 시트명별 주소·추출 셀 색인.
    Returns:
        정확히 일치 비교할 값과 셀 참조. 수식·와일드카드·오류는 빈 값.
    Raises:
        없음.
    Side Effects:
        없음. 수식이나 동적 참조를 계산하지 않는다.
    """
    reference = _sheet_reference(expression, sheet)
    source = None
    if reference and re.fullmatch(r"\$?[A-Z]+\$?\d+", reference[1], re.IGNORECASE):
        source = cells.get(reference[0], {}).get(reference[1].replace("$", "").upper())
        value = _text(source)
    elif re.fullmatch(r'"(?:[^"]|"")*"', expression):
        value = expression[1:-1].replace('""', '"')
    else:
        return "", None
    if any(char in value for char in "*?~") or value.startswith(("<", ">", "=")):
        return "", _ref(source)
    return value, _ref(source)


def _finish_contract(contract: dict) -> None:
    """허용값 집합과 근거 범위의 해시를 메모리 계약에 기록한다.

    Args:
        contract: 값 색인·범위·헤더 근거를 가진 내부 사전.
    Returns:
        없음. contract_digest를 추가한다.
    Raises:
        TypeError: 내부 계약에 JSON 변환 불가 자료가 있는 경우.
    Side Effects:
        전달한 내부 계약만 갱신한다. 원본과 파일은 변경하지 않는다.
    """
    payload = {"values": sorted(contract["values"]), "ranges": contract["ranges"]}
    contract["contract_digest"] = hashlib.sha256(
        json.dumps(payload, ensure_ascii=False, sort_keys=True).encode()
    ).hexdigest()


def build_business_contracts(workbook: dict) -> dict:
    """동일 원본 코드표와 실제 SUMIFS 연결을 행 검증 계약으로 색인한다.

    Args:
        workbook: 추출된 조직 원본. 기존 마스터나 정답을 받지 않는다.
    Returns:
        예산코드 등록 집합과 시트·열별 집계 기준값 집합. 전체 표는 출력하지 않는다.
        직접 해석할 수 없는 기준이 있는 열은 complete=False로 보존한다.
    Raises:
        KeyError: 추출 객체 필수 필드가 없는 경우.
    Side Effects:
        없음. 원본을 읽기만 하며 파일·수식·금액을 변경하지 않는다.
    """
    cells = {
        sheet["name"]: {cell["address"]: cell for cell in sheet["cells"]}
        for sheet in workbook["sheets"]
    }
    accounts = {"values": {}, "provenance": [], "ranges": [], "complete": True}
    categories = {}
    for sheet in workbook["sheets"]:
        rows = defaultdict(dict)
        for cell in sheet["cells"]:
            rows[cell["row"]][cell["column_index"]] = cell
        for number, row in rows.items():
            labels = {_text(cell): column for column, cell in row.items()}
            if not {"예산코드", "예산명칭"} <= labels.keys():
                continue
            code_column, name_column = labels["예산코드"], labels["예산명칭"]
            accounts["provenance"].append(_ref(row[code_column]))
            accounts["ranges"].append(
                {
                    "sheet": sheet["name"],
                    "column": code_column,
                    "first_row": number + 1,
                    "last_row": max(rows),
                }
            )
            for next_number, data in rows.items():
                code_cell, name_cell = data.get(code_column), data.get(name_column)
                code, name = _text(code_cell), _text(name_cell)
                if next_number > number and code and name and code != "예산코드":
                    accounts["values"].setdefault(
                        code, _unique_refs([_ref(code_cell), _ref(name_cell)])
                    )
        for cell in sheet["cells"]:
            formula = (cell.get("formula") or {}).get("text") or ""
            for arguments in _sumifs_arguments(formula):
                for position in range(1, len(arguments) - 1, 2):
                    source_range = _column_range(arguments[position], sheet["name"])
                    if source_range is None or source_range[0] not in cells:
                        continue
                    source_sheet, column = source_range
                    contract = categories.setdefault(source_sheet, {}).setdefault(
                        column,
                        {
                            "values": {},
                            "provenance": [_ref(cell)],
                            "ranges": [{"sheet": source_sheet, "column": column}],
                            "complete": True,
                        },
                    )
                    value, criterion_ref = _criterion(
                        arguments[position + 1], sheet["name"], cells
                    )
                    if value:
                        contract["values"].setdefault(
                            value, _unique_refs([criterion_ref, _ref(cell)])
                        )
                    else:
                        contract["complete"] = False
    _finish_contract(accounts)
    for columns in categories.values():
        for contract in columns.values():
            _finish_contract(contract)
    return {"account_codes": accounts, "aggregation_categories": categories}


def _field_result(
    cell: dict | None, contract: dict | None, *, observed: str | None = None
) -> dict:
    """하나의 원본 필드와 적용 가능한 등록 계약을 비교한다.

    Args:
        cell: 원본 필드 셀.
        contract: 동일 원본의 허용 집합 또는 None.
        observed: 설명에서 읽은 ID처럼 별도 추출된 관측 문자열.
    Returns:
        상태·관측값·최소 출처·선택 계약 해시. 계약 부재는 unverified이다.
    Raises:
        KeyError: 내부 계약 구조가 잘못된 경우.
    Side Effects:
        없음. 원문 또는 호출자 계약을 수정하지 않는다.
    """
    value = _text(cell) if observed is None else observed
    result = {
        "status": "unverified" if value else "missing",
        "observed_value": value or None,
        "provenance": _unique_refs([_ref(cell)]),
    }
    if not contract or not contract["values"] or not contract["complete"]:
        return result
    result["status"] = (
        "valid" if value in contract["values"] else "invalid" if value else "missing"
    )
    result["contract_digest"] = contract["contract_digest"]
    result["contract_ranges"] = contract["ranges"]
    result["provenance"] = _unique_refs(
        result["provenance"] + contract["values"].get(value, contract["provenance"])
    )
    return result


def validate_business_row(row: dict[int, dict], layout: dict, contracts: dict) -> dict:
    """원본 상세행의 업무필드 유효성을 금액·조직 추론과 분리해 반환한다.

    Args:
        row: 하나의 원본행을 열 번호로 색인한 사전. 빈 금액 행에도 사용할 수 있다.
        layout: 기존 파서가 식별한 kind·columns·header_row 계약.
        contracts: build_business_contracts가 같은 원본에서 만든 색인.
    Returns:
        상태·필드별 관측값과 상태·문제·최소 출처. None ID 문자열과 미등록 코드를
        원문 그대로 남기며 invalid로 표시한다. 계약이 없으면 유효성을 추정하지 않는다.
    Raises:
        KeyError: 기존 레이아웃 또는 내부 계약 필수 필드가 없는 경우.
    Side Effects:
        없음. 원본, 정규금액, ID, 계정·조직 코드와 매핑 상태를 변경하지 않는다.
    """
    if layout["kind"] not in {"revenue_detail", "expense_detail"} or not row:
        return {"status": "not_applicable", "issues": [], "provenance": []}
    sample = next(iter(row.values()))
    if sample["row"] <= layout["header_row"]:
        return {"status": "not_applicable", "issues": [], "provenance": []}
    columns = layout["columns"]
    description = row.get(columns.get("거래내역 및 산출근거"))
    identity = re.search(r"\[ID:([^;\]\s]+)\s*;\s*수정:(\d+)\]", _text(description))
    detail_id = _field_result(
        description, None, observed=identity.group(1) if identity else ""
    )
    if identity:
        detail_id["status"] = (
            "invalid"
            if identity.group(1).casefold() in {"none", "null", "nan"}
            else "valid"
        )
    result = {"status": "valid", "detail_id": detail_id, "issues": []}
    if layout["kind"] == "expense_detail":
        result["account_code"] = _field_result(
            row.get(columns.get("예산Code")), contracts.get("account_codes")
        )
    else:
        column = columns.get("관리분류")
        contract = (
            contracts.get("aggregation_categories", {})
            .get(sample["sheet"], {})
            .get(column)
        )
        result["management_category"] = _field_result(row.get(column), contract)
    for field, value in list(result.items()):
        if not isinstance(value, dict):
            continue
        invalid = value.get("status") == "invalid"
        missing_required = (
            value.get("status") == "missing" and "contract_digest" in value
        )
        if not invalid and not missing_required:
            continue
        result["issues"].append(
            {
                "code": "BUSINESS_"
                + field.upper()
                + ("_INVALID" if invalid else "_MISSING"),
                "field": field,
                "message": "원본 업무필드가 명시 식별자 또는 같은 원본 등록·집계 계약을 만족하지 않습니다.",
                "observed_value": value["observed_value"],
                "provenance": value["provenance"],
            }
        )
    if result["issues"]:
        result["status"] = (
            "invalid"
            if any(
                value.get("status") == "invalid"
                for value in result.values()
                if isinstance(value, dict)
            )
            else "unresolved"
        )
    elif not any(
        value.get("status") == "valid"
        for value in result.values()
        if isinstance(value, dict)
    ):
        result["status"] = "not_applicable"
    result["provenance"] = _unique_refs(
        [
            ref
            for value in result.values()
            if isinstance(value, dict)
            for ref in value.get("provenance", [])
        ]
    )
    return result
