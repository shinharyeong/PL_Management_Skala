"""조회 전용 대화형 Agent의 입력·native Tool·관찰·종료 계약."""
from __future__ import annotations

from typing import Any, Literal
from decimal import Decimal

from pydantic import ConfigDict, Field, StrictInt, StrictStr, field_validator

from .models import ContractModel, Identifier


class ConversationTurn(ContractModel):
    """사실 근거로 사용하지 않는 제한된 사용자·assistant 대화 문맥."""

    role: Literal["user", "assistant"]
    content: StrictStr = Field(min_length=1, max_length=2000)


class AssistantContext(ContractModel):
    """Spring 권한 검사 후 전달한 고정 기준. 모델이 이 값을 바꾸는 인자를 제공하지 않는다."""

    masterId: Identifier
    masterLabel: StrictStr = Field(min_length=1, max_length=700)
    year: StrictInt = Field(ge=2000, le=9999)
    baseMonth: StrictInt = Field(ge=1, le=12)
    analysisId: Identifier | None
    revision: StrictInt | None = Field(ge=1)
    asOf: StrictStr = Field(min_length=1, max_length=64)


class AssistantRequest(ContractModel):
    """대화 요청과 모델·그래프 상태에 넣지 않을 단기 capability."""

    message: StrictStr = Field(min_length=1, max_length=2000)
    history: list[ConversationTurn] = Field(max_length=8)
    context: AssistantContext
    toolAccessToken: StrictStr = Field(min_length=16, max_length=2048, repr=False)


class NoArguments(ContractModel):
    """임의 scope·SQL·URL 입력을 거부하는 빈 인자 계약."""


class DriverArguments(ContractModel):
    """계획 대비 손익 차이 순위의 표시 개수."""

    limit: StrictInt = Field(default=5, ge=1, le=10, description="계획 대비 영업이익 차이 표시 개수. 차이의 크기는 인과 원인이나 개선 가능액이 아니다.")


class OrganizationArguments(ContractModel):
    """서버가 고정 조직 안에서 추가 검증할 선택 조직 ID."""

    organizationId: Identifier | None = Field(default=None, description="생략하면 고정 마스터의 모든 허용 조직을 한 번에 조회한다. 특정 조직만 필요할 때 기존 관찰에서 읽은 조직 ID를 사용한다.")


class ExplanationArguments(OrganizationArguments):
    """승인 소명 조회의 조직과 반환 상한."""

    limit: StrictInt = Field(default=10, ge=1, le=20)


class VersionArguments(ContractModel):
    """허용된 제출 버전 목록을 조회할 조직."""

    organizationId: Identifier


class ComparisonArguments(VersionArguments):
    """앞선 목록 Tool에서 확인한 비교 제출 ID. 범위 검증은 Spring이 수행한다."""

    baseSubmissionId: Identifier


class ScenarioArguments(ContractModel):
    """저장된 후보 중 시뮬레이션할 ID. 새 금액·가정·후보를 입력하지 않는다."""

    candidateIds: list[Identifier] | None = Field(default=None, max_length=100)


class DraftArguments(ContractModel):
    """저장된 후보 또는 가설에서 요청 화면에 전달할 초안의 출처."""

    sourceKind: Literal["candidate", "hypothesis"]
    sourceId: Identifier


class GoalPreviewArguments(ContractModel):
    """저장하지 않는 영업이익 목표 비교의 억 원 단위 입력."""

    targetProfitEok: StrictStr = Field(
        pattern=r"^(?:0|[1-9][0-9]{0,15})(?:\.[0-9]{1,8})?$",
        description="사용자가 명시한 영업이익 목표의 억 원 단위 십진 문자열. 900억원은 '900'. 양수·소수 여덟 자리까지, 쉼표·단위·지수·부호 금지. 저장 없이 기존 연간 전망과 비교한다.")
    targetYear: StrictInt = Field(ge=2000, le=9999, description="사용자가 목표에 명시한 연도. 목표 연도를 명시하지 않은 경우에만 context.year를 사용한다. 선택 마스터와 다르면 임의로 기준 연도로 바꾸지 않는다.")

    @field_validator("targetProfitEok")
    @classmethod
    def positive_target(cls, value: str) -> str:
        """형식 검사를 통과한 목표가 최소 원 단위의 양수인지 확인한다.

        Args: value 억 원 단위 문자열.
        Returns: 계산하거나 반올림하지 않은 입력 문자열.
        Raises: ValueError: 목표가 양수가 아닌 경우.
        Side Effects: 없음. 금액 환산과 차이 계산은 Spring만 수행한다.
        """
        if Decimal(value) <= 0:
            raise ValueError("영업이익 목표는 양수여야 합니다.")
        return value


class ObservationDetailArguments(ContractModel):
    """이번 실행에서 이미 성공한 원문의 제한된 페이지를 다시 읽는다."""

    observationId: Identifier
    path: StrictStr = Field(pattern=r"^/data(?:/.*)?$", max_length=1000)
    offset: StrictInt = Field(default=0, ge=0, le=10000)
    limit: StrictInt = Field(default=5, ge=1, le=10)


class PlannedRequirement(ContractModel):
    """사용자 요청의 실제 부분 인용에 연결한 확인 항목."""

    id: Identifier
    requestQuote: StrictStr = Field(min_length=1, max_length=500)
    question: StrictStr = Field(min_length=1, max_length=400)


class AnalysisPlan(ContractModel):
    """모델이 조회 전에 선언하며 같은 실행 동안 변경하지 않는 요구 항목."""

    requirements: list[PlannedRequirement] = Field(min_length=1, max_length=6)


class GroundedFinding(ContractModel):
    """사실·해석·가설을 구분하고 각 문장의 실제 근거를 선택한다."""

    id: Identifier
    kind: Literal["FACT", "INTERPRETATION", "HYPOTHESIS"]
    text: StrictStr = Field(min_length=1, max_length=700, description="연결한 근거가 뒷받침하는 판단 하나를 한국어로 쓴다. 숫자가 없는 실제 조직명은 정확히 쓰고 비교 기준을 명시한다. 금액·비율·순번·날짜는 쓰지 않는다. 숫자 포함 조직명만 '해당 조직'으로 표현하고 이름 필드를 citationIds에 포함한다. 일부 조직의 결과를 전체로 일반화하거나 같은 문장을 반복하지 않는다.")
    citationIds: list[Identifier] = Field(min_length=1, max_length=4, description="실제 citationOptions ID를 하나 이상 네 개 이하 선택한다. 빈 배열은 금지다. 근거 없는 제안·확인 요청은 findings에 넣지 않고 requirement의 missingInformation·followUpQuestion에 작성한다.")
    uncertainty: StrictStr | None = Field(default=None, min_length=1, max_length=400)


class RequirementResolution(ContractModel):
    """계획한 항목마다 답한 근거 또는 아직 부족한 자료와 확인 질문을 기록한다."""

    id: Identifier
    status: Literal["ANSWERED", "NEEDS_INPUT", "UNSUPPORTED"]
    findingIds: list[Identifier] = Field(default_factory=list, max_length=8, description="실제 작성한 finding ID만 사용한다. ANSWERED는 하나 이상 필요하다. 근거 없는 미해결 항목은 빈 배열을 사용할 수 있다.")
    missingInformation: StrictStr | None = Field(default=None, min_length=1, max_length=400, description="ANSWERED이면 null. 미해결이면 필요한 자료·조건을 구체적으로 쓴다. 아직 조회하지 않은 자료를 존재하지 않는다고 단정하지 않는다.")
    followUpQuestion: StrictStr | None = Field(default=None, min_length=1, max_length=400, description="ANSWERED이면 null. 미해결이면 사용자가 제공할 구체적인 값·문서·시점을 요청한다. 우선순위 추천처럼 사용자가 판단을 요청한 질문을 그대로 되묻지 않는다. 이 질문에는 인용이 없어도 된다.")


class GroundedFinish(ContractModel):
    """질문별 요약과 근거가 연결된 설명·미해결 항목을 담는 종료 제안."""

    summary: StrictStr = Field(min_length=1, max_length=1000, description="findings를 종합한 핵심 판단과 미해결 사항. 숫자·금액·비율·날짜를 쓰지 않는다. 목표액은 '요청한 목표', 숫자 포함 이름은 '해당 조직'으로 표현한다. 실제 숫자는 화면의 목표 비교와 조직 표에 자동 표시된다.")
    findings: list[GroundedFinding] = Field(max_length=8)
    requirements: list[RequirementResolution] = Field(min_length=1, max_length=6)
    nextActions: list[Literal["OPEN_DISCOVERY", "OPEN_SCENARIOS", "OPEN_WORKFLOW"]] = Field(default_factory=list, max_length=3)


class ProseRepairUpdate(ContractModel):
    """서버가 허용한 설명 필드 한 곳의 모델 작성 대체 문장."""

    path: StrictStr = Field(min_length=1, max_length=80)
    text: StrictStr = Field(min_length=1, max_length=1000, description="해당 필드의 의미와 근거를 유지하되 숫자·금액·비율 표현 없이 다시 쓴다. 실제 수치는 이미 연결된 인용에서 확인하며 임의 금액이나 새 사실을 추가하지 않는다.")


class ProseRepair(ContractModel):
    """숫자 검증에 실패한 설명만 고치는 로컬 도구 계약."""

    updates: list[ProseRepairUpdate] = Field(min_length=1, max_length=8)


class Citation(ContractModel):
    """이번 실행의 성공 관찰 scalar에 대한 정확한 인용."""

    observationId: Identifier
    path: StrictStr = Field(max_length=1000)
    quote: StrictStr = Field(min_length=1, max_length=2000)


class FinishAnswer(ContractModel):
    """모델의 정성 답변과 출처·수동 화면 이동 제안."""

    summary: StrictStr = Field(min_length=1, max_length=2000)
    citations: list[Citation] = Field(max_length=32)
    nextActions: list[Literal["OPEN_DISCOVERY", "OPEN_SCENARIOS", "OPEN_WORKFLOW"]] = Field(max_length=3)


class FinishSelection(ContractModel):
    """모델이 문장·숫자·경로를 생성하지 않고 실제 근거 ID만 선택하는 native 종료 계약."""

    citationIds: list[Identifier] = Field(max_length=12, description="이번 대화 ToolMessage의 citationOptions에 있는 id만 선택한다. 숫자·quote·JSON pointer·자유 문장을 작성하지 않는다. 사용자가 물은 지표와 조직에 해당하는 근거를 고른다.")
    answerStyle: Literal["RESULT", "NEEDS_INPUT"] = Field(default="RESULT", description="자료가 있으면 RESULT, 추가 기준·자료가 필요하면 NEEDS_INPUT. 정성 안내 문장은 코드가 구성한다.")
    nextActions: list[Literal["OPEN_DISCOVERY", "OPEN_SCENARIOS", "OPEN_WORKFLOW"]] = Field(default_factory=list, max_length=3, description="필요할 때만 제안할 업무 화면. 저장·발송·승인을 실행하지 않는다.")


class ObservationBasis(ContractModel):
    """Spring이 반환한 조회 시점·고정 범위. 추가 출처 메타데이터는 그대로 보존한다."""

    model_config = ConfigDict(extra="allow")
    masterId: Identifier
    year: StrictInt = Field(ge=2000, le=9999)
    baseMonth: StrictInt = Field(ge=1, le=12)
    analysisId: Identifier | None
    revision: StrictInt | None = Field(ge=1)
    capturedAt: StrictStr = Field(min_length=1, max_length=64)
    comparisonBasis: StrictStr = Field(min_length=1, max_length=2000)


class ToolObservation(ContractModel):
    """원문 그대로 반환할 Spring 실행 기록. AI가 합성한 오류와 구분한다."""

    id: Identifier
    tool: StrictStr = Field(min_length=1, max_length=100)
    label: StrictStr = Field(min_length=1, max_length=700)
    status: Literal["SUCCESS", "ERROR"]
    basis: ObservationBasis
    data: Any
    warnings: list[StrictStr] = Field(max_length=30)


ARGUMENT_MODELS = {
    "analysis_context": NoArguments, "company_pnl": NoArguments, "profit_drivers": DriverArguments,
    "organization_pnl": OrganizationArguments, "pnl_waterfall": NoArguments,
    "approved_explanations": ExplanationArguments, "submission_versions": VersionArguments,
    "submission_comparison": ComparisonArguments, "scenario_preview": ScenarioArguments,
    "work_items": NoArguments, "review_request_draft": DraftArguments,
    "goal_preview": GoalPreviewArguments,
    "observation_detail": ObservationDetailArguments,
}
TOOL_DESCRIPTIONS = {
    "analysis_context": "고정 master·snapshot과 선택 analysis의 목표·저장 후보·기존 계산을 읽는다.",
    "company_pnl": "고정 master의 전사 연간 전망·계획 차이와 업무 API의 지표별 증감·유불리 판정을 조회한다. 질문의 미해결 항목에 필요한 추가 근거를 판단한다. 비용 증가를 이익 개선으로 해석하지 않는다.",
    "profit_drivers": "계획 대비 영업이익 차이가 큰 항목을 조회한다. 절댓값이 큰 차이는 큰 미달일 수도 있으며 개선 순위가 아니다. 차이는 인과 원인이나 개선 가능액이 아니다.",
    "organization_pnl": "organizationId를 생략하면 모든 허용 조직의 손익·계획 대비 OP 차이·증감·유불리·순위를 한 번에 조회한다. signed 순위는 OP 차이 내림차순, absolute 순위는 절댓값순이다. 원인·개선 가능성은 이 수치만으로 단정하지 말고 필요한 소명이나 상세 근거를 추가로 확인한다.",
    "pnl_waterfall": "고정 master 손익의 워터폴 구성과 비교 기준을 조회한다.",
    "approved_explanations": "조직 ID를 생략하면 허용 조직의 승인 소명을 상한까지 읽는다. items.organizationId와 facts의 기간으로 분석 대상과 대조하고 label의 계정명이 아닌 items.text의 실제 회신에서 사유를 읽는다. total이 items 수보다 크면 일부 조회다. 필요한 조직이 목록에 없으면 실제 조회한 organizationId로 다시 요청하며, observation_detail은 이미 반환된 text의 잘린 내용만 더 읽을 수 있다. 계정명을 원인으로 바꾸거나 소명 승인을 새 과제 승인·개선액 증명으로 해석하지 않는다.",
    "submission_versions": "조직의 승인된 과거 제출 목록을 읽는다. 비교하려면 먼저 이 도구로 base ID를 확인한다.",
    "submission_comparison": "앞선 submission_versions에서 확인한 과거 제출과 고정 제출을 비교한다.",
    "scenario_preview": "선택 analysis의 기존 후보만 Spring 계산기로 시뮬레이션한다. 새 금액 입력·저장은 없다.",
    "work_items": "선택 analysis의 요청·회신·검토·결정·후속 상태를 읽는다.",
    "review_request_draft": "저장된 candidate 또는 hypothesis로 사용자가 확인할 요청 화면 초안을 만든다. 저장·발송하지 않는다.",
    "goal_preview": "사용자가 명시한 영업이익 목표(억 원)와 목표 연도를 Spring 계산기로 기존 고정 연간 전망과 비교한다. targetYear는 필수이며 목표 연도가 기준과 다르면 계산하지 않는다. 기간 불일치는 해당 연도 마스터 선택 또는 목표 연도 확인이 필요한 미해결 항목이다. 저장·새 분석 생성은 없다. 목표 부족액·초과액·전망 기준 충족 여부를 반환하며 실적 달성으로 해석하지 않는다. 임의 목표를 만들지 않는다.",
    "observation_detail": "현재 실행의 SUCCESS 관찰에서 /data 아래 원문을 페이지로 읽는다. observationId와 availableDetails의 path를 사용한다. 배열은 limit개, 문자열은 limit×400자, 객체는 limit개 속성이다. nextOffset이 있으면 같은 원천·경로의 그 offset을 요청한다. 상세 도구 결과 자체를 다시 원천으로 삼지 않는다. DB·파일·URL 임의 조회는 불가능하다.",
}
