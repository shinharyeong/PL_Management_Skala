package io.skala.backend.domain.submission.value;

/** 엑셀 파싱 작업 상태 (parsing_job.status) */
public enum ParsingJobStatus {

    /** 대기 */
    QUEUED,
    /** 처리 중 */
    RUNNING,
    /** 성공 */
    SUCCEEDED,
    /** 실패 */
    FAILED
}
