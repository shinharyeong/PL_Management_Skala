package io.skala.backend.domain.report.dto;

import java.util.Map;

/** RPT-14 결과. 합계는 그 소명이 속한 Gap 설명 블록(연·월·비교 종류) 기준 */
public record ClassificationResponse(String explanationId, String categoryId, Map<String, String> categoryTotals,
                                     String classifiedTotal, String gapTotal, boolean matchesGapTotal) {
}
