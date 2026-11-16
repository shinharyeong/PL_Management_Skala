"""조직 원본에서 파싱한 상세만으로 재현 가능한 기술 마스터를 조립한다.

파일 읽기·쓰기와 공식 승인·버전 발급은 수행하지 않는다. 조직 제출본은 전체
교체하며, 상세 수정본 선택과 완전 동일 재전송의 출처를 결과에 모두 보존한다.
"""

from __future__ import annotations

import copy
import hashlib
import json
import re
from collections import defaultdict
from decimal import Decimal, InvalidOperation


def _decimal(value: object) -> Decimal | None:
    """이미 정규화된 값을 유한 Decimal로만 읽는다.

    Args:
        value: 파서의 원화 정규화 값. 빈 값과 불리언은 금액이 아니다.
    Returns:
        유한 Decimal 또는 미확정 None. 원본 금액을 재추정하지 않는다.
    Raises:
        없음. 형식 오류는 None으로 반환한다.
    Side Effects:
        없음.
    """
    if value is None or isinstance(value, bool) or value == "":
        return None
    try:
        number = Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None
    return number if number.is_finite() else None


def _decimal_text(value: Decimal) -> str:
    """Decimal의 값은 보존하고 불필요한 소수점 0만 제거한다.

    Args:
        value: 유한 Decimal.
    Returns:
        지수 표기가 없는 표준 문자열. 양수·음수 0은 모두 0이다.
    Raises:
        ValueError: 비유한 Decimal인 경우.
    Side Effects:
        없음.
    """
    if not value.is_finite():
        raise ValueError("유한 Decimal만 직렬화할 수 있습니다")
    if not value:
        return "0"
    text = format(value, "f")
    return text.rstrip("0").rstrip(".") if "." in text else text


def _json_default(value: object) -> str:
    """재현 해시에 포함할 Decimal을 정밀도 손실 없이 변환한다.

    Args:
        value: JSON 기본 인코더가 처리하지 못한 값.
    Returns:
        Decimal의 표준 문자열.
    Raises:
        TypeError: Decimal 이외의 지원하지 않는 값.
        ValueError: 비유한 Decimal.
    Side Effects:
        없음.
    """
    if isinstance(value, Decimal):
        return _decimal_text(value)
    raise TypeError("마스터 입력의 JSON 직렬화를 지원하지 않습니다")


def _canonical(value: object) -> str:
    """입력 순서와 무관한 사전 직렬화 표현을 만든다.

    Args:
        value: JSON 및 Decimal로 표현할 수 있는 값.
    Returns:
        정렬된 키와 고정 구분자를 사용하는 JSON 문자열.
    Raises:
        TypeError, ValueError: 직렬화 불가 또는 비유한 값.
    Side Effects:
        없음.
    """
    return json.dumps(value, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), default=_json_default, allow_nan=False)


def _fingerprint(value: object) -> str:
    """동일 조립 입력·결과를 식별하는 SHA256을 계산한다.

    Args:
        value: 정규 JSON으로 직렬화할 값.
    Returns:
        SHA256 16진수 문자열.
    Raises:
        TypeError, ValueError: 직렬화할 수 없는 입력.
    Side Effects:
        없음.
    """
    return hashlib.sha256(_canonical(value).encode("utf-8")).hexdigest()


def _version(submission: dict, closing_month: str) -> tuple[int, int] | None:
    """근거가 확인된 제출 메타데이터의 비교 가능한 버전을 읽는다.

    Args:
        submission: version_verified 또는 version_evidence와 제출 버전이 있는 사전.
        closing_month: 조립할 YYYY-MM 마감월.
    Returns:
        회차·개정번호 튜플. 근거 부재, 형식 오류, 다른 마감월이면 None.
    Raises:
        없음.
    Side Effects:
        없음. 파일명이나 상세행 수정번호를 제출 버전으로 사용하지 않는다.
    """
    if not (submission.get("version_verified") is True or submission.get("version_evidence")):
        return None
    text = submission.get("submission_version")
    if isinstance(text, str):
        match = re.fullmatch(r"(\d{4}-\d{2})-(\d+)", text)
        if match:
            return (int(match.group(2)), 0) if match.group(1) == closing_month else None
        match = re.fullmatch(r"V(\d+)(?:\.(\d+))?", text)
        if match:
            return int(match.group(1)), int(match.group(2) or 0)
    revision = submission.get("submission_revision")
    return (0, revision) if type(revision) is int and revision >= 0 else None


def replace_organization(submissions: list[dict], replacement: dict) -> list[dict]:
    """선택된 한 조직의 제출 상태 전체를 새 제출본으로 교체한다.

    Args:
        submissions: 현재 조직별 제출 상태 목록. 입력 객체는 수정하지 않는다.
        replacement: 원본에서 확인한 organization_code와 records를 가진 새 제출본.
    Returns:
        해당 조직의 이전 항목을 모두 제거하고 새 항목 한 개를 넣은 복사본.
        동일 입력을 다시 교체해도 결과가 같으며 다른 조직은 그대로 유지한다.
    Raises:
        ValueError: 조직 코드 또는 records 목록이 없는 경우.
    Side Effects:
        없음. 디스크·기존 마스터·원본 파일에 접근하지 않는다.
    """
    organization = replacement.get("organization_code")
    if not isinstance(organization, str) or not organization.strip() or not isinstance(replacement.get("records"), list):
        raise ValueError("교체 제출본에는 확인된 organization_code와 records가 필요합니다")
    result = [copy.deepcopy(s) for s in submissions if s.get("organization_code") != organization]
    result.append(copy.deepcopy(replacement))
    return sorted(result, key=lambda item: (str(item.get("organization_code")), _fingerprint(item)))


def _select_submissions(submissions: list[dict], closing_month: str) -> tuple[list[dict], list[dict], list[dict]]:
    """조직별 단일 제출본 또는 확인된 최고 버전을 선택한다.

    Args:
        submissions: 원본 조직 코드·해시·선택 메타데이터·상세를 가진 제출 목록.
        closing_month: 조립 마감월.
    Returns:
        선택 제출본, 선택되지 않은 제출 요약, 문제 내역의 세 목록.
        완전히 같은 입력 재전송은 한 번 반영하고 횟수를 기록한다.
    Raises:
        TypeError, ValueError: 입력을 재현 JSON으로 만들 수 없는 경우.
    Side Effects:
        없음. 원본과 전달받은 사전을 변경하지 않는다.
    """
    groups = defaultdict(list)
    issues = []
    excluded = []
    selected = []
    for submission in submissions:
        organization = submission.get("organization_code")
        if not organization or not isinstance(submission.get("records"), list):
            issues.append({"code": "submission_identity_unresolved", "source_file": submission.get("source_file"), "severity": "error"})
            excluded.append({"source_file": submission.get("source_file"), "reason": "submission_identity_unresolved"})
            continue
        groups[organization].append(submission)
    for organization, candidates in sorted(groups.items()):
        unique = {}
        for candidate in candidates:
            unique.setdefault(_fingerprint(candidate), candidate)
        options = list(unique.values())
        chosen = None
        reason = "unique_source"
        if len(options) == 1:
            chosen = options[0]
            if len(candidates) > 1:
                reason = "exact_duplicate_submission"
        else:
            versions = [_version(item, closing_month) for item in options]
            if all(version is not None for version in versions):
                highest = max(versions)
                winners = [item for item, version in zip(options, versions) if version == highest]
                if len(winners) == 1:
                    chosen = winners[0]
                    reason = "highest_verified_submission_version"
        if chosen is None:
            chosen_hash = None
            issues.append({"code": "submission_selection_unresolved", "organization_code": organization,
                           "severity": "error", "candidate_count": len(options)})
        else:
            chosen_hash = _fingerprint(chosen)
            chosen = copy.deepcopy(chosen)
            chosen["selection_reason"] = reason
            chosen["duplicate_submission_count"] = len(candidates) - len(options)
            selected.append(chosen)
        for digest, candidate in unique.items():
            if digest != chosen_hash:
                excluded.append({"organization_code": organization, "source_file": candidate.get("source_file"),
                                 "source_sha256": candidate.get("source_sha256"),
                                 "submission_version": candidate.get("submission_version"),
                                 "reason": "superseded_submission" if chosen is not None else "submission_selection_unresolved"})
    return selected, excluded, issues


def _business_payload(record: dict) -> dict:
    """중복 전달을 확인할 한 월 상세의 의미·금액·상태를 만든다.

    Args:
        record: 원본 출처가 포함된 평탄 파서 레코드.
    Returns:
        출처 좌표를 제외한 업무 필드와 원본 수식 캐시 상태. 금액 표현만 통일한다.
        정규금액이 미확정이면 서로 다른 원문·수식을 같은 빈값으로 묶지 않는다.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    fields = ("domain", "organization_code", "account_code", "detail_id", "year", "month",
              "scenario", "period_group", "aggregation", "role_kind", "value_kind", "value_status", "semantic_complete")
    payload = {field: record.get(field) for field in fields}
    validation = record.get("business_validation") or {}
    payload["business_validation_status"] = validation.get("status")
    payload["business_field_states"] = {
        field: {key: validation.get(field, {}).get(key) for key in ("status", "observed_value")}
        for field in ("detail_id", "account_code", "management_category")
    }
    amount = _decimal(record.get("normalized_value"))
    payload["normalized_value"] = _decimal_text(amount) if amount is not None else None
    payload["formula_cache_status"] = (record.get("formula") or {}).get("cached_status")
    payload["raw_blank"] = "raw_value" in record and record["raw_value"] in (None, "") and not record.get("formula")
    if amount is None:
        payload["unresolved_raw_value"] = record.get("raw_value")
        payload["unresolved_formula"] = copy.deepcopy(record.get("formula"))
    return payload


def _source_reference(record: dict) -> dict:
    """선택·중복·제외의 원본 근거를 보존한다.

    Args:
        record: 평탄 파서 레코드.
    Returns:
        파일·시트·셀·해시·원값·정규값·수정번호·수식 상태의 복사본.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    fields = ("source_file", "sheet", "cell", "source_row", "source_sha256", "raw_value",
              "normalized_value", "revision", "value_status", "formula", "submission_owner", "business_validation")
    return copy.deepcopy({field: record.get(field) for field in fields})


def _select_detail_rows(records: list[dict]) -> tuple[list[dict], list[dict], list[dict]]:
    """상세 ID별 원본행의 전체 월별 내용으로 개정·동일 재전송을 판정한다.

    Args:
        records: 선택 제출본들의 평탄 월별 상세. cell 또는 source_row가 필요하다.
    Returns:
        선택 상세, 제외·미확정 원본 상세, 문제 내역. 최고 수정번호의 모든
        월별 의미·금액·상태가 동일할 때만 한 번 반영하고 각 출처를 보존한다.
        원본에서 무효로 확인한 문자열 ID는 물리행별로 격리하며 수정본 선택이나
        동일 재전송 판정에 사용하지 않는다. 관측된 ID·금액은 그대로 남긴다.
    Raises:
        TypeError, ValueError: 업무 내용을 JSON으로 직렬화할 수 없는 경우.
    Side Effects:
        없음. 정답·마스터·파일에 접근하지 않으며 원본을 수정하지 않는다.
    """
    identities = defaultdict(lambda: defaultdict(list))
    excluded = []
    issues = []
    details = []
    for record in records:
        identity = tuple(record.get(field) for field in ("domain", "organization_code", "detail_id"))
        row = record.get("source_row")
        match = re.search(r"(\d+)$", str(record.get("cell", "")))
        if row is None and match:
            row = int(match.group(1))
        if not all(identity) or not record.get("source_file") or not record.get("sheet") or type(row) is not int or row < 1:
            excluded.append({**copy.deepcopy(record), "exclusion_reason": "detail_identity_unresolved"})
            issues.append({"code": "detail_identity_unresolved", "severity": "error", **_source_reference(record)})
            continue
        if (record.get("business_validation") or {}).get("detail_id", {}).get("status") == "invalid":
            detail = copy.deepcopy(record)
            detail.update(selection_reason="unvalidated_identity_preserved_by_source_location",
                          source_occurrences=[_source_reference(record)], duplicate_delivery_count=0)
            details.append(detail)
            continue
        location = (str(record.get("source_file")), str(record.get("sheet")), row)
        identities[identity][location].append(record)
    for identity, native_rows in sorted(identities.items()):
        candidates = []
        invalid_revision = False
        for location, row_records in sorted(native_rows.items()):
            revisions = {record.get("revision") for record in row_records}
            if len(revisions) != 1 or any(value is not None and (type(value) is not int or value < 0) for value in revisions):
                invalid_revision = True
            revision = next(iter(revisions)) if len(revisions) == 1 else None
            payloads = {}
            sources = defaultdict(list)
            for record in row_records:
                signature = _canonical(_business_payload(record))
                payloads.setdefault(signature, record)
                sources[signature].append(_source_reference(record))
            slots = defaultdict(set)
            for signature, record in payloads.items():
                slot = tuple(record.get(field) for field in ("year", "month", "scenario", "period_group"))
                slots[slot].add(signature)
            if any(len(signatures) > 1 for signatures in slots.values()):
                invalid_revision = True
            candidates.append({"location": location, "revision": revision, "records": row_records,
                               "payloads": payloads, "sources": dict(sources), "signature": tuple(sorted(payloads))})
        winners = candidates
        reason = "unique_detail_row"
        if len(candidates) > 1 and not invalid_revision:
            revisions = [candidate["revision"] for candidate in candidates]
            if all(revision is not None for revision in revisions):
                highest = max(revisions)
                winners = [candidate for candidate in candidates if candidate["revision"] == highest]
                reason = "highest_source_revision"
            elif len({candidate["signature"] for candidate in candidates}) != 1:
                invalid_revision = True
        conflict = invalid_revision or len({candidate["signature"] for candidate in winners}) != 1
        if conflict:
            issues.append({"code": "detail_revision_conflict", "severity": "error",
                           "domain": identity[0], "organization_code": identity[1], "detail_id": identity[2],
                           "sources": [_source_reference(candidate["records"][0]) for candidate in candidates]})
            for candidate in candidates:
                excluded.extend({**copy.deepcopy(record), "exclusion_reason": "detail_revision_conflict"}
                                for record in candidate["records"])
            continue
        if len(winners) > 1:
            reason = "exact_duplicate_delivery"
            issues.append({"code": reason, "severity": "info", "detail_id": identity[2],
                           "organization_code": identity[1], "delivery_count": len(winners)})
        winner = winners[0]
        winner_locations = {candidate["location"] for candidate in winners}
        for candidate in candidates:
            if candidate["location"] not in winner_locations:
                excluded.extend({**copy.deepcopy(record), "exclusion_reason": "superseded_detail_revision"}
                                for record in candidate["records"])
        for signature, record in sorted(winner["payloads"].items()):
            detail = copy.deepcopy(record)
            occurrences = [item for candidate in winners for item in candidate["sources"][signature]]
            detail.update(selection_reason=reason, source_occurrences=occurrences,
                          duplicate_delivery_count=len(occurrences) - 1)
            details.append(detail)
    return details, excluded, issues


def _amount_eligibility(record: dict) -> tuple[Decimal | None, str | None]:
    """월별 상세의 의미·숫자·빈칸·수식 상태를 합계 전에 확인한다.

    Args:
        record: 선택된 평탄 원화 레코드.
    Returns:
        합산 가능한 Decimal과 None, 또는 None과 미확정 사유.
        업무키 검증 실패는 숫자 금액 확정 상태와 별도로 합계에서 제외한다.
    Raises:
        없음.
    Side Effects:
        없음. 누락 금액을 0으로 바꾸거나 수식을 재계산하지 않는다.
    """
    fields = ("domain", "organization_code", "account_code", "detail_id", "year", "month", "scenario")
    if (record.get("business_validation") or {}).get("status") in {"invalid", "unresolved"}:
        return None, "business_key_unresolved"
    if any(record.get(field) in (None, "", "unknown") for field in fields) or record.get("semantic_complete") is not True:
        return None, "semantic_unresolved"
    if record.get("scenario") not in {"actual", "plan", "estimate"} or type(record.get("year")) is not int:
        return None, "period_unresolved"
    if type(record.get("month")) is not int or not 1 <= record["month"] <= 12:
        return None, "period_unresolved"
    formula = record.get("formula") or {}
    if formula and formula.get("cached_status") not in {"stored_unverified", "confirmed"}:
        return None, "formula_cache_unresolved"
    if "raw_value" in record and record["raw_value"] in (None, "") and not formula:
        return None, "blank_amount"
    if record.get("value_status") != "confirmed" or record.get("value_kind") != "amount" or record.get("confirmed") is False:
        return None, "amount_unconfirmed"
    amount = _decimal(record.get("normalized_value"))
    return (amount, None) if amount is not None else (None, "amount_unresolved")


def _exact_sum(values: list[Decimal]) -> Decimal:
    """전역 Decimal 정밀도에 영향을 받지 않고 유한 금액을 합산한다.

    Args:
        values: 유한 Decimal 목록.
    Returns:
        정수 계수로 자릿수를 정렬하여 계산한 정확한 Decimal 합. 빈 목록은 0.
    Raises:
        ValueError: 비유한 값이 포함된 경우.
    Side Effects:
        없음. 전역 Decimal context를 변경하지 않는다.
    """
    if not values:
        return Decimal(0)
    if any(not value.is_finite() for value in values):
        raise ValueError("유한 금액만 합산할 수 있습니다")
    exponent = min(value.as_tuple().exponent for value in values)
    total = 0
    for value in values:
        sign, digits, scale = value.as_tuple()
        coefficient = int("".join(str(digit) for digit in digits))
        total += (-1 if sign else 1) * coefficient * 10 ** (scale - exponent)
    digits = tuple(int(digit) for digit in str(abs(total)))
    return Decimal((1 if total < 0 else 0, digits, exponent))


def _totals(details: list[dict], *, by_organization: bool) -> list[dict]:
    """선택 상세를 유지한 채 별도 조직별 또는 전사 월별 합계를 만든다.

    Args:
        details: 포함 가능 여부가 판정된 선택 상세.
        by_organization: True이면 조직 코드를 합계 키에 추가한다.
    Returns:
        영역·계정·연월·구분별 원화 합계와 상세 수. 미확정은 합산하지 않는다.
    Raises:
        ValueError: 합산 가능으로 선언된 레코드의 금액이 유효하지 않은 경우.
    Side Effects:
        없음. 원본 상세를 합계 레코드로 대체하지 않는다.
    """
    fields = (["organization_code"] if by_organization else []) + ["domain", "account_code", "year", "month", "scenario"]
    groups = defaultdict(list)
    for detail in details:
        if detail["included_in_totals"]:
            groups[tuple(detail[field] for field in fields)].append(_decimal(detail["normalized_value"]))
    return [{**dict(zip(fields, key)), "normalized_value": _decimal_text(_exact_sum(values)),
             "currency": "KRW", "unit": "원", "detail_count": len(values)}
            for key, values in sorted(groups.items(), key=lambda item: repr(item[0]))]


def assemble_master(
    submissions: list[dict], expected_organizations: list[str], closing_month: str,
    type_name: str, *, publication_policy: str | None = None,
) -> dict:
    """조직 제출 상태를 전체 조립하여 동일 입력의 중복 누적을 방지한다.

    Args:
        submissions: 원본 확인 조직코드·source_file·source_sha256·records 목록.
            여러 제출본의 우선순위에는 version_verified/version_evidence가 필요하다.
            records는 원화 금액과 의미·상태·원본좌표를 가진 평탄 월별 상세이다.
        expected_organizations: 입력 목록에서 확인한 기대 조직 목록. 기대 수 확인에만
            사용하며 조직 의미를 보충하거나 코드 변경을 덮어쓰지 않는다.
        closing_month: YYYY-MM 마감 스냅샷. 상세의 대상월과 구분한다.
        type_name: 실행 유형 식별자.
        publication_policy: None이면 기존 기술 마스터를 그대로 보존한다.
            source-contract-v4이면 수정본 선택 뒤 원본 계약의 행 제외를 적용한다.
    Returns:
        details, totals, organization_totals, issues, selected_submissions,
        excluded_records/submissions, status, content_sha256를 가진 기술 마스터.
        공식 승인·버전은 발급하지 않으며 business_master_issued는 False이다.
    Raises:
        ValueError: 잘못된 마감월·유형·기대 조직 목록.
        TypeError: 입력 계약 또는 JSON 표현을 지원하지 않는 경우.
    Side Effects:
        없음. 순수 메모리 조립이며 파일·정답·기존 마스터를 읽거나 쓰지 않는다.
    """
    if not re.fullmatch(r"\d{4}-(0[1-9]|1[0-2])", closing_month) or not type_name:
        raise ValueError("유효한 YYYY-MM 마감월과 유형이 필요합니다")
    if not expected_organizations or len(set(expected_organizations)) != len(expected_organizations):
        raise ValueError("기대 조직은 비어 있지 않은 중복 없는 목록이어야 합니다")
    selected, excluded_submissions, issues = _select_submissions(submissions, closing_month)
    records = []
    excluded_records = []
    selected_metadata = []
    for submission in selected:
        owner = submission["organization_code"]
        record_start = len(records)
        selected_metadata.append({key: copy.deepcopy(value) for key, value in submission.items() if key != "records"})
        for raw in submission["records"]:
            record = copy.deepcopy(raw)
            record.setdefault("source_file", submission.get("source_file"))
            record.setdefault("source_sha256", submission.get("source_sha256"))
            record["submission_owner"] = owner
            if record.get("aggregation") != "month" or record.get("role_kind", "detail") != "detail":
                excluded_records.append({**record, "exclusion_reason": "outside_monthly_detail_scope"})
                continue
            if record.get("organization_code") and record["organization_code"] != owner:
                issues.append({"code": "record_organization_differs_from_submission_owner", "severity": "warning",
                               "organization_code": record["organization_code"], **_source_reference(record)})
            records.append(record)
        if len(records) == record_start:
            issues.append({"code": "submission_has_no_monthly_details", "severity": "error",
                           "organization_code": owner, "source_file": submission.get("source_file")})
    group_has_value = defaultdict(bool)
    for record in records:
        group = (record.get("domain"), record.get("period_group"))
        group_has_value[group] = group_has_value[group] or (
            record.get("raw_value") not in (None, "")
            or _decimal(record.get("normalized_value")) is not None
            or bool(record.get("formula"))
        )
    details, row_excluded, row_issues = _select_detail_rows(records)
    excluded_records.extend(row_excluded)
    issues.extend(row_issues)
    publication = None
    if publication_policy is not None:
        from .publication_policy import (
            partition_publication_records,
            source_organization_rejection,
        )

        publication = partition_publication_records(details, closing_month=closing_month,
                                                     submissions=selected_metadata, policy_name=publication_policy)
        for record in row_excluded:
            evidence = source_organization_rejection(record)
            if evidence is not None:
                record["publication_rejection_evidence"] = evidence
        details = publication["records"]
        excluded_records.extend(publication["excluded_records"])
        issues.extend(publication["issues"])
    for detail in details:
        amount, reason = _amount_eligibility(detail)
        detail["included_in_totals"] = reason is None
        detail["assembly_status"] = "confirmed" if reason is None else "unresolved"
        if amount is not None:
            detail["normalized_value"] = _decimal_text(amount)
        group = (detail.get("domain"), detail.get("period_group"))
        optional_blank = (detail.get("period_group") == "next_projection"
                          and detail.get("semantic_complete") is True
                          and reason in {"blank_amount", "amount_unconfirmed", "amount_unresolved"}
                          and not group_has_value[group]
                          and detail.get("raw_value") in (None, "")
                          and not detail.get("formula")
                          and _decimal(detail.get("normalized_value")) is None)
        if optional_blank:
            detail["assembly_status"] = "missing_optional"
        elif reason:
            issues.append({"code": reason, "severity": "error", "detail_id": detail.get("detail_id"),
                           "organization_code": detail.get("organization_code"), **_source_reference(detail)})
    if len(selected) != len(expected_organizations):
        issues.append({"code": "organization_count_mismatch", "severity": "error",
                       "expected_count": len(expected_organizations), "selected_count": len(selected)})
    details.sort(key=_canonical)
    excluded_records.sort(key=_canonical)
    excluded_submissions.sort(key=_canonical)
    issues.sort(key=_canonical)
    organization_totals = [{"scope": "organization", **row} for row in _totals(details, by_organization=True)]
    company_totals = [{"scope": "company", "organization_code": None, **row} for row in _totals(details, by_organization=False)]
    result = {
        "contract_version": "source-only-technical-master-v1", "type": type_name,
        "closing_month": closing_month, "status": "partial" if any(issue["severity"] == "error" for issue in issues) else "complete",
        "business_master_issued": False, "approval_status": "not_asserted", "generation_complete": True,
        "expected_organization_count": len(expected_organizations),
        "selected_organization_count": len(selected),
        "selection_policy": "unique source or highest verified submission; highest source row revision; identical whole-row deliveries once",
        "selected_submissions": selected_metadata, "excluded_submissions": excluded_submissions,
        "records": details, "excluded_records": excluded_records, "issues": issues,
        "totals": organization_totals + company_totals,
        "organization_totals": organization_totals,
        "detail_count": len(details), "confirmed_detail_count": sum(detail["included_in_totals"] for detail in details),
        "optional_blank_count": sum(detail["assembly_status"] == "missing_optional" for detail in details),
        "excluded_record_count": len(excluded_records), "input_submission_count": len(submissions),
    }
    if publication is not None:
        unresolved_published = sum(detail["assembly_status"] == "unresolved" for detail in details)
        unresolved_identity = sum(record.get("exclusion_reason") == "detail_identity_unresolved"
                                  and not record.get("publication_rejection_evidence") for record in row_excluded)
        selection_unresolved = (len(selected) != len(expected_organizations)
                                or any(item.get("reason") != "superseded_submission" for item in excluded_submissions)
                                or any(record.get("exclusion_reason") == "detail_revision_conflict" for record in row_excluded)
                                or unresolved_identity > 0)
        result["publication_policy"] = {"version": publication["policy_version"], "contract": publication["contract"],
                                        "counts": publication["counts"], "decision_evidence_location": "issues.publication_decision"}
        result["publication_status"] = ("partial" if unresolved_published or selection_unresolved else
                                        "complete_with_exclusions" if excluded_records else "complete")
        result["publication_status_definition"] = "발행 상세의 필수 미확정과 제출/수정본 선택 상태이며 정답 일치나 원본 품질 해결을 뜻하지 않음"
        result["source_quality"] = {"status": result["status"],
                                    "policy_rejected_row_count": publication["counts"].get("source_rejected_rows", 0),
                                    "unresolved_identity_slot_count": sum(record.get("exclusion_reason") == "detail_identity_unresolved" for record in row_excluded),
                                    "contract_rejected_identity_slot_count": sum(bool(record.get("publication_rejection_evidence")) for record in row_excluded),
                                    "unresolved_publication_identity_slot_count": unresolved_identity,
                                    "required_actual_blank_slot_count": publication["counts"].get("required_actual_blank_slots", 0),
                                    "unresolved_published_slot_count": unresolved_published,
                                    "original_values_corrected": False}
    result["content_sha256"] = _fingerprint(result)
    result["details"] = result["records"]
    return result
