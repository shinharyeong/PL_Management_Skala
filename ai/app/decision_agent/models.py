"""Spring이 확정한 분석 스냅샷·후보·계산 결과의 내부 전달 계약.

금액은 십진 문자열로만 받으며 이 모듈은 금액을 계산하거나 원본을 조회하지 않는다.
"""
from __future__ import annotations

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictInt, StrictStr

Amount = Annotated[StrictStr, Field(pattern=r"^-?\d{1,30}(?:\.\d{1,6})?$", max_length=38)]
CalculatedAmount = Annotated[StrictStr, Field(pattern=r"^-?\d{1,40}(?:\.\d{1,14})?$", max_length=56)]
Identifier = Annotated[StrictStr, Field(min_length=1, max_length=100)]
Category = Literal["OPERATIONS", "PROCUREMENT", "REVENUE", "HEADCOUNT", "RND", "UNKNOWN"]


class ContractModel(BaseModel):
    """명세에 없는 필드를 거부하는 내부 DTO의 공통 기반."""

    model_config = ConfigDict(extra="forbid")


class SnapshotOrganization(ContractModel):
    """고정 스냅샷의 조직 식별자와 백엔드가 계산한 손익 값."""

    id: Identifier
    code: StrictStr = Field(min_length=1, max_length=100)
    name: StrictStr = Field(min_length=1, max_length=200)
    revenueWon: Amount
    costWon: Amount
    profitWon: Amount


class DecisionSnapshot(ContractModel):
    """확정 master에 연결된 원천 제출본과 연간 전망의 불변 입력."""

    masterId: Identifier
    masterLabel: StrictStr = Field(min_length=1, max_length=200)
    year: StrictInt = Field(ge=2000, le=9999)
    baseMonth: StrictInt = Field(ge=1, le=12)
    asOf: StrictStr = Field(min_length=1, max_length=64)
    sourceSubmissionIds: list[Identifier] = Field(min_length=1, max_length=1000)
    organizations: list[SnapshotOrganization] = Field(min_length=1, max_length=1000)
    baselineProfitWon: Amount


class DecisionCandidate(ContractModel):
    """사용자가 입력한 개선 후보와 증빙 확인 상태. 실제 효과 실현을 뜻하지 않는다."""

    id: Identifier
    organizationId: Identifier
    title: StrictStr = Field(min_length=1, max_length=200)
    type: Literal["NET_PROFIT", "REVENUE"]
    amountWon: Amount
    contributionMarginPercent: Amount | None = None
    implementationCostWon: Amount
    embeddedEffectWon: Amount
    recognitionMonth: StrictInt = Field(ge=1, le=24)
    category: Category
    duplicateGroup: StrictStr | None = Field(default=None, max_length=100)
    evidenceStatus: Literal["ASSUMPTION", "REVIEWED"]
    evidenceNote: StrictStr | None = Field(default=None, max_length=4000)
    evidenceRef: StrictStr | None = Field(default=None, max_length=2000)


class CandidateEffect(ContractModel):
    """후보별 포함 여부·검토 표시·제외 사유를 담은 백엔드 계산 결과."""

    candidateId: Identifier
    netEffectWon: CalculatedAmount | None
    included: StrictBool
    reviewed: StrictBool
    exclusionReasons: list[Annotated[StrictStr, Field(max_length=1000)]] = Field(max_length=20)


class DecisionResult(ContractModel):
    """시나리오 계산 엔진의 권위 있는 결과. AI는 값을 변경하거나 재산출하지 않는다."""

    baselineProfitWon: CalculatedAmount
    targetProfitWon: CalculatedAmount
    initialGapWon: CalculatedAmount
    reviewedEffectWon: CalculatedAmount
    conditionalEffectWon: CalculatedAmount
    reviewedOutlookWon: CalculatedAmount
    conditionalOutlookWon: CalculatedAmount
    reviewedGapWon: CalculatedAmount
    conditionalGapWon: CalculatedAmount
    targetCoveragePercent: CalculatedAmount | None
    effects: list[CandidateEffect] = Field(max_length=100)
    warnings: list[Annotated[StrictStr, Field(max_length=2000)]] = Field(max_length=100)


class DecisionComposeRequest(ContractModel):
    """의사결정서 설명에 필요한 고정 입력. 사용자 요청문은 실행 지시가 아닌 분석 자료다."""

    analysisId: Identifier
    title: StrictStr = Field(min_length=1, max_length=200)
    prompt: StrictStr = Field(max_length=4000)
    targetProfitWon: Amount
    protectedCategories: list[Category] = Field(max_length=6)
    snapshot: DecisionSnapshot
    candidates: list[DecisionCandidate] = Field(max_length=100)
    result: DecisionResult
