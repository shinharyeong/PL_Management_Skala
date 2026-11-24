package io.skala.backend.domain.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * RPT-01·02 PDF 분석 작업. SUCCEEDED면 definition(AI가 추출한 틀 초안, 확인 필요 블록은 needs_review)과
 * 숫자를 가린 페이지 이미지 주소를 준다. FAILED면 errorCode·errorMessage(화면은 직접 만들기 안내).
 */
public record PdfJobResponse(String id, String status, boolean maskNumbers, JsonNode definition, List<Page> pages,
                             String errorCode, String errorMessage, OffsetDateTime createdAt, OffsetDateTime finishedAt) {

    public record Page(int no, String maskedImageUrl) {
    }
}
