package io.skala.backend.domain.report.value;

/** 보고서 PDF 분석 작업 상태 (pdf_job.status) */
public enum PdfJobStatus {

    /** 대기 */
    QUEUED,
    /** 처리 중 */
    RUNNING,
    /** 성공 */
    SUCCEEDED,
    /** 실패 */
    FAILED
}
