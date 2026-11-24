package io.skala.backend.domain.dashboard.service;

import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 홈 손익(HOME-03~06)이 함께 쓰는 숫자 묶음을 읽는다.
 * <ul>
 *   <li>실적: 그 달 월간 요청의 master(masterId가 없으면 최신 master) 조직별 승인본.
 *       master가 없으면 조직별 최신 승인본으로 잠정 계산(PROVISIONAL), 승인본도 없으면 NONE</li>
 *   <li>계획: 월간 요청 때 고정한 확정 연간 계획(request_target.confirmed_plan_submission_id)의 계획 값</li>
 *   <li>전월 추정: 전월 요청의 최신 master(없으면 최신 승인본)에 적힌 이번 달 추정 값. 2월 이후만</li>
 *   <li>연간 탭: 연간 전체. 실적 = 고른 달 시트의 1월~그 달 실적 + 다음 달~12월 추정, 계획 = 1~12월 계획,
 *       전월 대비 = 전월 시트의 같은 연간 전망</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
class PnlSnapshotLoader {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final Clock clock;

    enum Period { MONTH, YEAR }

    record Org(long id, String code, String name) {
    }

    record Item(long id, String name, Long parentId, String kind, int order) {
    }

    /** 조직 → 보고 항목 → 금액(그 항목에 직접 적힌 값의 합) */
    record Values(Map<Long, Map<Long, BigDecimal>> byOrg) {

        static Values empty() {
            return new Values(Map.of());
        }

        Map<Long, BigDecimal> of(long orgId) {
            return byOrg.getOrDefault(orgId, Map.of());
        }

        Map<Long, BigDecimal> company() {
            Map<Long, BigDecimal> sum = new HashMap<>();
            byOrg.values().forEach(m -> m.forEach((item, amount) -> sum.merge(item, amount, BigDecimal::add)));
            return sum;
        }
    }

    record Snapshot(String dataStatus, Period period, int year, int month, List<Org> orgs, Map<Long, Item> items,
                    List<Long> sourceSubmissionIds, Values actual, Values plan, Values prevEstimate) {

        int fromMonth() {
            return period == Period.YEAR ? 1 : month;
        }
    }

    Snapshot load(String period, int year, Integer month, Long masterId) {
        Period p = parsePeriod(period);
        if (year < 2000 || year > 9999) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "year는 2000~9999입니다.");
        }
        if (month != null && (month < 1 || month > 12)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "month는 1~12입니다.");
        }
        int m = month != null ? month : p == Period.MONTH ? LocalDate.now(clock.withZone(KST)).getMonthValue()
                : latestApprovedMonth(year);
        Long requestId = requestId(year, m);
        if (masterId != null) {
            Long owner = jdbc.query("SELECT request_id FROM master_version WHERE id = ?",
                    (rs, i) -> rs.getLong(1), masterId).stream().findFirst().orElse(null);
            if (owner == null || !owner.equals(requestId)) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "이 달의 master가 아닙니다.");
            }
        }
        Map<Long, Item> items = items();
        Sources sources = requestId == null ? Sources.NONE : sources(requestId, masterId);
        if (sources.byOrg().isEmpty()) {
            return new Snapshot("NONE", p, year, m, List.of(), items, List.of(), Values.empty(), Values.empty(), Values.empty());
        }
        boolean yearly = p == Period.YEAR;
        Collection<Long> submissions = sources.byOrg().values();
        Values actual = yearly ? outlook(submissions, year, m) : values(submissions, "ACTUAL", year, m, m);

        Map<Long, Long> plans = new LinkedHashMap<>();
        jdbc.query("""
                SELECT organization_id, confirmed_plan_submission_id FROM request_target
                 WHERE request_id = ? AND confirmed_plan_submission_id IS NOT NULL""",
                rs -> {
                    plans.put(rs.getLong(1), rs.getLong(2));
                }, requestId);
        plans.keySet().retainAll(sources.byOrg().keySet());
        Values plan = yearly ? values(plans.values(), "PLAN", year, 1, 12) : values(plans.values(), "PLAN", year, m, m);

        Values prevEstimate = Values.empty();
        Long previousRequest = m > 1 ? requestId(year, m - 1) : null;
        if (previousRequest != null) {
            Map<Long, Long> previous = new LinkedHashMap<>(sources(previousRequest, null).byOrg());
            previous.keySet().retainAll(sources.byOrg().keySet());
            prevEstimate = yearly ? outlook(previous.values(), year, m - 1)
                    : values(previous.values(), "ESTIMATE", year, m, m);
        }
        return new Snapshot(sources.status(), p, year, m, orgs(sources.byOrg().keySet()), items,
                List.copyOf(submissions), actual, plan, prevEstimate);
    }

    private record Sources(String status, Map<Long, Long> byOrg) {
        static final Sources NONE = new Sources("NONE", Map.of());
    }

    /** 조직 → 제출본. 지정 master → 최신 master → 조직별 최신 승인본(잠정) 순 */
    private Sources sources(long requestId, Long masterId) {
        Long master = masterId != null ? masterId : jdbc.query(
                "SELECT id FROM master_version WHERE request_id = ? ORDER BY seq DESC LIMIT 1",
                (rs, i) -> rs.getLong(1), requestId).stream().findFirst().orElse(null);
        Map<Long, Long> byOrg = new LinkedHashMap<>();
        if (master != null) {
            jdbc.query("SELECT organization_id, submission_id FROM master_source WHERE master_id = ?",
                    rs -> {
                        byOrg.put(rs.getLong(1), rs.getLong(2));
                    }, master);
            return new Sources("CONFIRMED", byOrg);
        }
        jdbc.query("""
                SELECT t.organization_id, la.id FROM request_target t
                  JOIN LATERAL (SELECT s.id FROM submission s
                                 WHERE s.request_id = t.request_id AND s.organization_id = t.organization_id
                                   AND s.status = 'APPROVED' ORDER BY s.approved_seq DESC LIMIT 1) la ON true
                 WHERE t.request_id = ? AND t.selected AND NOT t.excluded""",
                rs -> {
                    byOrg.put(rs.getLong(1), rs.getLong(2));
                }, requestId);
        return byOrg.isEmpty() ? Sources.NONE : new Sources("PROVISIONAL", byOrg);
    }

    /** 그 달 시트의 연간 전망: 1월~그 달 실적 + 다음 달~12월 추정 */
    private Values outlook(Collection<Long> submissionIds, int year, int month) {
        Values actual = values(submissionIds, "ACTUAL", year, 1, month);
        if (month == 12) {
            return actual;
        }
        Map<Long, Map<Long, BigDecimal>> sum = new HashMap<>();
        for (Values part : List.of(actual, values(submissionIds, "ESTIMATE", year, month + 1, 12))) {
            part.byOrg().forEach((org, items) -> items.forEach((item, amount) ->
                    sum.computeIfAbsent(org, k -> new HashMap<>()).merge(item, amount, BigDecimal::add)));
        }
        return new Values(sum);
    }

    private Values values(Collection<Long> submissionIds, String scenario, int year, int fromMonth, int toMonth) {
        if (submissionIds.isEmpty()) {
            return Values.empty();
        }
        Map<Long, Map<Long, BigDecimal>> byOrg = new HashMap<>();
        Object[] args = new Object[submissionIds.size() + 4];
        int i = 0;
        for (Long id : submissionIds) {
            args[i++] = id;
        }
        args[i++] = scenario;
        args[i++] = year;
        args[i++] = fromMonth;
        args[i] = toMonth;
        jdbc.query("""
                SELECT s.organization_id, l.report_item_id, sum(v.amount) AS amount
                  FROM financial_value v
                  JOIN financial_line l ON l.id = v.line_id
                  JOIN submission s ON s.id = l.submission_id
                 WHERE l.submission_id IN (""" + placeholders(submissionIds.size()) + """
                ) AND v.scenario = ? AND v.year = ? AND v.month BETWEEN ? AND ?
                 GROUP BY s.organization_id, l.report_item_id""",
                rs -> {
                    byOrg.computeIfAbsent(rs.getLong(1), k -> new HashMap<>()).put(rs.getLong(2), rs.getBigDecimal(3));
                }, args);
        return new Values(byOrg);
    }

    private Map<Long, Item> items() {
        Map<Long, Item> items = new HashMap<>();
        jdbc.query("SELECT id, name, parent_id, kind, display_order FROM report_item", rs -> {
            long id = rs.getLong("id");
            items.put(id, new Item(id, rs.getString("name"), (Long) rs.getObject("parent_id"), rs.getString("kind"),
                    rs.getInt("display_order")));
        });
        return items;
    }

    private List<Org> orgs(Collection<Long> ids) {
        return jdbc.query("SELECT id, code, name FROM organization WHERE id IN (" + placeholders(ids.size()) + ") ORDER BY code",
                (rs, i) -> new Org(rs.getLong("id"), rs.getString("code"), rs.getString("name")), ids.toArray());
    }

    private Long requestId(int year, int month) {
        return jdbc.query("""
                SELECT r.id FROM submission_request r JOIN annual_plan p ON p.id = r.annual_plan_id
                 WHERE p.year = ? AND r.type = 'MONTHLY_ACTUAL' AND r.month = ?""",
                (rs, i) -> rs.getLong(1), year, month).stream().findFirst().orElse(null);
    }

    /** 연간 탭에서 월을 주지 않으면: 승인본이 있는 가장 최근 달(없으면 12월) */
    private int latestApprovedMonth(int year) {
        Integer month = jdbc.queryForObject("""
                SELECT max(r.month) FROM submission_request r JOIN annual_plan p ON p.id = r.annual_plan_id
                 WHERE p.year = ? AND r.type = 'MONTHLY_ACTUAL'
                   AND EXISTS (SELECT 1 FROM submission s WHERE s.request_id = r.id AND s.status = 'APPROVED')""",
                Integer.class, year);
        return month != null ? month : 12;
    }

    private static Period parsePeriod(String period) {
        if (period == null || period.isBlank()) {
            return Period.MONTH;
        }
        try {
            return Period.valueOf(period);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "period는 MONTH 또는 YEAR입니다.");
        }
    }

    static String placeholders(int n) {
        return String.join(",", Collections.nCopies(n, "?"));
    }
}
