"""원본 생성 계약으로 확인한 행 제외를 감사와 발행 상세로 분리한다.

Data의 생성 코드는 실행하거나 import하지 않는다. 고정된 정책 근거만 설명하고
원본에서 파싱한 값·상태·출처를 메모리에서 판정한다. 정답이나 채점은 입력이 아니다.
"""

from __future__ import annotations

import copy
import hashlib
import json
import re
from collections import Counter, defaultdict
from decimal import Decimal, InvalidOperation

POLICY_V4 = "source-contract-v4"
POLICY_EVIDENCE = {
    "business_invalid": "model_data/variants.py:148-168; strict_adapter.py:183",
    "expense_owner_mismatch": "strict_adapter.py:82-97; 원본 답안지 A4/매출 A2/OpEx D3의 제출 조직 기재 지침",
    "missing_required_actual": "model_data/variants.py:140-146 actual[0]=None REJECT_ROW; 필수 actual 월 계약은 [1]",
    "inactive_revenue": "strict_adapter.py:30-32,180-190; prior/plan/actual/next_plan만 사용하고 projection 제외",
    "source_organization_conflict": "model_data/variants.py:148-156,168; 원본 조직명·코드 불일치와 미등록 팀은 REJECT_ROW",
}


def _number(value: object) -> Decimal | None:
    """저장된 유한 정규금액을 읽고 빈값과 실제 0을 구분한다.

    Args:
        value: 이미 정규화된 값.
    Returns:
        유한 Decimal 또는 None.
    Raises:
        없음. 형식 오류와 비유한 값은 None이다.
    Side Effects:
        없음. 원문을 변환하거나 금액을 보완하지 않는다.
    """
    if value is None or isinstance(value, bool) or value == "":
        return None
    try:
        number = Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None
    return number if number.is_finite() else None


def _source(record: dict) -> dict:
    """행 정책 판단에 사용한 원본 셀의 관측 근거를 복사한다.

    Args:
        record: 선택 후의 원본 월별 상세.
    Returns:
        파일·시트·셀·행·해시·기간·원값·정규값·상태·수식 근거.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    fields = ("source_file", "sheet", "cell", "source_row", "source_sha256", "period_group",
              "year", "month", "scenario", "raw_value", "normalized_value", "confirmed",
              "value_status", "value_kind", "formula")
    return copy.deepcopy({key: record.get(key) for key in fields})


def _blank(record: dict) -> bool:
    """실제 원본 빈 금액만 판정하고 수식 캐시 미확정은 빈칸으로 취급하지 않는다.

    Args:
        record: 원값·정규금액·수식 상태를 가진 상세.
    Returns:
        raw_value 필드가 명시적으로 비었고 정규금액·수식이 없으면 True.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    return ("raw_value" in record and record["raw_value"] in (None, "")
            and record.get("normalized_value") in (None, "") and not record.get("formula"))


def _invalid_business_evidence(record: dict) -> list[dict]:
    """필드별 원본 근거가 확인된 업무 무효 사유만 반환한다.

    Args:
        record: 기존 business_validation을 보존한 상세.
    Returns:
        명시 무효 ID 또는 같은 원본 등록 계약 위반의 근거 목록.
        상태만 invalid이거나 근거가 없으면 빈 목록이다.
    Raises:
        없음.
    Side Effects:
        없음. ID로 계정·조직을 추론하지 않는다.
    """
    validation = record.get("business_validation") or {}
    if validation.get("status") != "invalid":
        return []
    evidence = []
    for field in ("detail_id", "account_code", "management_category"):
        item = validation.get(field) or {}
        if item.get("status") != "invalid" or not item.get("provenance"):
            continue
        issues = [issue for issue in validation.get("issues", [])
                  if issue.get("field") == field and issue.get("code") == "BUSINESS_" + field.upper() + "_INVALID"
                  and issue.get("observed_value") == item.get("observed_value") and issue.get("provenance")]
        if not issues:
            continue
        if field == "detail_id":
            verified = (isinstance(item.get("observed_value"), str)
                        and item["observed_value"].casefold() in {"none", "null", "nan"}
                        and record.get("detail_id") == item.get("observed_value"))
        else:
            verified = (bool(re.fullmatch(r"[0-9a-f]{64}", str(item.get("contract_digest", ""))))
                        and bool(item.get("contract_ranges")))
        if verified:
            evidence.append({"field": field, "validation": copy.deepcopy(item), "issues": copy.deepcopy(issues)})
    return evidence


def _owner_evidence(records: list[dict], submissions: dict[str, dict]) -> dict | None:
    """원본 선언 소유자와 확정된 비용 행 조직의 범위 위반만 확인한다.

    Args:
        records: 한 물리 원본행의 선택 상세.
        submissions: 원본 파일별 선택 제출 메타데이터.
    Returns:
        서로 다른 확인된 조직 및 원본 선언 근거 또는 None.
    Raises:
        없음.
    Side Effects:
        없음. 행 조직이나 소유자 값을 바꾸지 않는다.
    """
    first = records[0]
    submission = submissions.get(first.get("source_file"), {})
    declared = submission.get("source_declared_owner") or {}
    owner = submission.get("organization_code")
    organizations = {record.get("organization_code") for record in records}
    if (first.get("domain") != "expense" or len(organizations) != 1 or not owner
            or declared.get("status") != "resolved" or declared.get("code") != owner
            or not declared.get("provenance") or not first.get("organization_provenance")
            or not all(record.get("organization_status") == "resolved" for record in records)):
        return None
    organization = next(iter(organizations))
    if not organization or organization == owner:
        return None
    return {"submission_owner": owner, "row_organization": organization,
            "source_declared_owner": copy.deepcopy(declared),
            "organization_provenance": copy.deepcopy(first.get("organization_provenance", []))}


def _inactive_revenue_evidence(records: list[dict], closing_year: int, closing_month: int) -> list[dict] | None:
    """계약에 명시된 네 금액 그룹의 전체 슬롯이 None/0인 매출행만 확인한다.

    Args:
        records: 한 물리 원본행의 수정본 선택 후 상세.
        closing_year: 제출 기준연도.
        closing_month: 제출 기준월 번호.
    Returns:
        prior/plan/next_plan 전체와 마감 이하 actual의 셀 근거 또는 None.
        미래 estimate와 next_projection은 활성 판단에 참여하지 않는다.
    Raises:
        없음.
    Side Effects:
        없음. 개별 0원을 일괄 제거하지 않으며 슬롯 누락·수식 미확정은 보류한다.
    """
    if (records[0].get("domain") != "revenue"
            or any(record.get("semantic_complete") is not True
                   or (record.get("business_validation") or {}).get("status") in {"invalid", "unresolved"}
                   for record in records)):
        return None
    expected = {(group, year, scenario, month)
                for group, year, scenario, months in (
                    ("prior", closing_year - 1, "actual", range(1, 13)),
                    ("plan", closing_year, "plan", range(1, 13)),
                    ("next_plan", closing_year + 1, "plan", range(1, 13)),
                    ("outlook", closing_year, "actual", range(1, closing_month + 1)))
                for month in months}
    observed = {}
    for record in records:
        key = tuple(record.get(field) for field in ("period_group", "year", "scenario", "month"))
        if key not in expected:
            continue
        if key in observed:
            return None
        observed[key] = record
        if _blank(record):
            continue
        formula = record.get("formula") or {}
        if (record.get("confirmed") is False or record.get("value_status") != "confirmed"
                or record.get("value_kind") != "amount" or _number(record.get("normalized_value")) != 0
                or (formula and formula.get("cached_status") not in {"stored_unverified", "confirmed"})):
            return None
    return [_source(observed[key]) for key in sorted(expected)] if set(observed) == expected else None


def source_organization_rejection(record: dict) -> dict | None:
    """일반 식별 누락과 원본 근거가 있는 조직 충돌 행 제외를 구분한다.

    Args:
        record: 기존 상세 선택에서 detail_identity_unresolved로 제외된 원본 슬롯.
    Returns:
        조직명·셀·충돌 상태가 있고 나머지 식별이 확인되면 계약 근거, 아니면 None.
    Raises:
        없음.
    Side Effects:
        없음. 조직코드를 제출 소유자·ID로 보완하지 않는다.
    """
    required = ("source_file", "sheet", "account_code", "detail_id")
    if (record.get("exclusion_reason") != "detail_identity_unresolved"
            or record.get("domain") not in {"revenue", "expense"}
            or record.get("organization_code") not in (None, "", "unknown")
            or record.get("organization_status") != "conflict"
            or not record.get("organization_name") or not record.get("organization_provenance")
            or any(record.get(field) in (None, "", "unknown") for field in required)
            or str(record.get("detail_id")).casefold() in {"none", "null", "nan"}
            or type(record.get("source_row")) is not int or record["source_row"] < 1
            or type(record.get("year")) is not int or type(record.get("month")) is not int
            or not 1 <= record["month"] <= 12 or record.get("scenario") not in {"actual", "plan", "estimate"}
            or (record.get("business_validation") or {}).get("status") in {"invalid", "unresolved"}):
        return None
    return {"policy_version": POLICY_V4, "reason": "source_organization_conflict",
            "contract_evidence": POLICY_EVIDENCE["source_organization_conflict"],
            "organization_name": record["organization_name"], "organization_status": record["organization_status"],
            "organization_provenance": copy.deepcopy(record["organization_provenance"]),
            "organization_code_corrected": False}


def partition_publication_records(records: list[dict], *, closing_month: str, submissions: list[dict],
                                  policy_name: str = POLICY_V4) -> dict:
    """원본 계약에 따른 행 전체 제외를 발행 상세와 완전한 감사로 분리한다.

    Args:
        records: 수정본·동일 재전송 선택이 끝난 월별 상세.
        closing_month: YYYY-MM 제출 기준월.
        submissions: 원본에서 확인한 선택 제출 메타데이터.
        policy_name: 명시 opt-in 정책 이름 source-contract-v4.
    Returns:
        records, excluded_records, issues, counts, policy_version, contract를 가진 사전.
        제외 행의 모든 기간·0·빈칸·수식·금액·출처는 감사에 보존한다.
    Raises:
        ValueError: 알 수 없는 정책·기준월·물리 원본행 식별 오류.
    Side Effects:
        없음. 입력·금액·키를 바꾸거나 파일·정답·외부 API를 읽지 않는다.
    """
    if policy_name != POLICY_V4 or not re.fullmatch(r"\d{4}-(0[1-9]|1[0-2])", closing_month):
        raise ValueError("알려진 발행 정책과 YYYY-MM 기준월이 필요합니다")
    year, month = map(int, closing_month.split("-"))
    by_file = {submission["source_file"]: submission for submission in submissions if submission.get("source_file")}
    groups = defaultdict(list)
    for record in records:
        row = record.get("source_row")
        if row is None:
            match = re.search(r"(\d+)$", str(record.get("cell", "")))
            row = int(match.group(1)) if match else None
        key = (record.get("source_file"), record.get("sheet"), row, record.get("domain"))
        if not all(key) or type(row) is not int or row < 1:
            raise ValueError("발행 정책에는 확인된 물리 원본행이 필요합니다")
        groups[key].append(record)
    published, excluded, issues, counts = [], [], [], Counter()
    for key, row in sorted(groups.items()):
        reasons, evidence = [], {}
        invalid = next((found for record in row if (found := _invalid_business_evidence(record))), None)
        if invalid:
            reasons.append("business_invalid")
            evidence["business_invalid"] = invalid
        owner = _owner_evidence(row, by_file)
        if owner:
            reasons.append("expense_owner_mismatch")
            evidence["expense_owner_mismatch"] = owner
        missing = [record for record in row if record.get("period_group") == "outlook"
                   and record.get("year") == year and record.get("scenario") == "actual"
                   and record.get("month") == 1 and _blank(record)]
        if missing:
            reasons.append("missing_required_actual")
            evidence["missing_required_actual"] = [_source(record) for record in missing]
            counts["required_actual_blank_slots"] += len(missing)
        if not reasons:
            inactive = _inactive_revenue_evidence(row, year, month)
            if inactive is not None:
                reasons.append("inactive_revenue")
                evidence["inactive_revenue"] = inactive
        if not reasons:
            published.extend(copy.deepcopy(row))
            continue
        rejection = any(reason != "inactive_revenue" for reason in reasons)
        counts["excluded_rows"] += 1
        counts["excluded_slots"] += len(row)
        counts["source_rejected_rows" if rejection else "inactive_revenue_rows"] += 1
        for reason in reasons:
            counts[reason + "_row_count"] += 1
            counts[reason + "_slot_count"] += len(row)
        decision = {"policy_version": POLICY_V4, "reasons": reasons, "evidence": evidence,
                    "contract_evidence": {reason: POLICY_EVIDENCE[reason] for reason in reasons},
                    "source_quality": "rejected" if rejection else "valid_inactive",
                    "all_original_month_slots_preserved": True}
        decision["decision_id"] = hashlib.sha256(json.dumps(decision, ensure_ascii=False, sort_keys=True,
                                                           separators=(",", ":"), default=str).encode("utf-8")).hexdigest()
        reference = {field: copy.deepcopy(decision[field]) for field in (
            "policy_version", "decision_id", "reasons", "source_quality", "all_original_month_slots_preserved")}
        for record in row:
            audit = copy.deepcopy(record)
            audit.update(exclusion_reason="publication_" + reasons[0], included_in_totals=False,
                         assembly_status="excluded_by_publication_policy", publication_decision=copy.deepcopy(reference))
            excluded.append(audit)
        issues.append({"code": "publication_source_row_rejected" if rejection else "publication_inactive_revenue_omitted",
                       "severity": "error" if rejection else "info", "source_file": key[0], "sheet": key[1],
                       "source_row": key[2], "domain": key[3], "slot_count": len(row),
                       "publication_decision": copy.deepcopy(decision)})
    return {"records": published, "excluded_records": excluded, "issues": issues, "counts": dict(counts),
            "policy_version": POLICY_V4, "contract": {"required_actual_months": [1],
            "active_revenue_groups": ["prior", "plan", "next_plan", "outlook:actual_through_closing_month"],
            "future_estimate_in_active_revenue": False, "evidence": copy.deepcopy(POLICY_EVIDENCE)}}
