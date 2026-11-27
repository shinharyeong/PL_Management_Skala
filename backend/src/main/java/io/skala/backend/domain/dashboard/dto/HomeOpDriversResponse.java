package io.skala.backend.domain.dashboard.dto;

import java.util.List;

/** HOME-04 항목·조직별 영업이익 계획 대비 기여 차이 Top N. total은 전사 영업이익 계획 대비 차이 */
public record HomeOpDriversResponse(String dataStatus, String total, List<Item> items, List<Item> decreases, List<Item> increases) {

    public record Item(OrganizationRef organization, String reportItemId, String reportItemName, String opDiff, String opDiffRate) {
    }
}
