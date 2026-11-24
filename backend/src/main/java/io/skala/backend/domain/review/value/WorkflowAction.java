package io.skala.backend.domain.review.value;

/** 검토 화면 동작 이력 종류 (review_workflow_event.action) */
public enum WorkflowAction {

    /** 선택 항목 검토하기 */
    SELECT,
    /** 제외 */
    EXCLUDE,
    /** 소명 요청 */
    REQUEST_EXPLANATION
}
