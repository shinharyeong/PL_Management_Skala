package io.skala.backend.domain.notification.service;

import io.skala.backend.domain.notification.dto.MarkNotificationsReadRequest;
import io.skala.backend.domain.notification.dto.NotificationListResponse;
import io.skala.backend.domain.notification.dto.NotificationListResponse.Group;
import io.skala.backend.domain.notification.dto.NotificationListResponse.Item;
import io.skala.backend.domain.notification.dto.NotificationListResponse.Link;
import io.skala.backend.domain.notification.dto.NotificationListResponse.OrganizationRef;
import io.skala.backend.domain.notification.dto.NotificationListResponse.Summary;
import io.skala.backend.domain.notification.dto.NotificationListResponse.UserRef;
import io.skala.backend.domain.notification.dto.UnreadCountResponse;
import io.skala.backend.domain.notification.repository.NotificationRepository;
import io.skala.backend.domain.notification.value.NotificationType;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.util.OffsetCursor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** NOTI-01 알림 목록, NOTI-02 읽음 처리. 경영기획팀 전용이고 자기에게 온 알림만 다룬다. */
@Service
@RequiredArgsConstructor
public class NotificationQueryService {

    /** 날짜 묶음과 '오늘·어제' 판단 기준(NFR-DISP-004) */
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 기한이 지났는데 아직 제출하지 않은 (요청, 조직). 요청 진행 현황(REQ-04)의 OVERDUE와 같은 기준이다.
     * 파라미터: 기준 시각
     */
    static final String OVERDUE_TARGETS_SQL = """
            SELECT r.id AS request_id, t.organization_id, r.type, p.year, r.month, r.due_at
              FROM submission_request r
              JOIN annual_plan p ON p.id = r.annual_plan_id
              JOIN request_target t ON t.request_id = r.id AND t.selected AND NOT t.excluded
             WHERE r.status = 'OPEN' AND r.due_at < ?
               AND NOT EXISTS (SELECT 1 FROM submission s
                                WHERE s.request_id = r.id AND s.organization_id = t.organization_id AND s.status <> 'DRAFT')""";

    private static final String LIST_SQL = """
            SELECT n.id, n.type, n.title, n.occurred_at, n.read_at, n.request_id, n.organization_id, n.submission_id,
                   o.code AS org_code, o.name AS org_name, r.type AS request_type, e.review_item_id,
                   COALESCE(ea.id, sa.id) AS actor_id, COALESCE(ea.name, sa.name) AS actor_name
              FROM notification n
              JOIN organization o ON o.id = n.organization_id
              JOIN submission_request r ON r.id = n.request_id
              LEFT JOIN submission s ON s.id = n.submission_id
              LEFT JOIN app_user sa ON sa.id = s.submitted_by
              LEFT JOIN review_explanation e ON e.id = n.review_explanation_id
              LEFT JOIN app_user ea ON ea.id = e.answered_by
            """;

    private final NotificationRepository notificationRepository;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(readOnly = true)
    public NotificationListResponse list(AuthenticatedUser actor, String type, Boolean unreadOnly, String sort,
                                         String cursor, Integer limit) {
        actor.requireRole(UserRole.PLANNER);
        NotificationType filter = parseType(type);
        boolean unread = Boolean.TRUE.equals(unreadOnly);
        if (sort != null && !"LATEST".equals(sort)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "sort는 LATEST만 지원합니다.");
        }
        String scope = "noti|" + filter + "|" + unread;
        int size = OffsetCursor.limit(limit);
        int offset = OffsetCursor.decode(cursor, scope);

        StringBuilder where = new StringBuilder(" WHERE n.recipient_id = ?");
        List<Object> args = new ArrayList<>(List.of(actor.id()));
        if (unread) {
            where.append(" AND n.read_at IS NULL");
        }
        Map<String, Long> typeCounts = typeCounts(where.toString(), args);
        if (filter != null) {
            where.append(" AND n.type = ?");
            args.add(filter.name());
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size + 1);
        pageArgs.add(offset);
        List<Row> rows = jdbc.query(LIST_SQL + where + " ORDER BY n.occurred_at DESC, n.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> toRow(rs), pageArgs.toArray());
        boolean hasMore = rows.size() > size;
        List<Row> page = rows.stream().limit(size).toList();

        Map<LocalDate, Long> unreadByDate = unreadByDate(where.toString(), args, page);
        Map<LocalDate, List<Item>> grouped = new LinkedHashMap<>();
        for (Row row : page) {
            grouped.computeIfAbsent(row.date(), d -> new ArrayList<>()).add(row.item());
        }
        List<Group> groups = grouped.entrySet().stream()
                .map(e -> new Group(e.getKey(), unreadByDate.getOrDefault(e.getKey(), 0L), e.getValue()))
                .toList();
        return new NotificationListResponse(OffsetCursor.next(hasMore, offset, size, scope), summary(actor.id()),
                typeCounts, groups);
    }

    @Transactional
    public UnreadCountResponse markRead(AuthenticatedUser actor, MarkNotificationsReadRequest input) {
        actor.requireRole(UserRole.PLANNER);
        boolean all = Boolean.TRUE.equals(input.all());
        boolean hasIds = input.ids() != null && !input.ids().isEmpty();
        if (all == hasIds || (input.all() != null && !all)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "ids 또는 all=true 중 하나만 보내 주세요.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (all) {
            notificationRepository.markAllRead(actor.id(), now);
        } else {
            notificationRepository.markRead(actor.id(), input.ids(), now);
        }
        return new UnreadCountResponse(notificationRepository.countByRecipientIdAndReadAtIsNull(actor.id()));
    }

    private Summary summary(Long recipientId) {
        long replyPending = count("""
                SELECT count(*) FROM review_explanation e
                  JOIN review_item ri ON ri.id = e.review_item_id AND ri.active AND e.round = ri.current_round
                  JOIN submission s ON s.id = ri.submission_id
                 WHERE s.status = 'PENDING' AND e.answer IS NOT NULL AND e.verdict = 'PENDING'""");
        long overdue = count("SELECT count(*) FROM (" + OVERDUE_TARGETS_SQL + ") o", OffsetDateTime.now(clock));
        return new Summary(notificationRepository.countByRecipientIdAndReadAtIsNull(recipientId), replyPending, overdue);
    }

    private Map<String, Long> typeCounts(String where, List<Object> args) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("ALL", 0L);
        for (NotificationType t : NotificationType.values()) {
            counts.put(t.name(), 0L);
        }
        jdbc.query("SELECT n.type, count(*) AS cnt FROM notification n" + where + " GROUP BY n.type", rs -> {
            counts.put(rs.getString("type"), rs.getLong("cnt"));
            counts.merge("ALL", rs.getLong("cnt"), Long::sum);
        }, args.toArray());
        return counts;
    }

    /** 페이지에 나온 날짜들의 안 읽은 알림 수(같은 필터, 페이지 밖 포함) */
    private Map<LocalDate, Long> unreadByDate(String where, List<Object> args, List<Row> page) {
        Map<LocalDate, Long> result = new LinkedHashMap<>();
        if (page.isEmpty()) {
            return result;
        }
        List<Object> dayArgs = new ArrayList<>(args);
        dayArgs.add(page.getLast().date().atStartOfDay(KST).toOffsetDateTime());
        dayArgs.add(page.getFirst().date().plusDays(1).atStartOfDay(KST).toOffsetDateTime());
        jdbc.query("SELECT n.occurred_at FROM notification n" + where
                        + " AND n.read_at IS NULL AND n.occurred_at >= ? AND n.occurred_at < ?",
                rs -> {
                    LocalDate day = rs.getObject("occurred_at", OffsetDateTime.class).atZoneSameInstant(KST).toLocalDate();
                    result.merge(day, 1L, Long::sum);
                }, dayArgs.toArray());
        return result;
    }

    private static NotificationType parseType(String type) {
        if (type == null || "ALL".equals(type)) {
            return null;
        }
        try {
            return NotificationType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "type은 ALL·SUBMISSION·REPLY·OVERDUE 중 하나입니다.");
        }
    }

    private static Row toRow(ResultSet rs) throws SQLException {
        NotificationType type = NotificationType.valueOf(rs.getString("type"));
        OffsetDateTime occurredAt = rs.getObject("occurred_at", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC);
        Long actorId = (Long) rs.getObject("actor_id");
        Item item = new Item(String.valueOf(rs.getLong("id")), type, rs.getString("title"),
                new OrganizationRef(String.valueOf(rs.getLong("organization_id")), rs.getString("org_code"), rs.getString("org_name")),
                type == NotificationType.OVERDUE || actorId == null ? null : new UserRef(String.valueOf(actorId), rs.getString("actor_name")),
                occurredAt, rs.getObject("read_at") != null,
                link(rs.getString("request_type"), rs.getLong("request_id"), rs.getLong("organization_id"),
                        (Long) rs.getObject("submission_id"), (Long) rs.getObject("review_item_id")));
        return new Row(occurredAt.atZoneSameInstant(KST).toLocalDate(), item);
    }

    /** 연간 요청은 연간 계획 관리, 월간 요청은 월간 시트 관리 화면으로 보낸다(FR-NOTI-A-005). */
    static Link link(String requestType, long requestId, long organizationId, Long submissionId, Long reviewItemId) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("requestId", String.valueOf(requestId));
        query.put("organizationId", String.valueOf(organizationId));
        if (submissionId != null) {
            query.put("submissionId", String.valueOf(submissionId));
        }
        if (reviewItemId != null) {
            query.put("reviewItemId", String.valueOf(reviewItemId));
        }
        return new Link("ANNUAL_PLAN".equals(requestType) ? "/planning/annual-plan" : "/planning/gap", query);
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private record Row(LocalDate date, Item item) {
    }
}
