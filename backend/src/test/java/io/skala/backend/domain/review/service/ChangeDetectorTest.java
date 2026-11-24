package io.skala.backend.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.skala.backend.domain.financial.entity.FinancialLine;
import io.skala.backend.domain.financial.entity.FinancialValue;
import io.skala.backend.domain.financial.entity.ReportItem;
import io.skala.backend.domain.financial.value.ReportItemKind;
import io.skala.backend.domain.financial.value.Scenario;
import io.skala.backend.domain.review.service.ChangeDetector.Detection;
import io.skala.backend.domain.review.service.ChangeDetector.Key;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChangeDetectorTest {

    private final ReportItem sales = item(1L, "매출");
    private final ReportItem rent = item(2L, "임차료");

    @Test
    void 같은_키의_셀_금액을_합친다() {
        var agg = ChangeDetector.aggregate(List.of(
                value(sales, "매출", 10, 11, Scenario.ACTUAL, "100", "N10"),
                value(sales, "매출", 11, 11, Scenario.ACTUAL, "250.5", "N11")));

        assertThat(agg).hasSize(1);
        assertThat(agg.get(new Key(1L, 2026, 11, Scenario.ACTUAL)).total()).isEqualByComparingTo("350.5");
    }

    @Test
    void 기준이_없으면_변경으로_보지_않는다() {
        var current = ChangeDetector.aggregate(List.of(value(sales, "매출", 10, 11, Scenario.PLAN, "100", "N10")));

        List<Detection> result = ChangeDetector.detect(current, null);

        assertThat(result).singleElement().satisfies(d -> {
            assertThat(d.changed()).isFalse();
            assertThat(d.baseline()).isNull();
        });
    }

    @Test
    void 금액이_같으면_소수_자릿수가_달라도_변경_아님() {
        var current = ChangeDetector.aggregate(List.of(value(sales, "매출", 10, 11, Scenario.ACTUAL, "100.000000", "N10")));
        var baseline = ChangeDetector.aggregate(List.of(value(sales, "매출", 10, 11, Scenario.ACTUAL, "100", "N10")));

        assertThat(ChangeDetector.detect(current, baseline)).singleElement()
                .extracting(Detection::changed).isEqualTo(false);
        assertThat(current.values().iterator().next().fingerprint())
                .isEqualTo(baseline.values().iterator().next().fingerprint());
    }

    @Test
    void 금액이_바뀌거나_새로_생기거나_사라지면_변경() {
        var current = ChangeDetector.aggregate(List.of(
                value(sales, "매출", 10, 11, Scenario.ACTUAL, "120", "N10"),
                value(rent, "비용", 5, 11, Scenario.ACTUAL, "0", "N5")));
        var baseline = ChangeDetector.aggregate(List.of(
                value(sales, "매출", 10, 11, Scenario.ACTUAL, "100", "N10"),
                value(sales, "매출", 10, 12, Scenario.ESTIMATE, "90", "O10")));

        Map<Key, Detection> byKey = ChangeDetector.detect(current, baseline).stream()
                .collect(java.util.stream.Collectors.toMap(Detection::key, d -> d));

        assertThat(byKey.get(new Key(1L, 2026, 11, Scenario.ACTUAL)).changed()).isTrue();
        // 0은 '값 없음'과 다르다: 기준에 없던 0 입력도 변경
        Detection newZero = byKey.get(new Key(2L, 2026, 11, Scenario.ACTUAL));
        assertThat(newZero.changed()).isTrue();
        assertThat(newZero.current()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(newZero.baseline()).isNull();
        Detection removed = byKey.get(new Key(1L, 2026, 12, Scenario.ESTIMATE));
        assertThat(removed.changed()).isTrue();
        assertThat(removed.current()).isNull();
        assertThat(removed.reportItem()).isSameAs(sales);
    }

    @Test
    void 기준에_없는_구분은_변경으로_보지_않는다() {
        // 연간 계획(계획 구분만)과 1월 시트(계획·실적·추정) 비교: 같은 구분끼리만 본다.
        var current = ChangeDetector.aggregate(List.of(
                value(sales, "매출", 10, 1, Scenario.PLAN, "100", "C10"),
                value(sales, "매출", 10, 1, Scenario.ACTUAL, "120", "D10"),
                value(sales, "매출", 10, 2, Scenario.ESTIMATE, "130", "E10")));
        var plan = ChangeDetector.aggregate(List.of(
                value(sales, "매출", 10, 1, Scenario.PLAN, "90", "C10"),
                value(sales, "매출", 10, 2, Scenario.PLAN, "95", "D10")));

        Map<Key, Detection> byKey = ChangeDetector.detect(current, plan).stream()
                .collect(java.util.stream.Collectors.toMap(Detection::key, d -> d));

        assertThat(byKey.get(new Key(1L, 2026, 1, Scenario.PLAN)).changed()).isTrue();
        assertThat(byKey.get(new Key(1L, 2026, 1, Scenario.ACTUAL)).changed()).isFalse();
        assertThat(byKey.get(new Key(1L, 2026, 2, Scenario.ESTIMATE)).changed()).isFalse();
        // 2월 계획이 1월 시트에서 빠졌으면(값 없음) 계획 구분 안의 변경
        assertThat(byKey.get(new Key(1L, 2026, 2, Scenario.PLAN)).changed()).isTrue();
    }

    @Test
    void 기준금액이_다르면_맥락_지문도_다르다() {
        Key key = new Key(1L, 2026, 11, Scenario.ACTUAL);
        assertThat(ChangeDetector.contextFingerprint("c", key, 7L, new BigDecimal("1.0")))
                .isEqualTo(ChangeDetector.contextFingerprint("c", key, 7L, new BigDecimal("1")))
                .isNotEqualTo(ChangeDetector.contextFingerprint("c", key, 7L, new BigDecimal("2")))
                .hasSize(64);
    }

    private static ReportItem item(Long id, String name) {
        ReportItem item = ReportItem.create("code-" + id, name, null, ReportItemKind.REVENUE, true, 1);
        ReflectionTestUtils.setField(item, "id", id);
        return item;
    }

    private static FinancialValue value(ReportItem item, String sheet, int row, int month, Scenario scenario,
                                        String amount, String cell) {
        FinancialLine line = FinancialLine.builder().reportItem(item).sourceSheet(sheet).sourceRow(row).build();
        return FinancialValue.create(line, 2026, month, scenario, new BigDecimal(amount), "rec-" + cell, cell);
    }
}
