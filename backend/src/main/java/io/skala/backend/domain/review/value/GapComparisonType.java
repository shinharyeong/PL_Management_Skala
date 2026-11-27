package io.skala.backend.domain.review.value;

/** Gap 기준을 적용할 비교 종류 (gap_threshold.comparison) */
public enum GapComparisonType {

    /** 실적 vs 계획 */
    ACTUAL_VS_PLAN,
    /** 당월 실적 vs 전월 추정 또는 당월 직전 실적 */
    ACTUAL_VS_PREVIOUS,
    /** 추정 vs 직전 추정 */
    ESTIMATE_VS_PREVIOUS,
    /** 추정 vs 계획 */
    ESTIMATE_VS_PLAN
}
