package io.skala.backend.domain.report.dto;

/** RPT-14 분류함 배치. categoryId가 null이면 분류에서 뺀다('기타'로 돌아감) */
public record ClassificationRequest(String categoryId) {
}
