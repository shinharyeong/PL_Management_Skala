package io.skala.backend.domain.financial.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReportItemCodesTest {

    @Test
    void 파서_키와_같은_압축_JSON을_만든다() {
        // 파서: json.dumps([domain, header_path], ensure_ascii=False, separators=(",", ":"))
        assertThat(ReportItemCodes.of("REVENUE", List.of("매출", "제품매출")))
                .isEqualTo("[\"REVENUE\",[\"매출\",\"제품매출\"]]");
    }

    @Test
    void 길면_해시로_줄인다() {
        String code = ReportItemCodes.of("COST", List.of("가".repeat(200)));
        assertThat(code).startsWith("h:").hasSize(66);
        assertThat(ReportItemCodes.shorten("x".repeat(160))).hasSize(160);
    }

    @Test
    void 금액_문자열은_정밀_십진수로_읽고_범위를_넘으면_거절() {
        assertThat(FinancialIngestionService.parseAmount("1240000000.123456")).isEqualByComparingTo("1240000000.123456");
        assertThat(FinancialIngestionService.parseAmount("-5")).isEqualByComparingTo("-5");
        assertThat(FinancialIngestionService.parseAmount("1.1234567")).isNull();
        assertThat(FinancialIngestionService.parseAmount("1" + "0".repeat(24))).isNull();
        assertThat(FinancialIngestionService.parseAmount("")).isNull();
        assertThat(FinancialIngestionService.parseAmount("abc")).isNull();
    }
}
