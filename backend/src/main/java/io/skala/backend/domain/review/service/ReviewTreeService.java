package io.skala.backend.domain.review.service;

import io.skala.backend.domain.financial.entity.ReportItem;
import io.skala.backend.domain.financial.value.Scenario;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.repository.SubmissionRequestRepository;
import io.skala.backend.domain.review.dto.ReviewTreeResponse;
import io.skala.backend.domain.review.dto.ReviewTreeResponse.Node;
import io.skala.backend.domain.review.entity.GapComparison;
import io.skala.backend.domain.review.entity.ReviewExplanation;
import io.skala.backend.domain.review.entity.ReviewItem;
import io.skala.backend.domain.review.repository.GapComparisonRepository;
import io.skala.backend.domain.review.repository.ReviewExplanationRepository;
import io.skala.backend.domain.review.repository.ReviewItemRepository;
import io.skala.backend.domain.review.service.ChangeDetector.Aggregate;
import io.skala.backend.domain.review.service.ChangeDetector.Key;
import io.skala.backend.domain.review.value.GapBasis;
import io.skala.backend.domain.review.value.GapComparisonType;
import io.skala.backend.domain.review.value.WorkflowState;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.repository.SubmissionRepository;
import io.skala.backend.domain.submission.service.SubmissionLabels;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GAP-06 Gap 계층형 표(경영기획팀). 요청·조직의 최신 제출본에서 비교 종류에 맞는 상세 행(월·구분)을 모아
 * 보고 항목 대/중/소 계층으로 묶는다. 상위 행의 값은 하위 값의 합계다.
 *
 * <p>보이는 상세 행: Gap 기준을 넘었거나(제출 때 계산), 조직 소명이 있거나, 이미 처리(선택·요청·제외)한 항목.
 * tab=ALL이면 비교 종류에 맞는 모든 상세 행.
 */
@Service
@RequiredArgsConstructor
public class ReviewTreeService {

    private static final List<String> TABS = List.of("NEEDS_REVIEW", "SELECTED", "IN_PROGRESS", "RE_REVIEW",
            "ACCEPTED", "EXCLUDED");

    private final SubmissionRepository submissionRepository;
    private final SubmissionRequestRepository submissionRequestRepository;
    private final ReviewItemRepository reviewItemRepository;
    private final ReviewExplanationRepository reviewExplanationRepository;
    private final GapComparisonRepository gapComparisonRepository;
    private final GapCalculator gapCalculator;

    @Transactional(readOnly = true)
    public ReviewTreeResponse tree(AuthenticatedUser user, Long requestId, Long organizationId,
                                   GapComparisonType comparison, String tab, String sort, String order, String q) {
        user.requireRole(UserRole.PLANNER);
        if (requestId == null || organizationId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "requestId와 organizationId가 필요합니다.");
        }
        GapComparisonType type = comparison == null ? GapComparisonType.ACTUAL_VS_PLAN : comparison;
        String selectedTab = tab == null ? "NEEDS_REVIEW" : tab;
        if (!selectedTab.equals("ALL") && !TABS.contains(selectedTab)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "알 수 없는 탭입니다.");
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        TABS.forEach(t -> counts.put(t, 0));
        Submission submission = submissionRepository.findFirstByRequestIdAndOrganizationIdAndStatusNotOrderBySeqDesc(
                requestId, organizationId, SubmissionStatus.DRAFT).orElse(null);
        if (submission == null) {
            counts.put("ALL", 0);
            return new ReviewTreeResponse(null, counts, List.of());
        }
        SubmissionRequest request = submissionRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        GapBasis basis = GapRules.basisOf(type);
        Map<Key, Aggregate> baseline = gapCalculator.values(gapCalculator.sources(submission, request))
                .getOrDefault(basis, Map.of());
        Map<Long, GapComparison> gaps = gapComparisonRepository.findAllBySubmissionId(submission.getId()).stream()
                .filter(g -> g.getBasis() == basis)
                .collect(Collectors.toMap(g -> g.getReviewItem().getId(), Function.identity()));
        Map<Long, List<ReviewExplanation>> explanations = reviewExplanationRepository
                .findAllBySubmissionId(submission.getId()).stream()
                .collect(Collectors.groupingBy(e -> e.getReviewItem().getId()));

        Scenario scenario = GapRules.itemScenarioOf(type);
        String query = q == null || q.isBlank() ? null : q.strip().toLowerCase(Locale.ROOT);
        List<Leaf> leaves = new ArrayList<>();
        for (ReviewItem item : reviewItemRepository.findAllWithReportItemBySubmissionId(submission.getId())) {
            if (!Boolean.TRUE.equals(item.getActive()) || item.getScenario() != scenario) {
                continue;
            }
            ReviewExplanation current = ReviewItemQueryService.currentExplanation(
                    explanations.getOrDefault(item.getId(), List.of()), item);
            GapComparison gap = gaps.get(item.getId());
            boolean exceeded = gap != null && Boolean.TRUE.equals(gap.getExceeded());
            boolean visible = exceeded || current != null || item.getWorkflowState() != WorkflowState.NEEDS_REVIEW;
            if (!visible && !selectedTab.equals("ALL")) {
                continue;
            }
            if (query != null && !ReviewItemQueryService.path(item).toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            String itemTab = ReviewTabs.of(item, current);
            counts.merge(itemTab, 1, Integer::sum);
            if (!selectedTab.equals("ALL") && !selectedTab.equals(itemTab)) {
                continue;
            }
            Aggregate base = baseline.get(new Key(item.getReportItem().getId(), item.getYear(), item.getMonth(),
                    GapRules.baselineScenarioOf(type)));
            BigDecimal plan = gap != null ? gap.getBaselineAmount() : base == null ? null : base.total();
            leaves.add(new Leaf(item.getReportItem(), leafName(item.getMonth(), item.getScenario()), plan,
                    item.getCurrentAmount(), itemTab, stage(itemTab), exceeded, String.valueOf(item.getId()),
                    ReviewItemTokens.of(item, current, submission)));
        }
        counts.put("ALL", counts.values().stream().mapToInt(Integer::intValue).sum());

        Comparator<Node> comparator = comparator(sort, order);
        return new ReviewTreeResponse(new ReviewTreeResponse.SubmissionRef(String.valueOf(submission.getId()),
                SubmissionLabels.of(submission, request), submission.getStatus().name()), counts,
                build(leaves, comparator));
    }

    /** 제출본의 상태 탭 건수(비교 종류와 무관, 보이는 항목 기준). 선택 처리(GAP-07) 응답에 쓴다. */
    @Transactional(readOnly = true)
    public Map<String, Integer> tabCounts(Long submissionId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        TABS.forEach(t -> counts.put(t, 0));
        Map<Long, List<ReviewExplanation>> explanations = reviewExplanationRepository.findAllBySubmissionId(submissionId)
                .stream().collect(Collectors.groupingBy(e -> e.getReviewItem().getId()));
        for (ReviewItem item : reviewItemRepository.findAllWithReportItemBySubmissionId(submissionId)) {
            if (!Boolean.TRUE.equals(item.getActive())) {
                continue;
            }
            ReviewExplanation current = ReviewItemQueryService.currentExplanation(
                    explanations.getOrDefault(item.getId(), List.of()), item);
            if (Boolean.TRUE.equals(item.getGapExceeded()) || current != null
                    || item.getWorkflowState() != WorkflowState.NEEDS_REVIEW) {
                counts.merge(ReviewTabs.of(item, current), 1, Integer::sum);
            }
        }
        counts.put("ALL", counts.values().stream().mapToInt(Integer::intValue).sum());
        return counts;
    }

    /** 계층 표의 상세 행. 검토 항목이 아닌 행(버전 비교)은 tab·stage·reviewItemId·stateToken이 null */
    record Leaf(ReportItem reportItem, String name, BigDecimal plan, BigDecimal actual, String tab, Integer stage,
                boolean exceeded, String reviewItemId, String stateToken) {
    }

    static String leafName(int month, Scenario scenario) {
        String label = switch (scenario) {
            case ACTUAL -> "실적";
            case ESTIMATE -> "추정";
            case PLAN -> "계획";
        };
        return month + "월 " + label;
    }

    /** 보고 항목 경로를 따라 계층을 만든다. 상위 행 값은 하위 합계(값이 하나도 없으면 null). */
    static List<Node> build(List<Leaf> leaves, Comparator<Node> comparator) {
        Map<Long, Group> groups = new LinkedHashMap<>();
        List<Group> roots = new ArrayList<>();
        for (Leaf leaf : leaves) {
            LinkedList<ReportItem> chain = new LinkedList<>();
            for (ReportItem r = leaf.reportItem(); r != null; r = r.getParent()) {
                chain.addFirst(r);
            }
            Group parent = null;
            for (ReportItem r : chain) {
                Group group = groups.get(r.getId());
                if (group == null) {
                    group = new Group(r, parent == null ? 1 : parent.level + 1);
                    groups.put(r.getId(), group);
                    if (parent == null) {
                        roots.add(group);
                    } else {
                        parent.groups.add(group);
                    }
                }
                parent = group;
            }
            parent.leaves.add(leaf);
        }
        return roots.stream().map(g -> g.toNode(comparator)).sorted(comparator).toList();
    }

    private static final class Group {

        final ReportItem reportItem;
        final int level;
        final List<Group> groups = new ArrayList<>();
        final List<Leaf> leaves = new ArrayList<>();

        Group(ReportItem reportItem, int level) {
            this.reportItem = reportItem;
            this.level = level;
        }

        Node toNode(Comparator<Node> comparator) {
            List<Node> children = new ArrayList<>();
            groups.forEach(g -> children.add(g.toNode(comparator)));
            leaves.forEach(l -> children.add(leafNode(l, Math.min(level + 1, 4))));
            children.sort(comparator);
            BigDecimal plan = sum(children.stream().map(Node::plan).toList());
            BigDecimal actual = sum(children.stream().map(Node::actual).toList());
            boolean exceeded = children.stream().anyMatch(Node::gapExceeded);
            return new Node(String.valueOf(reportItem.getId()), reportItem.getName(), Math.min(level, 4),
                    Amounts.money(plan), Amounts.money(actual), Amounts.money(Amounts.diff(actual, plan)),
                    Amounts.rate(actual, plan), null, null, exceeded, null, null, children);
        }
    }

    private static Node leafNode(Leaf leaf, int level) {
        return new Node(String.valueOf(leaf.reportItem().getId()), leaf.name(), level, Amounts.money(leaf.plan()),
                Amounts.money(leaf.actual()), Amounts.money(Amounts.diff(leaf.actual(), leaf.plan())),
                Amounts.rate(leaf.actual(), leaf.plan()), leaf.stage(), leaf.tab(), leaf.exceeded(),
                leaf.reviewItemId(), leaf.stateToken(), List.of());
    }

    /** 진행 단계: 1 차이 항목 확인 → 2 검토 결과 확정 → 3 조직 소명 수집 → 4 보완 내용 재검토 → 5 승인 및 확정 */
    static int stage(String tab) {
        return switch (tab) {
            case "SELECTED", "EXCLUDED" -> 2;
            case "IN_PROGRESS" -> 3;
            case "RE_REVIEW" -> 4;
            case "ACCEPTED" -> 5;
            default -> 1;
        };
    }

    private static BigDecimal sum(List<String> values) {
        BigDecimal total = null;
        for (String v : values) {
            if (v != null) {
                total = total == null ? new BigDecimal(v) : total.add(new BigDecimal(v));
            }
        }
        return total;
    }

    /** 차액(기본) 또는 차이율의 절댓값 기준 정렬, 기본 내림차순. 값이 없으면 뒤로. */
    static Comparator<Node> comparator(String sort, String order) {
        Function<Node, BigDecimal> key = "RATE".equals(sort)
                ? n -> n.rate() == null ? null : new BigDecimal(n.rate()).abs()
                : n -> n.diff() == null ? null : new BigDecimal(n.diff()).abs();
        Comparator<BigDecimal> natural = "ASC".equals(order) ? Comparator.naturalOrder() : Comparator.reverseOrder();
        return Comparator.comparing(key, Comparator.nullsLast(natural))
                .thenComparing(Node::name, Comparator.nullsLast(Comparator.naturalOrder()));
    }
}
