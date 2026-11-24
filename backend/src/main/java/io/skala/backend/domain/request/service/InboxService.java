package io.skala.backend.domain.request.service;

import io.skala.backend.domain.request.dto.InboxResponse;
import io.skala.backend.domain.user.dto.OrganizationResponse;
import io.skala.backend.domain.user.entity.Organization;
import io.skala.backend.domain.user.repository.OrganizationRepository;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INBOX-01 조직 담당자 요청함.
 * - 제출 요청: 진행 중인 요청 중 아직 제출(검토 대기·승인)하지 않았거나 재제출 요청을 받은 것
 * - 소명 요청: 검토 대기 제출본에 경영기획팀이 소명을 요청했고 아직 답하지 않은 것
 */
@Service
@RequiredArgsConstructor
public class InboxService {

    private final OrganizationRepository organizationRepository;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public InboxResponse inbox(AuthenticatedUser actor) {
        actor.requireRole(UserRole.ORG_MANAGER);
        Organization org = organizationRepository.findById(actor.organizationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN));
        List<InboxResponse.Item> items = new ArrayList<>();
        jdbc.query("""
                SELECT r.id, r.type, p.year, r.month, r.due_at, ls.id AS sub_id, ls.status AS sub_status
                  FROM submission_request r
                  JOIN annual_plan p ON p.id = r.annual_plan_id
                  JOIN request_target t ON t.request_id = r.id AND t.organization_id = ? AND t.selected AND NOT t.excluded
                  LEFT JOIN LATERAL (
                       SELECT s.id, s.status FROM submission s
                        WHERE s.request_id = r.id AND s.organization_id = t.organization_id AND s.status <> 'DRAFT'
                        ORDER BY s.seq DESC LIMIT 1) ls ON true
                 WHERE r.status = 'OPEN' AND (ls.status IS NULL OR ls.status = 'REJECTED')""",
                rs -> {
                    boolean resubmit = "REJECTED".equals(rs.getString("sub_status"));
                    long requestId = rs.getLong("id");
                    items.add(new InboxResponse.Item("SUBMISSION",
                            title(rs.getString("type"), rs.getInt("year"), (Integer) rs.getObject("month"))
                                    + (resubmit ? " 재제출" : " 제출"),
                            String.valueOf(requestId), resubmit ? String.valueOf(rs.getLong("sub_id")) : null,
                            utc(rs.getObject("due_at", OffsetDateTime.class)), "START_SUBMISSION",
                            new InboxResponse.Link(resubmit ? "/submissions/" + rs.getLong("sub_id") : "/requests/" + requestId + "/upload")));
                }, org.getId());
        jdbc.query("""
                SELECT s.id AS sub_id, r.id, r.type, p.year, r.month, min(e.due_at) AS due_at
                  FROM review_explanation e
                  JOIN review_item ri ON ri.id = e.review_item_id AND ri.active AND e.round = ri.current_round
                  JOIN submission s ON s.id = ri.submission_id
                  JOIN submission_request r ON r.id = s.request_id
                  JOIN annual_plan p ON p.id = r.annual_plan_id
                 WHERE s.organization_id = ? AND s.status = 'PENDING' AND e.request_note IS NOT NULL AND e.answer IS NULL
                 GROUP BY s.id, r.id, r.type, p.year, r.month""",
                rs -> {
                    long submissionId = rs.getLong("sub_id");
                    items.add(new InboxResponse.Item("EXPLANATION",
                            title(rs.getString("type"), rs.getInt("year"), (Integer) rs.getObject("month")) + " 소명 요청",
                            String.valueOf(rs.getLong("id")), String.valueOf(submissionId),
                            utc(rs.getObject("due_at", OffsetDateTime.class)), "START_EXPLANATION",
                            new InboxResponse.Link("/submissions/" + submissionId)));
                }, org.getId());
        items.sort(Comparator.comparing(InboxResponse.Item::dueAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return new InboxResponse(new OrganizationResponse(String.valueOf(org.getId()), org.getCode(), org.getName(), null),
                items.size(), items);
    }

    private static String title(String type, int year, Integer month) {
        return "ANNUAL_PLAN".equals(type) ? year + "년 연간 계획" : month + "월 실적·추정";
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
