package io.skala.backend.domain.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpServer;
import io.skala.backend.domain.decisionagent.dto.DecisionAssistantDtos.ComposeRequest;
import io.skala.backend.domain.decisionagent.dto.DecisionAssistantDtos.Context;
import io.skala.backend.global.exception.BusinessException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/** 내부 토큰 규칙: 운영(require-token=true)은 토큰이 없으면 내부 보고도 report-ai 호출도 거부한다 */
class ReportAiClientTest {

    /** 목적: 토큰 검사 전용 클라이언트를 만든다. Args: token 공유 토큰, require 필수 여부. Returns: 클라이언트. Raises: 없음. Side Effects: 필수 토큰 누락 경고 로그. */
    private static ReportAiClient client(String token, boolean require) {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        return new ReportAiClient("http://127.0.0.1:9", token, 1, require, mapper);
    }

    /**
     * 목적: 실제 HTTP 요청의 기준 날짜가 Boot JSON 설정을 따라 ISO 문자열로 전달되는지 검증한다.
     * Args: 없음. Returns: 없음. Raises: 로컬 HTTP 서버 실패의 IOException, 검증 실패의 AssertionError.
     * Side Effects: 루프백 임시 포트와 Jackson 전용 Spring context를 열고 종료한다. DB·외부 모델·파일 접근 없음.
     */
    @Test
    void sendsIsoDatesWithBootMapperThroughActualHttp() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        server.createContext("/v1/decision-agent/", exchange -> {
            try {
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                token.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
                byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                    .run(context -> {
                        ObjectMapper mapper = context.getBean(ObjectMapper.class);
                        ReportAiClient client = new ReportAiClient(
                                "http://127.0.0.1:" + server.getAddress().getPort(), "internal-test", 5, true, mapper);
                        OffsetDateTime asOf = OffsetDateTime.parse("2026-11-26T09:30:00+09:00");
                        Context basis = new Context("101", "확정 기준", 2026, 11, null, null, asOf);
                        var response = client.post("/v1/decision-agent/assistant",
                                new ComposeRequest("차이를 알려줘", List.of(), basis, "ephemeral-test"), 1L);
                        var sent = mapper.readTree(body.get());
                        assertThat(sent.at("/context/asOf").isTextual()).isTrue();
                        assertThat(OffsetDateTime.parse(sent.at("/context/asOf").asText())).isEqualTo(asOf);
                        assertThat(response.path("status").asText()).isEqualTo("ok");
                        assertThat(token.get()).isEqualTo("internal-test");
                        for (String path : List.of("compose", "discover")) {
                            client.post("/v1/decision-agent/" + path, Map.of("snapshot", Map.of("asOf", asOf)), 1L);
                            assertThat(mapper.readTree(body.get()).at("/snapshot/asOf").isTextual()).isTrue();
                        }
                        assertThat(mapper.isEnabled(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)).isFalse();
                    });
        } finally {
            server.stop(0);
        }
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
