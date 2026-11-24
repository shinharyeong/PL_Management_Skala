"""월별 원본 양식의 마스터 상세를 평가 전용 고정 계약으로 변환한다.

생산 파서의 매핑과 정규화 결과는 사용하지 않는다. 월별 금액만 평가하며
연간 합계, 소계, 답안지와 코드표는 상세 레코드 정답에 포함하지 않는다.
"""

from collections import Counter, defaultdict
from decimal import Decimal, InvalidOperation
from pathlib import Path
import re

from ..extraction import extract_workbook, file_fingerprint


DETAIL_LAYOUTS = {
    "매출_세부_IFRS15後": {
        "domain": "revenue", "description": 6, "organization": 1,
        "person": 5, "account": 3, "first_row": 5,
        "blocks": ((8, "prior", 2025, "actual"),
                   (22, "plan", 2026, "plan"),
                   (36, "outlook", 2026, "boundary"),
                   (50, "next_plan", 2027, "plan")),
    },
    "OpEx_세부_IFRS15後": {
        "domain": "expense", "description": 15, "organization": 4,
        "person": 14, "account": 10, "first_row": 6,
        "blocks": ((17, "prior", 2025, "actual"),
                   (31, "plan", 2026, "plan"),
                   (45, "outlook", 2026, "boundary"),
                   (59, "next_plan", 2027, "plan"),
                   (73, "next_projection", 2027, "estimate")),
    },
}


def column_letter(column: int) -> str:
    """1부터 시작하는 열 번호를 Excel 열 문자로 변환한다.

    Args:
        column: 양수 열 번호.
    Returns:
        A, Z, AA와 같은 열 문자.
    Raises:
        ValueError: 양수가 아닌 열 번호인 경우.
    Side Effects:
        없음.
    """
    if column < 1:
        raise ValueError("열 번호는 양수여야 합니다")
    label = ""
    while column:
        column, remainder = divmod(column - 1, 26)
        label = chr(65 + remainder) + label
    return label


def decimal_amount(value: object) -> Decimal | None:
    """평가기의 독립적인 유한 십진수 변환을 수행한다.

    Args:
        value: 숫자 또는 숫자 문자열. None과 빈 문자열은 값이 없다.
    Returns:
        쉼표와 괄호 음수를 해석한 Decimal 또는 미확정 None.
    Raises:
        없음. 잘못된 숫자는 None으로 반환한다.
    Side Effects:
        없음.
    """
    if value is None or isinstance(value, bool):
        return None
    text = str(value).strip().replace(",", "")
    if not text:
        return None
    if text.startswith("(") and text.endswith(")"):
        text = "-" + text[1:-1]
    try:
        result = Decimal(text)
    except InvalidOperation:
        return None
    return result if result.is_finite() else None


def snapshot_label(closing_month: str | int) -> str:
    """비교 실행의 마감월을 2026-MM 형식으로 통일한다.

    Args:
        closing_month: 1~12 정수, 2026-MM 또는 2026_MM월마감 문자열.
    Returns:
        2026-MM 문자열.
    Raises:
        ValueError: 2026년 마감월로 해석할 수 없는 경우.
    Side Effects:
        없음.
    """
    if isinstance(closing_month, int):
        month = closing_month
    else:
        match = re.search(r"2026[-_](\d{1,2})", str(closing_month))
        if not match:
            raise ValueError("마감월은 2026-MM 형식이어야 합니다")
        month = int(match.group(1))
    if not 1 <= month <= 12:
        raise ValueError("마감월은 1~12이어야 합니다")
    return f"2026-{month:02d}"


def native_detail_index(workbook: dict) -> dict:
    """상세행 식별자와 고정 월별 열 범위를 원본에서 읽는다.

    Args:
        workbook: 기존 extract_workbook이 생성한 원본 추출 사전.
    Returns:
        시트별 layout, rows, details, monthly_columns 색인. details에는
        실제 설명 ID와 수정 번호 및 원본 행을 보존한다.
    Raises:
        없음. 알려진 상세 시트가 없으면 빈 사전을 반환한다.
    Side Effects:
        메모리 원본만 조회한다. 파일을 읽거나 변경하지 않는다.
    """
    result = {}
    for sheet in workbook.get("sheets", []):
        layout = DETAIL_LAYOUTS.get(sheet["name"])
        if layout is None:
            continue
        rows = defaultdict(dict)
        for cell in sheet["cells"]:
            rows[cell["row"]][cell["column_index"]] = cell
        details = {}
        for number, row in rows.items():
            if number < layout["first_row"]:
                continue
            description = str(row.get(layout["description"], {}).get("value") or "")
            identity = re.search(r"\bID:([^;\]]+)", description)
            organization = row.get(layout["organization"], {}).get("value")
            if identity is None and not (organization and description):
                continue
            revision = re.search(r"수정:(\d+)", description)
            details[number] = {
                "detail_id": identity.group(1).strip() if identity else None,
                "revision": int(revision.group(1)) if revision else None,
                "description_cell": f"{column_letter(layout['description'])}{number}",
            }
        monthly = {}
        for annual, group, year, scenario in layout["blocks"]:
            for month in range(1, 13):
                monthly[annual + month] = {
                    "period_group": group, "year": year, "month": month,
                    "scenario": scenario,
                }
        result[sheet["name"]] = {
            "layout": layout, "rows": dict(rows), "details": details,
            "monthly_columns": monthly,
        }
    return result


def _master_organization(row: dict, layout: dict, detail_id: str | None) -> str | None:
    """마스터 담당자와 상세 ID의 현재 담당 조직을 교차 확인한다.

    Args:
        row: 열 번호별 원본 셀 사전.
        layout: 확인된 상세 시트의 고정 위치 계약.
        detail_id: 설명 셀의 상세 ID 또는 None. 이관 수신 행의 -IN-F코드
            접미사는 현재 수신 조직이며 앞쪽 F코드는 원조직이다.
    Returns:
        두 근거가 일치하거나 하나만 존재하는 F 코드. 충돌하면 None.
    Raises:
        없음.
    Side Effects:
        없음. 조직 원본이나 파서 결과를 조회하지 않는다.
    """
    person = str(row.get(layout["person"], {}).get("value") or "")
    person_match = re.search(r"담당자_(F\d+)\b", person)
    # 이관 수신 ID는 원조직을 이력으로 보존하고 끝의 IN 코드로 수신 조직을 나타낸다.
    identity_match = (re.search(r"-IN-(F\d+)$", detail_id or "")
                      or re.search(r"-(F\d+)-", detail_id or ""))
    codes = {m.group(1) for m in (person_match, identity_match) if m}
    return next(iter(codes)) if len(codes) == 1 else None


def build_master_truth(
    master_path: Path, *, type_name: str, closing_month: str | int,
) -> dict:
    """마스터의 조직별 상세를 독립적인 월별 원화 정답 레코드로 만든다.

    Args:
        master_path: 읽기 전용 마스터 Excel 경로.
        type_name: 검증 유형 식별자. 파서에는 전달하지 않는다.
        closing_month: 실행 단위 마감월. 실제 답안지의 실적/추정 셀과 대조한다.
    Returns:
        records, status, metadata, issues, totals를 가진 사전. 숫자 0은 정답이며
        빈 셀은 별도 미측정 내역이다. 알려진 숫자는 의미가 미확정이어도
        records에 유지하여 정답 분모에서 빠지지 않는다.
    Raises:
        OSError, ValueError: 원본 읽기 또는 마감월 형식 오류.
    Side Effects:
        마스터 내용과 형제 파일의 이름 및 원본 해시를 읽기만 한다.
    """
    master_path = Path(master_path).resolve()
    snapshot = snapshot_label(closing_month)
    workbook = extract_workbook(master_path)
    indices = native_detail_index(workbook)
    issues = []
    records = []
    statuses = {}
    for sheet in workbook["sheets"]:
        if sheet["name"] == "답안지_IFRS15後":
            statuses = {
                cell["column_index"] - 37: cell.get("value")
                for cell in sheet["cells"]
                if cell["row"] == 6 and 38 <= cell["column_index"] <= 49
            }
    boundary_matches = all(
        statuses.get(month) == ("실적" if month <= int(snapshot[-2:]) else "추정")
        for month in range(1, 13)
    )
    if not boundary_matches:
        issues.append({
            "reason": "master_closing_boundary_mismatch",
            "sheet": "답안지_IFRS15後", "cell": "AL6:AW6",
            "closing_month": snapshot, "observed": statuses,
        })
    source_files = {}
    for path in master_path.parent.glob("*.xlsx"):
        match = re.match(r"\d+_(F\d+)_", path.name)
        if match:
            source_files[match.group(1)] = str(path)
    totals = defaultdict(Decimal)
    row_counts = {}
    for sheet_name, index in indices.items():
        layout = index["layout"]
        title = str(index["rows"].get(1, {}).get(1, {}).get("value") or "")
        unit_verified = "천원" in title
        if not unit_verified:
            issues.append({"reason": "master_unit_unresolved", "sheet": sheet_name, "cell": "A1"})
        row_counts[layout["domain"]] = len(index["details"])
        for row_number, detail in index["details"].items():
            row = index["rows"][row_number]
            org = _master_organization(row, layout, detail["detail_id"])
            raw_code = str(row.get(layout["account"], {}).get("value") or "")
            account_match = re.match(r"^(\d+)(?:\s|$)", raw_code.strip())
            account = account_match.group(1) if account_match else None
            for column, period in index["monthly_columns"].items():
                cell = row.get(column, {})
                address = f"{column_letter(column)}{row_number}"
                number = decimal_amount(cell.get("value"))
                scenario = period["scenario"]
                if scenario == "boundary":
                    scenario = {"실적": "actual", "추정": "estimate"}.get(statuses.get(period["month"]))
                base = {
                    "type": type_name, "closing_month": snapshot,
                    "domain": layout["domain"], "organization_code": org,
                    "account_code": account, **period, "scenario": scenario,
                    "detail_id": detail["detail_id"], "revision": detail["revision"],
                    "source_file": str(master_path), "sheet": sheet_name, "cell": address,
                    "organization_file": source_files.get(org),
                    "identity_cell": detail["description_cell"],
                    "normalized_value": str(number * 1000) if number is not None and unit_verified else None,
                    "raw_value": cell.get("value"), "source_unit": "천원" if unit_verified else None,
                }
                if number is None:
                    issues.append({**base, "reason": "master_blank" if cell.get("value") in (None, "") else "master_amount_unresolved"})
                    continue
                if not all((org, account, scenario, detail["detail_id"], unit_verified)):
                    issues.append({**base, "reason": "master_semantics_unresolved"})
                records.append(base)
                if base["normalized_value"] is not None:
                    key = (layout["domain"], period["period_group"], period["year"], period["month"], scenario)
                    totals[key] += Decimal(base["normalized_value"])
    key_counts = Counter((r["organization_code"], r["account_code"], r["year"], r["month"], r["scenario"]) for r in records)
    detail_counts = Counter((r["domain"], r["organization_code"], r["account_code"], r["year"], r["month"], r["scenario"], r["detail_id"]) for r in records)
    return {
        "status": "scored" if records else "not_scored",
        "records": records, "issues": issues,
        "totals": [{"domain": key[0], "period_group": key[1], "year": key[2], "month": key[3], "scenario": key[4], "normalized_value": str(value)} for key, value in totals.items()],
        "metadata": {
            "master_path": str(master_path), "master_sha256": file_fingerprint(master_path),
            "type": type_name, "closing_month": snapshot, "detail_rows": row_counts,
            "truth_count": len(records), "unmeasured_cells": len([i for i in issues if i["reason"] in {"master_blank", "master_amount_unresolved"}]),
            "base_key_duplicate_groups": sum(n > 1 for n in key_counts.values()),
            "detail_key_duplicate_groups": sum(n > 1 for n in detail_counts.values()),
            "key_extension": "설명 셀의 ID를 상세 식별자로 추가; 수정 번호는 보존하고 임의 삭제·합산하지 않음",
            "monthly_scope": "prior,plan,outlook,next_plan,next_projection의 월별 셀; 연간·소계 제외",
            "scenario_evidence": "답안지_IFRS15後!AL6:AW6의 실적/추정",
            "closing_boundary_matches": boundary_matches,
            "normalization": "독립 Decimal × 1000; 마스터 상세 제목의 천원 확인",
            "not_scored_reason": None if records else "마스터에서 조직별 상세 정답을 확인할 수 없음",
        },
    }
