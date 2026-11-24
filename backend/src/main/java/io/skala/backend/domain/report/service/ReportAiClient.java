package io.skala.backend.domain.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import io.skala.backend.domain.report.exception.ReportErrorCode;
import java.time.Duration;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * backend → report-ai 내부 호출(명세 12절). 프론트는 report-ai를 직접 부르지 않는다.
 * 헤더: X-Internal-Token(REPORT_AI_TOKEN), X-User-Id(감사·호출 한도), X-Request-Id.
 * 연결 실패·시간 초과·5xx는 REPORT_AI_UNAVAILABLE(503), 4xx는 REPORT_AI_REJECTED(400).
 */
@Slf4j
@Component
public class ReportAiClient {

    private final RestClient client;
    private final String token;
    private final boolean requireToken;

    public ReportAiClient(@Value("${app.report-ai.base-url}") String baseUrl,
                          @Value("${app.report-ai.token:}") String token,
                          @Value("${app.report-ai.timeout-seconds:90}") int timeoutSeconds,
                          @Value("${app.report-ai.require-token:false}") boolean requireToken) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.token = token;
        this.requireToken = requireToken;
        if (requireToken && !hasToken()) {
            log.warn("REPORT_AI_TOKEN이 없어 보고서 AI 호출과 내부 보고를 모두 거부합니다(app.report-ai.require-token=true).");
        }
    }

    private boolean hasToken() {
        return token != null && !token.isBlank();
    }

    /** JSON 요청 → JSON 응답(/v1/data, /v1/nl-query, /v1/edit) */
    public JsonNode post(String path, Object body, long userId) {
        return call(() -> client.post().uri(path).headers(h -> headers(h, userId))
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class), path);
    }

    /** PDF 분석 요청(1차 전송: multipart). report-ai는 202를 먼저 주고 끝나면 /internal/pdf-jobs/{id}/result로 보고한다 */
    public void submitPdf(long jobId, byte[] pdf, String filename, boolean maskNumbers, long userId) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("jobId", String.valueOf(jobId));
        form.add("maskNumbers", String.valueOf(maskNumbers));
        form.add("file", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return filename == null || filename.isBlank() ? "report.pdf" : filename;
            }
        });
        call(() -> client.post().uri("/v1/pdf-jobs").headers(h -> headers(h, userId))
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().toBodilessEntity(), "/v1/pdf-jobs");
    }

    private void headers(org.springframework.http.HttpHeaders h, long userId) {
        if (token != null && !token.isBlank()) {
            h.set("X-Internal-Token", token);
        }
        h.set("X-User-Id", String.valueOf(userId));
        h.set("X-Request-Id", UUID.randomUUID().toString());
    }

    private <T> T call(java.util.function.Supplier<T> request, String path) {
        if (requireToken && !hasToken()) {                 // 토큰 없이 내부 호출을 보내지 않는다(운영)
            throw ReportErrorCode.REPORT_AI_UNAVAILABLE.exception();
        }
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            log.warn("report-ai {} 응답 {}", path, e.getStatusCode().value());
            throw (e.getStatusCode().is4xxClientError() ? ReportErrorCode.REPORT_AI_REJECTED : ReportErrorCode.REPORT_AI_UNAVAILABLE)
                    .exception();
        } catch (ResourceAccessException e) {
            log.warn("report-ai {} 연결 실패: {}", path, e.getClass().getSimpleName());
            throw ReportErrorCode.REPORT_AI_UNAVAILABLE.exception();
        }
    }

    /** 내부 보고 토큰 확인. 설정이 비어 있으면 require-token=false(로컬 개발)일 때만 통과, 운영은 거부 */
    public boolean acceptsToken(String header) {
        if (!hasToken()) {
            return !requireToken;
        }
        return java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                header == null ? new byte[0] : header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
