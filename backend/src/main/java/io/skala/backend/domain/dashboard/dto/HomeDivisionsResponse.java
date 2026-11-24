package io.skala.backend.domain.dashboard.dto;

import java.util.List;

/** HOME-05 사업부(제출 조직)별 손익. vsPlanAmount는 영업이익 계획 대비 차이 */
public record HomeDivisionsResponse(String dataStatus, List<Column> columns, Total total) {

    public record Column(OrganizationRef organization, String revenue, String op, String opContributionRate,
                         String opMarginRate, String vsPlanAmount) {
    }

    public record Total(String revenue, String op, String opMarginRate, String vsPlanAmount) {
    }
}
