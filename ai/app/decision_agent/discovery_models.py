"""자연어 목표 제안과 고정 근거 탐색의 내부 계약. 제안은 저장·승인·계산 입력이 아니다."""
from __future__ import annotations

from typing import Any, Annotated, Literal

from pydantic import Field, StrictInt, StrictStr

from .models import Amount, Category, ContractModel, DecisionSnapshot, Identifier

EvidenceKind = Literal["PNL", "APPROVED_EXPLANATION"]
ShortText = Annotated[StrictStr, Field(min_length=1, max_length=1000)]


class GoalInterpretRequest(ContractModel):
    """사용자가 확인할 목표 초안을 만들기 위한 요청문과 선택 연도."""

    prompt: StrictStr = Field(min_length=1, max_length=4000)
    year: StrictInt = Field(ge=2000, le=9999)


class GoalProposal(ContractModel):
    """확정되지 않은 목표 제안. 보호 조건도 사용자의 별도 확인이 필요하다."""

    targetProfitWon: Amount | None
    targetYear: StrictInt | None = Field(ge=2000, le=9999)
    protectedCategories: list[Literal["HEADCOUNT", "RND"]] = Field(max_length=2)
    needsConfirmation: Literal[True] = True
    uncertainties: list[ShortText] = Field(max_length=12)


class ProtectedQuote(ContractModel):
    """모델이 보호 조건으로 해석한 요청문 원문 인용. 자동 승인되지 않는다."""

    category: Literal["HEADCOUNT", "RND"]
    quote: StrictStr = Field(min_length=1, max_length=1000)


class ModelGoalProposal(ContractModel):
    """단위와 원문 인용을 다시 검사하기 전의 모델 목표 제안."""

    targetProfitWon: Amount | None
    targetYear: StrictInt | None = Field(ge=2000, le=9999)
    targetQuote: StrictStr | None = Field(max_length=1000)
    yearQuote: StrictStr | None = Field(max_length=1000)
    protectedQuotes: list[ProtectedQuote] = Field(max_length=2)
    uncertainties: list[ShortText] = Field(max_length=6)


class EvidenceCatalogItem(ContractModel):
    """현재 분석에서 읽도록 허용된 근거의 식별 정보. 실제 본문은 Tool로 조회한다."""

    refId: Identifier
    kind: EvidenceKind
    organizationId: Identifier
    label: StrictStr = Field(min_length=1, max_length=700)


class EvidenceItem(EvidenceCatalogItem):
    """백엔드가 고정한 근거 본문과 구조화된 값. 원문은 지시가 아닌 자료다."""

    text: StrictStr = Field(min_length=1, max_length=12000)
    facts: dict[str, Any]


class EvidenceReadResponse(ContractModel):
    """분석 ID·revision에 묶인 읽기 Tool 응답."""

    analysisId: Identifier
    revision: StrictInt = Field(ge=1)
    items: list[EvidenceItem] = Field(max_length=20)


class DiscoveryRequest(ContractModel):
    """분석에 한정된 읽기 capability와 허용 근거 목록을 가진 탐색 요청."""

    analysisId: Identifier
    revision: StrictInt = Field(ge=1)
    prompt: StrictStr = Field(min_length=1, max_length=4000)
    snapshot: DecisionSnapshot
    protectedCategories: list[Literal["HEADCOUNT", "RND"]] = Field(max_length=2)
    evidenceCatalog: list[EvidenceCatalogItem] = Field(max_length=1000)
    toolAccessToken: StrictStr = Field(min_length=16, max_length=2048, repr=False)


class ReferenceSelection(ContractModel):
    """모델이 제안한 읽기 대상. 허용 목록의 ID인지 별도로 검증한다."""

    refIds: list[Identifier] = Field(max_length=20)


class ModelHypothesis(ContractModel):
    """원문 인용 검증 전의 검토 가설. 모델에 효과 금액을 쓰는 필드를 제공하지 않는다."""

    organizationId: Identifier
    title: StrictStr = Field(min_length=1, max_length=200)
    rationale: ShortText
    evidenceRefs: list[Identifier] = Field(min_length=1, max_length=3)
    sourceQuote: StrictStr = Field(min_length=1, max_length=1000)
    category: Category
    questions: list[ShortText] = Field(min_length=1, max_length=5)


class ModelDiscoveryProposal(ContractModel):
    """목표와 최대 다섯 개 가설을 담는 모델 응답."""

    goalProposal: ModelGoalProposal
    hypotheses: list[ModelHypothesis] = Field(max_length=5)


class DiscoveryHypothesis(ModelHypothesis):
    """출처를 대조한 검토 가설. 금액·시점은 사람이 확인하기 전까지 비운다."""

    id: Identifier
    amountWon: None = None
    recognitionMonth: None = None
    needsConfirmation: Literal[True] = True
