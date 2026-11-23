package io.skala.backend.domain.financial.value;

/** 손익 항목 성격 (report_item.kind) */
public enum ReportItemKind {

    /** 매출 */
    REVENUE,
    /** 비용 */
    COST,
    /** 계산 항목(영업이익 등) */
    DERIVED
}
