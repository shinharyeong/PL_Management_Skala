package io.skala.backend.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.skala.backend.domain.financial.value.Scenario;
import io.skala.backend.domain.review.value.GapBasis;
import io.skala.backend.domain.review.value.GapComparisonType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class GapRulesTest {

    private static final BigDecimal FIVE = new BigDecimal("5");
    private static final BigDecimal MIN = new BigDecimal("100");

    @Test
    void 둘_다_켜면_차이율과_금액을_모두_충족해야_Gap() {
        // 1000 → 1060: 6%, 60원 → 금액 미달
        assertThat(GapRules.exceeded(true, FIVE, true, MIN, d("1000"), d("1060"))).isFalse();
        // 1000 → 1200: 20%, 200원
        assertThat(GapRules.exceeded(true, FIVE, true, MIN, d("1000"), d("1200"))).isTrue();
        // 10000 → 10200: 2%, 200원 → 차이율 미달
        assertThat(GapRules.exceeded(true, FIVE, true, MIN, d("10000"), d("10200"))).isFalse();
    }

    @Test
    void 하나만_켜면_그_조건만_본다_감소도_절댓값으로() {
        assertThat(GapRules.exceeded(true, FIVE, false, MIN, d("1000"), d("940"))).isTrue();
        assertThat(GapRules.exceeded(false, FIVE, true, MIN, d("10000"), d("10200"))).isTrue();
        assertThat(GapRules.exceeded(false, FIVE, false, MIN, d("1"), d("999"))).isFalse();
    }

    @Test
    void 기준이_0이면_차이율은_산정_불가이고_금액이_달라졌으면_차이율_조건은_충족() {
        assertThat(GapRules.rate(d("10"), BigDecimal.ZERO)).isNull();
        assertThat(GapRules.exceeded(true, FIVE, false, MIN, BigDecimal.ZERO, d("10"))).isTrue();
        assertThat(GapRules.exceeded(true, FIVE, false, MIN, BigDecimal.ZERO, BigDecimal.ZERO)).isFalse();
        assertThat(GapRules.exceeded(true, FIVE, false, MIN, null, d("10"))).isFalse();
    }

    @Test
    void 비교_종류와_구분_대응() {
        assertThat(GapRules.typeOf(Scenario.ACTUAL, GapBasis.PLAN)).isEqualTo(GapComparisonType.ACTUAL_VS_PLAN);
        assertThat(GapRules.typeOf(Scenario.ESTIMATE, GapBasis.PLAN)).isEqualTo(GapComparisonType.ESTIMATE_VS_PLAN);
        assertThat(GapRules.typeOf(Scenario.ESTIMATE, GapBasis.PREVIOUS)).isEqualTo(GapComparisonType.ESTIMATE_VS_PREVIOUS);
        assertThat(GapRules.typeOf(Scenario.ACTUAL, GapBasis.PREVIOUS)).isEqualTo(GapComparisonType.ACTUAL_VS_PREVIOUS);
        assertThat(GapRules.typeOf(Scenario.PLAN, GapBasis.PLAN)).isNull();
        assertThat(GapRules.baselineScenarioOf(GapComparisonType.ESTIMATE_VS_PREVIOUS)).isEqualTo(Scenario.ESTIMATE);
        assertThat(GapRules.baselineScenarioOf(GapComparisonType.ACTUAL_VS_PLAN)).isEqualTo(Scenario.PLAN);
    }

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }
}
