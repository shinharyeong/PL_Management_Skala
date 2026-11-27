"""고정 근거·인용·목표 단위의 검증 규칙. DB·파일·네트워크 접근은 하지 않는다."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal, localcontext
import re
import unicodedata
from typing import Any

from .discovery_models import (DiscoveryHypothesis, EvidenceReadResponse, GoalProposal,
                               ModelDiscoveryProposal, ModelGoalProposal, ReferenceSelection)

MAX_READ_REFS = 20
DISCOVERY_LIMITATIONS = [
    "제안은 검토 가설이며 실행 가능성·개선 효과·실현 시점을 확인한 결과가 아닙니다.",
    "목표·기간·보호 조건과 후보의 채택은 사용자가 확인해야 하며 자동으로 저장하거나 계산에 반영하지 않습니다.",
    "승인 소명은 과거 설명의 승인 기록이며 새로운 개선 과제의 증빙 검증이나 추진 승인이 아닙니다.",
    "고정된 근거 목록에서 읽은 자료만 사용하며 ERP·외부 문서·증빙 URL을 조회하지 않습니다.",
]
_MONEY = re.compile(r"(?<![\d.,+−A-Za-z-])(?P<number>(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?)\s*"
                    r"(?P<unit>천만\s*원|백만\s*원|만\s*원|조\s*원|억\s*원|조|억|원)")
_MULTIPLIERS = {"원": 1, "만원": 10_000, "백만원": 1_000_000, "천만원": 10_000_000,
                "억": 100_000_000, "억원": 100_000_000, "조": 1_000_000_000_000, "조원": 1_000_000_000_000}
_AMBIGUOUS = re.compile(r"취소|철회|말고|아니라|하지\s*않아도|하지\s*말|제외해|제외하")
# 명백한 수사 구조는 붙여 쓴 금액도 검사하고 모호한 단순 수사는 어절·통화 표지를 요구한다.
_NUMERIC_CLAIM = re.compile(
    r"[\d０-９%]|퍼센트|절반|두\s*배|세\s*배|"
    r"(?:[수일이삼사오육칠팔구십백천]*[십백천][수일이삼사오육칠팔구십백천]*\s*(?:억|조|만)(?:\s*원)?|"
    r"(?<![가-힣])[수일이삼사오육칠팔구]+\s*(?:(?:억|조)(?:\s*원)?|만\s*원))"
    r"(?=$|[^가-힣]|[은는이가을를의에와과도]|으로|보다|부터|까지|입니다|이며|이고|인데|증가|감소|절감|달성|확보|필요|부족|초과|미달|수준|규모|가량|정도)"
    r"|\b(?:million|billion|percent)\b", re.I)
_ASSERTED_OUTCOME = re.compile(r"확정|보장|반드시|(?:승인|달성|실현|검증|확보)\s*완료|(?:달성|확보|실현|절감)(?:됩니다|합니다)")
_REDUCTION_PROHIBITION = re.compile(
    r"(?:(?:감축|축소|삭감)(?:을|를)?\s*하지|줄이지)\s*"
    r"(?:마(?:세요|라|십시오)?|말(?:아(?:줘|주세요|라)?|라|것))(?=$|[\s.!?])"
)
GOAL_VALIDATION_MESSAGES = {
    "UNCERTAINTY_CLAIM": "확인 질문에 새로운 숫자나 달성 확정을 추가할 수 없습니다.",
    "TARGET_QUOTE": "목표 금액을 뒷받침하는 단일한 요청문 인용이 없습니다.",
    "TARGET_VALUE": "목표 원 단위 금액이 요청문 인용과 다릅니다.",
    "TARGET_RANGE": "목표 금액 범위가 지원되지 않습니다.",
    "YEAR_QUOTE": "목표 연도를 뒷받침하는 원문이나 선택값이 없습니다.",
    "PROTECTED_QUOTE": "보호 조건 인용이 요청문에 없습니다.",
    "GOAL_CONTRACT": "목표 제안의 계약을 확인하지 못했습니다.",
}


class GoalValidationError(ValueError):
    """모델 목표 검증의 원인을 원문 없는 고정 코드로 구분한다."""

    def __init__(self, code: str) -> None:
        """알려진 검증 코드와 고정 안내만 보관한다.

        Args: code 등록된 목표 검증 오류 종류.
        Returns: 없음. Raises: 없음.
        Side Effects: 없음. 모델 인용·금액·응답 원문은 보관하거나 출력하지 않는다.
        """
        self.code = code if code in GOAL_VALIDATION_MESSAGES else "GOAL_CONTRACT"
        super().__init__(GOAL_VALIDATION_MESSAGES[self.code])


def ambiguous_protection_request(prompt: str) -> bool:
    """명백한 감축 금지 명령과 취소·예외·조건부 요청을 구분한다.

    Args: prompt 전체 사용자 요청 원문.
    Returns: 보호 조건을 자동 제안하기 어려운 취소·예외·조건 표현이 있으면 True.
    Raises: 없음.
    Side Effects: 없음. 원문을 수정하지 않으며 금지 명령의 구간만 메모리에서 대조한다.
    """
    if re.search(r"만약|경우|가능하면|조건부|다만|단\s*[,，:]", prompt):
        return True
    prohibitions = [match.span() for match in _REDUCTION_PROHIBITION.finditer(prompt)]
    return any(not any(start <= match.start() and match.end() <= end for start, end in prohibitions)
               for match in _AMBIGUOUS.finditer(prompt))


def numeric_claim_kind(text: str) -> str | None:
    """설명에서 금지된 수치의 종류만 반환하며 정상 조사와 모호한 어절을 금액으로 단정하지 않는다.

    Args: text 검증할 설명 문장.
    Returns: 고정된 수치 분류 코드 또는 수치가 검출되지 않으면 None.
    Raises: 없음.
    Side Effects: 없음. 일치한 원문·수치·위치를 기록하거나 반환하지 않는다. 의미적 진실성 검증은 아니다.
    """
    if any(unicodedata.category(character).startswith("N") for character in text):
        return "NUMERIC_CHARACTER"
    match = _NUMERIC_CLAIM.search(text)
    if match is None:
        return None
    matched = match.group().lower()
    if matched in {"%", "퍼센트", "percent"}:
        return "PERCENT_EXPRESSION"
    if matched == "절반" or re.fullmatch(r"[두세]\s*배", matched):
        return "RATIO_EXPRESSION"
    if matched in {"million", "billion"}:
        return "SCALE_EXPRESSION"
    return "KOREAN_AMOUNT"


def money_values(text: str) -> list[str]:
    """명시된 통화 단위를 원 단위 문자열로 정확히 변환한다.

    Args:
        text: 사용자가 직접 입력한 요청문 또는 그 부분 인용.
    Returns:
        양수·정수부 24자리·소수부 6자리 범위의 원 단위 값 목록.
    Raises:
        없음. 지원하지 않는 표기나 범위는 추출하지 않는다.
    Side Effects:
        없음. 손익 계산은 하지 않고 입력 단위만 해석한다.
    """
    values = []
    for match in _MONEY.finditer(text):
        with localcontext() as context:
            context.prec = 80
            amount = Decimal(match["number"].replace(",", "")) * _MULTIPLIERS[re.sub(r"\s", "", match["unit"])]
        value = format(amount, "f")
        if "." in value:
            value = value.rstrip("0").rstrip(".")
        if amount > 0 and re.fullmatch(r"\d{1,24}(?:\.\d{1,6})?", value):
            values.append(value)
    return values


def fallback_goal(prompt: str, year: int, protected: list[str] | None = None) -> dict[str, Any]:
    """명시적 목표 금액만 보수적으로 추출하고 모든 값을 확인 대상으로 둔다.

    Args:
        prompt: 원문 자연어 요청.
        year: 사용자가 선택한 분석 연도.
        protected: 이미 선택된 보호 범주. 요청문 키워드로 보호 조건을 확정하지 않는다.
    Returns:
        needsConfirmation이 참인 목표 제안.
    Raises:
        ValidationError: 호출자가 지원하지 않는 보호 범주를 전달한 경우.
    Side Effects:
        없음.
    """
    values = list(dict.fromkeys(money_values(prompt)))
    explicit_goal = ("영업이익" in prompt and not re.search(r"매출|순이익|공헌이익|현금", prompt)) or (
        "목표" in prompt and not re.search(r"매출|순이익|공헌이익|현금|비용", prompt))
    target = values[0] if len(values) == 1 and explicit_goal and not _AMBIGUOUS.search(prompt) else None
    years = list(dict.fromkeys(int(value) for value in re.findall(r"\b(20\d{2})\s*년", prompt)))
    target_year = years[0] if len(years) == 1 else year
    uncertainties = ["목표 금액·연도·보호 조건을 확인한 뒤 적용해 주세요.",
                     "기본 추출은 보호 조건의 부정·예외·조건문을 자동 해석하지 않습니다."]
    if target is None:
        uncertainties.append("단일한 목표 금액을 안전하게 특정하지 못했습니다. 원 단위 목표를 직접 확인해 주세요.")
    if len(years) > 1:
        uncertainties.append("요청에 여러 연도가 있어 선택한 분석 연도를 임시로 사용했습니다.")
    if target_year != year:
        uncertainties.append("요청한 목표 연도와 선택한 기준 연도가 다릅니다. 기준 자료 또는 목표 기간을 확인해 주세요.")
    return GoalProposal(targetProfitWon=target, targetYear=target_year,
                        protectedCategories=list(dict.fromkeys(protected or [])), needsConfirmation=True,
                        uncertainties=uncertainties).model_dump()


def validate_goal(value: Any, prompt: str, year: int, protected: list[str] | None = None) -> dict[str, Any]:
    """모델 목표를 요청문 인용·단위와 대조하고 보호 조건의 불명확한 해석을 보류한다.

    Args:
        value: 인용을 포함한 모델 목표 제안.
        prompt: 사용자가 입력한 전체 요청문.
        year: 선택한 분석 연도.
        protected: 기존에 선택된 보호 범주.
    Returns:
        원문 대조를 통과한 목표 제안. 적용 전 사용자 확인은 항상 필요하다.
    Raises:
        GoalValidationError: 숫자·연도·보호 인용이 원문과 다르거나 미검증 주장이 있는 경우.
        ValidationError: 목표 제안의 타입·필드 계약이 다른 경우.
    Side Effects:
        없음.
    """
    goal = ModelGoalProposal.model_validate(value)
    uncertainties = list(goal.uncertainties)
    if any(numeric_claim_kind(text) or _ASSERTED_OUTCOME.search(text) for text in uncertainties):
        raise GoalValidationError("UNCERTAINTY_CLAIM")
    if goal.targetProfitWon is not None:
        quote = goal.targetQuote
        if not quote or quote not in prompt or len(set(money_values(quote))) != 1:
            raise GoalValidationError("TARGET_QUOTE")
        if Decimal(goal.targetProfitWon) != Decimal(money_values(quote)[0]):
            raise GoalValidationError("TARGET_VALUE")
        if not re.fullmatch(r"\d{1,24}(?:\.\d{1,6})?", goal.targetProfitWon) or Decimal(goal.targetProfitWon) <= 0:
            raise GoalValidationError("TARGET_RANGE")
    if goal.targetYear is not None:
        quote = goal.yearQuote
        explicit = bool(quote and quote in prompt and str(goal.targetYear) in re.findall(r"\b(20\d{2})\s*년", quote))
        selected = goal.targetYear == year and (quote is None or quote in prompt)
        if not explicit and not selected:
            raise GoalValidationError("YEAR_QUOTE")
    categories = list(dict.fromkeys(protected or []))
    for item in goal.protectedQuotes:
        if item.quote not in prompt:
            raise GoalValidationError("PROTECTED_QUOTE")
        indicator = r"인력|인원|인건비|고용" if item.category == "HEADCOUNT" else r"R\s*&\s*D|연구\s*개발"
        protection = re.search(r"유지|보호|(?:감축|축소|삭감|줄이)[^.!?\n]{0,20}(?:없|금지|않|불가)", item.quote, re.I) or _REDUCTION_PROHIBITION.search(item.quote)
        reversal = re.search(r"(?:유지|보호)[^.!?\n]{0,8}(?:않|아니|불필요)|(?:감축|축소|삭감)[^.!?\n]{0,8}(?:가능|허용)|(?:유지|보호|감축|축소|삭감|줄이)[^.!?\n]{0,20}(?:않아도|말아도)", item.quote)
        if ambiguous_protection_request(prompt) or reversal or not protection or not re.search(indicator, item.quote, re.I):
            uncertainties.append("부정·예외가 있거나 의미가 불명확한 보호 조건은 자동 제안에서 제외했습니다.")
            continue
        if item.category not in categories:
            categories.append(item.category)
    uncertainties.append("요청문 인용을 대조했지만 목표·기간·보호 조건의 적용에는 사용자 확인이 필요합니다.")
    if goal.targetProfitWon is None:
        uncertainties.append("목표 금액이 정해지지 않았습니다. 목표 금액을 직접 확인해 주세요.")
    if goal.targetYear is not None and goal.targetYear != year:
        uncertainties.append("목표 연도와 기준 연도가 다르므로 해당 기간의 기준 자료를 확인해 주세요.")
    return GoalProposal(targetProfitWon=goal.targetProfitWon, targetYear=goal.targetYear or year,
                        protectedCategories=categories, needsConfirmation=True,
                        uncertainties=list(dict.fromkeys(uncertainties))[:12]).model_dump()


def validate_discovery_context(payload: dict[str, Any]) -> None:
    """허용 근거가 고정 스냅샷 조직과 연결되는지 확인한다.

    Args:
        payload: 스키마 검증을 마친 탐색 요청.
    Returns:
        없음.
    Raises:
        ValueError: 중복 ID·다른 조직·시간대 없는 기준일이 포함된 경우.
    Side Effects:
        없음.
    """
    snapshot = payload["snapshot"]
    organizations = [item["id"] for item in snapshot["organizations"]]
    refs = [item["refId"] for item in payload["evidenceCatalog"]]
    if len(organizations) != len(set(organizations)) or len(refs) != len(set(refs)):
        raise ValueError("기준 조직 또는 근거 ID가 중복됐습니다.")
    if any(item["organizationId"] not in organizations for item in payload["evidenceCatalog"]):
        raise ValueError("고정 기준에 없는 조직의 근거가 포함됐습니다.")
    if datetime.fromisoformat(snapshot["asOf"].replace("Z", "+00:00")).tzinfo is None:
        raise ValueError("고정 기준일에 시간대가 없습니다.")


def default_references(catalog: list[dict[str, Any]]) -> list[str]:
    """손익과 승인 소명을 번갈아 선택해 제한된 읽기 범위를 정한다.

    Args:
        catalog: 백엔드가 허용한 고정 근거 목록.
    Returns:
        최대 스무 개의 고유한 근거 ID. 전체 조회를 의미하지 않는다.
    Raises:
        없음.
    Side Effects:
        없음.
    """
    pnl = [item["refId"] for item in catalog if item["kind"] == "PNL"]
    explanations = [item["refId"] for item in catalog if item["kind"] == "APPROVED_EXPLANATION"]
    selected = []
    for index in range(max(len(pnl), len(explanations))):
        for refs in (pnl, explanations):
            if index < len(refs) and len(selected) < MAX_READ_REFS:
                selected.append(refs[index])
    return selected


def validate_references(value: Any, catalog: list[dict[str, Any]]) -> list[str]:
    """모델이 허용 목록의 근거만 읽도록 제한한다.

    Args:
        value: refIds 필드만 가진 모델 제안.
        catalog: 현재 분석의 허용 목록.
    Returns:
        중복 없는 허용 ID 목록.
    Raises:
        ValueError 또는 ValidationError: 미지 ID·중복·의미 없는 빈 선택인 경우.
    Side Effects:
        없음.
    """
    selected = ReferenceSelection.model_validate(value).refIds
    allowed = {item["refId"] for item in catalog}
    if len(set(selected)) != len(selected) or any(ref not in allowed for ref in selected) or (allowed and not selected):
        raise ValueError("읽기 대상으로 허용하지 않은 근거입니다.")
    return selected


def validate_read_result(value: Any, payload: dict[str, Any], refs: list[str]) -> list[dict[str, Any]]:
    """읽기 응답의 분석·revision·근거 집합과 조직을 고정 목록에 대조한다.

    Args:
        value: 백엔드 읽기 Tool의 JSON 응답.
        payload: 탐색 요청.
        refs: 실제 요청한 근거 ID 목록.
    Returns:
        동일한 분석 버전에서 요청한 근거 본문 목록.
    Raises:
        ValueError 또는 ValidationError: 다른 분석·조직·버전·누락·추가 근거가 반환된 경우.
    Side Effects:
        없음.
    """
    response = EvidenceReadResponse.model_validate(value)
    if response.analysisId != payload["analysisId"] or response.revision != payload["revision"]:
        raise ValueError("근거 응답이 요청한 분석 버전과 다릅니다.")
    items = [item.model_dump() for item in response.items]
    returned = [item["refId"] for item in items]
    if len(returned) != len(set(returned)) or set(returned) != set(refs):
        raise ValueError("읽기 응답의 근거 집합이 요청과 다릅니다.")
    catalog = {item["refId"]: item for item in payload["evidenceCatalog"]}
    for item in items:
        if any(item[key] != catalog[item["refId"]][key] for key in ("kind", "organizationId", "label")):
            raise ValueError("근거 응답의 식별 정보가 고정 목록과 다릅니다.")
    return items


def fallback_hypotheses(items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """실제로 읽은 자료에 연결된 일반 검토 질문만 생성한다.

    Args:
        items: 연결 검증을 마친 근거 목록.
    Returns:
        조직별 최대 다섯 개의 금액·시점 미확정 검토 가설.
    Raises:
        ValidationError: 내부 근거 계약을 위반한 경우.
    Side Effects:
        없음. 근거 없는 절감 가능액이나 원인을 만들지 않는다.
    """
    output = []
    seen = set()
    ordered = sorted(items, key=lambda item: item["kind"] != "APPROVED_EXPLANATION")
    for item in ordered:
        if item["organizationId"] in seen or len(output) >= 5:
            continue
        seen.add(item["organizationId"])
        explanation = item["kind"] == "APPROVED_EXPLANATION"
        output.append(DiscoveryHypothesis(
            id=f"hypothesis-{len(output) + 1}", organizationId=item["organizationId"],
            title="승인 소명 원인의 개선 가능성 검토" if explanation else "고정 손익의 개선 여지 검토",
            rationale="제시된 소명에서 추가로 개선 가능한 원인이 있는지 검토할 가설입니다." if explanation else
                      "고정 손익 자료를 출발점으로 추가 근거와 개선 가능성을 확인할 가설입니다.",
            evidenceRefs=[item["refId"]], sourceQuote=item["text"][:1000], category="UNKNOWN",
            questions=["현재 전망에 이미 반영되지 않은 개선 여지가 있습니까?", "순효과·실현 시점·실행 비용과 증빙을 확인할 수 있습니까?"],
            amountWon=None, recognitionMonth=None, needsConfirmation=True).model_dump())
    return output


def validate_discovery_proposal(value: Any, payload: dict[str, Any], items: list[dict[str, Any]]) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """모델 가설을 실제 읽은 출처와 대조하고 금액·시점은 항상 미확정으로 둔다.

    Args:
        value: 모델이 반환한 목표·가설 객체.
        payload: 고정 분석과 자연어 요청.
        items: 읽기 Tool을 통해 검증한 근거 목록.
    Returns:
        사용자 확인용 목표 제안과 출처가 연결된 가설 목록.
    Raises:
        ValueError 또는 ValidationError: 미지 참조·다른 조직·없는 인용·금액 주장·추가 필드가 있는 경우.
    Side Effects:
        없음.
    """
    proposal = ModelDiscoveryProposal.model_validate(value)
    goal = validate_goal(proposal.goalProposal.model_dump(), payload["prompt"], payload["snapshot"]["year"],
                         payload["protectedCategories"])
    evidence = {item["refId"]: item for item in items}
    output = []
    for index, hypothesis in enumerate(proposal.hypotheses):
        refs = hypothesis.evidenceRefs
        if len(refs) != len(set(refs)) or any(ref not in evidence for ref in refs):
            raise ValueError("실제로 읽지 않은 근거를 참조했습니다.")
        if any(evidence[ref]["organizationId"] != hypothesis.organizationId for ref in refs):
            raise ValueError("가설의 조직과 근거 조직이 다릅니다.")
        if not any(hypothesis.sourceQuote in evidence[ref]["text"] for ref in refs):
            raise ValueError("가설의 인용이 원문에 없습니다.")
        if any(_NUMERIC_CLAIM.search(text) or _ASSERTED_OUTCOME.search(text)
               for text in [hypothesis.title, hypothesis.rationale, *hypothesis.questions]):
            raise ValueError("검증되지 않은 숫자나 실현 확정은 가설 설명에 추가할 수 없습니다.")
        data = hypothesis.model_dump()
        if hypothesis.category in payload["protectedCategories"]:
            data["questions"] = [*data["questions"][:4], "선택한 보호 조건을 침해하지 않는 대안인지 확인해 주세요."]
        output.append(DiscoveryHypothesis(**data, id=f"hypothesis-{index + 1}",
                                          amountWon=None, recognitionMonth=None, needsConfirmation=True).model_dump())
    return goal, output
