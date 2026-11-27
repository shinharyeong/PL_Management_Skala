package io.skala.backend.domain.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.global.security.SessionTokenStore;
import io.skala.backend.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class SubmissionRequestIntegrationTest extends PostgresIntegrationTest {

    static final Path TEMPLATE = Path.of(System.getProperty("java.io.tmpdir"), "skala-test-template-" + UUID.randomUUID() + ".xlsx");

    @DynamicPropertySource
    static void template(DynamicPropertyRegistry registry) {
        registry.add("app.submission-template.location", () -> "file:" + TEMPLATE);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    SessionTokenStore sessions;

    long orgA;
    long orgB;
    long plannerId;
    String planner;
    String managerA;
    String managerB;
    int year;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        orgA = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '테스트A') RETURNING id", Long.class, "RA" + s);
        orgB = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '테스트B') RETURNING id", Long.class, "RB" + s);
        plannerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role) VALUES (?, '기획', 'PLANNER') RETURNING id", Long.class, "p" + s + "@sk.com");
        long a = jdbc.queryForObject("INSERT INTO app_user(email, name, role, organization_id) VALUES (?, '담당A', 'ORG_MANAGER', ?) RETURNING id", Long.class, "a" + s + "@sk.com", orgA);
        long b = jdbc.queryForObject("INSERT INTO app_user(email, name, role, organization_id) VALUES (?, '담당B', 'ORG_MANAGER', ?) RETURNING id", Long.class, "b" + s + "@sk.com", orgB);
        planner = sessions.issue(plannerId).token();
        managerA = sessions.issue(a).token();
        managerB = sessions.issue(b).token();
        do {
            year = ThreadLocalRandom.current().nextInt(2100, 9999);
        } while (jdbc.queryForObject("SELECT count(*) FROM annual_plan WHERE year = ?", Integer.class, year) > 0);
    }

    @Test
    void plannerSendsAnnualRequestAndSeesProgress() throws Exception {
        String id = createRequest("ANNUAL_PLAN", null, OffsetDateTime.now(ZoneOffset.UTC).plusDays(3), List.of(orgA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(year + "년 연간 계획"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.submittedCount").value(0))
                .andReturn().getResponse().getHeader("Location").replaceAll(".*/", "");

        createRequest("ANNUAL_PLAN", null, OffsetDateTime.now(ZoneOffset.UTC).plusDays(3), List.of(orgA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ALREADY_EXISTS"));

        JsonNode detail = read(mvc.perform(auth(get("/api/v1/submission-requests/" + id), planner)).andExpect(status().isOk()));
        JsonNode targetA = target(detail, orgA);
        JsonNode targetB = target(detail, orgB);
        assertThat(targetA.get("selected").asBoolean()).isTrue();
        assertThat(targetA.get("displayStatus").asText()).isEqualTo("WAITING");
        assertThat(targetB.get("selected").asBoolean()).isFalse();

        long submissionId = insertSubmission(Long.parseLong(id), orgA, "PENDING", 1);
        detail = read(mvc.perform(auth(get("/api/v1/submission-requests/" + id), planner)).andExpect(status().isOk()));
        assertThat(target(detail, orgA).get("displayStatus").asText()).isEqualTo("SUBMITTED");
        assertThat(target(detail, orgA).at("/latestSubmission/label").asText()).isEqualTo(year + "-PLAN-01");
        assertThat(target(detail, orgA).at("/latestSubmission/id").asText()).isEqualTo(String.valueOf(submissionId));
        assertThat(detail.at("/request/submittedCount").asInt()).isEqualTo(1);

        mvc.perform(auth(get("/api/v1/submission-requests").param("type", "ANNUAL_PLAN").param("year", String.valueOf(year)), planner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void monthlyRequestNeedsConfirmedAnnualPlanAndOverdueIsShown() throws Exception {
        createRequest("ANNUAL_PLAN", null, OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), List.of(orgA)).andExpect(status().isCreated());
        createRequest("MONTHLY_ACTUAL", 10, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), List.of(orgA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANNUAL_PLAN_NOT_CONFIRMED"));
        jdbc.update("UPDATE annual_plan SET status = 'CONFIRMED', confirmed_by = ?, confirmed_at = now() WHERE year = ?", plannerId, year);
        String id = createRequest("MONTHLY_ACTUAL", 10, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), List.of(orgA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(year + "년 10월 월간 시트"))
                .andReturn().getResponse().getHeader("Location").replaceAll(".*/", "");
        JsonNode detail = read(mvc.perform(auth(get("/api/v1/submission-requests/" + id), planner)));
        assertThat(target(detail, orgA).get("displayStatus").asText()).isEqualTo("OVERDUE");
        createRequest("MONTHLY_ACTUAL", null, OffsetDateTime.now(ZoneOffset.UTC), List.of(orgA))
                .andExpect(status().isBadRequest());
    }

    @Test
    void inboxShowsOpenSubmissionAndResubmitCards() throws Exception {
        String id = createRequest("ANNUAL_PLAN", null, OffsetDateTime.now(ZoneOffset.UTC).plusDays(3), List.of(orgA))
                .andReturn().getResponse().getHeader("Location").replaceAll(".*/", "");

        JsonNode inboxA = read(mvc.perform(auth(get("/api/v1/inbox"), managerA)).andExpect(status().isOk()));
        assertThat(cards(inboxA, id)).hasSize(1);
        assertThat(cards(inboxA, id).getFirst().get("action").asText()).isEqualTo("START_SUBMISSION");
        assertThat(cards(inboxA, id).getFirst().at("/link/path").asText()).isEqualTo("/requests/" + id + "/upload");
        assertThat(cards(read(mvc.perform(auth(get("/api/v1/inbox"), managerB))), id)).isEmpty();

        long initialDraft = insertSubmission(Long.parseLong(id), orgA, "PENDING", 1);
        jdbc.update("update submission set status='DRAFT',seq=null,submitted_by=null,submitted_at=null where id=?", initialDraft);
        inboxA = read(mvc.perform(auth(get("/api/v1/inbox"), managerA)));
        assertThat(cards(inboxA, id).getFirst().get("draftSubmissionId").asText()).isEqualTo(String.valueOf(initialDraft));

        long rejected = insertSubmission(Long.parseLong(id), orgA, "REJECTED", 1);
        inboxA = read(mvc.perform(auth(get("/api/v1/inbox"), managerA)));
        assertThat(cards(inboxA, id).getFirst().get("title").asText()).endsWith("재제출");
        assertThat(cards(inboxA, id).getFirst().at("/link/path").asText()).isEqualTo("/submissions/" + rejected);
        assertThat(cards(inboxA, id).getFirst().get("draftSubmissionId").isNull()).isTrue();

        long resubmissionDraft = insertSubmission(Long.parseLong(id), orgA, "PENDING", 2);
        jdbc.update("update submission set status='DRAFT',seq=null,submitted_by=null,submitted_at=null where id=?", resubmissionDraft);
        inboxA = read(mvc.perform(auth(get("/api/v1/inbox"), managerA)));
        assertThat(cards(inboxA, id).getFirst().get("draftSubmissionId").asText()).isEqualTo(String.valueOf(resubmissionDraft));
        assertThat(cards(inboxA, id).getFirst().get("submissionId").asText()).isEqualTo(String.valueOf(rejected));

        insertSubmission(Long.parseLong(id), orgA, "PENDING", 2);
        assertThat(cards(read(mvc.perform(auth(get("/api/v1/inbox"), managerA))), id)).isEmpty();
    }

    @Test
    void rejectedLatestSubmissionCountsAsNotSubmitted() throws Exception {
        String id = createRequest("ANNUAL_PLAN", null, OffsetDateTime.now(ZoneOffset.UTC).plusDays(3), List.of(orgA))
                .andReturn().getResponse().getHeader("Location").replaceAll(".*/", "");
        insertSubmission(Long.parseLong(id), orgA, "REJECTED", 1);

        JsonNode detail = read(mvc.perform(auth(get("/api/v1/submission-requests/" + id), planner)));
        assertThat(target(detail, orgA).get("displayStatus").asText()).isEqualTo("WAITING");
        assertThat(target(detail, orgA).at("/latestSubmission/displayStatus").asText()).isEqualTo("RESUBMIT_REQUESTED");
        assertThat(detail.at("/request/submittedCount").asInt()).isZero();
        mvc.perform(auth(get("/api/v1/me/badges"), managerA)).andExpect(jsonPath("$.openRequests").value(1));

        jdbc.update("UPDATE submission_request SET due_at = now() - interval '1 hour' WHERE id = ?", Long.parseLong(id));
        detail = read(mvc.perform(auth(get("/api/v1/submission-requests/" + id), planner)));
        assertThat(target(detail, orgA).get("displayStatus").asText()).isEqualTo("OVERDUE");

        insertSubmission(Long.parseLong(id), orgA, "PENDING", 2);
        detail = read(mvc.perform(auth(get("/api/v1/submission-requests/" + id), planner)));
        assertThat(target(detail, orgA).get("displayStatus").asText()).isEqualTo("SUBMITTED");
        assertThat(detail.at("/request/submittedCount").asInt()).isEqualTo(1);
        mvc.perform(auth(get("/api/v1/me/badges"), managerA)).andExpect(jsonPath("$.openRequests").value(0));
    }

    @Test
    void monthlyAdditionalDispatchPreservesExistingSubmissionAndUsesOwnDeadline() throws Exception {
        jdbc.update("INSERT INTO annual_plan(year,status,confirmed_by,confirmed_at) VALUES (?,'CONFIRMED',?,now())", year, plannerId);
        String id = createRequest("MONTHLY_ACTUAL", 11, OffsetDateTime.now(ZoneOffset.UTC).minusDays(1), List.of(orgA))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location").replaceAll(".*/", "");
        long submission = insertSubmission(Long.parseLong(id), orgA, "PENDING", 1);
        JsonNode before = read(mvc.perform(auth(get("/api/v1/submission-requests/" + id), planner)));
        OffsetDateTime due = OffsetDateTime.now(ZoneOffset.UTC).plusDays(7).withNano(0);
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            addTargets(id, planner, List.of(orgB), due, key).andExpect(status().isOk())
                    .andExpect(jsonPath("$.targetCount").value(2));
        }
        JsonNode after = read(mvc.perform(auth(get("/api/v1/submission-requests/" + id), planner)));
        assertThat(after.get("request").get("dueAt")).isEqualTo(before.get("request").get("dueAt"));
        assertThat(target(after, orgA)).isEqualTo(target(before, orgA));
        assertThat(target(after, orgA).at("/latestSubmission/id").asText()).isEqualTo(String.valueOf(submission));
        assertThat(target(after, orgB).get("selected").asBoolean()).isTrue();
        assertThat(target(after, orgB).get("displayStatus").asText()).isEqualTo("WAITING");
        assertThat(OffsetDateTime.parse(target(after, orgB).get("dueAt").asText())).isEqualTo(due);
        JsonNode inbox = read(mvc.perform(auth(get("/api/v1/inbox"), managerB)).andExpect(status().isOk()));
        assertThat(cards(inbox, id)).hasSize(1);
        assertThat(cards(inbox, id).getFirst().get("message").asText()).isEqualTo("추가 요청입니다.");
        assertThat(OffsetDateTime.parse(cards(inbox, id).getFirst().get("dueAt").asText())).isEqualTo(due);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM submission_request WHERE id = ?", Integer.class, Long.parseLong(id))).isEqualTo(1);
        addTargets(id, planner, List.of(orgB), due, UUID.randomUUID().toString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_TARGET_ALREADY_SELECTED"));
    }

    @Test
    void additionalDispatchRejectsMixedBatchWithoutChangingUnselectedTarget() throws Exception {
        jdbc.update("INSERT INTO annual_plan(year,status,confirmed_by,confirmed_at) VALUES (?,'CONFIRMED',?,now())", year, plannerId);
        String id = createRequest("MONTHLY_ACTUAL", 11, OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), List.of(orgA))
                .andReturn().getResponse().getHeader("Location").replaceAll(".*/", "");
        OffsetDateTime due = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3);
        addTargets(id, managerB, List.of(orgB), due, UUID.randomUUID().toString()).andExpect(status().isForbidden());
        addTargets(id, planner, List.of(orgB, orgA), due, UUID.randomUUID().toString()).andExpect(status().isConflict());
        addTargets(id, planner, List.of(orgB, Long.MAX_VALUE), due, UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        addTargets(id, planner, List.of(orgB), due.minusDays(5), UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        JsonNode detail = read(mvc.perform(auth(get("/api/v1/submission-requests/" + id), planner)));
        assertThat(target(detail, orgB).get("selected").asBoolean()).isFalse();
        jdbc.update("UPDATE submission_request SET status='CLOSED' WHERE id=?", Long.parseLong(id));
        addTargets(id, planner, List.of(orgB), due, UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        addTargets("9223372036854775807", planner, List.of(orgB), due, UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    @Test
    void additionalDispatchDoesNotExtendAnnualRequestScope() throws Exception {
        String id = createRequest("ANNUAL_PLAN", null, OffsetDateTime.now(ZoneOffset.UTC).plusDays(1), List.of(orgA))
                .andReturn().getResponse().getHeader("Location").replaceAll(".*/", "");
        addTargets(id, planner, List.of(orgB), OffsetDateTime.now(ZoneOffset.UTC).plusDays(3), UUID.randomUUID().toString())
                .andExpect(status().isBadRequest());
    }

    private ResultActions addTargets(String id, String token, List<Long> ids, OffsetDateTime due, String key) throws Exception {
        return mvc.perform(auth(post("/api/v1/submission-requests/" + id + "/targets"), token)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("organizationIds", ids, "dueAt", due.toString(), "message", "추가 요청입니다."))));
    }

    @Test
    void sharedTemplateIsDownloadedByAnyLoggedInUser() throws Exception {
        Files.deleteIfExists(TEMPLATE);
        mvc.perform(auth(get("/api/v1/submission-template/file"), managerA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));

        byte[] bytes = "template-bytes".getBytes();
        Files.write(TEMPLATE, bytes);
        for (String token : List.of(managerA, managerB, planner)) {
            byte[] downloaded = mvc.perform(auth(get("/api/v1/submission-template/file"), token))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("filename*=UTF-8''")))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(downloaded).isEqualTo(bytes);
        }
        mvc.perform(get("/api/v1/submission-template/file")).andExpect(status().isUnauthorized());
    }

    @Test
    void rolesAreEnforced() throws Exception {
        createRequestAs(managerA, "ANNUAL_PLAN", null, OffsetDateTime.now(ZoneOffset.UTC), List.of(orgA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(auth(get("/api/v1/inbox"), planner)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/v1/organizations"), managerA)).andExpect(status().isForbidden());
        JsonNode orgs = read(mvc.perform(auth(get("/api/v1/organizations").param("q", "테스트A").param("limit", "100"), planner))
                .andExpect(status().isOk()));
        assertThat(orgs.get("items").findValuesAsText("id")).contains(String.valueOf(orgA));
    }

    private ResultActions createRequest(String type, Integer month, OffsetDateTime dueAt, List<Long> orgIds) throws Exception {
        return createRequestAs(planner, type, month, dueAt, orgIds);
    }

    private ResultActions createRequestAs(String token, String type, Integer month, OffsetDateTime dueAt, List<Long> orgIds) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("year", year);
        body.put("month", month);
        body.put("dueAt", dueAt.toString());
        body.put("message", "제출해 주세요.");
        body.put("organizationIds", orgIds);
        return mvc.perform(auth(post("/api/v1/submission-requests"), token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private long insertSubmission(long requestId, long orgId, String status, int seq) {
        String key = "drafts/test/" + UUID.randomUUID() + ".xlsx";
        jdbc.update("INSERT INTO stored_object(object_key, content_type, size_bytes, sha256) VALUES (?, 'application/octet-stream', 1, ?)",
                key, "0".repeat(64));
        return jdbc.queryForObject("""
                INSERT INTO submission(request_id, organization_id, seq, status, original_filename, source_object_key,
                    source_sha256, uploaded_by, uploaded_at, submitted_by, submitted_at, approved_seq)
                VALUES (?, ?, ?, ?, 'f.xlsx', ?, ?, ?, now(), ?, now(), ?) RETURNING id""", Long.class,
                requestId, orgId, seq, status, key, "0".repeat(64), plannerId, plannerId, "APPROVED".equals(status) ? 1 : null);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private JsonNode target(JsonNode detail, long orgId) {
        for (JsonNode t : detail.get("targets")) {
            if (t.at("/organization/id").asText().equals(String.valueOf(orgId))) {
                return t;
            }
        }
        throw new AssertionError("target not found: " + orgId);
    }

    private List<JsonNode> cards(JsonNode inbox, String requestId) {
        return java.util.stream.StreamSupport.stream(inbox.get("items").spliterator(), false)
                .filter(item -> item.get("requestId").asText().equals(requestId)).toList();
    }
}
