package io.skala.backend.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.skala.backend.domain.notification.dto.NotificationEvent;
import io.skala.backend.domain.notification.repository.NotificationRepository;
import io.skala.backend.domain.notification.service.NotificationRecorder;
import io.skala.backend.domain.notification.value.NotificationType;
import io.skala.backend.support.PostgresIntegrationTest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class DefaultNotificationRecorderTest extends PostgresIntegrationTest {

    @Autowired
    NotificationRecorder recorder;
    @Autowired
    NotificationRepository notifications;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transaction;

    @Test
    void recordsOncePerPlannerForSameEventKey() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long orgId = jdbc.queryForObject("INSERT INTO organization(code, name) VALUES (?, '테스트 조직') RETURNING id", Long.class, "T" + suffix);
        Long planner1 = jdbc.queryForObject("INSERT INTO app_user(email, name, role) VALUES (?, '기획1', 'PLANNER') RETURNING id", Long.class, "p1-" + suffix + "@sk.com");
        Long planner2 = jdbc.queryForObject("INSERT INTO app_user(email, name, role) VALUES (?, '기획2', 'PLANNER') RETURNING id", Long.class, "p2-" + suffix + "@sk.com");
        Long planId = jdbc.queryForObject("INSERT INTO annual_plan(year, status) VALUES (?, 'COLLECTING') RETURNING id", Long.class, 2000 + (int) (Math.random() * 7000));
        Long requestId = jdbc.queryForObject("""
                INSERT INTO submission_request(annual_plan_id, type, status, due_at, requested_by, requested_at, period_key)
                VALUES (?, 'ANNUAL_PLAN', 'OPEN', now(), ?, now(), ?) RETURNING id""", Long.class, planId, planner1, "P-" + suffix);
        String eventKey = "overdue:" + requestId + ":" + orgId;
        NotificationEvent event = new NotificationEvent(NotificationType.OVERDUE, eventKey, requestId, orgId, null, null,
                "연간 계획 미제출", OffsetDateTime.now());

        transaction.executeWithoutResult(s -> recorder.record(event));
        transaction.executeWithoutResult(s -> recorder.record(event));

        assertThat(notifications.existsByRecipientIdAndEventKey(planner1, eventKey)).isTrue();
        assertThat(notifications.existsByRecipientIdAndEventKey(planner2, eventKey)).isTrue();
        Integer count = jdbc.queryForObject("SELECT count(*) FROM notification WHERE event_key=?", Integer.class, eventKey);
        assertThat(count).isGreaterThanOrEqualTo(2);
        Integer perRecipient = jdbc.queryForObject("SELECT max(c) FROM (SELECT count(*) c FROM notification WHERE event_key=? GROUP BY recipient_id) t", Integer.class, eventKey);
        assertThat(perRecipient).isEqualTo(1);
    }
}
