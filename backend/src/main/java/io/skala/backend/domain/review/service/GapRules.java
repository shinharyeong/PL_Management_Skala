package io.skala.backend.domain.review.service;

import io.skala.backend.domain.financial.value.Scenario;
import io.skala.backend.domain.review.value.GapBasis;
import io.skala.backend.domain.review.value.GapComparisonType;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Gap 판정 규칙(FR-MONTH-005).
 * <ul>
 *   <li>차이율 = (현재 − 기준) / |기준| × 100. 기준이 0이면 산정 불가(null)</li>
 *   <li>차이율·최소 금액을 각각 켜고 끈다. 켠 조건을 모두 충족하면 Gap(둘 다 켜면 둘 다 충족)</li>
 *   <li>기준이 0이라 차이율을 낼 수 없으면, 금액이 달라졌을 때 차이율 조건은 충족한 것으로 본다</li>
 * </ul>
 */
public final class GapRules {

    private GapRules() {
    }

    public static BigDecimal rate(BigDecimal current, BigDecimal baseline) {
        if (current == null || baseline == null || baseline.signum() == 0) {
            return null;
        }
        return current.subtract(baseline).multiply(BigDecimal.valueOf(100))
                .divide(baseline.abs(), 8, RoundingMode.HALF_UP);
    }

    public static boolean exceeded(boolean percentEnabled, BigDecimal percent, boolean amountEnabled,
                                   BigDecimal amount, BigDecimal baseline, BigDecimal current) {
        if (baseline == null || current == null || (!percentEnabled && !amountEnabled)) {
            return false;
        }
        BigDecimal gap = current.subtract(baseline).abs();
        boolean percentOk = !percentEnabled || (baseline.signum() == 0 ? gap.signum() != 0
                : gap.multiply(BigDecimal.valueOf(100)).compareTo(baseline.abs().multiply(percent)) >= 0);
        boolean amountOk = !amountEnabled || gap.compareTo(amount) >= 0;
        return percentOk && amountOk;
    }

    /** 이 항목(구분)과 기준에 해당하는 비교 종류. 해당 없으면 null */
    public static GapComparisonType typeOf(Scenario itemScenario, GapBasis basis) {
        if (basis == GapBasis.PLAN) {
            return switch (itemScenario) {
                case ACTUAL -> GapComparisonType.ACTUAL_VS_PLAN;
                case ESTIMATE -> GapComparisonType.ESTIMATE_VS_PLAN;
                case PLAN -> null;
            };
        }
        return itemScenario == Scenario.ESTIMATE ? GapComparisonType.ESTIMATE_VS_PREVIOUS
                : itemScenario == Scenario.ACTUAL ? GapComparisonType.ACTUAL_VS_PREVIOUS : null;
    }

    /** 비교 종류의 기준(PLAN 또는 PREVIOUS)과 기준 쪽 구분 */
    public static GapBasis basisOf(GapComparisonType type) {
        return (type == GapComparisonType.ESTIMATE_VS_PREVIOUS || type == GapComparisonType.ACTUAL_VS_PREVIOUS) ? GapBasis.PREVIOUS : GapBasis.PLAN;
    }

    public static Scenario itemScenarioOf(GapComparisonType type) {
        return (type == GapComparisonType.ACTUAL_VS_PLAN || type == GapComparisonType.ACTUAL_VS_PREVIOUS) ? Scenario.ACTUAL : Scenario.ESTIMATE;
    }

    public static Scenario baselineScenarioOf(GapComparisonType type) {
        return (type == GapComparisonType.ESTIMATE_VS_PREVIOUS || type == GapComparisonType.ACTUAL_VS_PREVIOUS) ? Scenario.ESTIMATE : Scenario.PLAN;
    }
}
