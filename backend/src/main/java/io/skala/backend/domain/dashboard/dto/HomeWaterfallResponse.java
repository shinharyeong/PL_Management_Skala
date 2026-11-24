package io.skala.backend.domain.dashboard.dto;

import java.util.List;

/**
 * HOME-06 손익 흐름. 매출(TOTAL) → 영업비용 중계정마다(DEDUCTION) → 영업이익(TOTAL).
 * breakdown은 그 단계의 하위 항목.
 */
public record HomeWaterfallResponse(String dataStatus, List<Step> steps) {

    /** key = REVENUE·COST·OP */
    public record Step(String key, String reportItemId, String name, String amount, String kind, List<Part> breakdown) {
    }

    public record Part(String reportItemId, String name, String amount) {
    }
}
