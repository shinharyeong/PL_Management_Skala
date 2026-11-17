package io.skala.backend.workspace;

import static io.skala.backend.submission.ApiData.*;
import io.skala.backend.common.ApiException;
import io.skala.backend.security.Actor;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 업무 사실에서 알림을 만들고 사용자별 읽음 상태를 영속한다. 외부 메시지를 발송하지 않는다. */
@Service
public class NotificationService {
    private final JdbcClient jdbc;

    /** 조회 의존성을 연결한다. Args: jdbc DB. Returns: 없음. Raises: 없음. Side Effects: 없음. */
    public NotificationService(JdbcClient jdbc) { this.jdbc = jdbc; }

    /** 기획자 알림을 날짜 역순과 유형별 요약으로 조회한다.
     * Args: actor 인증 사용자, query type/unreadOnly/cursor/limit.
     * Returns: 알림 페이지와 전체 요약. Raises: 권한·필터 오류. Side Effects: DB 읽기만 수행한다.
     */
    public Map<String,Object> list(Actor actor, Map<String,String> query) {
        requirePlanner(actor);
        if (!Set.of("type", "unreadOnly", "cursor", "limit").containsAll(query.keySet())) throw invalid();
        String type = query.getOrDefault("type", "ALL"), unread = query.getOrDefault("unreadOnly", "false");
        if (!Set.of("ALL", "SUBMISSION", "REPLY", "OVERDUE").contains(type) || !Set.of("true", "false").contains(unread)) throw invalid();
        int limit;
        try { limit = Integer.parseInt(query.getOrDefault("limit", "50")); } catch (NumberFormatException e) { throw invalid(); }
        if (limit < 1 || limit > 200) throw invalid();
        var all = events();
        var read = new HashSet<>(jdbc.sql("SELECT event_id FROM notification_read WHERE user_id=?").param(Long.parseLong(actor.id())).query(String.class).list());
        all.forEach(event -> event.put("read", read.contains(event.get("id"))));
        all.sort(Comparator.comparing((Map<String,Object> event) -> event.get("occurredAt").toString()).reversed().thenComparing(event -> event.get("id").toString()));
        var counts = new LinkedHashMap<String,Long>();
        for (String kind : List.of("ALL", "SUBMISSION", "REPLY", "OVERDUE")) counts.put(kind, all.stream().filter(event -> "ALL".equals(kind) || kind.equals(event.get("type"))).count());
        var summary = object("unreadCount", all.stream().filter(event -> !Boolean.TRUE.equals(event.get("read"))).count(),
                "replyPendingCount", all.stream().filter(event -> "REPLY".equals(event.get("type")) && Boolean.TRUE.equals(event.get("pending"))).count(),
                "overdueOrganizationCount", all.stream().filter(event -> "OVERDUE".equals(event.get("type"))).map(event -> event.get("organizationId")).distinct().count());
        var filtered = all.stream().filter(event -> "ALL".equals(type) || type.equals(event.get("type"))).filter(event -> !"true".equals(unread) || !Boolean.TRUE.equals(event.get("read"))).toList();
        String scope = actor.id() + ":notifications:" + type + ":" + unread;
        int start = Math.min(offset(query.get("cursor"), scope), filtered.size()), end = Math.min(start + limit, filtered.size());
        return object("items", filtered.subList(start, end), "nextCursor", end < filtered.size() ? cursor(end, scope) : null, "summary", summary, "counts", counts);
    }

    /** 현재 접근 가능한 알림의 읽음만 기록한다.
     * Args: actor 기획자, eventIds 명시한 이벤트 ID(최대 1000).
     * Returns: 처리 후 요약. Raises: 권한·잘못된 이벤트 오류. Side Effects: 읽음 행을 추가하며 기존 읽음 시각은 보존한다.
     */
    @Transactional
    public Map<String,Object> read(Actor actor, List<String> eventIds) {
        requirePlanner(actor);
        if (eventIds == null || eventIds.isEmpty() || eventIds.size() > 1000 || eventIds.stream().anyMatch(Objects::isNull)) throw invalid();
        var known = events().stream().map(event -> event.get("id").toString()).collect(java.util.stream.Collectors.toSet());
        if (!known.containsAll(eventIds)) throw invalid();
        jdbc.sql("SELECT id FROM app_user WHERE id=? FOR UPDATE").param(Long.parseLong(actor.id())).query(Long.class).single();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        for (String eventId : new LinkedHashSet<>(eventIds)) {
            jdbc.sql("INSERT INTO notification_read(user_id,event_id,read_at) SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM notification_read WHERE user_id=? AND event_id=?)")
                    .params(Long.parseLong(actor.id()), eventId, now, Long.parseLong(actor.id()), eventId).update();
        }
        return list(actor, Map.of("limit", "1"));
    }

    /** 제출·명시적 요청의 실제 소명 회신·미완료 마감에서 안정된 이벤트 식별자를 만든다.
     * Args: 없음. Returns: 수정 가능한 알림 목록. Raises: DB 조회 오류. Side Effects: DB 읽기.
     * 최초 제출에 함께 낸 소명과 이어받은 소명은 제출 알림에서 확인하며 별도 회신으로 중복 집계하지 않는다.
     */
    private List<Map<String,Object>> events() {
        var events = new ArrayList<Map<String,Object>>();
        for (var row : jdbc.sql("""
                SELECT s.id,s.request_id,s.organization_id,s.submitted_at,s.status,u.name AS actor_name,
                       o.name AS organization_name,o.code,p.year,r.month,r.type
                FROM submission s JOIN submission_request r ON r.id=s.request_id JOIN annual_plan p ON p.id=r.annual_plan_id
                JOIN organization o ON o.id=s.organization_id JOIN app_user u ON u.id=s.submitted_by
                WHERE s.status <> 'DRAFT' AND o.active=TRUE ORDER BY s.submitted_at DESC,s.id DESC
                """).query().listOfRows()) {
            String title = row.get("year") + "년 " + ("ANNUAL_PLAN".equals(row.get("type")) ? "연간 계획" : row.get("month") + "월 실적·추정") + " 제출";
            events.add(event("submission:" + row.get("id"), "SUBMISSION", row, title, instant(row.get("submitted_at")), "/submissions/" + row.get("id") + "?from=" + ("ANNUAL_PLAN".equals(row.get("type")) ? "requests" : "gap"), "PENDING".equals(row.get("status"))));
        }
        for (var row : jdbc.sql("""
                SELECT e.id,e.review_item_id,e.answered_at,e.verdict,u.name AS actor_name,i.month,i.workflow_state,s.organization_id,s.request_id,s.status AS submission_status,
                       o.name AS organization_name,o.code,ri.name AS item_name
                FROM review_explanation e JOIN review_item i ON i.id=e.review_item_id AND i.current_round=e.round AND i.active=TRUE
                JOIN submission s ON s.id=i.submission_id JOIN organization o ON o.id=s.organization_id
                JOIN report_item ri ON ri.id=i.report_item_id JOIN app_user u ON u.id=e.answered_by
                WHERE e.answer IS NOT NULL AND e.requested_by IS NOT NULL AND e.inherited_from_id IS NULL
                  AND s.status <> 'DRAFT' AND o.active=TRUE
                """).query().listOfRows()) {
            boolean pending = "PENDING".equals(row.get("submission_status")) && "PENDING".equals(row.get("verdict")) && !"EXCLUDED".equals(row.get("workflow_state"));
            events.add(event("reply:" + row.get("id") + ":" + instant(row.get("answered_at")), "REPLY", row, row.get("month") + "월 " + row.get("item_name") + " 소명 회신 도착", instant(row.get("answered_at")), "/planning/gap?reviewItemId=" + row.get("review_item_id") + "&requestId=" + row.get("request_id") + "&organizationId=" + row.get("organization_id"), pending));
        }
        for (var row : jdbc.sql("""
                SELECT r.id,r.due_at,t.organization_id,o.name AS organization_name,o.code,p.year,r.month,r.type
                FROM submission_request r JOIN annual_plan p ON p.id=r.annual_plan_id
                JOIN request_target t ON t.request_id=r.id JOIN organization o ON o.id=t.organization_id
                WHERE r.status='OPEN' AND r.due_at < ? AND t.excluded=FALSE AND t.selected=TRUE AND o.active=TRUE
                AND NOT EXISTS (SELECT 1 FROM submission s WHERE s.request_id=r.id AND s.organization_id=t.organization_id AND s.status IN ('PENDING','APPROVED'))
                """).param(OffsetDateTime.now(ZoneOffset.UTC)).query().listOfRows()) {
            String title = row.get("year") + "년 " + ("ANNUAL_PLAN".equals(row.get("type")) ? "연간 계획" : row.get("month") + "월 시트") + " 제출 기한 경과";
            events.add(event("overdue:" + row.get("id") + ":" + row.get("organization_id") + ":" + instant(row.get("due_at")), "OVERDUE", row, title, instant(row.get("due_at")), ("ANNUAL_PLAN".equals(row.get("type")) ? "/planning/annual-plan" : "/planning/gap") + "?requestId=" + row.get("id"), true));
        }
        return events;
    }

    /** 알림 공통 DTO를 만든다. Args: id/type 식별, row 출처, title 제목, time 시각, link 내부 경로, pending 미처리. Returns: DTO. Raises: 없음. Side Effects: 없음. */
    private Map<String,Object> event(String id, String type, Map<String,Object> row, String title, String time, String link, boolean pending) {
        return object("id", id, "type", type, "title", title, "occurredAt", time, "organizationId", row.get("organization_id").toString(), "organizationName", row.get("organization_name"), "organizationCode", row.get("code"), "actorName", row.get("actor_name"), "href", link, "pending", pending);
    }

    /** 기획 권한을 확인한다. Args: actor 사용자. Returns: 없음. Raises: 403. Side Effects: 없음. */
    private void requirePlanner(Actor actor) { if (!"PLANNER".equals(actor.role())) throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "경영기획팀만 알림을 조회할 수 있습니다."); }
    /** 공개 입력 오류를 만든다. Args: 없음. Returns: 오류. Raises: 없음. Side Effects: 없음. */
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "알림 조회 조건 또는 대상이 올바르지 않습니다."); }
}
