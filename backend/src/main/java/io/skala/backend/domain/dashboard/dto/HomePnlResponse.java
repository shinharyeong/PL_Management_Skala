package io.skala.backend.domain.dashboard.dto;

import java.util.List;

/**
 * HOME-03 핵심 KPI와 전사 손익 요약표. 금액은 원 단위 십진 문자열, 비율은 %.
 *
 * @param opMargin 영업이익률(답안지의 마지막 행). 금액이 아니라 비율이라 rows와 따로 둔다.
 */
public record HomePnlResponse(String dataStatus, List<Kpi> kpis, List<Row> rows, OpMargin opMargin) {

    /** key = REVENUE·COST·OP */
    public record Kpi(String key, String actual, String vsPlanRate, String vsPlanAmount) {
    }

    /**
     * kind = ITEM(보고 항목) · OP(영업이익, 매출 − 영업비용). OP 행은 reportItemId가 없다.
     * gapExceeded·explanation은 말단 항목에만 있다.
     */
    public record Row(String reportItemId, String kind, String name, int level, String actual, String vsPlanRate,
                      String vsPlanAmount, String vsPrevEstimateRate, String vsPrevEstimateAmount, boolean gapExceeded,
                      String explanation) {
    }

    /** 영업이익률(%)과 계획·전월 추정 대비 차이(%p) */
    public record OpMargin(String actualRate, String planRate, String vsPlanPoint, String prevEstimateRate,
                           String vsPrevEstimatePoint) {
    }
}
