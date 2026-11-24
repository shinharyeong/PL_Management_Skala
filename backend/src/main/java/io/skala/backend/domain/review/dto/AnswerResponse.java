package io.skala.backend.domain.review.dto;

/** PROOF-04 응답 */
public record AnswerResponse(String answerState, OrgReviewListResponse.Progress progress, String stateToken) {
}
