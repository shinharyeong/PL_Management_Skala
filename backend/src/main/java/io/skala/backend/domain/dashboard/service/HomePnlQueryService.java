package io.skala.backend.domain.dashboard.service;

import io.skala.backend.domain.dashboard.dto.HomeDivisionsResponse;
import io.skala.backend.domain.dashboard.dto.HomeOpDriversResponse;
import io.skala.backend.domain.dashboard.dto.HomePnlResponse;
import io.skala.backend.domain.dashboard.dto.HomeWaterfallResponse;
import io.skala.backend.domain.dashboard.dto.OrganizationRef;
import io.skala.backend.domain.dashboard.service.PnlSnapshotLoader.Item;
import io.skala.backend.domain.dashboard.service.PnlSnapshotLoader.Org;
import io.skala.backend.domain.dashboard.service.PnlSnapshotLoader.Period;
import io.skala.backend.domain.dashboard.service.PnlSnapshotLoader.Snapshot;
import io.skala.backend.domain.review.service.Amounts;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * HOME-03 핵심 KPI·전사 손익표, HOME-04 영업이익 변동 원인, HOME-05 사업부별 손익, HOME-06 손익 흐름. 경영기획팀 전용.
 * 매출 = REVENUE 항목 합, 영업비용 = COST 항목 합, 영업이익 = 매출 − 영업비용(답안지의 영업이익(A-B)).
 * 금액 계산은 BigDecimal로만 하고, 화면 단위 환산은 unit 쿼리로 응답 직전에 한다(NFR-ACC-002).
 */
@Service
@RequiredArgsConstructor
public class HomePnlQueryService {

    private static final String REVENUE = "REVENUE";
    private static final String COST = "COST";

    private final PnlSnapshotLoader loader;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public HomePnlResponse pnl(AuthenticatedUser actor, String period, int year, Integer month, Long masterId) {
        actor.requireRole(UserRole.PLANNER);
        Snapshot s = loader.load(period, year, month, masterId);
        Tree actual = new Tree(s.items(), s.actual().company());
        Tree plan = new Tree(s.items(), s.plan().company());
        Tree prev = new Tree(s.items(), s.prevEstimate().company());

        List<HomePnlResponse.Kpi> kpis = List.of(
                kpi(REVENUE, actual.kindTotal(REVENUE), plan.kindTotal(REVENUE)),
                kpi(COST, actual.kindTotal(COST), plan.kindTotal(COST)),
                kpi("OP", actual.op(), plan.op()));

        Set<Long> gapItems = gapItems(s);
        Map<Long, String> explanations = explanations(s);
        Set<Long> shown = new HashSet<>(actual.rolled().keySet());
        shown.addAll(plan.rolled().keySet());
        shown.addAll(prev.rolled().keySet());
        List<HomePnlResponse.Row> rows = new ArrayList<>();
        for (Placed placed : ordered(s.items(), shown)) {
            Item item = placed.item();
            BigDecimal a = actual.rolled().get(item.id());
            BigDecimal pl = plan.rolled().get(item.id());
            BigDecimal pr = prev.rolled().get(item.id());
            rows.add(new HomePnlResponse.Row(String.valueOf(item.id()), "ITEM", item.name(), placed.level(),
                    Amounts.money(a), Amounts.rate(a, pl), Amounts.money(Amounts.diff(a, pl)),
                    Amounts.rate(a, pr), Amounts.money(Amounts.diff(a, pr)),
                    gapItems.contains(item.id()), explanations.get(item.id())));
        }
        if (!rows.isEmpty()) {
            BigDecimal op = actual.op();
            rows.add(new HomePnlResponse.Row(null, "OP", "영업이익", 1, Amounts.money(op), Amounts.rate(op, plan.op()),
                    Amounts.money(Amounts.diff(op, plan.op())), Amounts.rate(op, prev.op()),
                    Amounts.money(Amounts.diff(op, prev.op())), false, null));
        }
        BigDecimal marginActual = ratio(actual.op(), actual.kindTotal(REVENUE));
        BigDecimal marginPlan = ratio(plan.op(), plan.kindTotal(REVENUE));
        BigDecimal marginPrev = ratio(prev.op(), prev.kindTotal(REVENUE));
        HomePnlResponse.OpMargin margin = new HomePnlResponse.OpMargin(text(marginActual), text(marginPlan),
                text(Amounts.diff(marginActual, marginPlan)), text(marginPrev), text(Amounts.diff(marginActual, marginPrev)));
        return new HomePnlResponse(s.dataStatus(), kpis, rows, margin);
    }

    @Transactional(readOnly = true)
    public HomeOpDriversResponse opDrivers(AuthenticatedUser actor, String period, int year, Integer month,
                                           Long masterId, Integer limit) {
        actor.requireRole(UserRole.PLANNER);
        int size = limit == null ? 5 : limit;
        if (size < 1 || size > 10) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "limit은 1~10입니다.");
        }
        Snapshot s = loader.load(period, year, month, masterId);
        record Driver(Org org, BigDecimal diff, String rate) {
        }
        List<Driver> drivers = new ArrayList<>();
        for (Org org : s.orgs()) {
            BigDecimal op = new Tree(s.items(), s.actual().of(org.id())).op();
            BigDecimal planOp = new Tree(s.items(), s.plan().of(org.id())).op();
            drivers.add(new Driver(org, Amounts.diff(op, planOp), Amounts.rate(op, planOp)));
        }
        drivers.sort(Comparator.comparing((Driver d) -> d.diff() == null)
                .thenComparing(d -> d.diff() == null ? BigDecimal.ZERO : d.diff().abs(), Comparator.reverseOrder())
                .thenComparing(d -> d.org().code()));
        BigDecimal total = Amounts.diff(new Tree(s.items(), s.actual().company()).op(),
                new Tree(s.items(), s.plan().company()).op());
        return new HomeOpDriversResponse(s.dataStatus(), Amounts.money(total), drivers.stream().limit(size)
                .map(d -> new HomeOpDriversResponse.Item(ref(d.org()), Amounts.money(d.diff()), d.rate())).toList());
    }

    @Transactional(readOnly = true)
    public HomeDivisionsResponse divisions(AuthenticatedUser actor, String period, int year, Integer month, Long masterId) {
        actor.requireRole(UserRole.PLANNER);
        Snapshot s = loader.load(period, year, month, masterId);
        Tree company = new Tree(s.items(), s.actual().company());
        Tree companyPlan = new Tree(s.items(), s.plan().company());
        List<HomeDivisionsResponse.Column> columns = new ArrayList<>();
        for (Org org : s.orgs()) {
            Tree t = new Tree(s.items(), s.actual().of(org.id()));
            BigDecimal op = t.op();
            BigDecimal planOp = new Tree(s.items(), s.plan().of(org.id())).op();
            columns.add(new HomeDivisionsResponse.Column(ref(org), Amounts.money(t.kindTotal(REVENUE)), Amounts.money(op),
                    text(ratio(op, company.op())), text(ratio(op, t.kindTotal(REVENUE))),
                    Amounts.money(Amounts.diff(op, planOp))));
        }
        HomeDivisionsResponse.Total total = new HomeDivisionsResponse.Total(Amounts.money(company.kindTotal(REVENUE)),
                Amounts.money(company.op()), text(ratio(company.op(), company.kindTotal(REVENUE))),
                Amounts.money(Amounts.diff(company.op(), companyPlan.op())));
        return new HomeDivisionsResponse(s.dataStatus(), columns, total);
    }

    @Transactional(readOnly = true)
    public HomeWaterfallResponse waterfall(AuthenticatedUser actor, String period, int year, Integer month, Long masterId) {
        actor.requireRole(UserRole.PLANNER);
        Snapshot s = loader.load(period, year, month, masterId);
        Tree actual = new Tree(s.items(), s.actual().company());
        Set<Long> shown = actual.rolled().keySet();
        List<HomeWaterfallResponse.Step> steps = new ArrayList<>();
        if (shown.isEmpty()) {
            return new HomeWaterfallResponse(s.dataStatus(), steps);
        }
        steps.add(new HomeWaterfallResponse.Step(REVENUE, null, "매출", Amounts.money(actual.kindTotal(REVENUE)), "TOTAL",
                parts(topLevel(s.items(), shown, REVENUE), actual)));
        for (Item category : topLevel(s.items(), shown, COST)) {
            steps.add(new HomeWaterfallResponse.Step(COST, String.valueOf(category.id()), category.name(),
                    Amounts.money(actual.rolled().get(category.id())), "DEDUCTION",
                    parts(children(s.items(), shown, category.id()), actual)));
        }
        steps.add(new HomeWaterfallResponse.Step("OP", null, "영업이익", Amounts.money(actual.op()), "TOTAL", List.of()));
        return new HomeWaterfallResponse(s.dataStatus(), steps);
    }

    /** 보고 항목 금액을 상위 항목까지 더한 것 */
    static final class Tree {

        private final Map<Long, Item> items;
        private final Map<Long, BigDecimal> direct;
        private final Map<Long, BigDecimal> rolled = new HashMap<>();

        Tree(Map<Long, Item> items, Map<Long, BigDecimal> direct) {
            this.items = items;
            this.direct = direct;
            direct.forEach((id, amount) -> {
                Set<Long> seen = new HashSet<>();
                for (Item item = items.get(id); item != null && seen.add(item.id());
                     item = item.parentId() == null ? null : items.get(item.parentId())) {
                    rolled.merge(item.id(), amount, BigDecimal::add);
                }
            });
        }

        Map<Long, BigDecimal> rolled() {
            return rolled;
        }

        /** 그 성격 항목의 합. 값이 하나도 없으면 null */
        BigDecimal kindTotal(String kind) {
            BigDecimal sum = null;
            for (Map.Entry<Long, BigDecimal> e : direct.entrySet()) {
                Item item = items.get(e.getKey());
                if (item != null && kind.equals(item.kind())) {
                    sum = sum == null ? e.getValue() : sum.add(e.getValue());
                }
            }
            return sum;
        }

        /** 매출 − 영업비용. 둘 다 없으면 null, 한쪽만 있으면 다른 쪽은 0 */
        BigDecimal op() {
            BigDecimal revenue = kindTotal(REVENUE);
            BigDecimal cost = kindTotal(COST);
            if (revenue == null && cost == null) {
                return null;
            }
            return (revenue == null ? BigDecimal.ZERO : revenue).subtract(cost == null ? BigDecimal.ZERO : cost);
        }
    }

    record Placed(Item item, int level) {
    }

    /** 답안지 순서: 매출 → 영업비용 → 기타, 같은 단계는 display_order 순으로 깊이 우선 */
    static List<Placed> ordered(Map<Long, Item> items, Set<Long> shown) {
        Map<Long, List<Item>> children = new HashMap<>();
        List<Item> roots = new ArrayList<>();
        for (Long id : shown) {
            Item item = items.get(id);
            if (item == null) {
                continue;
            }
            if (item.parentId() == null || !shown.contains(item.parentId())) {
                roots.add(item);
            } else {
                children.computeIfAbsent(item.parentId(), k -> new ArrayList<>()).add(item);
            }
        }
        Comparator<Item> order = Comparator.comparingInt((Item i) -> kindOrder(i.kind())).thenComparingInt(Item::order)
                .thenComparingLong(Item::id);
        roots.sort(order);
        children.values().forEach(list -> list.sort(order));
        List<Placed> result = new ArrayList<>();
        for (Item root : roots) {
            place(root, 1, children, result);
        }
        return result;
    }

    private static void place(Item item, int level, Map<Long, List<Item>> children, List<Placed> out) {
        out.add(new Placed(item, level));
        for (Item child : children.getOrDefault(item.id(), List.of())) {
            place(child, level + 1, children, out);
        }
    }

    private static int kindOrder(String kind) {
        return REVENUE.equals(kind) ? 0 : COST.equals(kind) ? 1 : 2;
    }

    /** 워터폴 단계: 그 성격의 최상위 항목이 하나(예: 영업비용)면 그 하위 항목들, 여럿이면 최상위 항목들 */
    private static List<Item> topLevel(Map<Long, Item> items, Set<Long> shown, String kind) {
        List<Item> roots = ordered(items, shown).stream().filter(p -> p.level() == 1 && kind.equals(p.item().kind()))
                .map(Placed::item).toList();
        if (roots.size() == 1) {
            List<Item> below = children(items, shown, roots.getFirst().id());
            return below.isEmpty() ? roots : below;
        }
        return roots;
    }

    private static List<Item> children(Map<Long, Item> items, Set<Long> shown, long parentId) {
        return ordered(items, shown).stream().map(Placed::item)
                .filter(i -> i.parentId() != null && i.parentId() == parentId).toList();
    }

    private static List<HomeWaterfallResponse.Part> parts(List<Item> items, Tree tree) {
        return items.stream().map(i -> new HomeWaterfallResponse.Part(String.valueOf(i.id()), i.name(),
                Amounts.money(tree.rolled().get(i.id())))).toList();
    }

    /** 승인본 항목 중 Gap 기준을 넘은 보고 항목(실적, 조회 기간) */
    private Set<Long> gapItems(Snapshot s) {
        if (s.sourceSubmissionIds().isEmpty()) {
            return Set.of();
        }
        List<Object> args = new ArrayList<>(s.sourceSubmissionIds());
        args.add(s.year());
        args.add(s.fromMonth());
        args.add(s.month());
        return new HashSet<>(jdbc.queryForList("""
                SELECT DISTINCT report_item_id FROM review_item
                 WHERE submission_id IN (""" + PnlSnapshotLoader.placeholders(s.sourceSubmissionIds().size()) + """
                ) AND active AND gap_exceeded AND scenario = 'ACTUAL' AND year = ? AND month BETWEEN ? AND ?""",
                Long.class, args.toArray()));
    }

    /** 툴팁용 수락 소명(항목의 마지막 수락 회차). 여러 조직이면 "조직명: 소명"을 줄바꿈으로 잇는다. */
    private Map<Long, String> explanations(Snapshot s) {
        if (s.sourceSubmissionIds().isEmpty()) {
            return Map.of();
        }
        List<Object> args = new ArrayList<>(s.sourceSubmissionIds());
        args.add(s.year());
        args.add(s.fromMonth());
        args.add(s.month());
        Map<Long, List<String>> lines = new LinkedHashMap<>();
        boolean yearly = s.period() == Period.YEAR;
        jdbc.query("""
                SELECT x.report_item_id, x.month, x.org_name, x.answer FROM (
                    SELECT DISTINCT ON (ri.id) ri.report_item_id, ri.month, o.code AS org_code, o.name AS org_name, e.answer
                      FROM review_item ri
                      JOIN review_explanation e ON e.review_item_id = ri.id
                      JOIN submission s ON s.id = ri.submission_id
                      JOIN organization o ON o.id = s.organization_id
                     WHERE ri.submission_id IN (""" + PnlSnapshotLoader.placeholders(s.sourceSubmissionIds().size()) + """
                ) AND ri.active AND ri.scenario = 'ACTUAL' AND ri.year = ? AND ri.month BETWEEN ? AND ?
                       AND e.verdict = 'ACCEPTED' AND e.answer IS NOT NULL
                     ORDER BY ri.id, e.round DESC) x
                 ORDER BY x.report_item_id, x.org_code, x.month""",
                rs -> {
                    String who = rs.getString("org_name") + (yearly ? " " + rs.getInt("month") + "월" : "");
                    lines.computeIfAbsent(rs.getLong("report_item_id"), k -> new ArrayList<>())
                            .add(who + ": " + rs.getString("answer"));
                }, args.toArray());
        Map<Long, String> result = new HashMap<>();
        lines.forEach((id, list) -> result.put(id, String.join("\n", list)));
        return result;
    }

    private static HomePnlResponse.Kpi kpi(String key, BigDecimal actual, BigDecimal plan) {
        return new HomePnlResponse.Kpi(key, Amounts.money(actual), Amounts.rate(actual, plan),
                Amounts.money(Amounts.diff(actual, plan)));
    }

    /** a / b × 100(소수 첫째 자리). b가 없거나 0이면 null */
    static BigDecimal ratio(BigDecimal a, BigDecimal b) {
        if (a == null || b == null || b.signum() == 0) {
            return null;
        }
        return a.multiply(BigDecimal.valueOf(100)).divide(b, 1, RoundingMode.HALF_UP);
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static OrganizationRef ref(Org org) {
        return new OrganizationRef(String.valueOf(org.id()), org.code(), org.name());
    }
}
