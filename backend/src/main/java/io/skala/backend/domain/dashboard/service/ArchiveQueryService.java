package io.skala.backend.domain.dashboard.service;

import io.skala.backend.domain.dashboard.dto.ChangeHistoryResponse;
import io.skala.backend.domain.dashboard.dto.OrganizationVersionsResponse;
import io.skala.backend.domain.dashboard.dto.OrganizationVersionsResponse.FileRef;
import io.skala.backend.domain.dashboard.dto.OrganizationVersionsResponse.History;
import io.skala.backend.domain.dashboard.dto.OrganizationVersionsResponse.MonthFinal;
import io.skala.backend.domain.dashboard.dto.OrganizationVersionsResponse.OrganizationRef;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
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

/** ARCH-02 조직별 월별 버전·이력, ARCH-03 변경 이력. 경영기획팀 전용, 조회만 한다. */
@Service
@RequiredArgsConstructor
public class ArchiveQueryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(readOnly = true)
    public OrganizationVersionsResponse versions(AuthenticatedUser actor, Long organizationId, Integer year, Integer month) {
        actor.requireRole(UserRole.PLANNER);
        if (month != null && (month < 1 || month > 12)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "month는 1~12입니다.");
        }
        OrganizationRef organization = jdbc.query("SELECT id, code, name FROM organization WHERE id = ?",
                        (rs, i) -> new OrganizationRef(String.valueOf(rs.getLong("id")), rs.getString("code"), rs.getString("name")),
                        organizationId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        int y = year != null ? year : LocalDate.now(clock.withZone(KST)).getYear();

        List<MonthFinal> months = jdbc.query("""
                SELECT r.month,
                       (SELECT max(s.approved_seq) FROM submission s
                         WHERE s.request_id = r.id AND s.organization_id = ? AND s.status = 'APPROVED') AS approved_seq
                  FROM submission_request r JOIN annual_plan p ON p.id = r.annual_plan_id
                 WHERE p.year = ? AND r.type = 'MONTHLY_ACTUAL'
                 ORDER BY r.month""", (rs, i) -> {
            int m = rs.getInt("month");
            Integer approvedSeq = (Integer) rs.getObject("approved_seq");
            return new MonthFinal(m, approvedSeq == null ? null : "ver%d.%02d".formatted(m, approvedSeq));
        }, organizationId, y);

        List<History> history = month == null ? List.of() : jdbc.query("""
                SELECT s.id, s.seq, s.status, s.original_filename,
                       greatest(s.submitted_at, coalesce(s.reviewed_at, s.submitted_at)) AS last_modified_at
                  FROM submission s
                  JOIN submission_request r ON r.id = s.request_id
                  JOIN annual_plan p ON p.id = r.annual_plan_id
                 WHERE s.organization_id = ? AND p.year = ? AND r.type = 'MONTHLY_ACTUAL' AND r.month = ?
                   AND s.status <> 'DRAFT'
                 ORDER BY s.seq DESC""", (rs, i) -> new History(String.valueOf(rs.getLong("id")),
                "%d-%02d-%02d".formatted(y, month, rs.getInt("seq")), rs.getString("status"),
                rs.getObject("last_modified_at", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC),
                new FileRef(rs.getString("original_filename"), "/api/v1/submissions/" + rs.getLong("id") + "/file")),
                organizationId, y, month);
        return new OrganizationVersionsResponse(organization, months, history);
    }

    @Transactional(readOnly = true)
    public ChangeHistoryResponse changeHistory(AuthenticatedUser actor, Long submissionId) {
        actor.requireRole(UserRole.PLANNER);
        if (jdbc.queryForObject("SELECT count(*) FROM submission WHERE id = ?", Long.class, submissionId) == 0) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        List<Row> rows = jdbc.query("""
                SELECT e.round, e.answer, e.answered_at, s.submitted_at, rpt.code, rpt.name, ri.month, ri.scenario
                  FROM review_explanation e
                  JOIN review_item ri ON ri.id = e.review_item_id
                  JOIN report_item rpt ON rpt.id = ri.report_item_id
                  JOIN submission s ON s.id = ri.submission_id
                 WHERE ri.submission_id = ? AND e.answer IS NOT NULL
                 ORDER BY e.round > 1, e.answered_at, rpt.display_order, rpt.code, ri.month, ri.scenario""",
                (rs, i) -> new Row(rs.getInt("round"), rs.getString("answer"),
                        rs.getObject("answered_at", OffsetDateTime.class), rs.getObject("submitted_at", OffsetDateTime.class),
                        rs.getString("code"), rs.getString("name"), rs.getInt("month"), rs.getString("scenario")),
                submissionId);

        // 1회차는 제출 한 묶음, 재소명은 답한 날짜(KST)별 묶음
        Map<String, List<Row>> groups = new LinkedHashMap<>();
        for (Row row : rows) {
            String key = row.round() == 1 ? "EXPLAINED"
                    : "RE_EXPLAINED:" + row.answeredAt().atZoneSameInstant(KST).toLocalDate();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        List<ChangeHistoryResponse.DateGroup> dates = groups.entrySet().stream().map(entry -> {
            List<Row> group = entry.getValue();
            boolean first = entry.getKey().equals("EXPLAINED");
            OffsetDateTime changedAt = first && group.getFirst().submittedAt() != null ? group.getFirst().submittedAt()
                    : group.stream().map(Row::answeredAt).filter(java.util.Objects::nonNull).max(OffsetDateTime::compareTo).orElse(null);
            List<ChangeHistoryResponse.Item> items = new ArrayList<>();
            for (Row row : group) {
                items.add(new ChangeHistoryResponse.Item(items.size() + 1, row.code(), row.name(), row.month(), row.scenario(),
                        row.answer()));
            }
            return new ChangeHistoryResponse.DateGroup(changedAt == null ? null : changedAt.withOffsetSameInstant(ZoneOffset.UTC),
                    first ? "EXPLAINED" : "RE_EXPLAINED", items);
        }).toList();
        return new ChangeHistoryResponse(dates);
    }

    private record Row(int round, String answer, OffsetDateTime answeredAt, OffsetDateTime submittedAt, String code,
                       String name, int month, String scenario) {
    }
}
