package io.skala.backend.domain.review.dto;

/** GAP-11 응답: 판단한(재요청이면 새로 연) 소명 회차와 항목의 상태 탭 */
public record ExplanationDecisionResponse(ExplanationResponse explanation, String tab, String stateToken) {
}
