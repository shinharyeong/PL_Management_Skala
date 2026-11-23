package io.skala.backend.domain.dashboard.service;

import io.skala.backend.domain.dashboard.dto.HomeStatusResponse;
import io.skala.backend.domain.dashboard.dto.HomeStatusResponse.Step;
import io.skala.backend.domain.dashboard.dto.HomeTodoResponse;
import io.skala.backend.domain.master.dto.MasterResponse;
import io.skala.backend.domain.master.service.MasterLabels;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HOME-01 마감 현황, HOME-02 오늘 할 일. 경영기획팀 전용, 조회만 한다.
 * 조직의 제출 여부는 요청 진행 현황(REQ-04)과 같이 최신 제출본(임시 저장 제외)이 검토 대기·승인이면 제출로 본다.
 */
@Service
@RequiredArgsConstructor
public class HomeQueryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Set<String> SUBMITTED = Set.of("PENDING", "APPROVED");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(readOnly = true)
    public HomeStatusResponse status(AuthenticatedUser actor, Integer year, Integer month) {
        actor.requireRole(UserRole.PLANNER);
        int y = year != null ? year : today().getYear();
        int m = requireMonth(month);
        Request request = request("MONTHLY_ACTUAL", y, m);
        if (request == null) {
            return new HomeStatusResponse("NONE", null, List.of(), null, null, List.of(
                    new Step("REQUESTED", "제출 요청", 0, 1, null), new Step("SUBMITTED", "조직 제출", 0, 0, null),
                    new Step("GAP_REVIEW", "Gap 검토", 0, 0, null), new Step("EXPLAINED", "소명 완료", 0, 0, null),
                    new Step("MASTER", "master 생성", 0, 1, null)));
        }
        List<Target> targets = targets(request.id());
        List<Long> submittedIds = targets.stream().filter(Target::submitted).map(Target::submissionId).toList();

        List<MasterResponse> masters = jdbc.query("""
                SELECT id, seq, created_at FROM master_version WHERE request_id = ? ORDER BY seq DESC""",
                (rs, i) -> new MasterResponse(String.valueOf(rs.getLong("id")), y, m, rs.getInt("seq"),
                        MasterLabels.monthly(m, rs.getInt("seq")), "CONFIRMED",
                        rs.getObject("created_at", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC)),
                request.id());
        MasterResponse latest = masters.isEmpty() ? null : masters.getFirst();
        boolean anyApproved = count("SELECT count(*) FROM submission WHERE request_id = ? AND status = 'APPROVED'", request.id()) > 0;
        String dataStatus = latest != null ? "CONFIRMED" : anyApproved ? "PROVISIONAL" : "NONE";

        long gapTotal = 0;
        long gapDone = 0;
        long explainedOrgs = 0;
        if (!submittedIds.isEmpty()) {
            String in = placeholders(submittedIds.size());
            // Gap 기준을 넘은 항목 중 경영기획팀이 처리(선정·소명 요청·제외)했거나 이미 승인된 제출본의 항목
            Map<String, Object> gap = jdbc.queryForMap("""
                    SELECT count(*) AS total,
                           count(*) FILTER (WHERE ri.workflow_state <> 'NEEDS_REVIEW' OR s.status = 'APPROVED') AS done
                      FROM review_item ri JOIN submission s ON s.id = ri.submission_id
                     WHERE ri.active AND ri.gap_exceeded AND ri.submission_id IN (""" + in + ")", submittedIds.toArray());
            gapTotal = ((Number) gap.get("total")).longValue();
            gapDone = ((Number) gap.get("done")).longValue();
            // 제출했고, 요청받은 소명 중 답하지 않은 것이 없는 조직
            long waiting = count("""
                    SELECT count(DISTINCT ri.submission_id) FROM review_explanation e
                      JOIN review_item ri ON ri.id = e.review_item_id AND ri.active AND e.round = ri.current_round
                     WHERE e.request_note IS NOT NULL AND e.answer IS NULL AND ri.submission_id IN (""" + in + ")",
                    submittedIds.toArray());
            explainedOrgs = submittedIds.size() - waiting;
        }

        OffsetDateTime dueAt = request.dueAt();
        Long dDay = dueAt == null ? null : ChronoUnit.DAYS.between(today(), dueAt.atZoneSameInstant(KST).toLocalDate());
        return new HomeStatusResponse(dataStatus, latest, masters, dueAt, dDay, List.of(
                new Step("REQUESTED", "제출 요청", 1, 1, kstDate(request.requestedAt())),
                new Step("SUBMITTED", "조직 제출", submittedIds.size(), targets.size(), null),
                new Step("GAP_REVIEW", "Gap 검토", gapDone, gapTotal, null),
                new Step("EXPLAINED", "소명 완료", explainedOrgs, targets.size(), null),
                new Step("MASTER", "master 생성", latest == null ? 0 : 1, 1,
                        latest == null ? null : kstDate(latest.createdAt()))));
    }

    /** tab=ANNUAL이면 연간 계획, MONTHLY면 그 달 월간 시트(월이 없으면 이번 달) */
    @Transactional(readOnly = true)
    public HomeTodoResponse todos(AuthenticatedUser actor, String tab, Integer year, Integer month) {
        actor.requireRole(UserRole.PLANNER);
        boolean annual = "ANNUAL".equals(tab);
        if (!annual && !"MONTHLY".equals(tab)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "tab은 ANNUAL 또는 MONTHLY입니다.");
        }
        int y = year != null ? year : today().getYear();
        Integer m = annual ? null : month != null ? requireMonth(month) : today().getMonthValue();
        String name = annual ? "연간 계획" : "월간 시트";
        Request request = annual ? request("ANNUAL_PLAN", y, null) : request("MONTHLY_ACTUAL", y, m);

        List<HomeTodoResponse.Item> items = new ArrayList<>();
        Map<String, String> period = new LinkedHashMap<>();
        period.put("year", String.valueOf(y));
        if (m != null) {
            period.put("month", String.valueOf(m));
        }
        items.add(new HomeTodoResponse.Item("REQUEST_NEEDED", name + " 제출 요청", request == null ? 1 : 0,
                new HomeTodoResponse.Link(annual ? "/planning/annual-plan/new" : "/planning/requests", period)));
        long notSubmitted = 0;
        long reviewNeeded = 0;
        long explanationReview = 0;
        Map<String, String> byRequest = Map.of();
        if (request != null) {
            List<Target> targets = targets(request.id());
            notSubmitted = targets.stream().filter(t -> !t.submitted()).count();
            List<Long> pending = targets.stream().filter(t -> "PENDING".equals(t.status())).map(Target::submissionId).toList();
            reviewNeeded = pending.size();
            if (!pending.isEmpty()) {
                // 경영기획팀이 다시 요청한 소명에 조직이 답했고 아직 판단하지 않은 조직
                explanationReview = count("""
                        SELECT count(DISTINCT ri.submission_id) FROM review_explanation e
                          JOIN review_item ri ON ri.id = e.review_item_id AND ri.active AND e.round = ri.current_round
                         WHERE e.round > 1 AND e.answer IS NOT NULL AND e.verdict = 'PENDING'
                           AND ri.submission_id IN (""" + placeholders(pending.size()) + ")", pending.toArray());
            }
            byRequest = Map.of("requestId", String.valueOf(request.id()));
        }
        HomeTodoResponse.Link manage = new HomeTodoResponse.Link(annual ? "/planning/annual-plan" : "/planning/gap", byRequest);
        items.add(new HomeTodoResponse.Item("NOT_SUBMITTED", name + " 미제출", notSubmitted, manage));
        items.add(new HomeTodoResponse.Item("REVIEW_NEEDED", name + " 검토 필요 조직", reviewNeeded, manage));
        items.add(new HomeTodoResponse.Item("EXPLANATION_REVIEW_NEEDED", name + " 소명 검토 필요 조직", explanationReview, manage));
        return new HomeTodoResponse(items);
    }

    private Request request(String type, int year, Integer month) {
        return jdbc.query("""
                SELECT r.id, r.due_at, r.requested_at FROM submission_request r
                  JOIN annual_plan p ON p.id = r.annual_plan_id
                 WHERE p.year = ? AND r.type = ? AND r.month IS NOT DISTINCT FROM ?""",
                (rs, i) -> new Request(rs.getLong("id"), utc(rs.getObject("due_at", OffsetDateTime.class)),
                        utc(rs.getObject("requested_at", OffsetDateTime.class))), year, type, month)
                .stream().findFirst().orElse(null);
    }

    /** 요청을 보낸 대상 조직(selected, 제외 안 됨)과 최신 제출본 */
    private List<Target> targets(long requestId) {
        return jdbc.query("""
                SELECT t.organization_id, ls.id AS submission_id, ls.status
                  FROM request_target t
                  LEFT JOIN LATERAL (SELECT s.id, s.status FROM submission s
                                      WHERE s.request_id = t.request_id AND s.organization_id = t.organization_id
                                        AND s.status <> 'DRAFT' ORDER BY s.seq DESC LIMIT 1) ls ON true
                 WHERE t.request_id = ? AND t.selected AND NOT t.excluded""",
                (rs, i) -> new Target(rs.getLong("organization_id"), (Long) rs.getObject("submission_id"), rs.getString("status")),
                requestId);
    }

    private static int requireMonth(Integer month) {
        if (month == null || month < 1 || month > 12) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "month는 1~12입니다.");
        }
        return month;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(KST));
    }

    private static LocalDate kstDate(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(KST).toLocalDate();
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static String placeholders(int n) {
        return java.util.Collections.nCopies(n, "?").stream().collect(Collectors.joining(","));
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private record Request(long id, OffsetDateTime dueAt, OffsetDateTime requestedAt) {
    }

    private record Target(long organizationId, Long submissionId, String status) {
        boolean submitted() {
            return status != null && SUBMITTED.contains(status);
        }
    }
}
