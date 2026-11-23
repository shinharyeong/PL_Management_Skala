package io.skala.backend.domain.review.value;

/** 소명에 대한 경영기획팀 판단 (review_explanation.verdict) */
public enum ExplanationVerdict {

    /** 판단 전 */
    PENDING,
    /** 수락 */
    ACCEPTED,
    /** 재요청 */
    REJECTED
}
