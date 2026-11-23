package io.skala.backend.domain.submission.value;

/** 제출본 상태 (submission.status) */
public enum SubmissionStatus {

    /** 업로드만 함(미제출) */
    DRAFT,
    /** 검토 대기 */
    PENDING,
    /** 재제출 요청됨 */
    REJECTED,
    /** 승인 */
    APPROVED
}
