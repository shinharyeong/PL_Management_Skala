package io.skala.backend.domain.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.global.security.SessionTokenStore;
import io.skala.backend.support.PostgresIntegrationTest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class HomeArchiveIntegrationTest extends PostgresIntegrationTest {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

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
    String manager;
    int year;
    long request;
    long subA;
    long subB;
    long itemRequested;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        orgA = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '홈A') RETURNING id", Long.class, "HA" + s);
        orgB = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '홈B') RETURNING id", Long.class, "HB" + s);
        long orgC = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '홈C') RETURNING id", Long.class, "HC" + s);
        plannerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role) VALUES (?, '기획', 'PLANNER') RETURNING id", Long.class, "hp" + s + "@sk.com");
        long managerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role, organization_id) VALUES (?, '담당', 'ORG_MANAGER', ?) RETURNING id",
                Long.class, "hm" + s + "@sk.com", orgA);
        planner = sessions.issue(plannerId).token();
        manager = sessions.issue(managerId).token();
        do {
            year = ThreadLocalRandom.current().nextInt(2100, 9999);
        } while (jdbc.queryForObject("SELECT count(*) FROM annual_plan WHERE year = ?", Integer.class, year) > 0);
        long plan = jdbc.queryForObject("INSERT INTO annual_plan(year, status) VALUES (?, 'CONFIRMED') RETURNING id", Long.class, year);
        OffsetDateTime due = LocalDate.now(KST).plusDays(2).atTime(LocalTime.of(18, 0)).atZone(KST).toOffsetDateTime();
        request = jdbc.queryForObject("""
                INSERT INTO submission_request(annual_plan_id, type, month, status, due_at, requested_by, requested_at, period_key)
                VALUES (?, 'MONTHLY_ACTUAL', 10, 'OPEN', ?, ?, now(), ?) RETURNING id""", Long.class, plan, due, plannerId, year + "-10");
        jdbc.update("INSERT INTO request_target(request_id, organization_id, selected) VALUES (?, ?, true), (?, ?, true), (?, ?, false)",
                request, orgA, request, orgB, request, orgC);
    }

    @Test
    void homeStatusAndTodosFollowTheMonthlyClosingFlow() throws Exception {
        JsonNode empty = read(homeStatus());
        assertThat(empty.get("dataStatus").asText()).isEqualTo("NONE");
        assertThat(empty.get("dDay").asLong()).isEqualTo(2);
        assertThat(step(empty, "SUBMITTED").get("total").asInt()).isEqualTo(2);
        assertThat(step(empty, "REQUESTED").get("date").asText()).isEqualTo(LocalDate.now(KST).toString());

        prepareSubmissions();
        JsonNode body = read(homeStatus());
        assertThat(body.get("dataStatus").asText()).isEqualTo("PROVISIONAL");
        assertThat(step(body, "SUBMITTED").get("done").asInt()).isEqualTo(2);
        assertThat(step(body, "GAP_REVIEW").get("done").asInt()).isEqualTo(2);
        assertThat(step(body, "GAP_REVIEW").get("total").asInt()).isEqualTo(3);
        assertThat(step(body, "EXPLAINED").get("done").asInt()).isEqualTo(1);
        assertThat(step(body, "MASTER").get("done").asInt()).isZero();

        JsonNode todos = read(mvc.perform(auth(get("/api/v1/home/todos").param("tab", "MONTHLY")
                .param("year", String.valueOf(year)).param("month", "10"), planner)));
        assertThat(todo(todos, "REQUEST_NEEDED").get("count").asInt()).isZero();
        assertThat(todo(todos, "NOT_SUBMITTED").get("count").asInt()).isZero();
        assertThat(todo(todos, "REVIEW_NEEDED").get("count").asInt()).isEqualTo(1);
        assertThat(todo(todos, "EXPLANATION_REVIEW_NEEDED").get("count").asInt()).isZero();
        assertThat(todo(todos, "REVIEW_NEEDED").at("/link/query/requestId").asText()).isEqualTo(String.valueOf(request));

        jdbc.update("UPDATE review_explanation SET answer = '재소명', answered_by = ?, answered_at = now() WHERE review_item_id = ? AND round = 2",
                plannerId, itemRequested);
        todos = read(mvc.perform(auth(get("/api/v1/home/todos").param("tab", "MONTHLY")
                .param("year", String.valueOf(year)).param("month", "10"), planner)));
        assertThat(todo(todos, "EXPLANATION_REVIEW_NEEDED").get("count").asInt()).isEqualTo(1);
        assertThat(step(read(homeStatus()), "EXPLAINED").get("done").asInt()).isEqualTo(2);

        jdbc.update("""
                INSERT INTO master_version(request_id, seq, trigger_submission_id, content_sha256, calculation_version, created_by, created_at)
                VALUES (?, 1, ?, ?, 'test', ?, now())""", request, subB, "0".repeat(64), plannerId);
        body = read(homeStatus());
        assertThat(body.get("dataStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(body.at("/master/label").asText()).isEqualTo("master_ver10.01");
        assertThat(body.get("masters")).hasSize(1);
        assertThat(step(body, "MASTER").get("done").asInt()).isEqualTo(1);

        JsonNode annual = read(mvc.perform(auth(get("/api/v1/home/todos").param("tab", "ANNUAL")
                .param("year", String.valueOf(year)), planner)));
        assertThat(todo(annual, "REQUEST_NEEDED").get("count").asInt()).isEqualTo(1);
        assertThat(todo(annual, "REQUEST_NEEDED").at("/link/path").asText()).isEqualTo("/planning/annual-plan/new");

        mvc.perform(auth(get("/api/v1/home/status").param("month", "13"), planner)).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/v1/home/todos").param("tab", "WEEKLY"), planner)).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/v1/home/status").param("month", "10"), manager)).andExpect(status().isForbidden());
    }

    @Test
    void archiveShowsMonthlyVersionsAndChangeHistory() throws Exception {
        prepareSubmissions();
        JsonNode b = read(mvc.perform(auth(get("/api/v1/organizations/" + orgB + "/versions")
                .param("year", String.valueOf(year)), planner)).andExpect(status().isOk()));
        assertThat(b.get("months")).hasSize(1);
        assertThat(b.at("/months/0/finalLabel").asText()).isEqualTo("ver10.01");
        assertThat(b.get("history")).isEmpty();

        JsonNode a = read(mvc.perform(auth(get("/api/v1/organizations/" + orgA + "/versions")
                .param("year", String.valueOf(year)).param("month", "10"), planner)));
        assertThat(a.at("/months/0/finalLabel").isNull()).isTrue();
        assertThat(a.get("history")).hasSize(1);
        assertThat(a.at("/history/0/label").asText()).isEqualTo(year + "-10-01");
        assertThat(a.at("/history/0/file/url").asText()).isEqualTo("/api/v1/submissions/" + subA + "/file");

        jdbc.update("UPDATE review_explanation SET answer = '재소명 답', answered_by = ?, answered_at = now() WHERE review_item_id = ? AND round = 2",
                plannerId, itemRequested);
        JsonNode history = read(mvc.perform(auth(get("/api/v1/submissions/" + subA + "/change-history"), planner))
                .andExpect(status().isOk()));
        assertThat(history.get("dates")).hasSize(2);
        assertThat(history.at("/dates/0/state").asText()).isEqualTo("EXPLAINED");
        assertThat(history.at("/dates/0/items")).hasSize(2);
        assertThat(history.at("/dates/0/items/0/no").asInt()).isEqualTo(1);
        assertThat(history.at("/dates/1/state").asText()).isEqualTo("RE_EXPLAINED");
        assertThat(history.at("/dates/1/items/0/explanation").asText()).isEqualTo("재소명 답");

        mvc.perform(auth(get("/api/v1/organizations/999999999/versions"), planner)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/submissions/999999999/change-history"), planner)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/submissions/" + subA + "/change-history"), manager)).andExpect(status().isForbidden());
    }

    /**
     * A: 검토 대기. Gap 항목 2개(검토 필요 1, 소명 요청 1 — 2회차 요청에 아직 답 없음). 1회차 소명은 둘 다 작성됨.
     * B: 승인. Gap 항목 1개(검토 필요 상태지만 승인됐으므로 검토 완료로 셈).
     */
    private void prepareSubmissions() {
        subA = insertSubmission(orgA, "PENDING", null);
        subB = insertSubmission(orgB, "APPROVED", 1);
        long reportItem = jdbc.queryForObject("INSERT INTO report_item(code, name, kind, is_leaf) VALUES (?, '매출', 'REVENUE', true) RETURNING id",
                Long.class, "HR" + UUID.randomUUID());
        long needsReview = insertItem(subA, reportItem, "ACTUAL", "NEEDS_REVIEW", 1);
        itemRequested = insertItem(subA, reportItem, "ESTIMATE", "REQUESTED", 2);
        insertItem(subB, reportItem, "ACTUAL", "NEEDS_REVIEW", 1);
        jdbc.update("""
                INSERT INTO review_explanation(review_item_id, round, answer, answered_by, answered_at, verdict)
                VALUES (?, 1, '1회차 소명', ?, now() - interval '1 day', 'PENDING'), (?, 1, '1회차 소명2', ?, now() - interval '1 day', 'PENDING')""",
                needsReview, plannerId, itemRequested, plannerId);
        jdbc.update("""
                INSERT INTO review_explanation(review_item_id, round, request_note, requested_by, requested_at, verdict)
                VALUES (?, 2, '근거를 보완해 주세요', ?, now(), 'PENDING')""", itemRequested, plannerId);
    }

    private long insertItem(long submission, long reportItem, String scenario, String state, int round) {
        return jdbc.queryForObject("""
                INSERT INTO review_item(submission_id, report_item_id, year, month, scenario, amount_present, current_amount,
                    change_detected, gap_exceeded, source_fingerprint, context_fingerprint, current_round, workflow_state)
                VALUES (?, ?, ?, 10, ?, true, 100, true, true, ?, ?, ?, ?) RETURNING id""", Long.class,
                submission, reportItem, year, scenario, "s".repeat(64), "c".repeat(64), round, state);
    }

    private long insertSubmission(long orgId, String status, Integer approvedSeq) {
        String key = "uploads/test/" + UUID.randomUUID() + ".xlsx";
        jdbc.update("INSERT INTO stored_object(object_key, content_type, size_bytes, sha256) VALUES (?, 'application/octet-stream', 1, ?)",
                key, "0".repeat(64));
        return jdbc.queryForObject("""
                INSERT INTO submission(request_id, organization_id, seq, status, original_filename, source_object_key,
                    source_sha256, uploaded_by, uploaded_at, submitted_by, submitted_at, reviewed_by, reviewed_at, approved_seq)
                VALUES (?, ?, 1, ?, 'f.xlsx', ?, ?, ?, now(), ?, now(), ?, ?, ?) RETURNING id""", Long.class,
                request, orgId, status, key, "a".repeat(64), plannerId, plannerId,
                approvedSeq == null ? null : plannerId, approvedSeq == null ? null : OffsetDateTime.now(), approvedSeq);
    }

    private ResultActions homeStatus() throws Exception {
        return mvc.perform(auth(get("/api/v1/home/status").param("year", String.valueOf(year)).param("month", "10"), planner))
                .andExpect(status().isOk());
    }

    private JsonNode step(JsonNode body, String key) {
        for (JsonNode s : body.get("steps")) {
            if (s.get("key").asText().equals(key)) {
                return s;
            }
        }
        throw new AssertionError("step not found: " + key);
    }

    private JsonNode todo(JsonNode body, String key) {
        for (JsonNode s : body.get("items")) {
            if (s.get("key").asText().equals(key)) {
                return s;
            }
        }
        throw new AssertionError("todo not found: " + key);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
