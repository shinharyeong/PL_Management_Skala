package io.skala.backend.domain.review.value;

/** 경영기획팀 검토 진행 상태 (review_item.workflow_state) */
public enum WorkflowState {

    /** 검토 필요 */
    NEEDS_REVIEW,
    /** 검토 대상으로 선정 */
    SELECTED,
    /** 소명 요청함 */
    REQUESTED,
    /** 제외 */
    EXCLUDED
}
