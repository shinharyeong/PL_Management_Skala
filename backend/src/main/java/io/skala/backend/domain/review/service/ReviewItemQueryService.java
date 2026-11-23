package io.skala.backend.domain.review.service;

import io.skala.backend.domain.financial.entity.FinancialValue;
import io.skala.backend.domain.financial.entity.ReportItem;
import io.skala.backend.domain.financial.value.ReportItemKind;
import io.skala.backend.domain.financial.value.Scenario;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.repository.SubmissionRequestRepository;
import io.skala.backend.domain.review.dto.AttachmentResponse;
import io.skala.backend.domain.review.dto.ExplanationResponse;
import io.skala.backend.domain.review.dto.GapSummary;
import io.skala.backend.domain.review.dto.GapThresholdResponse;
import io.skala.backend.domain.review.entity.GapComparison;
import io.skala.backend.domain.review.repository.GapComparisonRepository;
import io.skala.backend.domain.review.repository.GapThresholdRepository;
import io.skala.backend.domain.review.value.GapBasis;
import io.skala.backend.domain.review.value.GapComparisonType;
import io.skala.backend.domain.review.dto.OrgReviewListResponse;
import io.skala.backend.domain.review.dto.ReviewItemDetailResponse;
import io.skala.backend.domain.review.entity.ReviewAttachment;
import io.skala.backend.domain.review.entity.ReviewExplanation;
import io.skala.backend.domain.review.entity.ReviewItem;
import io.skala.backend.domain.review.repository.ReviewAttachmentRepository;
import io.skala.backend.domain.review.repository.ReviewExplanationRepository;
import io.skala.backend.domain.review.repository.ReviewItemRepository;
import io.skala.backend.domain.review.service.ChangeDetector.Aggregate;
import io.skala.backend.domain.review.service.ChangeDetector.Key;
import io.skala.backend.domain.review.service.ReviewComparison.View;
import io.skala.backend.domain.review.value.ExplanationVerdict;
import io.skala.backend.domain.submission.dto.BaselineResponse;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.service.SubmissionLabels;
import io.skala.backend.domain.submission.support.SubmissionAccess;
import io.skala.backend.domain.submission.value.BaselineKind;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PROOF-02 소명 대상 목록(조직 보기), PROOF-03 항목 상세 */
@Service
@RequiredArgsConstructor
public class ReviewItemQueryService {

    private static final List<Scenario> SCENARIO_ORDER = List.of(Scenario.ACTUAL, Scenario.ESTIMATE, Scenario.PLAN);

    private final SubmissionAccess submissionAccess;
    private final ReviewComparison reviewComparison;
    private final ReviewItemRepository reviewItemRepository;
    private final ReviewExplanationRepository reviewExplanationRepository;
    private final ReviewAttachmentRepository reviewAttachmentRepository;
    private final SubmissionRequestRepository submissionRequestRepository;
    private final ExplanationTargetService explanationTargetService;
    private final GapComparisonRepository gapComparisonRepository;
    private final GapThresholdRepository gapThresholdRepository;
    private final GapCalculator gapCalculator;

    @Transactional(readOnly = true)
    public OrgReviewListResponse listForOrg(Long submissionId, AuthenticatedUser user) {
        Submission submission = submissionAccess.loadForRead(submissionId, user);
        View view = reviewComparison.build(submission);
        Map<Long, ReviewExplanation> round1 = reviewExplanationRepository.findAllBySubmissionId(submissionId).stream()
                .filter(e -> e.getRound() == ExplanationTargetService.ORG_ROUND)
                .collect(Collectors.toMap(e -> e.getReviewItem().getId(), Function.identity()));

        Map<ReportItemKind, TreeMap<Integer, List<OrgReviewListResponse.Item>>> grouped = new LinkedHashMap<>();
        grouped.put(ReportItemKind.REVENUE, new TreeMap<>());
        grouped.put(ReportItemKind.COST, new TreeMap<>());
        List<ReviewItem> items = reviewItemRepository.findAllWithReportItemBySubmissionId(submissionId).stream()
                .filter(i -> Boolean.TRUE.equals(i.getActive()) && round1.containsKey(i.getId()))
                .sorted(Comparator.comparing((ReviewItem i) -> i.getReportItem().getDisplayOrder())
                        .thenComparing(ReviewItem::getId))
                .toList();
        for (ReviewItem item : items) {
            ReviewExplanation explanation = round1.get(item.getId());
            Key key = key(item);
            OrgReviewListResponse.Item row = new OrgReviewListResponse.Item(
                    String.valueOf(item.getId()),
                    item.getReportItem().getName(),
                    firstCell(view, key),
                    Amounts.money(view.baselineOf(key)),
                    view.kind() == BaselineKind.REJECTED ? Amounts.money(view.previousOf(key)) : null,
                    Amounts.money(item.getCurrentAmount()),
                    Amounts.rate(item.getCurrentAmount(), view.baselineOf(key)),
                    answerState(explanation),
                    ReviewItemTokens.of(item, explanation, submission));
            grouped.computeIfAbsent(item.getReportItem().getKind(), k -> new TreeMap<>())
                    .computeIfAbsent(slot(item), k -> new ArrayList<>()).add(row);
        }

        List<OrgReviewListResponse.Group> groups = new ArrayList<>();
        grouped.forEach((kind, slots) -> {
            if (slots.isEmpty()) {
                return;
            }
            List<OrgReviewListResponse.MonthGroup> months = new ArrayList<>();
            slots.forEach((slot, rows) -> months.add(new OrgReviewListResponse.MonthGroup(slot / 10,
                    SCENARIO_ORDER.get(slot % 10), rows)));
            groups.add(new OrgReviewListResponse.Group(kind.name(),
                    months.stream().mapToInt(m -> m.items().size()).sum(), months));
        });
        ExplanationTargetService.Progress progress = explanationTargetService.progress(submissionId);
        return new OrgReviewListResponse(baseline(view),
                new OrgReviewListResponse.Progress(progress.answered(), progress.required()), groups);
    }

    @Transactional(readOnly = true)
    public ReviewItemDetailResponse detail(Long reviewItemId, AuthenticatedUser user) {
        ReviewItem item = load(reviewItemId, user);
        Submission submission = item.getSubmission();
        View view = reviewComparison.build(submission);
        Key key = key(item);
        List<ReviewExplanation> explanations = reviewExplanationRepository.findAllByReviewItemIdOrderByRoundAsc(item.getId());
        Map<Long, List<ReviewAttachment>> attachments = attachmentsOf(explanations);
        ReviewExplanation round1 = explanations.stream()
                .filter(e -> e.getRound() == ExplanationTargetService.ORG_ROUND).findFirst().orElse(null);

        return new ReviewItemDetailResponse(
                String.valueOf(item.getId()),
                path(item),
                item.getScenario(),
                item.getMonth(),
                user.isPlanner() ? gapSummary(item, view) : null,
                rows(view, key),
                previousExplanation(view, key, round1),
                explanations.stream().map(e -> toResponse(e, attachments.getOrDefault(e.getId(), List.of()))).toList(),
                ReviewItemTokens.of(item, currentExplanation(explanations, item), submission));
    }

    /** 경영기획팀만: 확정 연간 계획 대비 값과 Gap 기준. 계획 구분 항목이거나 계획이 없으면 값이 비어 있다. */
    private GapSummary gapSummary(ReviewItem item, View view) {
        GapComparisonType type = GapRules.typeOf(item.getScenario(), GapBasis.PLAN);
        if (type == null) {
            return null;
        }
        GapComparison stored = gapComparisonRepository.findAllBySubmissionId(item.getSubmission().getId()).stream()
                .filter(g -> g.getReviewItem().getId().equals(item.getId()) && g.getBasis() == GapBasis.PLAN)
                .findFirst().orElse(null);
        BigDecimal plan;
        GapThresholdResponse threshold;
        if (stored != null) {
            plan = stored.getBaselineAmount();
            threshold = GapThresholdResponse.of(stored.getThreshold());
        } else {
            Aggregate base = gapCalculator.values(gapCalculator.sources(item.getSubmission(), view.request()))
                    .getOrDefault(GapBasis.PLAN, Map.of())
                    .get(new Key(item.getReportItem().getId(), item.getYear(), item.getMonth(), GapRules.baselineScenarioOf(type)));
            plan = base == null ? null : base.total();
            threshold = gapThresholdRepository.findFirstByComparisonOrderByIdDesc(type)
                    .map(GapThresholdResponse::of).orElse(null);
        }
        BigDecimal actual = item.getCurrentAmount();
        return new GapSummary(Amounts.money(plan), Amounts.money(actual), Amounts.money(Amounts.diff(actual, plan)),
                Amounts.rate(actual, plan), threshold);
    }

    /** 항목 조회 + 권한(경영기획팀 전체, 조직 담당자는 자기 조직) */
    ReviewItem load(Long reviewItemId, AuthenticatedUser user) {
        ReviewItem item = reviewItemRepository.findById(reviewItemId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        user.requireOrganizationAccess(item.getSubmission().getOrganizationId());
        return item;
    }

    static ReviewExplanation currentExplanation(List<ReviewExplanation> explanations, ReviewItem item) {
        return explanations.stream().filter(e -> Objects.equals(e.getRound(), item.getCurrentRound()))
                .findFirst().orElse(null);
    }

    private List<ReviewItemDetailResponse.Row> rows(View view, Key key) {
        Map<String, BigDecimal[]> cells = new TreeMap<>();
        collect(cells, view.currentCells(), key, 0);
        collect(cells, view.baselineCells(), key, 1);
        if (view.kind() == BaselineKind.REJECTED) {
            collect(cells, view.previousCells(), key, 2);
        }
        List<ReviewItemDetailResponse.Row> rows = new ArrayList<>();
        cells.forEach((cell, v) -> rows.add(new ReviewItemDetailResponse.Row(cell, Amounts.money(v[1]),
                Amounts.money(v[2]), Amounts.money(v[0]), Amounts.money(Amounts.diff(v[0], v[1])),
                Amounts.rate(v[0], v[1]))));
        return rows;
    }

    /** 같은 보고 항목·기간·구분의 원천 셀을 시트!셀 주소로 맞춘다(인덱스 0 현재, 1 기준, 2 직전). */
    private static void collect(Map<String, BigDecimal[]> cells, List<FinancialValue> values, Key key, int index) {
        for (FinancialValue v : values) {
            ReportItem reportItem = v.getLine().getReportItem();
            if (!reportItem.getId().equals(key.reportItemId()) || v.getYear() != key.year()
                    || v.getMonth() != key.month() || v.getScenario() != key.scenario()) {
                continue;
            }
            BigDecimal[] slot = cells.computeIfAbsent(v.getLine().getSourceSheet() + "!" + v.getSourceCell(),
                    c -> new BigDecimal[3]);
            slot[index] = slot[index] == null ? v.getAmount() : slot[index].add(v.getAmount());
        }
    }

    /** 지난 승인 소명: 이어받은 소명이 있으면 그것, 아니면 승인 기준 버전의 같은 항목 최신 수락 소명 */
    private ExplanationResponse previousExplanation(View view, Key key, ReviewExplanation round1) {
        ReviewExplanation source = round1 != null && round1.isInherited() ? round1.getInheritedFrom() : null;
        if (source == null && view.approved() != null) {
            source = reviewItemRepository.findAllWithReportItemBySubmissionId(view.approved().getId()).stream()
                    .filter(i -> key(i).equals(key)).findFirst()
                    .flatMap(i -> reviewExplanationRepository.findAllByReviewItemIdOrderByRoundAsc(i.getId()).stream()
                            .filter(e -> e.getVerdict() == ExplanationVerdict.ACCEPTED && e.isAnswered())
                            .reduce((a, b) -> b))
                    .orElse(null);
        }
        if (source == null) {
            return null;
        }
        return toResponse(source, attachmentsOf(List.of(source)).getOrDefault(source.getId(), List.of()));
    }

    /** 소명 한 건을 첨부와 함께 응답으로 */
    ExplanationResponse toResponse(ReviewExplanation e) {
        return toResponse(e, attachmentsOf(List.of(e)).getOrDefault(e.getId(), List.of()));
    }

    ExplanationResponse toResponse(ReviewExplanation e, List<ReviewAttachment> attachments) {
        Submission owner = e.getReviewItem().getSubmission();
        String approvedLabel = e.getVerdict() == ExplanationVerdict.ACCEPTED && owner.getStatus() == SubmissionStatus.APPROVED
                ? SubmissionLabels.approved(owner, request(owner)) : null;
        ExplanationResponse.InheritedFrom inherited = null;
        if (e.getInheritedFrom() != null) {
            Submission from = e.getInheritedFrom().getReviewItem().getSubmission();
            SubmissionRequest fromRequest = request(from);
            String label = from.getApprovedSeq() != null ? SubmissionLabels.approved(from, fromRequest)
                    : SubmissionLabels.of(from, fromRequest);
            inherited = new ExplanationResponse.InheritedFrom(String.valueOf(e.getInheritedFrom().getId()), label);
        }
        Long itemId = e.getReviewItem().getId();
        return new ExplanationResponse(String.valueOf(e.getId()), e.getRound(), e.getRequestNote(),
                user(e.getRequestedBy()), e.getRequestedAt(), e.getDueAt(), e.getAnswer(), user(e.getAnsweredBy()),
                e.getAnsweredAt(), e.getVerdict(), e.getReviewNote(), approvedLabel, inherited,
                attachments.stream().map(a -> AttachmentResponse.of(itemId, a)).toList());
    }

    private Map<Long, List<ReviewAttachment>> attachmentsOf(List<ReviewExplanation> explanations) {
        if (explanations.isEmpty()) {
            return Map.of();
        }
        return reviewAttachmentRepository.findAllByExplanationIdInOrderByIdAsc(
                        explanations.stream().map(ReviewExplanation::getId).toList()).stream()
                .collect(Collectors.groupingBy(a -> a.getExplanation().getId()));
    }

    private BaselineResponse baseline(View view) {
        Submission base = view.kind() == BaselineKind.REJECTED ? view.previous() : view.approved();
        if (base == null) {
            return new BaselineResponse(BaselineKind.NONE, null, null);
        }
        return new BaselineResponse(view.kind(), String.valueOf(base.getId()), SubmissionLabels.of(base, request(base)));
    }

    private SubmissionRequest request(Submission submission) {
        return submissionRequestRepository.findById(submission.getRequestId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    static String answerState(ReviewExplanation e) {
        if (e.isInherited()) {
            return "INHERITED";
        }
        return e.isAnswered() ? "WRITTEN" : "EMPTY";
    }

    static Key key(ReviewItem item) {
        return new Key(item.getReportItem().getId(), item.getYear(), item.getMonth(), item.getScenario());
    }

    /** 월 오름차순, 같은 월이면 실적 → 추정 → 계획 */
    private static int slot(ReviewItem item) {
        return item.getMonth() * 10 + SCENARIO_ORDER.indexOf(item.getScenario());
    }

    private static String firstCell(View view, Key key) {
        Aggregate a = view.current().get(key);
        if (a == null) {
            a = view.baseline().get(key);
        }
        if (a == null || a.cells().isEmpty()) {
            return null;
        }
        return a.cells().stream().map(c -> c.sheet() + "!" + c.cell()).sorted().findFirst().orElse(null);
    }

    /** 대 › 중 › 소 › 12월 추정 */
    static String path(ReviewItem item) {
        LinkedList<String> names = new LinkedList<>();
        for (ReportItem r = item.getReportItem(); r != null; r = r.getParent()) {
            names.addFirst(r.getName());
        }
        String scenario = switch (item.getScenario()) {
            case ACTUAL -> "실적";
            case ESTIMATE -> "추정";
            case PLAN -> "계획";
        };
        names.addLast(item.getMonth() + "월 " + scenario);
        return String.join(" › ", names);
    }

    private static ExplanationResponse.UserRef user(AppUser user) {
        return user == null ? null : new ExplanationResponse.UserRef(String.valueOf(user.getId()), user.getName());
    }
}
