package io.skala.backend.domain.notification.service;

import io.skala.backend.domain.notification.dto.NotificationEvent;
import io.skala.backend.domain.notification.value.NotificationType;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기한 경과 알림. 회신 기한이 지났는데 제출하지 않은 (요청, 조직)마다 OVERDUE 알림을 한 번 남긴다.
 * 알림 시각은 회신 기한이다(화면은 '기한 11.10 18:00 경과'로 표시). eventKey가 같으면 다시 저장하지 않는다.
 * 주기는 app.notification.overdue-scan.cron(기본 5분), "-"이면 꺼진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OverdueNotificationScheduler {

    private final NotificationRecorder recorder;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Scheduled(cron = "${app.notification.overdue-scan.cron:0 */5 * * * *}", zone = "Asia/Seoul")
    @Transactional
    public void run() {
        int recorded = scan();
        if (recorded > 0) {
            log.info("기한 경과 알림 대상 {}건 확인", recorded);
        }
    }

    /** 기한이 지난 미제출 대상 수를 돌려준다. */
    @Transactional
    public int scan() {
        List<NotificationEvent> events = jdbc.query(NotificationQueryService.OVERDUE_TARGETS_SQL, (rs, i) -> {
            long requestId = rs.getLong("request_id");
            long organizationId = rs.getLong("organization_id");
            String title = "ANNUAL_PLAN".equals(rs.getString("type"))
                    ? rs.getInt("year") + "년 연간 계획 제출 기한 경과"
                    : rs.getInt("month") + "월 월간 시트 제출 기한 경과";
            return new NotificationEvent(NotificationType.OVERDUE, "overdue:" + requestId + ":" + organizationId,
                    requestId, organizationId, null, null, title, rs.getObject("due_at", OffsetDateTime.class));
        }, OffsetDateTime.now(clock));
        events.forEach(recorder::record);
        return events.size();
    }
}
