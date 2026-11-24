package io.skala.backend.domain.request.service;

import io.skala.backend.domain.master.entity.AnnualPlan;
import io.skala.backend.domain.master.repository.AnnualPlanRepository;
import io.skala.backend.domain.request.dto.CreateSubmissionRequestRequest;
import io.skala.backend.domain.request.dto.RequestDetailResponse;
import io.skala.backend.domain.request.dto.SubmissionRequestResponse;
import io.skala.backend.domain.request.entity.RequestTarget;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.exception.RequestErrorCode;
import io.skala.backend.domain.request.repository.RequestTargetRepository;
import io.skala.backend.domain.request.repository.SubmissionRequestRepository;
import io.skala.backend.domain.request.value.RequestStatus;
import io.skala.backend.domain.request.value.RequestType;
import io.skala.backend.domain.user.dto.OrganizationResponse;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.entity.Organization;
import io.skala.backend.domain.user.repository.OrganizationRepository;
import io.skala.backend.domain.user.service.OrganizationQueryService;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.response.PageResponse;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.util.OffsetCursor;
import io.skala.backend.global.util.StateToken;
import jakarta.persistence.EntityManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 연간 계획·월간 시트 제출 요청(REQ-02~04). 경영기획팀 전용. */
@Service
@RequiredArgsConstructor
public class SubmissionRequestService {

    private static final String SUMMARY_SQL = """
            SELECT r.id, r.type, p.year, r.month, r.status, r.message, r.due_at, r.requested_at,
                   u.id AS user_id, u.name AS user_name,
                   (SELECT count(*) FROM request_target t
                     WHERE t.request_id = r.id AND t.selected AND NOT t.excluded) AS target_count,
                   (SELECT count(*) FROM request_target t
                     WHERE t.request_id = r.id AND t.selected AND NOT t.excluded
                       AND (SELECT s.status FROM submission s
                             WHERE s.request_id = t.request_id AND s.organization_id = t.organization_id AND s.status <> 'DRAFT'
                             ORDER BY s.seq DESC LIMIT 1) IN ('PENDING', 'APPROVED')) AS submitted_count
              FROM submission_request r
              JOIN annual_plan p ON p.id = r.annual_plan_id
              JOIN app_user u ON u.id = r.requested_by
            """;

    private final SubmissionRequestRepository requestRepository;
    private final RequestTargetRepository targetRepository;
    private final AnnualPlanRepository annualPlanRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationQueryService organizationQueryService;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** REQ-02: 사용 중인 모든 조직을 대상으로 고정하고, 고른 조직만 selected=true로 요청을 보낸다. */
    @Transactional
    public SubmissionRequestResponse create(AuthenticatedUser actor, CreateSubmissionRequestRequest input) {
        actor.requireRole(UserRole.PLANNER);
        boolean annual = input.type() == RequestType.ANNUAL_PLAN;
        if (annual != (input.month() == null)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "연간 요청은 월이 없고 월간 요청은 월이 필수입니다.");
        }
        String periodKey = SubmissionRequest.periodKey(input.year(), input.type(), input.month());
        if (requestRepository.existsByPeriodKey(periodKey)) {
            throw RequestErrorCode.REQUEST_ALREADY_EXISTS.exception();
        }
        AnnualPlan plan = annualPlanRepository.findByYear(input.year())
                .orElseGet(() -> annualPlanRepository.save(AnnualPlan.collecting(input.year())));
        if (!annual && !plan.isConfirmed()) {
            throw RequestErrorCode.ANNUAL_PLAN_NOT_CONFIRMED.exception();
        }
        List<Organization> active = organizationRepository.findAllByActiveTrueOrderByCodeAsc();
        if (active.isEmpty()) {
            throw RequestErrorCode.REQUEST_TARGETS_EMPTY.exception();
        }
        Set<Long> activeIds = new HashSet<>(active.stream().map(Organization::getId).toList());
        Set<Long> selected = new HashSet<>(input.organizationIds());
        if (!activeIds.containsAll(selected)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "요청할 수 없는 조직이 포함되어 있습니다.");
        }
        AppUser requester = entityManager.getReference(AppUser.class, actor.id());
        SubmissionRequest request = requestRepository.saveAndFlush(SubmissionRequest.open(plan, input.type(), input.month(),
                input.message(), input.dueAt(), requester, OffsetDateTime.now(clock)));
        targetRepository.saveAll(active.stream()
                .map(org -> RequestTarget.of(request.getId(), org.getId(), selected.contains(org.getId())))
                .toList());
        targetRepository.flush();
        if (!annual) {
            fillConfirmedPlan(request.getId(), plan.getId());
        }
        return summary(request.getId());
    }

    /**
     * 월간 요청의 대상 조직마다 '확정 연간 계획' 제출본을 넣는다(실적 대 계획 Gap·1월 비교 기준).
     * 요청을 보내는 시점의 최신 연간 master 기준이며, 나중에 연간 master가 다시 만들어져도 이미 보낸 요청은 그대로 둔다.
     * 연간 계획에 없던 조직은 null(계획 없음)로 남는다.
     */
    private void fillConfirmedPlan(Long requestId, Long annualPlanId) {
        jdbc.update("""
                UPDATE request_target t SET confirmed_plan_submission_id = ams.submission_id
                  FROM annual_master_source ams
                 WHERE t.request_id = ? AND ams.organization_id = t.organization_id
                   AND ams.master_id = (SELECT am.id FROM annual_master am
                                          JOIN submission_request r ON r.id = am.request_id
                                         WHERE r.annual_plan_id = ? AND r.type = 'ANNUAL_PLAN'
                                         ORDER BY am.seq DESC LIMIT 1)""", requestId, annualPlanId);
    }

    /** REQ-03 */
    @Transactional(readOnly = true)
    public PageResponse<SubmissionRequestResponse> list(AuthenticatedUser actor, RequestType type, Integer year,
                                                        RequestStatus status, String cursor, Integer limit) {
        actor.requireRole(UserRole.PLANNER);
        String scope = "req|" + type + "|" + year + "|" + status;
        int size = OffsetCursor.limit(limit);
        int offset = OffsetCursor.decode(cursor, scope);
        StringBuilder sql = new StringBuilder(SUMMARY_SQL).append(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (type != null) {
            sql.append(" AND r.type = ?");
            args.add(type.name());
        }
        if (year != null) {
            sql.append(" AND p.year = ?");
            args.add(year);
        }
        if (status != null) {
            sql.append(" AND r.status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY r.requested_at DESC, r.id DESC LIMIT ? OFFSET ?");
        args.add(size + 1);
        args.add(offset);
        List<SubmissionRequestResponse> rows = jdbc.query(sql.toString(), (rs, i) -> toSummary(rs), args.toArray());
        return PageResponse.of(rows.stream().limit(size).toList(), OffsetCursor.next(rows.size() > size, offset, size, scope));
    }

    /** REQ-04 */
    @Transactional(readOnly = true)
    public RequestDetailResponse detail(AuthenticatedUser actor, Long requestId) {
        actor.requireRole(UserRole.PLANNER);
        SubmissionRequestResponse request = summary(requestId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<RawTarget> raw = jdbc.query("""
                SELECT o.id, o.code, o.name, t.selected, ls.id AS sub_id, ls.status AS sub_status, ls.seq AS sub_seq
                  FROM request_target t
                  JOIN organization o ON o.id = t.organization_id
                  LEFT JOIN LATERAL (
                       SELECT s.id, s.status, s.seq FROM submission s
                        WHERE s.request_id = t.request_id AND s.organization_id = t.organization_id AND s.status <> 'DRAFT'
                        ORDER BY s.seq DESC LIMIT 1) ls ON true
                 WHERE t.request_id = ? AND NOT t.excluded
                 ORDER BY t.selected DESC, o.code""",
                (rs, i) -> new RawTarget(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getBoolean("selected"),
                        (Long) rs.getObject("sub_id"), rs.getString("sub_status"), (Integer) rs.getObject("sub_seq")),
                requestId);
        Map<Long, List<OrganizationResponse.Manager>> managers = organizationQueryService.managersOf(
                raw.stream().map(RawTarget::orgId).toList());
        List<RequestDetailResponse.Target> targets = raw.stream().map(t -> {
            RequestDetailResponse.LatestSubmission latest = t.submissionId() == null ? null
                    : new RequestDetailResponse.LatestSubmission(String.valueOf(t.submissionId()), t.submissionStatus(),
                    "REJECTED".equals(t.submissionStatus()) ? "RESUBMIT_REQUESTED" : t.submissionStatus(),
                    label(request, t.seq()));
            // 최신 제출본이 반려(재제출 요청)면 아직 제출하지 않은 것으로 보고 기한 기준으로 표시한다.
            boolean submitted = latest != null && !"REJECTED".equals(t.submissionStatus());
            String display = submitted ? "SUBMITTED"
                    : request.dueAt() != null && request.dueAt().isBefore(now) ? "OVERDUE" : "WAITING";
            return new RequestDetailResponse.Target(
                    new OrganizationResponse(String.valueOf(t.orgId()), t.code(), t.name(), managers.getOrDefault(t.orgId(), List.of())),
                    t.selected(), request.requestedAt(), request.dueAt(), display, latest);
        }).toList();
        return new RequestDetailResponse(request, targets);
    }

    SubmissionRequestResponse summary(Long requestId) {
        return jdbc.query(SUMMARY_SQL + " WHERE r.id = ?", (rs, i) -> toSummary(rs), requestId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** 조직 제출본 버전 이름(YYYY-MM-NN·YYYY-PLAN-NN, docs/api/api-spec.md 0절) */
    static String label(SubmissionRequestResponse request, Integer seq) {
        if (seq == null) {
            return null;
        }
        return request.type() == RequestType.ANNUAL_PLAN
                ? "%d-PLAN-%02d".formatted(request.year(), seq)
                : "%d-%02d-%02d".formatted(request.year(), request.month(), seq);
    }

    private static SubmissionRequestResponse toSummary(ResultSet rs) throws SQLException {
        RequestType type = RequestType.valueOf(rs.getString("type"));
        int year = rs.getInt("year");
        Integer month = (Integer) rs.getObject("month");
        RequestStatus status = RequestStatus.valueOf(rs.getString("status"));
        OffsetDateTime dueAt = utc(rs.getObject("due_at", OffsetDateTime.class));
        String message = rs.getString("message");
        long id = rs.getLong("id");
        String title = type == RequestType.ANNUAL_PLAN ? year + "년 연간 계획" : year + "년 " + month + "월 월간 시트";
        return new SubmissionRequestResponse(String.valueOf(id), type, year, month, title, status, message, dueAt,
                utc(rs.getObject("requested_at", OffsetDateTime.class)),
                new SubmissionRequestResponse.UserRef(String.valueOf(rs.getLong("user_id")), rs.getString("user_name")),
                rs.getLong("submitted_count"), rs.getLong("target_count"),
                StateToken.of(id, status, dueAt, message));
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private record RawTarget(Long orgId, String code, String name, boolean selected, Long submissionId,
                             String submissionStatus, Integer seq) {
    }
}
