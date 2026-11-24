package io.skala.backend.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.report.exception.ReportErrorCode;
import io.skala.backend.domain.report.service.ReportAiClient;
import io.skala.backend.global.security.SessionTokenStore;
import io.skala.backend.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** RPT-01·02·10·11·15와 report-ai 내부 보고(명세 12절). report-ai 호출은 가짜(실제 HTTP 없음) */
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class ReportAiRelayIntegrationTest extends PostgresIntegrationTest {

    private static final byte[] PDF = "%PDF-1.4\n% test\n".getBytes(StandardCharsets.US_ASCII);
    private static final String PNG = Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 'P', 'N', 'G'});

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SessionTokenStore sessions;
    @Autowired
    ObjectMapper objectMapper;
    @MockitoBean
    ReportAiClient reportAi;

    String planner;
    String manager;
    long plannerId;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        long org = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, 'AI조직') RETURNING id", Long.class, "AI" + s);
        plannerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role) VALUES (?, '기획', 'PLANNER') RETURNING id",
                Long.class, "ap" + s + "@sk.com");
        long managerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role, organization_id) VALUES (?, '담당', 'ORG_MANAGER', ?) RETURNING id",
                Long.class, "am" + s + "@sk.com", org);
        planner = sessions.issue(plannerId).token();
        manager = sessions.issue(managerId).token();
        when(reportAi.acceptsToken("t0k")).thenReturn(true);
    }

    @Test
    void pdfJobIsSentToAiThenCompletedByInternalReport() throws Exception {
        // RPT-01: 저장 → RUNNING → report-ai에 전달
        String id = objectMapper.readTree(mvc.perform(auth(multipart("/api/v1/report/pdf-jobs")
                        .file(new MockMultipartFile("file", "월보고.pdf", "application/pdf", PDF)).param("maskNumbers", "true"), planner))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.maskNumbers").value(true))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        verify(reportAi).submitPdf(eq(Long.parseLong(id)), eq(PDF), eq("월보고.pdf"), eq(true), eq(plannerId));

        // 내부 보고: 토큰이 틀리면 401
        String result = """
                {"status": "SUCCEEDED", "error": null,
                 "definition": {"schema_version": 1, "name": "월보고", "source": "pdf",
                                "blocks": [{"id": "b0", "kind": "title", "text": "월 실적", "needs_review": []},
                                           {"id": "b1", "kind": "table", "needs_review": ["header_ambiguous"]}]},
                 "pages": [{"no": 1, "width": 595, "height": 842, "imagePng": "%s"}]}""".formatted(PNG);
        report(id, "wrong", result).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_INTERNAL_TOKEN"));
        report(id, "t0k", result).andExpect(status().isNoContent());

        // RPT-02: 틀 초안과 숫자 가린 페이지 이미지
        mvc.perform(auth(get("/api/v1/report/pdf-jobs/{id}", id), planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.definition.blocks[1].needs_review[0]").value("header_ambiguous"))
                .andExpect(jsonPath("$.pages[0].maskedImageUrl").value("/api/v1/report/pdf-jobs/" + id + "/pages/1"))
                .andExpect(jsonPath("$.finishedAt").isNotEmpty());
        mvc.perform(auth(get("/api/v1/report/pdf-jobs/{id}/pages/1", id), planner))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[] {(byte) 0x89, 'P', 'N', 'G'}));

        // 끝난 작업에 다시 보고해도 바뀌지 않는다
        report(id, "t0k", "{\"status\": \"FAILED\", \"error\": {\"code\": \"X\", \"message\": \"x\"}}").andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM pdf_job WHERE id = ?", String.class, Long.parseLong(id))).isEqualTo("SUCCEEDED");

        mvc.perform(auth(get("/api/v1/report/pdf-jobs/{id}", id), manager)).andExpect(status().isForbidden());
    }

    @Test
    void pdfJobFailuresAreRecorded() throws Exception {
        // 실패 보고 → FAILED와 오류 코드
        String failed = createJob();
        report(failed, "t0k", "{\"status\": \"FAILED\", \"definition\": null, \"pages\": [], \"error\": {\"code\": \"PDF_UNREADABLE\", \"message\": \"깨진 PDF\"}}")
                .andExpect(status().isNoContent());
        mvc.perform(auth(get("/api/v1/report/pdf-jobs/{id}", failed), planner))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorCode").value("PDF_UNREADABLE"))
                .andExpect(jsonPath("$.definition").isEmpty());

        // 저장 봉투에 맞지 않는 초안은 받지 않고 FAILED(INVALID_TEMPLATE)
        String invalid = createJob();
        report(invalid, "t0k", "{\"status\": \"SUCCEEDED\", \"definition\": {\"schema_version\": 1, \"name\": \"x\", \"blocks\": [{\"kind\": \"title\"}]}, \"pages\": []}")
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT error_code FROM pdf_job WHERE id = ?", String.class, Long.parseLong(invalid)))
                .isEqualTo("INVALID_TEMPLATE");

        // report-ai에 전달하지 못하면 503, 작업은 FAILED
        doThrow(ReportErrorCode.REPORT_AI_UNAVAILABLE.exception()).when(reportAi)
                .submitPdf(anyLong(), any(), anyString(), anyBoolean(), anyLong());
        mvc.perform(auth(multipart("/api/v1/report/pdf-jobs").file(new MockMultipartFile("file", "b.pdf", "application/pdf", PDF)), planner))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REPORT_AI_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pdf_job WHERE requested_by = ? AND error_code = 'REPORT_AI_UNAVAILABLE'",
                Integer.class, plannerId)).isEqualTo(1);

        // PDF가 아니면 415
        mvc.perform(auth(multipart("/api/v1/report/pdf-jobs").file(new MockMultipartFile("file", "c.pdf", "application/pdf",
                        "hello".getBytes(StandardCharsets.UTF_8))), planner))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void dataNlQueryAndEditAreRelayedToReportAi() throws Exception {
        when(reportAi.post(eq("/v1/nl-query"), any(), eq(plannerId))).thenReturn(objectMapper.readTree(
                "{\"status\": \"OK\", \"sql\": \"SELECT 1\", \"explanation\": \"설명\", \"preview\": {\"dataStatus\": \"CONFIRMED\", \"columns\": [], \"rows\": []}}"));
        mvc.perform(auth(post("/api/v1/report/nl-query"), planner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"11월 조직별 영업이익\", \"baseYear\": 2026, \"baseMonth\": 11}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.preview.dataStatus").value("CONFIRMED"));
        verify(reportAi).post(eq("/v1/nl-query"), eq(Map.of("question", "11월 조직별 영업이익", "baseYear", 2026, "baseMonth", 11,
                "conversationId", "u" + plannerId)), eq(plannerId));

        when(reportAi.post(eq("/v1/data"), any(), eq(plannerId))).thenReturn(objectMapper.readTree(
                "{\"dataStatus\": \"PROVISIONAL\", \"blocks\": [{\"id\": \"b1\", \"missing\": false}]}"));
        mvc.perform(auth(post("/api/v1/report/data"), planner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"base\": {\"year\": 2026, \"month\": 11}, \"blocks\": [{\"id\": \"b1\", \"kind\": \"table\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dataStatus").value("PROVISIONAL"))
                .andExpect(jsonPath("$.blocks[0].id").value("b1"));

        when(reportAi.post(eq("/v1/edit"), any(), eq(plannerId))).thenReturn(objectMapper.readTree(
                "{\"status\": \"EDIT\", \"message\": \"편집안\", \"proposals\": [{\"op\": \"set_style\", \"target\": \"b1\"}]}"));
        String definition = "{\"schema_version\": 1, \"name\": \"틀\", \"blocks\": [{\"id\": \"b1\", \"kind\": \"title\"}]}";
        mvc.perform(auth(post("/api/v1/report/ai/edit"), planner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\": " + definition + ", \"instruction\": \"제목 24pt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposals[0].op").value("set_style"));

        // 검증: 기준 월·질문·틀 봉투. report-ai 장애는 503
        mvc.perform(auth(post("/api/v1/report/nl-query"), planner).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\": \"\", \"baseYear\": 2026, \"baseMonth\": 13}")).andExpect(status().isBadRequest());
        mvc.perform(auth(post("/api/v1/report/ai/edit"), planner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"definition\": {\"blocks\": []}, \"instruction\": \"x\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("INVALID_TEMPLATE"));
        when(reportAi.post(eq("/v1/data"), any(), anyLong())).thenThrow(ReportErrorCode.REPORT_AI_UNAVAILABLE.exception());
        mvc.perform(auth(post("/api/v1/report/data"), planner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"base\": {\"year\": 2026, \"month\": 11}, \"blocks\": []}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(auth(post("/api/v1/report/nl-query"), manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"x\", \"baseYear\": 2026, \"baseMonth\": 11}"))
                .andExpect(status().isForbidden());
    }

    private String createJob() throws Exception {
        return objectMapper.readTree(mvc.perform(auth(multipart("/api/v1/report/pdf-jobs")
                        .file(new MockMultipartFile("file", "a.pdf", "application/pdf", PDF)), planner))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions report(String id, String token, String body) throws Exception {
        return mvc.perform(post("/internal/pdf-jobs/{id}/result", id).header("X-Internal-Token", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }
}
