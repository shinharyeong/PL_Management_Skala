"""저장 Excel의 실제 셀을 읽어 기존 엄격 평가기와 독립 합계 대조에 연결한다.

생성 상세 JSON·마스터 JSON으로 셀값이나 상태를 보충하지 않는다. 기준 마스터는
완료된 생성 manifest와 모든 대상 Excel을 확인한 별도 평가 명령에서만 읽는다.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import time
from collections import Counter, defaultdict
from decimal import Decimal
from pathlib import Path

from ..extraction import extract_workbook, file_fingerprint
from ..master import _totals
from ..normalization import normalize_number
from .monthly import write_json
from .monthly_evaluation import _aggregate_checks, score_monthly_records
from .monthly_truth import build_master_truth, decimal_amount
from .reconstruction import write_jsonl
from .reconstruction_selection import _checked_path, _index_batches

SCENARIOS = {"실적": "actual", "계획": "plan", "추정": "estimate", "실적·추정": "mixed", "미확정": "unknown"}
PERIODS = {"전년 실적": "prior", "당년 계획": "plan", "당년 실적·추정": "outlook", "차년 계획": "next_plan", "차년 추정": "next_projection"}
DETAIL_SHEETS = {"매출상세": "revenue", "비용상세": "expense"}
TOTAL_SHEETS = {"전사월별합계": "company", "조직월별합계": "organization"}
MONTH_HEADERS = tuple(f"{month}월" for month in range(1, 13))
DETAIL_HEADERS = ("조직코드", "계정코드", "상세 ID", "연도", "구분", "기간 블록", "수정번호", *MONTH_HEADERS,
                  "조직명", "계정명", "원본 파일", "원본 시트", "원본 행", "월별 셀 주소", "월별 금액 상태")
TOTAL_KEY = ("type", "closing_month", "scope", "organization_code", "domain", "account_code", "year", "month", "scenario")


def _allowed() -> Path:
    """생성 Excel과 평가 결과의 허용 경계를 반환한다.

    Args: 없음.
    Returns: comparisons 절대 경로.
    Raises: 없음.
    Side Effects: 폴더를 만들지 않는다.
    """
    return (Path(__file__).resolve().parents[4] / "parser/output/comparisons").resolve()


def _integer(value: object) -> int | None:
    """실제 셀값이 정확한 정수일 때만 정수로 읽는다.

    Args: value - Excel 저장 값.
    Returns: 정수 또는 None.
    Raises: 없음.
    Side Effects: 불리언·빈값·소수값을 정수로 보완하지 않는다.
    """
    if isinstance(value, bool):
        return None
    amount = decimal_amount(value)
    return int(amount) if amount is not None and amount == amount.to_integral_value() else None


def _object_pairs(pairs: list[tuple]) -> dict:
    """Excel 안의 JSON에서 같은 키를 덮어쓰는 중복을 거부한다.

    Args: pairs - JSON 객체의 원래 순서 키·값.
    Returns: 중복 없는 사전.
    Raises: ValueError - 중복 키.
    Side Effects: 메모리만 사용한다.
    """
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("JSON 중복 키")
        result[key] = value
    return result


def _json_map(value: object) -> tuple[dict, bool]:
    """Excel 셀에 실제 저장된 월별 JSON만 읽는다.

    Args: value - 셀의 문자열.
    Returns: 월별 사전과 정상 판독 여부.
    Raises: 없음. 손상은 False로 반환해 평가에 남긴다.
    Side Effects: 외부 JSON을 읽거나 누락 상태를 채우지 않는다.
    """
    try:
        parsed = json.loads(value, object_pairs_hook=_object_pairs) if isinstance(value, str) else None
        valid = isinstance(parsed, dict) and all(re.fullmatch(r"(?:[1-9]|1[0-2])", str(key)) for key in parsed)
        return (parsed, True) if valid else ({}, False)
    except (ValueError, TypeError):
        return {}, False


def _index_sheet(sheet: dict) -> dict[int, dict[int, dict]]:
    """추출된 실제 내용 셀을 행·열로 색인한다.

    Args: sheet - 기존 extract_workbook의 시트 사전.
    Returns: 행 번호에서 열 번호·셀로의 대응.
    Raises: ValueError - 같은 실제 좌표가 중복된 경우.
    Side Effects: 원본 객체를 변경하지 않는다.
    """
    rows = defaultdict(dict)
    for cell in sheet.get("cells", []):
        if cell["column_index"] in rows[cell["row"]]:
            raise ValueError("같은 Excel 좌표가 중복되었습니다")
        rows[cell["row"]][cell["column_index"]] = cell
    return dict(rows)


def _context(rows: dict) -> dict:
    """A3·A4의 실제 유형·마감월·단위·생성 상태를 읽는다.

    Args: rows - 실제 셀 색인.
    Returns: Excel에 있는 문맥만 포함한 사전. 없으면 None.
    Raises: 없음.
    Side Effects: 파일명·generation JSON으로 셀 문맥을 보완하지 않는다.
    """
    title = rows.get(3, {}).get(1, {}).get("value")
    match = re.fullmatch(r"(.+?) · 마감 (\d{4}-(?:0[1-9]|1[0-2])) · 금액 단위 (.+)", str(title))
    result = {"type": match[1] if match else None, "closing_month": match[2] if match else None,
              "unit": match[3] if match else None, "context_valid": match is not None}
    status_text = str(rows.get(4, {}).get(1, {}).get("value") or "")
    status = re.search(r"출력 상태 (.*?) · 원본 품질 (.*?) · 조립 상태 (.*?) · 기대 조직 (\d+) · 반영 조직 (\d+)", status_text)
    result.update(publication_status=status[1] if status else None, source_quality=status[2] if status else None,
                  assembly_status=status[3] if status else None, expected_organizations=int(status[4]) if status else None,
                  included_organizations=int(status[5]) if status else None, metadata_valid=status is not None)
    return result


def _headers(rows: dict, required: tuple[str, ...]) -> dict[str, int] | None:
    """실제 5행 헤더의 유일성과 필수 열을 확인한다.

    Args: rows - 셀 색인. required - 필요한 헤더 이름.
    Returns: 헤더에서 실제 열 번호로의 대응 또는 None.
    Raises: 없음.
    Side Effects: 열 위치를 정답이나 JSON에서 추정하지 않는다.
    """
    result = {}
    for column, cell in rows.get(5, {}).items():
        value = cell.get("value")
        if value is None:
            continue
        if not isinstance(value, str) or value in result:
            return None
        result[value] = column
    return result if all(name in result for name in required) else None


def _money(cell: dict | None, unit: str | None) -> dict:
    """실제 금액 셀을 기존 Python 정규화기로 읽고 빈칸·수식 실패를 보존한다.

    Args: cell - 실제 셀 또는 None. unit - A3에서 읽은 실제 단위.
    Returns: 기존 정규화 결과 사전.
    Raises: 없음.
    Side Effects: 재계산·외부 조회·금액 보충을 하지 않는다.
    """
    cell = cell or {}
    result = normalize_number(cell.get("value"), data_type=cell.get("data_type", "blank"),
                              value_kind="amount", unit=unit, number_format=cell.get("number_format"))
    formula = cell.get("formula")
    if formula and formula.get("cached_status") not in {"stored_unverified", "confirmed"}:
        result = {**result, "status": "unresolved", "normalized_value": None,
                  "diagnostics": result["diagnostics"] + [{"code": "XLSX-FORMULA-CACHE", "message": "실제 Excel 수식 캐시 미확정"}]}
    return result


def _address(row: int, column: int) -> str:
    """실제 행·열 번호를 오류 위치용 Excel 주소로 만든다.

    Args: row, column - 1부터 시작하는 좌표.
    Returns: A1 주소.
    Raises: 없음.
    Side Effects: 메모리만 사용한다.
    """
    label = ""
    while column:
        column, remainder = divmod(column - 1, 26)
        label = chr(65 + remainder) + label
    return f"{label}{row}"


def _detail_records(sheet: dict, rows: dict, context: dict, path: str, issues: list[dict], *, legacy: bool) -> list[dict]:
    """가로 상세를 실제 셀·상태·월 슬롯에 따라 월별 레코드로 되돌린다.

    Args: sheet/rows/context - 추출 시트·색인·실제 문맥. path - 생성 Excel 경로.
        issues - 판독 문제를 추가할 목록. legacy - 이전 Z 상태의 명시 호환 여부.
    Returns: 중복 물리행과 숫자·빈칸을 보존한 상세 목록.
    Raises: 없음. 구조 실패는 문제와 누락으로 남긴다.
    Side Effects: issues만 갱신하며 source JSON·기준 마스터를 읽지 않는다.
    """
    headers = _headers(rows, DETAIL_HEADERS)
    if headers is None:
        issues.append({"code": "detail_headers_missing_or_duplicate", "sheet": sheet["name"]})
        return []
    records = []
    for row_number, cells in sorted(rows.items()):
        if row_number < 6 or not any(cell.get("value") is not None or cell.get("formula") for cell in cells.values()):
            continue
        values = {name: cells.get(column, {}).get("value") for name, column in headers.items()}
        locations, locations_ok = _json_map(values["월별 셀 주소"])
        states, states_ok = _json_map(values["월별 금액 상태"])
        slots = {int(key) for key in locations.keys() | states.keys()}
        slots.update(month for month in range(1, 13) if cells.get(headers[f"{month}월"], {}).get("value") is not None or cells.get(headers[f"{month}월"], {}).get("formula"))
        for month in sorted(slots):
            column = headers[f"{month}월"]
            cell = cells.get(column)
            normalized = _money(cell, context["unit"])
            state = states.get(str(month), {})
            state = state if isinstance(state, dict) else {}
            complete_state = (all(field in state for field in ("confirmed", "semantic_complete", "value_kind", "value", "assembly", "included"))
                              and all(type(state[field]) is bool for field in ("confirmed", "semantic_complete", "included")))
            legacy_state = legacy and not complete_state and all(field in state for field in ("value", "assembly", "included"))
            declared_confirmed = state.get("confirmed") if complete_state else state.get("value") == "confirmed" if legacy_state else None
            declared_semantic = state.get("semantic_complete") if complete_state else state.get("assembly") in {"confirmed", "missing_optional"} if legacy_state else False
            confirmed = normalized["status"] == "confirmed" and declared_confirmed is not False
            if complete_state:
                confirmed = confirmed and state["value"] == "confirmed" and state["value_kind"] == "amount"
            code = values["조직코드"]
            account = values["계정코드"]
            detail_id = values["상세 ID"]
            year = _integer(values["연도"])
            scenario = SCENARIOS.get(values["구분"], values["구분"])
            period_group = PERIODS.get(values["기간 블록"], values["기간 블록"])
            key_present = all(value not in (None, "", "unknown") for value in (code, account, detail_id, year, scenario))
            semantic = (declared_semantic is True and key_present and context["context_valid"] and scenario in {"actual", "plan", "estimate"}
                        and locations_ok and states_ok and isinstance(locations.get(str(month)), str))
            state_valid = complete_state or legacy_state
            if not state_valid or not locations_ok or not states_ok:
                issues.append({"code": "detail_state_unresolved", "sheet": sheet["name"], "cell": _address(row_number, column), "legacy": legacy})
            amount = normalized["normalized_value"]
            reasons = state.get("mapping_reasons", [])
            reasons = list(reasons) if isinstance(reasons, list) else ["invalid_excel_mapping_reasons"]
            if not state_valid:
                reasons.append("Excel 월별 확정 상태 부재·손상; 외부 JSON으로 보충하지 않음")
            if not semantic and normalized["status"] == "confirmed":
                reasons.append("Excel 숫자는 보존하되 업무키 미확정으로 일치 불가")
            record = {"type": context["type"], "closing_month": context["closing_month"], "domain": DETAIL_SHEETS[sheet["name"]],
                      "organization_code": str(code) if code is not None else None, "account_code": str(account) if account is not None else None,
                      "detail_id": str(detail_id) if detail_id is not None else None, "year": year, "month": month, "scenario": scenario,
                      "period_group": period_group, "revision": _integer(values["수정번호"]), "aggregation": "month", "role_kind": "detail",
                      "normalized_value": str(amount) if amount is not None else None, "raw_value": (cell or {}).get("value"),
                      "value_kind": state.get("value_kind", "amount"), "value_status": normalized["status"], "confirmed": confirmed,
                      "semantic_complete": semantic, "source_file": path, "sheet": sheet["name"], "cell": _address(row_number, column),
                      "source_row": row_number, "formula": (cell or {}).get("formula"), "source_unit": context["unit"],
                      "mapping_status": state.get("mapping_status", "legacy_derived" if legacy_state else "unresolved"), "mapping_reasons": reasons,
                      "organization_source_file": values["원본 파일"], "organization_source_sheet": values["원본 시트"],
                      "organization_source_cell": locations.get(str(month)), "organization_source_row": _integer(values["원본 행"]),
                      "declared_excel_state": state, "legacy_state_derived": bool(legacy_state)}
            record["included_in_totals"] = state.get("included") is True and confirmed and semantic
            record["assembly_status"] = state.get("assembly") if state_valid else "unresolved"
            if not confirmed and record["assembly_status"] == "confirmed":
                record["assembly_status"] = "unresolved"
            records.append(record)
    return records


def _total_records(sheet: dict, rows: dict, context: dict, path: str, issues: list[dict], *, legacy: bool) -> list[dict]:
    """전사·조직 합계 시트의 실제 월 금액과 월별 건수를 읽는다.

    Args: sheet/rows/context/path - 실제 Excel 근거. issues - 문제 목록.
        legacy - 이전 합계표의 월별 건수 미측정 허용 여부.
    Returns: 합산하거나 중복 제거하지 않은 실제 합계 셀 목록.
    Raises: 없음. 누락 월별 건수는 None으로 남긴다.
    Side Effects: issues만 갱신하며 상세 JSON으로 합계 셀을 보충하지 않는다.
    """
    scope = TOTAL_SHEETS[sheet["name"]]
    required = (("조직코드",) if scope == "organization" else ()) + ("영역", "계정코드", "연도", "구분", *MONTH_HEADERS, "합산 상세 수")
    headers = _headers(rows, required)
    if headers is None:
        issues.append({"code": "total_headers_missing_or_duplicate", "sheet": sheet["name"]})
        return []
    has_counts = "월별 상세 수" in headers
    if not has_counts and not legacy:
        issues.append({"code": "monthly_total_counts_missing", "sheet": sheet["name"]})
    records = []
    for row_number, cells in sorted(rows.items()):
        if row_number < 6 or not any(cell.get("value") is not None or cell.get("formula") for cell in cells.values()):
            continue
        values = {name: cells.get(column, {}).get("value") for name, column in headers.items()}
        counts, valid = _json_map(values.get("월별 상세 수"))
        if has_counts and (not valid or any(type(count) is not int or count < 0 for count in counts.values())):
            issues.append({"code": "monthly_total_counts_invalid", "sheet": sheet["name"], "row": row_number})
            counts, valid = {}, False
        if valid and sum(counts.values()) != _integer(values["합산 상세 수"]):
            issues.append({"code": "total_row_count_mismatch", "sheet": sheet["name"], "row": row_number})
        slots = {int(key) for key in counts}
        slots.update(month for month in range(1, 13) if cells.get(headers[f"{month}월"], {}).get("value") is not None or cells.get(headers[f"{month}월"], {}).get("formula"))
        for month in sorted(slots):
            column = headers[f"{month}월"]
            normalized = _money(cells.get(column), context["unit"])
            amount = normalized["normalized_value"]
            records.append({"type": context["type"], "closing_month": context["closing_month"], "scope": scope,
                            "organization_code": values.get("조직코드") if scope == "organization" else None,
                            "domain": {"매출": "revenue", "비용": "expense"}.get(values["영역"]),
                            "account_code": str(values["계정코드"]) if values["계정코드"] is not None else None,
                            "year": _integer(values["연도"]), "month": month, "scenario": SCENARIOS.get(values["구분"], values["구분"]),
                            "normalized_value": str(amount) if amount is not None else None, "confirmed": normalized["status"] == "confirmed",
                            "detail_count": counts.get(str(month)), "source_file": path, "sheet": sheet["name"],
                            "cell": _address(row_number, column), "raw_value": cells.get(column, {}).get("value"),
                            "count_measured": valid and str(month) in counts, "legacy_count_unmeasured": legacy and not has_counts})
    return records


def parse_generated_workbook(workbook: dict, *, legacy_compatible: bool = False) -> dict:
    """기존 추출기의 메모리 Excel을 엄격 채점용 실제 출력으로 해석한다.

    Args: workbook - extract_workbook 결과. legacy_compatible - 명시 이전 양식 호환.
    Returns: records, actual_totals, issues, 실제 문맥과 0/빈칸·상태 계수.
    Raises: ValueError - 중복 시트명·실제 좌표.
    Side Effects: 파일·정답·생성 JSON을 읽지 않는다.
    """
    path = workbook["file"]["path"]
    sheets = {}
    for sheet in workbook["sheets"]:
        if sheet["name"] in sheets:
            raise ValueError("같은 시트 이름이 중복되었습니다")
        sheets[sheet["name"]] = sheet
    records, totals, issues, contexts = [], [], [], []
    for name in (*DETAIL_SHEETS, *TOTAL_SHEETS):
        if name not in sheets:
            issues.append({"code": "sheet_missing", "sheet": name})
            continue
        sheet = sheets[name]
        indexed = _index_sheet(sheet)
        context = _context(indexed)
        contexts.append({"sheet": name, **context})
        if not context["context_valid"] or (not legacy_compatible and not context["metadata_valid"]):
            issues.append({"code": "sheet_context_incomplete", "sheet": name})
        if name in DETAIL_SHEETS:
            records.extend(_detail_records(sheet, indexed, context, path, issues, legacy=legacy_compatible))
        else:
            totals.extend(_total_records(sheet, indexed, context, path, issues, legacy=legacy_compatible))
    context_keys = {(row["type"], row["closing_month"], row["unit"]) for row in contexts}
    if len(context_keys) != 1:
        issues.append({"code": "sheet_context_conflict"})
    zero_by_domain = Counter(record["domain"] for record in records if decimal_amount(record["normalized_value"]) == 0)
    return {"records": records, "actual_totals": totals, "issues": issues, "contexts": contexts,
            "legacy_compatible": legacy_compatible, "metadata_json_reads": 0,
            "counts": {"detail_slots": len(records), "numeric_amounts": sum(row["normalized_value"] is not None for row in records),
                       "blank_or_unresolved_amounts": sum(row["normalized_value"] is None for row in records),
                       "actual_zero_amounts": sum(zero_by_domain.values()), "actual_zero_by_domain": dict(zero_by_domain),
                       "confirmed_outputs": sum(row["confirmed"] for row in records),
                       "legacy_derived_slots": sum(row["legacy_state_derived"] for row in records)}}


def read_generated_excel(path: Path, *, legacy_compatible: bool = False) -> dict:
    """승인된 산출물 Excel만 실제로 읽고 판독 전후 불변성을 확인한다.

    Args: path - comparisons 내부 생성 XLSX. legacy_compatible - 명시 호환 경로.
    Returns: 파싱 결과와 Excel SHA256·읽기 시간.
    Raises: OSError, ValueError - 경계·파일·추출·읽기 중 변경 오류.
    Side Effects: 생성 XLSX 한 개만 읽는다. 원본·정답·JSON·API에 접근하지 않는다.
    """
    path = _checked_path(path, _allowed(), exists=True)
    if path.suffix.lower() != ".xlsx":
        raise ValueError("생성 XLSX만 읽습니다")
    started = time.perf_counter()
    before = file_fingerprint(path)
    result = parse_generated_workbook(extract_workbook(path), legacy_compatible=legacy_compatible)
    if file_fingerprint(path) != before:
        raise ValueError("Excel 판독 중 파일이 변경되었습니다")
    result.update(excel_path=str(path), excel_sha256=before, excel_unchanged=True,
                  read_seconds=time.perf_counter() - started)
    return result


def _expected_totals(records: list[dict], *, truth: bool = False) -> list[dict]:
    """실제 상세 또는 독립 정답으로 합계표 셀 대조용 합계를 만든다.

    Args: records - 이미 읽은 상세. truth - 독립 정답이면 알려진 금액을 포함한다.
    Returns: 유형·마감월별 전사·조직·계정 합계와 상세 수.
    Raises: ValueError - 포함 상세의 금액 오류.
    Side Effects: 입력 상세·엄격 점수·합계 검증 규칙을 바꾸지 않는다.
    """
    groups = defaultdict(list)
    for record in records:
        row = dict(record)
        if truth:
            row["included_in_totals"] = decimal_amount(row.get("normalized_value")) is not None
        groups[(row.get("type"), row.get("closing_month"))].append(row)
    result = []
    for (type_name, closing_month), rows in groups.items():
        for scope in ("company", "organization"):
            for total in _totals(rows, by_organization=scope == "organization"):
                result.append({"type": type_name, "closing_month": closing_month, "scope": scope,
                               "organization_code": None, **total})
    return result


def compare_total_cells(actual: list[dict], expected: list[dict], *, basis: str) -> list[dict]:
    """물리 합계 셀을 일대일 대조하며 중복·빈칸·금액·월별 건수 차이를 남긴다.

    Args: actual - Excel 실측 합계 셀. expected - 상세에서 계산한 합계.
        basis - excel_details 또는 reference_details 구분.
    Returns: 모든 합계 키의 실제 셀·기대 값·금액 및 건수 일치 결과.
    Raises: 없음.
    Side Effects: 중복 실제 합계를 더하거나 삭제하지 않는다.
    """
    observed, targets = defaultdict(list), defaultdict(list)
    for row in actual:
        observed[tuple(row.get(field) for field in TOTAL_KEY)].append(row)
    for row in expected:
        targets[tuple(row.get(field) for field in TOTAL_KEY)].append(row)
    result = []
    for key in sorted(observed.keys() | targets.keys(), key=repr):
        outputs, truths = observed[key], targets[key]
        for index in range(max(len(outputs), len(truths))):
            output = outputs[index] if index < len(outputs) else None
            target = truths[index] if index < len(truths) else None
            amount_match = bool(output and target and output.get("confirmed")
                                and decimal_amount(output.get("normalized_value")) == decimal_amount(target.get("normalized_value")))
            count_match = bool(output and target and output.get("count_measured") and output.get("detail_count") == target.get("detail_count"))
            result.append({"basis": basis, **dict(zip(TOTAL_KEY, key)), "matching": amount_match,
                           "count_matching": count_match, "fully_matching": amount_match and count_match,
                           "status": "missing" if output is None else "extra" if target is None else "matched" if amount_match and count_match else "different",
                           "expected_value": (target or {}).get("normalized_value"), "output_value": (output or {}).get("normalized_value"),
                           "expected_detail_count": (target or {}).get("detail_count"), "output_detail_count": (output or {}).get("detail_count"),
                           "source_file": (output or {}).get("source_file"), "sheet": (output or {}).get("sheet"), "cell": (output or {}).get("cell"),
                           "duplicate_actual_key": len(outputs) > 1, "duplicate_expected_key": len(truths) > 1,
                           "count_measured": (output or {}).get("count_measured", False)})
    return result


def evaluate_excel_data(parsed: dict, truth: dict, batch: dict) -> dict:
    """실제 Excel 상세를 기존 엄격 평가기에 그대로 넣고 실제 합계표를 추가 검증한다.

    Args: parsed - 실제 Excel 판독 결과. truth - 독립 정답 변환 결과.
        batch - 생성 완료 목록의 기대 유형·마감월·조직 입력 수.
    Returns: 기존 점수·합계와 Excel 실측 상태·0원·건수·합계표 대조 결과.
    Raises: ValueError - 포함 금액 오류.
    Side Effects: 메모리만 사용하며 정답 분모·키·점수 계산 규칙은 변경하지 않는다.
    """
    records = parsed["records"]
    score = score_monthly_records(records, truth["records"])
    locators = {(r["source_file"], r["sheet"], r["cell"]): r for r in records}
    for error in score["details"]:
        source = locators.get((error["source_file"], error["sheet"], error["cell"]), {})
        error.update({field: source.get(field) for field in ("organization_source_file", "organization_source_sheet", "organization_source_cell", "organization_source_row")})
    total_details = [{**r, "confirmed": bool(r.get("included_in_totals"))} for r in records]
    sums = [{"scope": "company", "organization_code": None, **g} for g in _aggregate_checks(total_details, truth["records"])]
    organizations = {r.get("organization_code") for r in records + truth["records"]}
    for organization in sorted(organizations, key=str):
        sums.extend({"scope": "organization", "organization_code": organization, **g}
                    for g in _aggregate_checks([r for r in total_details if r.get("organization_code") == organization],
                                               [r for r in truth["records"] if r.get("organization_code") == organization]))
    for group in sums:
        group["difference_won"] = str(Decimal(group["output_total"]) - Decimal(group["truth_total"]))
    detail_checks = compare_total_cells(parsed["actual_totals"], _expected_totals(records), basis="excel_details")
    truth_checks = compare_total_cells(parsed["actual_totals"], _expected_totals(truth["records"], truth=True), basis="reference_details")
    issues = list(parsed["issues"])
    contexts = parsed["contexts"]
    context = contexts[0] if contexts else {}
    expected_count = batch["organization_count"]
    source_files = {r["organization_source_file"] for r in records if r.get("organization_source_file")}
    for item in contexts:
        if (item.get("type"), item.get("closing_month")) != (batch["type"], batch["closing_month"]):
            issues.append({"code": "excel_scope_differs_from_manifest", "sheet": item["sheet"]})
        if (item.get("expected_organizations") != expected_count or item.get("included_organizations") != expected_count
                or len(source_files) != expected_count):
            issues.append({"code": "excel_organization_count_mismatch", "sheet": item["sheet"], "expected_manifest": expected_count,
                           "measured_source_files": len(source_files), "declared_expected": item.get("expected_organizations"),
                           "declared_included": item.get("included_organizations")})
    status_keys = {(r.get("publication_status"), r.get("source_quality"), r.get("assembly_status"),
                    r.get("expected_organizations"), r.get("included_organizations")) for r in contexts}
    if len(status_keys) != 1:
        issues.append({"code": "excel_publication_context_conflict"})
    zero_extras = sum(e["status"] == "extra" and decimal_amount(e.get("output_value")) == 0 for e in score["details"])
    nonzero_fp = sum(e["status"] in {"extra", "amount_mismatch"} and decimal_amount(e.get("output_value")) not in (None, Decimal(0)) for e in score["details"])
    totals_match = all(g["matching"] for g in sums)
    stored_verified = bool(detail_checks) and all(g["fully_matching"] for g in detail_checks)
    stored_truth_match = bool(truth_checks) and all(g["matching"] for g in truth_checks)
    summary = {"type": batch["type"], "closing_month": batch["closing_month"],
               "generation_status": context.get("assembly_status"), "publication_status": context.get("publication_status"),
               "source_quality_status": context.get("source_quality"), "expected_organizations": context.get("expected_organizations"),
               "included_organizations": context.get("included_organizations"), "measured_source_file_count": len(source_files),
               **score["summary"], "source_zero_absent_from_reference": zero_extras, "nonzero_false_positive": nonzero_fp,
               "unresolved_required": sum(r.get("assembly_status") == "unresolved" for r in records),
               "optional_blank_count": sum(r.get("assembly_status") == "missing_optional" for r in records),
               "actual_zero_amounts": parsed["counts"]["actual_zero_amounts"],
               "actual_zero_revenue": parsed["counts"]["actual_zero_by_domain"].get("revenue", 0),
               "actual_zero_expense": parsed["counts"]["actual_zero_by_domain"].get("expense", 0),
               "actual_blank_or_unresolved_amounts": parsed["counts"]["blank_or_unresolved_amounts"],
               "stored_totals_verified": stored_verified, "actual_sheet_totals_match_reference": stored_truth_match,
               "actual_sheet_total_groups": len(truth_checks), "actual_sheet_total_matching_groups": sum(g["matching"] for g in truth_checks),
               "actual_sheet_count_matching_groups": sum(g["count_matching"] for g in truth_checks),
               "total_groups": len(sums), "total_matching_groups": sum(g["matching"] for g in sums), "all_totals_match": totals_match,
               "detailed_and_total_compared": True, "excel_structure_issue_count": len(issues), "excel_structure_complete": not issues,
               "strict_detail_and_total_match": score["summary"]["false_positive"] == 0 and score["summary"]["false_negative"] == 0 and totals_match,
               "strict_excel_detail_and_total_match": score["summary"]["false_positive"] == 0 and score["summary"]["false_negative"] == 0 and totals_match and stored_verified and stored_truth_match and not issues,
               "prediction_source": "saved_excel", "legacy_compatible": parsed["legacy_compatible"], "legacy_derived_slots": parsed["counts"]["legacy_derived_slots"],
               "metadata_json_value_reads": 0}
    return {"summary": summary, "errors": score["details"], "by_domain": score["by_domain"], "totals": sums,
            "excel_total_checks": detail_checks + truth_checks, "excel_issues": issues}


def _preflight_run(run_dir: Path, output_dir: Path) -> tuple[dict, list[tuple[dict, Path]], Path]:
    """완료 목록과 모든 생성 Excel을 먼저 확인해 조기 기준 마스터 읽기를 막는다.

    Args: run_dir - 완료 generation.json 실행. output_dir - 신규 평가 경로.
    Returns: 읽은 목록, 배치·실제 Excel 경로 쌍, 검증된 새 출력 경로.
    Raises: ValueError, OSError - 미완료·중복·경계·기존 출력·누락 Excel.
    Side Effects: generation.json과 경로 메타데이터만 읽는다.
    """
    run_dir = _checked_path(run_dir, _allowed(), exists=True)
    output_dir = _checked_path(output_dir, _allowed(), exists=False)
    if output_dir.exists():
        raise ValueError("기존 Excel 평가 결과를 덮어쓰지 않습니다")
    manifest_path = _checked_path(run_dir / "generation.json", _allowed(), exists=True)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    batches = _index_batches(manifest, "Excel evaluation")
    paths = []
    for batch in batches.values():
        path = _checked_path(Path(batch["directory"]) / "master.xlsx", _allowed(), exists=True)
        completion = _checked_path(Path(batch["directory"]) / "workbook_verification.json", _allowed(), exists=True)
        if not path.is_file() or not completion.is_file():
            raise ValueError("모든 생성 Excel이 완료되어야 평가할 수 있습니다")
        paths.append((batch, path))
    if len({path for _, path in paths}) != len(paths):
        raise ValueError("여러 배치가 같은 생성 Excel을 가리킵니다")
    return manifest, paths, output_dir


def evaluate_excel_run(run_dir: Path, output_dir: Path, *, write_output: bool = False,
                       legacy_compatible: bool = False) -> dict:
    """생성 완료 뒤 실제 Excel을 읽고 기준 마스터는 평가 단계에서만 연다.

    Args: run_dir - 완료 생성 실행. output_dir - 새 Excel 평가 폴더.
        write_output - 사용자가 승인한 결과 저장을 명시. legacy_compatible - 이전 양식 호환.
    Returns: 유형·월별 엄격 점수와 실측 Excel·합계 검증 보고서.
    Raises: ValueError, OSError - 완료·경계·변경·쓰기·정답 읽기 오류.
    Side Effects: 생성 XLSX와 평가용 기준 마스터를 읽고 새 평가 파일을 저장한다.
        상세/마스터/내보내기 검증 JSON은 읽지 않으며 기존 Excel을 수정하지 않는다.
    """
    if not write_output:
        raise ValueError("결과 저장에는 --write-output이 필요합니다")
    manifest, paths, output_dir = _preflight_run(run_dir, output_dir)
    output_dir.mkdir(parents=True, exist_ok=False)
    rows, journal = [], []
    started = time.perf_counter()
    for index, (batch, path) in enumerate(paths, 1):
        parsed = read_generated_excel(path, legacy_compatible=legacy_compatible)
        reference = Path(batch["master"])
        before = file_fingerprint(reference)
        truth = build_master_truth(reference, type_name=batch["type"], closing_month=batch["closing_month"])
        if before != file_fingerprint(reference):
            raise ValueError("평가 중 기준 마스터가 변경되었습니다")
        result = evaluate_excel_data(parsed, truth, batch)
        row = result["summary"]
        row.update(excel_path=str(path), excel_sha256=parsed["excel_sha256"], excel_unchanged=parsed["excel_unchanged"],
                   reference_unchanged=True, excel_read_seconds=parsed["read_seconds"])
        target = output_dir / batch["type"] / batch["closing_month"]
        target.mkdir(parents=True)
        for name, payload in (("errors", result["errors"]), ("excel_records", parsed["records"]),
                              ("actual_totals", parsed["actual_totals"]), ("excel_total_checks", result["excel_total_checks"])):
            write_jsonl(target / (name + ".jsonl.gz"), payload)
        for name, payload in (("summary", row), ("totals", result["totals"]), ("by_domain", result["by_domain"]),
                              ("excel_issues", result["excel_issues"]), ("excel_contexts", parsed["contexts"]),
                              ("truth_metadata", {k: v for k, v in truth.items() if k != "records"})):
            write_json(target / (name + ".json"), payload)
        rows.append(row)
        journal.append({"type": batch["type"], "closing_month": batch["closing_month"], "excel": str(path),
                        "excel_sha256": parsed["excel_sha256"], "reference_master": str(reference), "reference_sha256": before,
                        "sequence": "all_generation_and_excel_presence_checked -> actual_excel_read -> reference_master_read -> strict_score",
                        "reference_master_reads": 1, "source_organization_reads": 0, "metadata_json_value_reads": 0})
        print(f"excel-evaluated {index}/{len(paths)} {batch['type']}/{batch['closing_month']} match={row['match_count']}/{row['truth_count']} ExcelTotals={row['actual_sheet_total_matching_groups']}/{row['actual_sheet_total_groups']}", flush=True)
    truth_count = sum(r["truth_count"] for r in rows)
    output_count = sum(r["confirmed_output_count"] for r in rows)
    matched = sum(r["match_count"] for r in rows)
    report = {"command": sys.argv, "rows": rows, "generated_count": len(rows), "detailed_and_total_compared_count": len(rows),
              "strict_detail_and_total_match_count": sum(r["strict_detail_and_total_match"] for r in rows),
              "strict_excel_detail_and_total_match_count": sum(r["strict_excel_detail_and_total_match"] for r in rows),
              "all_totals_match_count": sum(r["all_totals_match"] for r in rows),
              "actual_sheet_totals_match_reference_count": sum(r["actual_sheet_totals_match_reference"] for r in rows),
              "stored_totals_verified_count": sum(r["stored_totals_verified"] for r in rows),
              "reference_masters_read": len(journal), "source_organization_reads": 0, "metadata_json_value_reads": 0,
              "external_api_calls": 0, "generation_implementation_sha256": manifest["implementation_sha256"],
              "legacy_compatible": legacy_compatible, "evaluation_seconds": time.perf_counter() - started,
              "micro": {"truth_count": truth_count, "confirmed_output_count": output_count, "match_count": matched,
                        "false_positive": output_count - matched, "false_negative": truth_count - matched,
                        "precision": matched / output_count if output_count else 0, "recall": matched / truth_count if truth_count else None,
                        "f1": 2 * matched / (output_count + truth_count) if truth_count else None},
              "policy": "actual XLSX fields, values and status only; unchanged strict evaluator and independent truth; no JSON cell substitution"}
    write_json(output_dir / "validation.json", report)
    write_json(output_dir / "provenance.json", journal)
    with (output_dir / "validation.csv").open("x", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=[k for k in rows[0] if k != "error_counts"])
        writer.writeheader()
        writer.writerows({k: v for k, v in row.items() if k != "error_counts"} for row in rows)
    return report


def main() -> None:
    """완료된 생성 Excel 전용 평가 명령을 실행한다.

    Args: 없음. CLI에서 --run, --output, --write-output, --legacy-compatible을 읽는다.
    Returns: 없음.
    Raises: ValueError, OSError - 평가 계약 또는 읽기·쓰기 오류.
    Side Effects: 명시 승인된 새 평가 결과만 저장하고 요약을 콘솔에 출력한다.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--write-output", action="store_true")
    parser.add_argument("--legacy-compatible", action="store_true")
    args = parser.parse_args()
    report = evaluate_excel_run(args.run, args.output, write_output=args.write_output, legacy_compatible=args.legacy_compatible)
    print(json.dumps({key: value for key, value in report.items() if key != "rows"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
