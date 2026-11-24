package io.skala.backend.domain.file.value;

import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 저장하는 파일의 용도. 용도마다 저장 위치(prefix)·허용 확장자·최대 크기가 다르다.
 * drafts/는 제출 전 임시 파일(수명 주기 규칙으로 정리), uploads/는 영구 보존 파일이다.
 * results/는 시스템이 만든 결과 파일로, 키가 바뀌지 않게 처음부터 이 위치에 저장한다
 * (제출되지 않은 초안의 결과는 정리 작업이 stored_object 참조를 보고 지운다).
 */
@Getter
@RequiredArgsConstructor
public enum FilePurpose {

    /** 조직 담당자가 올린 제출 엑셀(제출 전) */
    SUBMISSION_EXCEL("drafts/excel/", Set.of("xlsx", "xlsb"), 20L * 1024 * 1024),
    /** 소명 첨부(제출 전) */
    EXPLANATION_ATTACHMENT("drafts/attachments/", Set.of(), 16L * 1024 * 1024),
    /** AI 보고서 틀 분석용 PDF */
    REPORT_PDF("uploads/pdf/", Set.of("pdf"), 30L * 1024 * 1024),
    /** report-ai가 보고한 PDF 분석 결과 JSON(틀 초안·숫자 가린 페이지 이미지, pdf_job.result_object_key) */
    REPORT_PDF_RESULT("results/pdf/", Set.of("json"), 100L * 1024 * 1024),
    /** 파서 출력 전체 JSON(parsing_job.result_object_key, submission_validation.parser_result_object_key) */
    PARSER_RESULT("results/parser/", Set.of("json"), 100L * 1024 * 1024),
    /** 검증 결과 JSON — UP-05 오류 목록(submission_validation.validation_result_object_key) */
    VALIDATION_RESULT("results/validation/", Set.of("json"), 20L * 1024 * 1024);

    private final String prefix;
    /** 비어 있으면 확장자 제한 없음 */
    private final Set<String> allowedExtensions;
    private final long maxBytes;

    public boolean isDraft() {
        return prefix.startsWith("drafts/");
    }
}
