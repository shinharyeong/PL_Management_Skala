"""의사결정 문장 선택의 순수 규칙. 파일·DB·네트워크 접근과 금액 계산을 수행하지 않는다."""
from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Any

BASE_LIMITATIONS = [
    "후보 효과는 미실현 조건부 값이며 추진 승인이나 확정 실적을 뜻하지 않습니다.",
    "근거 검토 표시는 사용자가 입력한 확인 기록이며 외부 증빙의 진위 검증을 뜻하지 않습니다.",
    "보호 범주 외에 자연어 요청에 적힌 추가 제약은 자동 검증되지 않았으므로 별도 확인이 필요합니다.",
    "목표 충족률은 금액 비율이며 목표 달성 확률이 아닙니다.",
]
FALLBACK_NOTICES = {
    "NO_MODEL": "모델이 설정되지 않아 고정 문장으로 검토 초안을 구성했습니다.",
    "MODEL_DISABLED": "모델 호출을 사용하지 않고 고정 문장으로 검토 초안을 구성했습니다.",
    "DAILY_LIMIT": "모델 호출 한도에 도달해 고정 문장으로 검토 초안을 구성했습니다.",
    "MODEL_FAILED": "모델 응답을 받지 못해 고정 문장으로 검토 초안을 구성했습니다.",
    "MODEL_REJECTED": "모델 응답이 허용된 설명 계약과 달라 고정 문장으로 대체했습니다.",
}


def validate_context(payload: dict[str, Any]) -> None:
    """식별자·버전 입력과 계산 결과의 연결만 검사한다.

    Args:
        payload: 구조 검증을 마친 스냅샷·후보·계산 결과.
    Returns:
        없음.
    Raises:
        ValueError: 기준 금액·목표·조직·후보 연결 또는 기준일이 잘못된 경우.
    Side Effects:
        없음. 업무 금액을 다시 계산하지 않는다.
    """
    snapshot, result = payload["snapshot"], payload["result"]
    if Decimal(snapshot["baselineProfitWon"]) != Decimal(result["baselineProfitWon"]):
        raise ValueError("스냅샷과 계산 결과의 기준 전망이 다릅니다.")
    if Decimal(payload["targetProfitWon"]) != Decimal(result["targetProfitWon"]):
        raise ValueError("요청 목표와 계산 결과의 목표가 다릅니다.")
    try:
        parsed = datetime.fromisoformat(snapshot["asOf"].replace("Z", "+00:00"))
    except ValueError as cause:
        raise ValueError("분석 기준일은 시간대가 있는 ISO 날짜여야 합니다.") from cause
    if parsed.tzinfo is None:
        raise ValueError("분석 기준일에 시간대가 없습니다.")
    organizations = [str(item["id"]) for item in snapshot["organizations"]]
    candidates = [str(item["id"]) for item in payload["candidates"]]
    effects = [str(item["candidateId"]) for item in result["effects"]]
    sources = [str(item) for item in snapshot["sourceSubmissionIds"]]
    if len(set(organizations)) != len(organizations) or len(set(sources)) != len(sources):
        raise ValueError("고정 스냅샷의 조직 또는 원천 제출본 ID가 중복됐습니다.")
    if len(set(candidates)) != len(candidates) or len(set(effects)) != len(effects):
        raise ValueError("후보 또는 후보 효과 ID가 중복됐습니다.")
    if set(candidates) != set(effects):
        raise ValueError("후보와 계산 결과가 같은 후보 집합을 참조하지 않습니다.")
    if any(str(item["organizationId"]) not in organizations for item in payload["candidates"]):
        raise ValueError("기준 스냅샷에 없는 조직의 후보가 포함됐습니다.")
    if any(item["included"] and item["netEffectWon"] is None for item in result["effects"]):
        raise ValueError("반영된 후보의 효과 금액이 없습니다.")


def build_catalog(payload: dict[str, Any]) -> dict[str, Any]:
    """권위 있는 결과 문자열로 허용된 요약과 검토 안건을 구성한다.

    Args:
        payload: 연결 검증을 마친 분석 요청.
    Returns:
        summaries, decisions와 기본 선택을 담은 문장 사전.
    Raises:
        KeyError: 내부 호출자가 필수 계약 필드를 빠뜨린 경우.
    Side Effects:
        없음. 단위 변환이나 금액 재계산을 하지 않는다.
    """
    result = payload["result"]
    summaries = {
        "conditional": (
            f"기준 전망 {result['baselineProfitWon']}원에 후보 순효과 {result['conditionalEffectWon']}원을 반영한 "
            f"가정 전망은 {result['conditionalOutlookWon']}원이며, 목표 대비 잔여 Gap은 {result['conditionalGapWon']}원입니다. "
            "후보 효과가 모두 실현된다는 가정이며 확정 실적이 아닙니다."
        ),
        "reviewed": (
            f"검토 표시된 후보의 순효과는 {result['reviewedEffectWon']}원이고, 이를 반영한 조건부 전망은 "
            f"{result['reviewedOutlookWon']}원입니다. 이 기준의 잔여 Gap은 {result['reviewedGapWon']}원이며 "
            "미검토 가정까지 포함한 전망과 구분해야 합니다."
        ),
    }
    decisions = {}
    effects = result["effects"]
    if any(item["included"] and not item["reviewed"] for item in effects):
        decisions["review_assumptions"] = "미검토 후보의 증빙·순효과·반영 시점을 확인해 주세요."
    if any(item["included"] for item in effects):
        decisions["review_execution"] = "반영 후보의 실행 조건과 추진 가능성을 검토해 주세요. 실제 추진 승인은 별도 업무입니다."
    if Decimal(result["conditionalGapWon"]) > 0:
        decisions["review_residual"] = f"잔여 Gap {result['conditionalGapWon']}원에 대한 추가 후보 또는 목표·기간 보완을 검토해 주세요."
    if any(not item["included"] for item in effects):
        decisions["review_exclusions"] = "제외된 후보의 제외 사유와 중복·보호 조건을 확인해 주세요."
    decisions["review_constraints"] = "자연어 요청의 추가 제약과 실제 실행 시점의 업무 조건을 확인해 주세요."
    return {"summaries": summaries, "decisions": decisions,
            "default": {"summaryKey": "conditional", "decisionKeys": list(decisions)}}


def validate_selection(value: Any, catalog: dict[str, Any]) -> dict[str, Any]:
    """모델이 허용 문장만 선택하고 모든 필수 검토 안건을 유지하는지 검사한다.

    Args:
        value: 모델이 반환한 JSON 값.
        catalog: 이 분석에 허용된 문장 사전.
    Returns:
        요약 ID와 검토 안건 순서의 사본.
    Raises:
        ValueError: 임의 문장·숫자·추가 필드·미지 ID·누락·중복이 있는 경우.
    Side Effects:
        없음.
    """
    if not isinstance(value, dict) or set(value) != {"summaryKey", "decisionKeys"}:
        raise ValueError("모델은 허용된 요약·검토 안건 ID만 반환해야 합니다.")
    summary, decisions = value["summaryKey"], value["decisionKeys"]
    if not isinstance(summary, str) or summary not in catalog["summaries"]:
        raise ValueError("허용되지 않은 요약입니다.")
    if not isinstance(decisions, list) or not all(isinstance(item, str) for item in decisions):
        raise ValueError("검토 안건 ID 목록이 아닙니다.")
    if len(set(decisions)) != len(decisions) or set(decisions) != set(catalog["decisions"]):
        raise ValueError("필수 검토 안건이 누락되거나 허용하지 않은 안건이 추가됐습니다.")
    return {"summaryKey": summary, "decisionKeys": list(decisions)}


def render_narrative(payload: dict[str, Any], catalog: dict[str, Any], selection: dict[str, Any],
                     mode: str, reason: str | None, steps: list[dict[str, str]]) -> dict[str, Any]:
    """선택된 문장에 고정 금액을 유지해 최종 설명 계약을 반환한다.

    Args:
        payload: 백엔드에서 받은 분석 자료.
        catalog: 허용 문장 사전.
        selection: 검증된 문장 ID 선택.
        mode: MODEL 또는 TEMPLATE.
        reason: 템플릿 대체 사유 코드. 모델 선택 성공이면 None.
        steps: 실제 수행한 단계와 상태.
    Returns:
        mode, summary, decisionRequests, limitations, steps 객체.
    Raises:
        ValueError: 허용하지 않은 모드 또는 대체 사유.
    Side Effects:
        없음. 입력값을 수정하지 않는다.
    """
    if ((mode == "MODEL" and reason is not None) or
            (mode == "TEMPLATE" and reason not in FALLBACK_NOTICES) or
            mode not in {"MODEL", "TEMPLATE"}):
        raise ValueError("설명 생성 상태가 올바르지 않습니다.")
    selected = validate_selection(selection, catalog)
    limitations = list(BASE_LIMITATIONS)
    limitations.extend(payload["result"]["warnings"])
    if reason:
        limitations.append(FALLBACK_NOTICES[reason])
    else:
        limitations.append("모델은 허용된 요약과 검토 순서만 선택했으며 금액과 문장은 고정된 계산 결과에 연결됩니다.")
    return {"mode": mode, "summary": catalog["summaries"][selected["summaryKey"]],
            "decisionRequests": [catalog["decisions"][key] for key in selected["decisionKeys"]],
            "limitations": list(dict.fromkeys(limitations)), "steps": [dict(step) for step in steps]}
