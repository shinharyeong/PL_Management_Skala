"""대화형 Tool 허용 인자·관찰 범위·정성 답변·JSON pointer 인용 검증."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal
import json
import re
import unicodedata
from typing import Any

from pydantic import ValidationError

from .assistant_models import ARGUMENT_MODELS, TOOL_DESCRIPTIONS, AnalysisPlan, FinishAnswer, GroundedFinish, ProseRepair, ToolObservation
from .discovery_core import _ASSERTED_OUTCOME, _NUMERIC_CLAIM

MAX_OBSERVATION_BYTES = 256 * 1024
MAX_OBSERVATIONS_BYTES = 200 * 1024
FINISH_ERRORS = {
    "FINISH_SCHEMA": "finish_answer의 summary, citations, nextActions만 schema에 맞춰 제출하세요. 인용은 observationId, path, quote 문자열로 구성하세요.",
    "FINISH_NOT_ALONE": "finish_answer는 다른 도구와 함께 호출하지 말고 단독으로 호출하세요.",
    "SUMMARY_EMPTY": "summary에 숫자 없는 짧은 정성 설명이나 확인 질문을 작성하세요.",
    "SUMMARY_UNICODE_NUMBER": "summary에서 숫자 글자를 제거하세요. 조직명·연도·번호에 포함된 숫자도 금지되므로 해당 조직명과 수치는 citations의 실제 원문으로 옮기세요.",
    "SUMMARY_NUMERIC_PATTERN": "summary에서 한글 금액·비율·배수 표현을 제거하고 수치는 citations의 실제 원문으로 옮기세요. 조직명 대신 '해당 조직' 등 숫자 없는 정성 표현을 사용하세요.",
    "SUMMARY_ASSERTED": "summary에서 확정·보장·반드시 등 결과 확정 표현을 제거하세요. '확정 마스터'는 '선택 기준'으로 표현하고 원문 상태가 필요하면 citations로 인용하세요.",
    "CITATION_SOURCE": "citations의 observationId는 이번 턴에 받은 SUCCESS 관찰 ID만 사용하고 quote를 비우지 마세요.",
    "CITATION_POINTER": "path는 관찰 전체 루트 기준으로 /data/...처럼 작성하세요. 실제 필드·배열 인덱스를 확인하고 객체·배열 전체가 아닌 scalar 하나를 가리키세요.",
    "CITATION_QUOTE": "quote는 해당 path의 원문과 일치해야 합니다. 숫자 문자열은 부호·소수점·전체 자릿수를 그대로 복사하고 단위 변환·쉼표 추가·부분 발췌를 하지 마세요.",
    "CITATION_DUPLICATE": "동일한 observationId, path, quote 조합은 citations에 한 번만 넣으세요.",
    "CITATION_REQUIRED": "이미 읽은 SUCCESS 관찰의 scalar를 citations에 포함하세요. 재조회 없이 기존 관찰을 사용하세요.",
    "ACTION_DUPLICATE": "nextActions에는 같은 화면 동작을 중복해서 넣지 마세요.",
    "INVALID_FINISH": "정성 summary와 이번 턴 SUCCESS 관찰의 정확한 scalar 인용을 단독 finish_answer로 제출하세요.",
    "FINISH_SELECTION_SCHEMA": "finish_answer에는 citationIds, answerStyle, nextActions만 제출하세요. summary·quote·path·citations를 작성하지 말고 citationOptions의 id를 선택하세요.",
    "CITATION_OPTION_UNKNOWN": "citationIds에는 이번 턴 ToolMessage의 citationOptions에 실제로 있는 id만 선택하세요. 근거 ID를 새로 만들지 마세요.",
    "CITATION_OPTION_REQUIRED": "이미 조회한 SUCCESS 자료의 citationOptions에서 질문에 해당하는 id를 선택해 finish_answer를 호출하세요. 추가 조회는 필요하지 않습니다.",
    "PLAN_REQUIRED": "먼저 plan_analysis로 현재 사용자 요청에서 확인해야 할 항목과 실제 부분 인용을 선언하세요.",
    "PLAN_SCHEMA": "plan_analysis에는 중복 없는 id·requestQuote·question의 requirements를 하나 이상 여섯 개 이하로 제출하세요.",
    "PLAN_QUOTE": "requestQuote는 현재 사용자 message에 실제로 포함된 부분 문자열이어야 합니다. 요구 항목을 임의로 추가하지 마세요.",
    "PLAN_COVERAGE": "requestQuote들의 합집합이 현재 message 전체를 빠짐없이 덮어야 합니다. 공백·문장부호를 제외한 누락 구간도 실제 부분 인용에 포함해 계획을 다시 제출하세요. 한 문장 안의 복수 요구도 확인 항목에서 누락하지 마세요. 최대 여섯 항목·인용별 오백 자 제한은 유지됩니다.",
    "PLAN_IMMUTABLE": "이미 수락한 계획은 이 실행에서 변경할 수 없습니다. 각 요구 항목을 해결하거나 부족한 정보를 명시하세요.",
    "BRIEF_SCHEMA": "finish_answer에는 summary, findings, requirements, nextActions를 제출하세요. 각 finding은 id·kind·text·citationIds·uncertainty를 사용합니다. citationIds는 실제 근거 하나 이상 네 개 이하입니다. 근거 없는 제안은 finding에서 제거하고 해당 requirement의 NEEDS_INPUT·missingInformation·followUpQuestion으로 옮기세요. 제거한 finding의 참조와 summary도 함께 고치세요.",
    "BRIEF_FINDING": "finding의 id는 중복할 수 없고 실제 업무 근거가 필요합니다. 요구 항목의 findingIds는 실제 작성한 finding만 참조하세요.",
    "BRIEF_UNCERTAINTY": "INTERPRETATION·HYPOTHESIS에는 해석의 한계나 검증할 조건을 uncertainty에 명시하세요.",
    "BRIEF_COVERAGE": "계획한 모든 요구 항목을 빠짐없이 반환하세요. ANSWERED에는 findingIds가 필요하며 부족 정보·확인 질문은 null이어야 합니다.",
    "BRIEF_MISSING": "미해결 요구 항목에는 missingInformation과 구체적인 followUpQuestion을 모두 작성하세요. 조회 성공만으로 해결됐다고 표시하지 마세요.",
    "BRIEF_TEXT": "설명과 확인 질문에는 공백 대신 의미 있는 문장을 작성하세요.",
    "BRIEF_NUMERIC": "표시된 문장 필드를 숫자·금액·비율·날짜·순번 없이 의미를 유지해 다시 쓰세요. 한글로 쓴 금액·절반·배수 표현도 수치입니다. 목표액은 '요청한 목표', 숫자 포함 조직명은 '해당 조직'으로 표현하세요. 수치는 기존 인용 카드에 있으므로 설명에 반복하지 않습니다. repair_answer가 제공되면 그 도구로 허용된 문장만 수정하고 인용·다른 필드는 유지하세요.",
    "BRIEF_REPAIR": "repair_answer만 단독 호출해 schema에 허용된 오류 경로를 각각 한 번씩 수정하세요. updates에는 path와 text만 사용하며 기존 의미·근거를 유지하세요. 계획·인용·식별자·상태·다른 문장은 변경할 수 없습니다.",
    "MODEL_OUTPUT_LIMIT": "모델 응답이 출력 길이 한도로 잘려 완료되지 않았습니다. 잘린 원문은 대화에서 제외했습니다. 기존 계획·관찰·인용만 사용해 현재 제공된 도구 계약에 맞는 짧은 결과를 다시 작성하세요.",
    "COMPACT_FINISH_ONLY": "새 조회나 계획 변경 없이 기존 관찰로 finish_answer만 단독 호출하세요. 모든 요구 항목을 유지하며 핵심 판단과 필요한 확인 질문만 짧게 작성하세요.",
    "FINALIZATION_ONLY": "남은 모델 호출은 종료 전용입니다. 추가 조회하지 말고 finish_answer로 기존 근거에 답한 항목과 아직 답하지 못한 항목을 모두 정리하세요. 미조회 항목은 NEEDS_INPUT과 구체적인 부족 자료로 남기세요.",
    "BRIEF_ASSERTED": "결과를 보장하거나 저장·발송·추진 승인 등을 실행했다고 쓰지 마세요. 가설과 조건부 판단을 구분하세요.",
}
NUMERIC_REASONS = {
    "NUMERIC_CHARACTER": "숫자 문자가 포함되어 있습니다. 수치는 기존 인용에 맡기세요.",
    "PERCENT_EXPRESSION": "백분율 표현을 설명에서 반복하지 마세요.",
    "RATIO_EXPRESSION": "절반·배수 같은 정량 비교를 기존 인용과 정성 설명으로 구분하세요.",
    "SCALE_EXPRESSION": "금액 규모 단어를 설명에서 반복하지 마세요.",
    "KOREAN_AMOUNT": "한글 수사와 금액 단위가 결합되어 있습니다. 목표액 대신 요청한 목표라고 표현하세요.",
}


class FinishValidationError(ValueError):
    """모델 원문을 담지 않는 종료 검증 코드와 고정 복구 안내."""

    def __init__(self, code: str, field_issues: list[str] | None = None, reason_codes: list[str] | None = None) -> None:
        """허용된 오류 코드만 복구 안내와 함께 보관한다.

        Args: code 내부 종료 검증 오류 코드, field_issues 원문 없는 허용 필드·오류 종류 목록, reason_codes 고정 수치 분류 코드.
        Returns: 없음.
        Raises: 없음.
        Side Effects: 없음. 응답 원문·금액·ID·인자를 저장하거나 기록하지 않는다.
        """
        self.code = code if code in FINISH_ERRORS else "INVALID_FINISH"
        self.field_issues = list(field_issues or [])[:8]
        self.reason_codes = list(dict.fromkeys(item for item in (reason_codes or []) if item in NUMERIC_REASONS))[:5]
        detail = " 수정할 필드: " + "; ".join(self.field_issues) if self.field_issues else ""
        reasons = " ".join(NUMERIC_REASONS[item] for item in self.reason_codes)
        super().__init__(FINISH_ERRORS[self.code] + detail + (" 판정 종류: " + reasons if reasons else ""))


def strict_schema(value: Any) -> Any:
    """계획·종료 계약을 제공자 strict function 형식으로 복사한다.

    Args: value Pydantic JSON Schema의 노드.
    Returns: 모든 객체 속성을 필수로 지정하고 기본값을 제거한 새 구조. nullable 타입은 보존하며 정성 설명은 숫자 생성 패턴을 제한한다.
    Raises: 없음.
    Side Effects: 없음. 원본 계약·업무 도구 인자는 변경하지 않는다.
    """
    if isinstance(value, list):
        return [strict_schema(item) for item in value]
    if not isinstance(value, dict):
        return value
    result = {key: strict_schema(item) for key, item in value.items() if key != "default"}
    if result.get("type") == "object":
        result["required"] = list(result.get("properties", {}))
        result["additionalProperties"] = False
        for name, field in result.get("properties", {}).items():
            if name in {"summary", "text", "uncertainty", "missingInformation", "followUpQuestion"}:
                for variant in field.get("anyOf", [field]):
                    if variant.get("type") == "string":
                        variant["pattern"] = r"^[^0-9０-９%]*$"
    return result


def tool_schemas(citation_ids: list[str] | None = None, plan: list[dict[str, str]] | None = None,
                 repair_fields: list[str] | None = None, compact_finish: bool = False,
                 request_message: str | None = None, finalization_only: bool = False) -> list[dict[str, Any]]:
    """백엔드 읽기 allowlist와 검증된 종료 도구의 native schema를 만든다.

    Args: citation_ids 실제 근거 ID, plan 수락한 요구 항목, repair_fields 오류 문장 경로, compact_finish 출력 잘림 후 짧은 종료 전용 여부,
        request_message 현재 사용자 원문, finalization_only 마지막 호출에서 조회 대신 종료만 허용하는지 여부.
    Returns: OpenAI function tool schema 목록.
    Raises: 없음.
    Side Effects: 없음.
    """
    if repair_fields:
        schema = strict_schema(ProseRepair.model_json_schema())
        schema["properties"]["updates"]["minItems"] = len(repair_fields)
        schema["properties"]["updates"]["maxItems"] = len(repair_fields)
        schema["$defs"]["ProseRepairUpdate"]["properties"]["path"]["enum"] = repair_fields
        return [{"type": "function", "function": {"name": "repair_answer", "strict": True,
                 "description": "거절된 finish_answer 전체를 다시 만들지 않고 허용된 오류 문장만 짧게 다시 쓴다. 숫자는 이미 연결된 인용에서 확인하므로 설명에 반복하지 않는다. 원래 의미·근거를 유지하며 새 사실을 추가하지 않는다. 조회 도구·계획·인용·다른 필드는 변경하지 않는다.",
                 "parameters": schema}}]
    if plan == []:
        schema = strict_schema(AnalysisPlan.model_json_schema())
        description = "현재 사용자 질문을 빠짐없는 확인 항목으로 나눈다. 각 항목의 requestQuote는 현재 message의 실제 부분 인용이며, 인용들의 합집합으로 요청 전체를 빠짐없이 덮는다. 공백·문장부호만 인용 범위에서 제외할 수 있다. 단문 안의 복수 요구도 누락하지 않는다. 답변·가설·수치를 만들지 않고 확인할 질문만 선언한다."
        if request_message is not None and 0 < len(request_message) <= 500:
            schema["$defs"]["PlannedRequirement"]["properties"]["requestQuote"]["enum"] = [request_message]
            description += " 이번 짧은 요청은 각 requestQuote에 enum의 전체 원문을 그대로 선택한다. 같은 인용을 여러 항목에서 사용해도 되며, question은 그 안의 서로 다른 업무 요구를 빠짐없이 구분한다. 인용을 요약하거나 재작성하지 않는다."
        return [{"type": "function", "function": {"name": "plan_analysis",
                 "description": description, "strict": True, "parameters": schema}}]
    tools = [{"type": "function", "function": {"name": name, "description": TOOL_DESCRIPTIONS[name],
              "parameters": model.model_json_schema()}} for name, model in ARGUMENT_MODELS.items()]
    if finalization_only:
        tools = []
    finish_schema = GroundedFinish.model_json_schema()
    if compact_finish:
        tools = []
        finish_schema["properties"]["summary"]["maxLength"] = 160
        finish_schema["properties"]["findings"]["maxItems"] = min(6, max(4, len(plan or [])))
        finding = finish_schema["$defs"]["GroundedFinding"]["properties"]
        finding["text"]["maxLength"] = 140
        for field in [finding["uncertainty"],
                      finish_schema["$defs"]["RequirementResolution"]["properties"]["missingInformation"],
                      finish_schema["$defs"]["RequirementResolution"]["properties"]["followUpQuestion"]]:
            for variant in field.get("anyOf", [field]):
                if variant.get("type") == "string":
                    variant["maxLength"] = 100
    if citation_ids:
        finish_schema["$defs"]["GroundedFinding"]["properties"]["citationIds"]["items"]["enum"] = citation_ids
    if plan:
        finish_schema["$defs"]["RequirementResolution"]["properties"]["id"]["enum"] = [item["id"] for item in plan]
    tools.append({"type": "function", "function": {"name": "finish_answer",
                  "description": "계획한 질문에 직접 답한 경우만 ANSWERED로 표시한다. 사실·해석·가설을 모두 만들 필요는 없으며 단순 비교에는 필요한 FACT만 쓴다. 계획 미달·초과를 시간 추세로 표현하지 않는다. 우선 확인할 사항은 어느 조직의 무엇을 먼저 왜 확인할지 실제 근거에 연결한 INTERPRETATION과 uncertainty로 설명하며 일반적 개선 가설로 대체하지 않는다. 각 finding은 실제 citationIds를 연결하고 미확인 사항은 구체적인 NEEDS_INPUT으로 남긴다. 숫자를 문장에 생성하지 않는다.",
                  "strict": True, "parameters": strict_schema(finish_schema)}})
    if compact_finish:
        tools[-1]["function"]["description"] = "출력 길이 한도로 잘린 답변을 기존 관찰·인용만 사용해 간결하게 다시 작성한다. summary는 짧은 한 문장, 각 finding도 짧은 판단 하나만 작성하고 숫자·원문 인용 내용을 반복하지 않는다. 모든 계획 항목은 그대로 포함한다. 새 조회·계획 변경은 하지 않는다."
    if finalization_only:
        tools[-1]["function"]["description"] += " 이번 호출은 종료 전용이다. 모든 계획 항목을 반환하고, 기존 관찰로 답할 수 없는 항목을 숨기거나 완료로 만들지 말고 NEEDS_INPUT으로 명시한다."
    return tools


def normalize_arguments(name: str, value: Any) -> dict[str, Any]:
    """알 수 없는 도구·인자를 거부하고 동일 호출의 기본값을 정규화한다.

    Args:
        name: 모델이 고른 읽기 도구 이름.
        value: 모델의 JSON 인자 객체.
    Returns:
        기본 limit을 포함하고 null 선택값을 제외한 인자.
    Raises:
        ValueError 또는 ValidationError: 미허용 도구·필드·범위·후보 ID 중복.
    Side Effects:
        없음.
    """
    if name not in ARGUMENT_MODELS or not isinstance(value, dict):
        raise ValueError("허용된 조회 도구와 JSON 인자만 사용할 수 있습니다.")
    args = ARGUMENT_MODELS[name].model_validate(value).model_dump(exclude_none=True)
    if "candidateIds" in args:
        ids = args["candidateIds"]
        if len(ids) != len(set(ids)):
            raise ValueError("후보 ID가 중복됐습니다.")
        args["candidateIds"] = sorted(ids)
    return args


def call_key(name: str, arguments: dict[str, Any]) -> str:
    """같은 도구·정규화 인자를 실행 중 재사용할 키로 만든다.

    Args: name 도구 이름, arguments 검증된 인자.
    Returns: 정렬된 JSON 기반 키.
    Raises: TypeError: 내부 인자가 JSON 값이 아닌 경우.
    Side Effects: 없음.
    """
    return name + ":" + json.dumps(arguments, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def exact_json(value: Any) -> str:
    """이미 정밀도가 보존된 JSON 자료를 손실 없이 직렬화한다.

    Args: value 문자열 금액·정수·일반 JSON 구조.
    Returns: 압축 JSON 원문.
    Raises: TypeError 또는 ValueError: 비정상 수치나 JSON이 아닌 객체.
    Side Effects: 없음.
    """
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def reject_decimal_numbers(value: Any) -> None:
    """금액·비율의 부동소수점 변환을 막기 위해 decimal JSON number를 거부한다.

    Args: value 백엔드 관찰의 JSON 구조.
    Returns: 없음.
    Raises: ValueError: float·Decimal이 문자열로 고정되지 않은 경우.
    Side Effects: 없음.
    """
    if isinstance(value, (float, Decimal)):
        raise ValueError("소수 수치는 원문 decimal 문자열로 전달해야 합니다.")
    if isinstance(value, dict):
        for child in value.values():
            reject_decimal_numbers(child)
    elif isinstance(value, list):
        for child in value:
            reject_decimal_numbers(child)


def validate_observation(value: Any, name: str, context: dict[str, Any]) -> dict[str, Any]:
    """백엔드 원장을 변형하지 않고 Tool 이름·고정 기준·시점·자료 형식을 대조한다.

    Args:
        value: callback JSON 관찰.
        name: 실제 호출한 Tool.
        context: Spring에서 권한 확인 후 받은 고정 문맥.
    Returns:
        입력 관찰 원본 객체.
    Raises:
        ValueError 또는 ValidationError: 다른 기준·도구·비정밀 수치·과도한 응답.
    Side Effects:
        없음.
    """
    checked = ToolObservation.model_validate(value)
    if checked.tool != name:
        raise ValueError("응답 도구가 실행한 도구와 다릅니다.")
    basis = value["basis"]
    if any(basis[key] != context[key] for key in ("masterId", "year", "baseMonth", "analysisId", "revision")):
        raise ValueError("관찰이 고정 기준 또는 분석 버전과 다릅니다.")
    if datetime.fromisoformat(checked.basis.capturedAt.replace("Z", "+00:00")).tzinfo is None:
        raise ValueError("관찰 시점의 시간대가 없습니다.")
    if checked.status == "ERROR" and checked.data is not None:
        raise ValueError("실패 관찰은 데이터를 포함할 수 없습니다.")
    reject_decimal_numbers(value)
    if len(exact_json(value).encode()) > MAX_OBSERVATION_BYTES:
        raise ValueError("관찰 응답 크기가 상한을 초과했습니다.")
    return value


def pointer_scalar(observation: dict[str, Any], path: str) -> Any:
    """RFC6901 경로를 해석해 인용 가능한 scalar만 반환한다.

    Args: observation 이번 실행의 관찰, path 관찰 루트 기준 JSON pointer.
    Returns: 문자열·정수·boolean·null scalar.
    Raises: ValueError: 잘못된 escape·없는 경로·객체·배열 인용.
    Side Effects: 없음.
    """
    if not path.startswith("/") or re.search(r"~(?![01])", path):
        raise ValueError("인용 경로는 올바른 JSON pointer여야 합니다.")
    value: Any = observation
    for segment in path[1:].split("/"):
        token = segment.replace("~1", "/").replace("~0", "~")
        if isinstance(value, dict) and token in value:
            value = value[token]
        elif isinstance(value, list) and re.fullmatch(r"0|[1-9]\d*", token) and int(token) < len(value):
            value = value[int(token)]
        else:
            raise ValueError("관찰에 존재하지 않는 인용 경로입니다.")
    if isinstance(value, (dict, list, float, Decimal)):
        raise ValueError("인용은 정밀한 scalar 값만 지원합니다.")
    return value


def validate_finish(value: Any, observations: list[dict[str, Any]]) -> dict[str, Any]:
    """정성 답변과 이번 실행의 성공 관찰 인용만 최종 결과로 수락한다.

    Args:
        value: finish_answer native 인자.
        observations: 실제 callback으로 받은 이번 턴 관찰.
    Returns:
        검증된 summary·citations·nextActions.
    Raises:
        FinishValidationError: schema·Unicode 숫자·실현 확정·거짓 참조·객체 인용·중복을 고정 코드로 구분.
    Side Effects:
        없음.
    """
    try:
        answer = FinishAnswer.model_validate(value)
    except ValidationError:
        raise FinishValidationError("FINISH_SCHEMA") from None
    # Spring의 Unicode Number 검사와 동일하게 십진·문자형·기타 숫자 범주를 거부한다.
    has_unicode_number = any(unicodedata.category(character).startswith("N") for character in answer.summary)
    if not answer.summary.strip():
        raise FinishValidationError("SUMMARY_EMPTY")
    if has_unicode_number:
        raise FinishValidationError("SUMMARY_UNICODE_NUMBER")
    if _NUMERIC_CLAIM.search(answer.summary):
        raise FinishValidationError("SUMMARY_NUMERIC_PATTERN")
    if _ASSERTED_OUTCOME.search(answer.summary):
        raise FinishValidationError("SUMMARY_ASSERTED")
    successful = {item["id"]: item for item in observations if item["status"] == "SUCCESS"}
    seen = set()
    for citation in answer.citations:
        if citation.observationId not in successful or not citation.quote.strip():
            raise FinishValidationError("CITATION_SOURCE")
        try:
            scalar = pointer_scalar(successful[citation.observationId], citation.path)
        except ValueError:
            raise FinishValidationError("CITATION_POINTER") from None
        numeric_string = isinstance(scalar, str) and re.fullmatch(r"[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?", scalar)
        correct = citation.quote == scalar if numeric_string else (citation.quote in scalar if isinstance(scalar, str) else citation.quote == exact_json(scalar))
        if not correct:
            raise FinishValidationError("CITATION_QUOTE")
        key = (citation.observationId, citation.path, citation.quote)
        if key in seen:
            raise FinishValidationError("CITATION_DUPLICATE")
        seen.add(key)
    if successful and not answer.citations:
        raise FinishValidationError("CITATION_REQUIRED")
    if len(answer.nextActions) != len(set(answer.nextActions)):
        raise FinishValidationError("ACTION_DUPLICATE")
    return answer.model_dump()
