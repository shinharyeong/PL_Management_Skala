package io.skala.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.skala.backend.global.exception.BusinessException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 내부 토큰 규칙: 운영(require-token=true)은 토큰이 없으면 내부 보고도 report-ai 호출도 거부한다 */
class ReportAiClientTest {

    private static ReportAiClient client(String token, boolean require) {
        return new ReportAiClient("http://127.0.0.1:9", token, 1, require);
    }

    @Test
    void tokenIsCheckedWhenConfigured() {
        ReportAiClient c = client("t0k", true);
        assertThat(c.acceptsToken("t0k")).isTrue();
        assertThat(c.acceptsToken("wrong")).isFalse();
        assertThat(c.acceptsToken(null)).isFalse();
    }

    @Test
    void missingTokenIsRejectedOnlyWhenRequired() {
        assertThat(client("", false).acceptsToken(null)).isTrue();          // 로컬 개발
        assertThat(client("", true).acceptsToken(null)).isFalse();          // 운영: 토큰 없으면 거부
        assertThat(client(null, true).acceptsToken("anything")).isFalse();
        assertThatThrownBy(() -> client("", true).post("/v1/data", Map.of(), 1L))   // 토큰 없이 report-ai를 부르지 않는다
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("REPORT_AI_UNAVAILABLE");
    }
}
