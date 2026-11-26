"""질문별 계획·근거 설명·미해결 항목의 계약을 검증한다."""
from __future__ import annotations

import re
import unicodedata
from typing import Any

from pydantic import ValidationError

from .assistant_core import FinishValidationError, pointer_scalar, validate_finish
from .assistant_models import AnalysisPlan, GroundedFinish
from .assistant_presentation import citation_catalog
from .discovery_core import _NUMERIC_CLAIM

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
              "kind", "text", "citationIds", "uncertainty", "status", "findingIds", "missingInformation", "followUpQuestion"}
    kinds = {"missing", "extra_forbidden", "string_type", "string_too_short", "string_too_long", "list_type",
             "too_short", "too_long", "literal_error", "model_type", "string_pattern_mismatch"}
    issues = []
    for item in error.errors(include_input=False, include_url=False)[:8]:
        path = ".".join(str(part) if isinstance(part, int) and 0 <= part <= 32 else part if part in fields else "추가필드"
                        for part in item["loc"])
        kind = item["type"] if item["type"] in kinds else "계약형식불일치"
        issues.append(f"{path or 'root'}: {kind}")
    return issues


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
    ids, covered = set(), set()
    for item in plan.requirements:
        if not item.id.strip() or item.id in ids or not item.question.strip():
            raise FinishValidationError("PLAN_SCHEMA")
        if not item.requestQuote.strip() or item.requestQuote not in message:
            raise FinishValidationError("PLAN_QUOTE")
        ids.add(item.id)
        # 반복·겹침 인용도 모든 출현 위치를 덮되 같은 글자의 다른 위치와 혼동하지 않는다.
        start = message.find(item.requestQuote)
        while start >= 0:
            covered.update(range(start, start + len(item.requestQuote)))
            start = message.find(item.requestQuote, start + 1)
    # 공백·문장부호·제어 문자는 제외하고 문자·결합 부호·숫자·기호의 누락을 검사한다.
    if any(index not in covered and unicodedata.category(character)[0] in {"L", "M", "N", "S"}
           for index, character in enumerate(message)):
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
    if any(unicodedata.category(character).startswith("N") for character in checked) or _NUMERIC_CLAIM.search(checked):
        raise FinishValidationError("BRIEF_NUMERIC", location)
    if _ASSERTED.search(checked):
        raise FinishValidationError("BRIEF_ASSERTED", location)


def resolve_brief(value: Any, observations: list[dict[str, Any]], plan: list[dict[str, str]]) -> dict[str, Any]:
    """모델 설명의 근거를 복원하고 계획한 요구 항목 전체의 해결 상태를 검사한다.

    Args: value 종료 제안, observations 실제 성공·실패 원장, plan 수락한 요구 항목.
    Returns: summary·citations·nextActions·brief·coverage 상태.
    Raises: FinishValidationError: 근거·설명·요구 항목·불확실성 계약 오류.
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
        validate_prose(finding.text, selected, observations, f"findings.{finding_index}.text")
        validate_prose(finding.uncertainty, selected, observations, f"findings.{finding_index}.uncertainty")
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
    if len(resolutions) != len(proposal.requirements) or set(resolutions) != set(planned):
        raise FinishValidationError("BRIEF_COVERAGE")
    requirements = []
    for requirement_index, (identifier, original) in enumerate(planned.items()):
        resolution = resolutions[identifier]
        if len(set(resolution.findingIds)) != len(resolution.findingIds) or any(item not in finding_ids for item in resolution.findingIds):
            raise FinishValidationError("BRIEF_FINDING")
        if resolution.status == "ANSWERED":
            if not resolution.findingIds or resolution.missingInformation is not None or resolution.followUpQuestion is not None:
                raise FinishValidationError("BRIEF_COVERAGE")
        elif not resolution.missingInformation or not resolution.followUpQuestion:
            raise FinishValidationError("BRIEF_MISSING")
        validate_prose(resolution.missingInformation, citations, observations, f"requirements.{requirement_index}.missingInformation")
        validate_prose(resolution.followUpQuestion, citations, observations, f"requirements.{requirement_index}.followUpQuestion")
        requirements.append({**original, **resolution.model_dump()})
    validate_prose(proposal.summary, citations, observations, "summary")
    if len(proposal.nextActions) != len(set(proposal.nextActions)):
        raise FinishValidationError("ACTION_DUPLICATE")
    completed = bool(findings) and all(item["status"] == "ANSWERED" for item in requirements)
    brief = {"summary": proposal.summary, "requirements": requirements, "findings": findings}
    return {"summary": proposal.summary, "citations": citations, "nextActions": proposal.nextActions,
            "brief": brief, "status": "COMPLETED" if completed else "NEEDS_INPUT"}
