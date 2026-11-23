package io.skala.backend.domain.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * UP-03 업로드 → 실제 파서 프로세스 실행 → UP-04 상태 조회 → UP-06 재시도 → 파일 다운로드.
 * 파서 입력으로 저장소의 PRD 엑셀(진짜 xlsx이지만 제출 양식은 아님)을 써서 '수정 필요' 경로를 확인한다.
 */
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@TestPropertySource(properties = "app.parser.sweeper.enabled=false")
class SubmissionUploadIntegrationTest extends PostgresIntegrationTest {

    private static final byte[] PRD_XLSX = read(Path.of("..", "docs", "경영팀을부탁해_PRD_ver1.xlsx"));

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;

    long orgId;
    long requestId;
    RequestPostProcessor manager;
    RequestPostProcessor outsider;

    @BeforeEach
    void seed() {
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String code = "U" + suffix.replaceAll("[^A-Z0-9]", "0");
        orgId = jdbc.queryForObject("insert into organization(code, name) values (?, ?) returning id", Long.class,
                code, "업로드사업부" + suffix);
        long otherOrg = jdbc.queryForObject("insert into organization(code, name) values (?, '다른') returning id",
                Long.class, "X" + suffix);
        long planner = jdbc.queryForObject("insert into app_user(email, name, role) values (?, '기획', 'PLANNER') returning id",
                Long.class, "p" + suffix + "@x.io");
        long managerId = jdbc.queryForObject("""
                insert into app_user(email, name, role, organization_id) values (?, '담당', 'ORG_MANAGER', ?) returning id
                """, Long.class, "o" + suffix + "@x.io", orgId);
        long plan = jdbc.queryForObject("insert into annual_plan(year, status) values (2026, 'CONFIRMED') "
                + "on conflict (year) do update set year = excluded.year returning id",
                Long.class);
        requestId = jdbc.queryForObject("""
                insert into submission_request(annual_plan_id, type, month, status, requested_by, requested_at, period_key)
                values (?, 'MONTHLY_ACTUAL', 11, 'OPEN', ?, now(), ?) returning id
                """, Long.class, plan, planner, "U-" + suffix);
        jdbc.update("insert into request_target(request_id, organization_id) values (?, ?)", requestId, orgId);
        manager = as(new AuthenticatedUser(managerId, UserRole.ORG_MANAGER, orgId));
        outsider = as(new AuthenticatedUser(managerId + 100000, UserRole.ORG_MANAGER, otherOrg));
        this.code = code;
    }

    String code;

    @Test
    void 파일_이름_조직_형식_규칙을_어기면_저장하지_않는다() throws Exception {
        upload("D100_2026-11.xls", PRD_XLSX).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_FILENAME"));
        upload(code + "_2026-10.xlsx", PRD_XLSX).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_FILENAME"));
        upload("ZZ999_2026-11.xlsx", PRD_XLSX).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_MISMATCH"));
        upload(code + "_2026-11.xlsx", "not a zip".getBytes()).andExpect(status().isUnsupportedMediaType());
        assertThat(jdbc.queryForObject("select count(*) from submission where request_id = ?", Integer.class, requestId))
                .isZero();
    }

    @Test
    void 업로드하면_파서가_돌고_수정_필요로_끝나며_같은_키_재전송은_첫_응답을_돌려준다() throws Exception {
        String key = "it-" + UUID.randomUUID();
        String body = mvc.perform(multipart("/api/v1/submission-requests/{id}/submissions", requestId)
                        .file(file(code + "_2026-11.xlsx", PRD_XLSX)).header("Idempotency-Key", key).with(manager))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.kind").value("MONTHLY_ACTUAL"))
                .andExpect(jsonPath("$.title").value("2026년 11월 월간 실적·추정 제출"))
                .andExpect(jsonPath("$.parsingJob.attemptNo").value(1))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();

        mvc.perform(multipart("/api/v1/submission-requests/{id}/submissions", requestId)
                        .file(file(code + "_2026-11.xlsx", PRD_XLSX)).header("Idempotency-Key", key).with(manager))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));

        JsonNode done = waitForParsing(id);
        assertThat(done.at("/parsingJob/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(done.at("/parsingJob/errorCode").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(done.at("/validation/canSubmit").asBoolean()).isFalse();

        // 실패한 작업만 재시도할 수 있다
        mvc.perform(post("/api/v1/submissions/{id}/parsing-retries", id).with(manager)
                        .header("Idempotency-Key", "it-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedStateToken\":\"" + done.get("stateToken").asText() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PARSING_RETRY_NOT_ALLOWED"));

        mvc.perform(get("/api/v1/submissions/{id}", id).with(outsider))
                .andExpect(status().isForbidden());
        byte[] downloaded = mvc.perform(get("/api/v1/submissions/{id}/file", id).with(manager))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(PRD_XLSX);
    }

    @Test
    void 파싱에_실패한_작업은_같은_파일로_다시_시도한다() throws Exception {
        String body = upload(code + "_2026-11.xlsx", PRD_XLSX).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();
        waitForParsing(id);
        jdbc.update("update parsing_job set status = 'FAILED', error_code = 'PARSER_TIMEOUT' where submission_id = ?",
                Long.parseLong(id));
        String token = objectMapper.readTree(mvc.perform(get("/api/v1/submissions/{id}", id).with(manager))
                .andReturn().getResponse().getContentAsString()).get("stateToken").asText();

        mvc.perform(post("/api/v1/submissions/{id}/parsing-retries", id).with(manager)
                        .header("Idempotency-Key", "it-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedStateToken\":\"" + token + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attemptNo").value(2));
        JsonNode again = waitForParsing(id);
        assertThat(again.at("/parsingJob/attemptNo").asInt()).isEqualTo(2);
        assertThat(again.at("/parsingJob/status").asText()).isEqualTo("SUCCEEDED");
    }

    private JsonNode waitForParsing(String id) throws Exception {
        for (int i = 0; i < 120; i++) {
            JsonNode node = objectMapper.readTree(mvc.perform(get("/api/v1/submissions/{id}", id).with(manager))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            String state = node.at("/parsingJob/status").asText();
            if (state.equals("SUCCEEDED") || state.equals("FAILED")) {
                return node;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("파싱이 60초 안에 끝나지 않았습니다");
    }

    private org.springframework.test.web.servlet.ResultActions upload(String name, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/v1/submission-requests/{id}/submissions", requestId)
                .file(file(name, content)).header("Idempotency-Key", "it-" + UUID.randomUUID()).with(manager));
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    private static RequestPostProcessor as(AuthenticatedUser user) {
        return authentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
