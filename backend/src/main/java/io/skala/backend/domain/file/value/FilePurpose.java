package io.skala.backend.domain.file.value;

import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 저장하는 파일의 용도. 용도마다 저장 위치(prefix)·허용 확장자·최대 크기가 다르다.
 * drafts/는 제출 전 임시 파일(수명 주기 규칙으로 정리), uploads/는 영구 보존 파일이다.
 */
@Getter
@RequiredArgsConstructor
public enum FilePurpose {

    /** 조직 담당자가 올린 제출 엑셀(제출 전) */
    SUBMISSION_EXCEL("drafts/excel/", Set.of("xlsx"), 20L * 1024 * 1024),
    /** 소명 첨부(제출 전) */
    EXPLANATION_ATTACHMENT("drafts/attachments/", Set.of(), 16L * 1024 * 1024),
    /** AI 보고서 틀 분석용 PDF */
    REPORT_PDF("uploads/pdf/", Set.of("pdf"), 30L * 1024 * 1024);

    private final String prefix;
    /** 비어 있으면 확장자 제한 없음 */
    private final Set<String> allowedExtensions;
    private final long maxBytes;

    public boolean isDraft() {
        return prefix.startsWith("drafts/");
    }
}
