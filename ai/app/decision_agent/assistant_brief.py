"""질문별 계획·근거 설명·미해결 항목의 계약을 검증한다."""
from __future__ import annotations

import re
import unicodedata
from copy import deepcopy
from typing import Any

from pydantic import ValidationError

from .assistant_core import FinishValidationError, pointer_scalar, validate_finish
from .assistant_models import AnalysisPlan, GroundedFinish, ProseRepair
from .assistant_presentation import citation_catalog
from .discovery_core import numeric_claim_kind

_ASSERTED = re.compile(
    r"보장(?:합니다|됩니다|했습니다)|(?:반드시|무조건)\s*(?:달성|실현|성공|절감|확보)|"
    r"(?:달성|확보|실현|절감)(?:됩니다|합니다|했습니다|하였습니다)|"
    r"(?:추진\s*승인|발송|저장|삭제)(?:을|를)?\s*(?:완료|했습니다|하였습니다|처리했습니다)"
)


def schema_issues(error: ValidationError) -> list[str]:
    """모델이 잘못된 필드만 고칠 수 있도록 값 없는 구조 오류를 제한해 반환한다.

    Args: error Pydantic 계약 검증 오류.
    Returns: 허용된 필드 이름·배열 위치·오류 종류만 포함한 최대 여덟 안내.
    Raises: 없음.
    Side Effects: 없음. 입력값·오류 원문·허용하지 않은 키 이름을 노출하지 않는다.
    """
    fields = {"summary", "requirements", "findings", "nextActions", "id", "requestQuote", "question",
              "kind", "text", "citationIds", "uncertainty", "status", "findingIds", "missingInformation", "followUpQuestion", "updates", "path"}
    kinds = {"missing", "extra_forbidden", "string_type", "string_too_short", "string_too_long", "list_type",
             "too_short", "too_long", "literal_error", "model_type", "string_pattern_mismatch"}
    issues = []
    for item in error.errors(include_input=False, include_url=False)[:8]:
        path = ".".join(str(part) if isinstance(part, int) and 0 <= part <= 32 else part if part in fields else "추가필드"
                        for part in item["loc"])
        kind = item["type"] if item["type"] in kinds else "계약형식불일치"
        issues.append(f"{path or 'root'}: {kind}")
    return issues


def uncovered_request_segments(message: str, quotes: list[str]) -> list[str]:
    """인용 위치가 덮지 못한 사용자 요청 구간을 원문 그대로 반환한다.

    Args: message 현재 사용자 원문, quotes 실제 부분 문자열 인용 목록.
    Returns: 문자·결합 부호·숫자·기호가 누락된 연속 구간의 부분 문자열. 내부 공백·문장부호는 보존한다.
    Raises: ValueError: 빈 인용 또는 원문에 없는 인용.
    Side Effects: 없음. 계획·질문의 의미를 생성하거나 원문을 정규화·로그 기록하지 않는다.
    """
    covered = set()
    for quote in quotes:
        if not quote.strip() or quote not in message:
            raise ValueError("실제 요청의 부분 인용만 사용할 수 있습니다.")
        start = message.find(quote)
        while start >= 0:
            covered.update(range(start, start + len(quote)))
            start = message.find(quote, start + 1)
    segments, start, last = [], None, None
    for index, character in enumerate(message):
        if unicodedata.category(character)[0] not in {"L", "M", "N", "S"}:
            continue
        if index not in covered:
            if start is None:
                start = index
            last = index
        elif start is not None:
            segments.append(message[start:last + 1])
            start, last = None, None
    if start is not None:
        segments.append(message[start:last + 1])
    return segments


def validate_plan(value: Any, message: str) -> list[dict[str, str]]:
    """현재 요청을 실제로 인용하고 원문의 의미 있는 문자 범위를 덮는 계획만 수락한다.

    Args: value 모델 계획, message 현재 사용자 질문 원문.
    Returns: 불변 기준으로 사용할 요구 항목 사본.
    Raises: FinishValidationError: 계약·중복·원문 인용 불일치 또는 인용 범위 누락.
    Side Effects: 없음. 문자 범위 검사는 요구의 의미 이해·분리·충족을 보증하지 않는다.
    """
    try:
        plan = AnalysisPlan.model_validate(value)
    except ValidationError as error:
        raise FinishValidationError("PLAN_SCHEMA", schema_issues(error)) from None
    ids = set()
    for item in plan.requirements:
        if not item.id.strip() or item.id in ids or not item.question.strip():
            raise FinishValidationError("PLAN_SCHEMA")
        if not item.requestQuote.strip() or item.requestQuote not in message:
            raise FinishValidationError("PLAN_QUOTE")
        ids.add(item.id)
    if uncovered_request_segments(message, [item.requestQuote for item in plan.requirements]):
        raise FinishValidationError("PLAN_COVERAGE")
    return [item.model_dump() for item in plan.requirements]


def validate_prose(text: str | None, citations: list[dict[str, str]], observations: list[dict[str, Any]],
                   field_name: str | None = None) -> None:
    """확인된 숫자 포함 이름만 예외로 하고 생성 금액·결과 보장 문장을 거부한다.

    Args: text 설명 또는 null, citations 이 설명의 원문 인용, observations 실제 원장, field_name 코드가 정한 오류 위치.
    Returns: 없음.
    Raises: FinishValidationError: 공백·근거 밖 수치·보장 또는 실행한 것처럼 쓰는 문장.
    Side Effects: 없음. 인용 일치가 해석의 의미까지 증명하지는 않는다.
    """
    if text is None:
        return
    location = [field_name] if field_name else []
    if not text.strip():
        raise FinishValidationError("BRIEF_TEXT", location)
    sources = {item["id"]: item for item in observations if item["status"] == "SUCCESS"}
    names = set()
    for citation in citations:
        if citation["path"].rsplit("/", 1)[-1] not in {"name", "organizationName"}:
            continue
        source = sources.get(citation["observationId"])
        if source is not None:
            value = pointer_scalar(source, citation["path"])
            if isinstance(value, str) and value == citation["quote"] and value.strip():
                names.add(value)
    checked = text
    for name in sorted(names, key=len, reverse=True):
        checked = checked.replace(name, "해당 조직")
    reason = numeric_claim_kind(checked)
    if reason:
        raise FinishValidationError("BRIEF_NUMERIC", location, [reason])
    if _ASSERTED.search(checked):
        raise FinishValidationError("BRIEF_ASSERTED", location)


def prose_field(value: dict[str, Any], path: str) -> tuple[dict[str, Any], str]:
    """기존 종료 제안에서 허용된 문자열 설명 필드의 위치만 찾는다.

    Args: value 기존 종료 제안, path 서버가 정한 점 표기 경로.
    Returns: 해당 필드를 가진 사전과 필드 이름.
    Raises: ValueError: 설명 외 경로·없는 위치·문자열이 아닌 값.
    Side Effects: 없음. 인용·식별자·상태·계획 항목은 접근 대상으로 허용하지 않는다.
    """
    if path == "summary":
        parent, field = value, "summary"
    else:
        match = re.fullmatch(r"(findings|requirements)\.(0|[1-9][0-9]?)\.([A-Za-z]+)", path)
        if not match:
            raise ValueError("허용된 설명 경로가 아닙니다.")
        group, index, field = match.groups()
        allowed = {"findings": {"text", "uncertainty"}, "requirements": {"missingInformation", "followUpQuestion"}}
        items = value.get(group)
        if field not in allowed[group] or not isinstance(items, list) or int(index) >= len(items):
            raise ValueError("존재하는 설명 필드가 아닙니다.")
        parent = items[int(index)]
    if not isinstance(parent, dict) or not isinstance(parent.get(field), str):
        raise ValueError("기존 문자열 설명만 수정할 수 있습니다.")
    return parent, field


def numeric_repair_fields(value: Any, error: FinishValidationError) -> list[str]:
    """숫자 오류의 안전한 필드 위치만 좁은 문장 수정 대상으로 선택한다.

    Args: value 거절된 종료 제안, error 원문 없는 검증 오류.
    Returns: 실제 존재하는 허용 설명 경로. 다른 종류 오류는 빈 목록.
    Raises: 없음.
    Side Effects: 없음. 원문·금액을 로그나 오류 안내에 추가하지 않는다.
    """
    if error.code != "BRIEF_NUMERIC" or not isinstance(value, dict):
        return []
    paths = []
    for path in error.field_issues:
        try:
            prose_field(value, path)
        except ValueError:
            return []
        if path not in paths:
            paths.append(path)
    return paths


def apply_prose_repair(value: dict[str, Any], arguments: Any, paths: list[str]) -> dict[str, Any]:
    """모델이 다시 쓴 허용 문장만 사본에 적용하고 다른 모든 필드는 보존한다.

    Args: value 기존 종료 제안, arguments 로컬 repair_answer 인자, paths 서버가 허용한 오류 경로.
    Returns: 수정 문장만 반영한 새 종료 제안. 호출자는 전체 resolve_brief 검증을 다시 수행해야 한다.
    Raises: FinishValidationError: 잘못된 수정 계약·중복·허용 범위 밖 경로.
    Side Effects: 없음. 원본 제안·관찰·계획을 변경하거나 코드로 문장을 생성하지 않는다.
    """
    try:
        repair = ProseRepair.model_validate(arguments)
    except ValidationError as error:
        raise FinishValidationError("BRIEF_REPAIR", schema_issues(error)) from None
    selected = [item.path for item in repair.updates]
    if not paths or set(selected) != set(paths):
        raise FinishValidationError("BRIEF_REPAIR", ["updates: path_set_mismatch"])
    if len(selected) != len(set(selected)):
        raise FinishValidationError("BRIEF_REPAIR", ["updates: duplicate_path"])
    try:
        result = deepcopy(value)
        for update in repair.updates:
            parent, field = prose_field(result, update.path)
            parent[field] = update.text
        return result
    except ValueError:
        raise FinishValidationError("BRIEF_REPAIR", ["updates: invalid_target"]) from None


def resolve_brief(value: Any, observations: list[dict[str, Any]], plan: list[dict[str, str]]) -> dict[str, Any]:
    """모델 설명의 근거를 복원하고 계획한 요구 항목 전체의 해결 상태를 검사한다.

    Args: value 종료 제안, observations 실제 성공·실패 원장, plan 수락한 요구 항목.
    Returns: summary·citations·nextActions·brief·coverage 상태.
    Raises: FinishValidationError: 근거·설명·요구 항목·불확실성 계약 오류. 숫자 문장 오류만 수집해 최대 여덟 원본 위치를 함께 반환한다.
    Side Effects: 없음. 금액 계산·업무 상태 변경·근거 진위의 의미 판정은 하지 않는다.
    """
    if not plan:
        raise FinishValidationError("PLAN_REQUIRED")
    try:
        proposal = GroundedFinish.model_validate(value)
    except ValidationError as error:
        raise FinishValidationError("BRIEF_SCHEMA", schema_issues(error)) from None
    catalog = {item["id"]: item for item in citation_catalog(observations)}
    source_tools = {item["id"]: item["tool"] for item in observations if item["status"] == "SUCCESS"}
    findings, citations, finding_ids, citation_keys = [], [], set(), set()
    numeric_fields: list[str] = []
    numeric_reasons: list[str] = []

    def check_prose(text: str | None, selected: list[dict[str, str]], field_name: str) -> None:
        """숫자 오류 위치만 모으고 다른 설명 검증 실패는 즉시 전파한다.

        Args: text 모델 설명, selected 해당 문장의 검증된 인용, field_name 원본 제안의 문장 위치.
        Returns: 없음. Raises: FinishValidationError: 숫자 오류 외 설명 계약 실패.
        Side Effects: 실행 메모리의 숫자 오류 위치 목록만 갱신하며 원문을 저장·수정하지 않는다.
        """
        try:
            validate_prose(text, selected, observations, field_name)
        except FinishValidationError as error:
            if error.code != "BRIEF_NUMERIC":
                raise
            numeric_fields.append(field_name)
            numeric_reasons.extend(error.reason_codes)

    for finding_index, finding in enumerate(proposal.findings):
        if not finding.id.strip() or finding.id in finding_ids:
            raise FinishValidationError("BRIEF_FINDING")
        if len(set(finding.citationIds)) != len(finding.citationIds):
            raise FinishValidationError("CITATION_DUPLICATE")
        if any(identifier not in catalog for identifier in finding.citationIds):
            raise FinishValidationError("CITATION_OPTION_UNKNOWN")
        selected = [{key: catalog[identifier][key] for key in ("observationId", "path", "quote")}
                    for identifier in finding.citationIds]
        if not any(item["path"].startswith("/data/") and (source_tools[item["observationId"]] != "observation_detail"
                   or item["path"] == "/data/value" or item["path"].startswith("/data/value/")) for item in selected):
            raise FinishValidationError("BRIEF_FINDING")
        validate_finish({"summary": "조회 근거를 확인합니다.", "citations": selected, "nextActions": []}, observations)
        if finding.kind != "FACT" and not finding.uncertainty:
            raise FinishValidationError("BRIEF_UNCERTAINTY")
        check_prose(finding.text, selected, f"findings.{finding_index}.text")
        check_prose(finding.uncertainty, selected, f"findings.{finding_index}.uncertainty")
        finding_ids.add(finding.id)
        findings.append({"id": finding.id, "kind": finding.kind, "text": finding.text,
                         "citations": selected, "uncertainty": finding.uncertainty})
        for citation in selected:
            key = tuple(citation[key] for key in ("observationId", "path", "quote"))
            if key not in citation_keys:
                citation_keys.add(key)
                citations.append(citation)
    planned = {item["id"]: item for item in plan}
    resolutions = {item.id: item for item in proposal.requirements}
    resolution_indices = {item.id: index for index, item in enumerate(proposal.requirements)}
    if len(resolutions) != len(proposal.requirements) or set(resolutions) != set(planned):
        raise FinishValidationError("BRIEF_COVERAGE")
    requirements = []
    for identifier, original in planned.items():
        resolution = resolutions[identifier]
        requirement_index = resolution_indices[identifier]
        if len(set(resolution.findingIds)) != len(resolution.findingIds) or any(item not in finding_ids for item in resolution.findingIds):
            raise FinishValidationError("BRIEF_FINDING")
        if resolution.status == "ANSWERED":
            if not resolution.findingIds or resolution.missingInformation is not None or resolution.followUpQuestion is not None:
                raise FinishValidationError("BRIEF_COVERAGE")
        elif not resolution.missingInformation or not resolution.followUpQuestion:
            raise FinishValidationError("BRIEF_MISSING")
        check_prose(resolution.missingInformation, citations, f"requirements.{requirement_index}.missingInformation")
        check_prose(resolution.followUpQuestion, citations, f"requirements.{requirement_index}.followUpQuestion")
        requirements.append({**original, **resolution.model_dump()})
    check_prose(proposal.summary, citations, "summary")
    if len(proposal.nextActions) != len(set(proposal.nextActions)):
        raise FinishValidationError("ACTION_DUPLICATE")
    if numeric_fields:
        raise FinishValidationError("BRIEF_NUMERIC", numeric_fields, numeric_reasons)
    completed = bool(findings) and all(item["status"] == "ANSWERED" for item in requirements)
    brief = {"summary": proposal.summary, "requirements": requirements, "findings": findings}
    return {"summary": proposal.summary, "citations": citations, "nextActions": proposal.nextActions,
            "brief": brief, "status": "COMPLETED" if completed else "NEEDS_INPUT"}
