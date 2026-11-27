package io.skala.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skala.backend.domain.notification.service.OverdueNotificationScheduler;
import io.skala.backend.global.security.SessionTokenStore;
import io.skala.backend.support.PostgresIntegrationTest;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class NotificationIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    SessionTokenStore sessions;
    @Autowired
    OverdueNotificationScheduler overdueScheduler;

    long orgA;
    long plannerId;
    long managerId;
    String planner;
    String manager;
    long annualRequest;
    long monthlyRequest;
    long submissionId;
    OffsetDateTime annualDue;

    @BeforeEach
    void setUp() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        orgA = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '알림조직') RETURNING id", Long.class, "NA" + s);
        plannerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role) VALUES (?, '기획', 'PLANNER') RETURNING id", Long.class, "np" + s + "@sk.com");
        managerId = jdbc.queryForObject("INSERT INTO app_user(email, name, role, organization_id) VALUES (?, '박담당', 'ORG_MANAGER', ?) RETURNING id",
                Long.class, "nm" + s + "@sk.com", orgA);
        planner = sessions.issue(plannerId).token();
        manager = sessions.issue(managerId).token();

        int year;
        do {
            year = ThreadLocalRandom.current().nextInt(2100, 9999);
        } while (jdbc.queryForObject("SELECT count(*) FROM annual_plan WHERE year = ?", Integer.class, year) > 0);
        long plan = jdbc.queryForObject("INSERT INTO annual_plan(year, status) VALUES (?, 'CONFIRMED') RETURNING id", Long.class, year);
        annualDue = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).truncatedTo(ChronoUnit.SECONDS);
        annualRequest = insertRequest(plan, "ANNUAL_PLAN", null, annualDue, year + "-PLAN");
        monthlyRequest = insertRequest(plan, "MONTHLY_ACTUAL", 10, OffsetDateTime.now(ZoneOffset.UTC).plusDays(3), year + "-10");
        submissionId = insertSubmission(monthlyRequest);
    }

    @Test
    void plannerSeesGroupedNotificationsWithSummaryAndLinks() throws Exception {
        insertNotification(plannerId, "SUBMISSION", "submission:" + submissionId, monthlyRequest, submissionId, "10월 월간 시트 제출", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2));
        insertNotification(plannerId, "REPLY", "reply:t" + submissionId, monthlyRequest, null, "10월 매출 Gap 소명 회신 도착", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        assertThat(overdueScheduler.scan()).isGreaterThanOrEqualTo(1);
        overdueScheduler.scan();
        // 같은 DB의 다른 테스트가 남긴 기한 경과 대상도 모든 기획 사용자에게 알림이 가므로 이 테스트 조직 것만 남긴다.
        jdbc.update("DELETE FROM notification WHERE recipient_id = ? AND organization_id <> ?", plannerId, orgA);

        JsonNode body = read(mvc.perform(auth(get("/api/v1/notifications"), planner)).andExpect(status().isOk()));
        assertThat(body.at("/summary/unreadCount").asLong()).isEqualTo(3);
        assertThat(body.at("/summary/overdueOrganizationCount").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(body.at("/typeCounts/ALL").asLong()).isEqualTo(3);
        assertThat(body.at("/typeCounts/OVERDUE").asLong()).isEqualTo(1);
        assertThat(body.get("nextCursor").isNull()).isTrue();

        JsonNode groups = body.get("groups");
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).get("date").asText()).isEqualTo(java.time.LocalDate.now(ZoneId.of("Asia/Seoul")).toString());
        assertThat(groups.get(0).get("unreadCount").asLong()).isEqualTo(2);
        JsonNode reply = groups.get(0).get("items").get(0);
        JsonNode submission = groups.get(0).get("items").get(1);
        assertThat(reply.get("type").asText()).isEqualTo("REPLY");
        assertThat(submission.at("/actor/name").asText()).isEqualTo("박담당");
        assertThat(submission.at("/organization/id").asText()).isEqualTo(String.valueOf(orgA));
        assertThat(submission.at("/link/path").asText()).isEqualTo("/planning/gap");
        assertThat(submission.at("/link/query/submissionId").asText()).isEqualTo(String.valueOf(submissionId));
        assertThat(submission.get("read").asBoolean()).isFalse();

        JsonNode overdue = groups.get(1).get("items").get(0);
        assertThat(overdue.get("type").asText()).isEqualTo("OVERDUE");
        assertThat(overdue.get("title").asText()).endsWith("연간 계획 제출 기한 경과");
        assertThat(overdue.get("actor").isNull()).isTrue();
        assertThat(OffsetDateTime.parse(overdue.get("occurredAt").asText()).toInstant()).isEqualTo(annualDue.toInstant());
        assertThat(overdue.at("/link/path").asText()).isEqualTo("/planning/annual-plan");
        assertThat(overdue.at("/link/query/requestId").asText()).isEqualTo(String.valueOf(annualRequest));

        mvc.perform(auth(get("/api/v1/notifications").param("type", "OVERDUE"), planner))
                .andExpect(jsonPath("$.groups.length()").value(1))
                .andExpect(jsonPath("$.groups[0].items[0].type").value("OVERDUE"))
                .andExpect(jsonPath("$.typeCounts.SUBMISSION").value(1));

        JsonNode first = read(mvc.perform(auth(get("/api/v1/notifications").param("limit", "2"), planner)));
        String cursor = first.get("nextCursor").asText();
        assertThat(first.at("/groups/0/items")).hasSize(2);
        mvc.perform(auth(get("/api/v1/notifications").param("limit", "2").param("cursor", cursor), planner))
                .andExpect(jsonPath("$.groups[0].items[0].type").value("OVERDUE"))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
        mvc.perform(auth(get("/api/v1/notifications").param("type", "REPLY").param("cursor", cursor), planner))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        mvc.perform(auth(get("/api/v1/notifications").param("type", "GAP"), planner)).andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/v1/notifications"), manager)).andExpect(status().isForbidden());
    }

    @Test
    void plannerMarksSelectedOrAllNotificationsRead() throws Exception {
        long mine = insertNotification(plannerId, "SUBMISSION", "submission:" + submissionId, monthlyRequest, submissionId, "제출", OffsetDateTime.now(ZoneOffset.UTC));
        insertNotification(plannerId, "REPLY", "reply:r" + submissionId, monthlyRequest, null, "회신", OffsetDateTime.now(ZoneOffset.UTC));
        long otherPlanner = jdbc.queryForObject("INSERT INTO app_user(email, name, role) VALUES (?, '다른기획', 'PLANNER') RETURNING id",
                Long.class, "op" + UUID.randomUUID() + "@sk.com");
        long others = insertNotification(otherPlanner, "SUBMISSION", "submission:" + submissionId, monthlyRequest, submissionId, "제출", OffsetDateTime.now(ZoneOffset.UTC));

        String key = UUID.randomUUID().toString();
        markRead("{\"ids\":[\"" + mine + "\",\"" + others + "\"]}", key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));
        markRead("{\"ids\":[\"" + mine + "\",\"" + others + "\"]}", key)
                .andExpect(header().string("Idempotency-Replayed", "true"));
        assertThat(jdbc.queryForObject("SELECT read_at IS NULL FROM notification WHERE id = ?", Boolean.class, others)).isTrue();

        mvc.perform(auth(get("/api/v1/notifications").param("unreadOnly", "true"), planner))
                .andExpect(jsonPath("$.summary.unreadCount").value(1))
                .andExpect(jsonPath("$.typeCounts.ALL").value(1))
                .andExpect(jsonPath("$.groups[0].items.length()").value(1))
                .andExpect(jsonPath("$.groups[0].items[0].type").value("REPLY"));

        markRead("{}", UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        markRead("{\"ids\":[\"" + mine + "\"],\"all\":true}", UUID.randomUUID().toString()).andExpect(status().isBadRequest());
        markRead("{\"all\":true}", UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
        mvc.perform(auth(get("/api/v1/me/badges"), planner)).andExpect(jsonPath("$.unreadNotifications").value(0));
    }

    @Test
    void reviewedAnnualSubmissionsLeaveTheInboxAndBadgeButKeepTheirSubmissionHistory() throws Exception {
        long annual = insertSubmission(annualRequest);
        long annualNotification = insertNotification(plannerId, "SUBMISSION", "annual:" + annual,
                annualRequest, annual, "연간 제출", OffsetDateTime.now(ZoneOffset.UTC));
        insertNotification(plannerId, "SUBMISSION", "monthly:" + submissionId, monthlyRequest,
                submissionId, "월간 제출", OffsetDateTime.now(ZoneOffset.UTC));
        for (String state : List.of("PENDING", "REJECTED", "APPROVED")) {
            jdbc.update("update submission set status=?,approved_seq=? where id=?", state,
                    "APPROVED".equals(state) ? 1 : null, annual);
            int expected = "PENDING".equals(state) ? 2 : 1;
            mvc.perform(auth(get("/api/v1/notifications"), planner))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.typeCounts.ALL").value(expected))
                    .andExpect(jsonPath("$.typeCounts.SUBMISSION").value(expected))
                    .andExpect(jsonPath("$.summary.unreadCount").value(expected));
            mvc.perform(auth(get("/api/v1/me/badges"), planner))
                    .andExpect(jsonPath("$.unreadNotifications").value(expected));
        }
        insertNotification(plannerId, "REPLY", "monthly-reply:" + submissionId, monthlyRequest,
                submissionId, "월간 소명 회신", OffsetDateTime.now(ZoneOffset.UTC));
        insertNotification(plannerId, "OVERDUE", "monthly-overdue:" + submissionId, monthlyRequest,
                null, "월간 기한 경과", OffsetDateTime.now(ZoneOffset.UTC));
        jdbc.update("update submission set status='APPROVED',approved_seq=1 where id=?", submissionId);
        mvc.perform(auth(get("/api/v1/notifications"), planner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.typeCounts.ALL").value(0))
                .andExpect(jsonPath("$.summary.unreadCount").value(0));
        mvc.perform(auth(get("/api/v1/me/badges"), planner))
                .andExpect(jsonPath("$.unreadNotifications").value(0));
        // 삭제나 읽음 상태 조작 없이 보이는 범위만 바꾼다. 원본 제출본·알림 이력은 유지한다.
        assertThat(jdbc.queryForObject("select count(*) from submission where id=?", Integer.class, annual)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select read_at is null from notification where id=?", Boolean.class,
                annualNotification)).isTrue();
    }

    private ResultActions markRead(String json, String key) throws Exception {
        return mvc.perform(auth(post("/api/v1/notifications/read"), planner)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long insertRequest(long plan, String type, Integer month, OffsetDateTime dueAt, String periodKey) {
        long id = jdbc.queryForObject("""
                INSERT INTO submission_request(annual_plan_id, type, month, status, due_at, requested_by, requested_at, period_key)
                VALUES (?, ?, ?, 'OPEN', ?, ?, now(), ?) RETURNING id""", Long.class, plan, type, month, dueAt, plannerId, periodKey);
        jdbc.update("INSERT INTO request_target(request_id, organization_id) VALUES (?, ?)", id, orgA);
        return id;
    }

    private long insertSubmission(long requestId) {
        String key = "uploads/test/" + UUID.randomUUID() + ".xlsx";
        jdbc.update("INSERT INTO stored_object(object_key, content_type, size_bytes, sha256) VALUES (?, 'application/octet-stream', 1, ?)",
                key, "0".repeat(64));
        return jdbc.queryForObject("""
                INSERT INTO submission(request_id, organization_id, seq, status, original_filename, source_object_key,
                    source_sha256, uploaded_by, uploaded_at, submitted_by, submitted_at)
                VALUES (?, ?, 1, 'PENDING', 'f.xlsx', ?, ?, ?, now(), ?, now()) RETURNING id""", Long.class,
                requestId, orgA, key, "0".repeat(64), managerId, managerId);
    }

    private long insertNotification(long recipient, String type, String eventKey, long requestId, Long submission,
                                    String title, OffsetDateTime occurredAt) {
        return jdbc.queryForObject("""
                INSERT INTO notification(recipient_id, type, event_key, request_id, organization_id, submission_id, title, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                recipient, type, eventKey, requestId, orgA, submission, title, occurredAt);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
