package io.skala.backend.domain.review.service;

import io.skala.backend.domain.financial.repository.FinancialValueRepository;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.repository.SubmissionRequestRepository;
import io.skala.backend.domain.request.value.RequestType;
import io.skala.backend.domain.review.entity.GapComparison;
import io.skala.backend.domain.review.entity.GapThreshold;
import io.skala.backend.domain.review.entity.ReviewItem;
import io.skala.backend.domain.review.repository.GapComparisonRepository;
import io.skala.backend.domain.review.repository.GapThresholdRepository;
import io.skala.backend.domain.review.repository.ReviewItemRepository;
import io.skala.backend.domain.review.service.ChangeDetector.Aggregate;
import io.skala.backend.domain.review.service.ChangeDetector.Key;
import io.skala.backend.domain.review.value.GapBasis;
import io.skala.backend.domain.review.value.GapComparisonType;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.service.BaselineResolver;
import io.skala.backend.domain.submission.service.BaselineResolver.Baseline;
import io.skala.backend.domain.submission.service.PlanBaselines;
import io.skala.backend.domain.submission.value.BaselineKind;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제출할 때 Gap을 계산해 gap_comparison에 남긴다(그때의 기준으로 고정, 나중에 기준을 바꿔도 소급하지 않음).
 * <ul>
 *   <li>PLAN 기준: 확정 연간 계획(request_target.confirmed_plan_submission_id)의 같은 달 계획 값 — 실적·추정 항목</li>
 *   <li>PREVIOUS 기준: 직전 승인본(같은 요청 승인본 또는 전월 승인본)의 같은 달 추정 값 — 추정 항목</li>
 * </ul>
 * 기준 값이나 현재 값이 없거나, 그 비교 종류의 기준(gap_threshold)이 아직 없으면 비교를 만들지 않는다.
 */
@Service
@RequiredArgsConstructor
public class GapCalculator {

    private final SubmissionRequestRepository submissionRequestRepository;
    private final PlanBaselines planBaselines;
    private final BaselineResolver baselineResolver;
    private final FinancialValueRepository financialValueRepository;
    private final ReviewItemRepository reviewItemRepository;
    private final GapThresholdRepository gapThresholdRepository;
    private final GapComparisonRepository gapComparisonRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void calculate(Submission submission) {
        gapThresholdRepository.lockGuardForSubmit();
        gapComparisonRepository.deleteAllBySubmissionId(submission.getId());
        SubmissionRequest request = submissionRequestRepository.findById(submission.getRequestId()).orElseThrow();

        Map<GapBasis, Submission> sources = sources(submission, request);
        Map<GapBasis, Map<Key, Aggregate>> values = values(sources);
        Map<GapComparisonType, GapThreshold> thresholds = new EnumMap<>(GapComparisonType.class);
        for (GapComparisonType type : GapComparisonType.values()) {
            gapThresholdRepository.findFirstByComparisonOrderByIdDesc(type).ifPresent(t -> thresholds.put(type, t));
        }

        for (ReviewItem item : reviewItemRepository.findAllWithReportItemBySubmissionId(submission.getId())) {
            if (!Boolean.TRUE.equals(item.getActive())) {
                continue;
            }
            boolean exceeded = false;
            for (GapBasis basis : GapBasis.values()) {
                GapComparisonType type = GapRules.typeOf(item.getScenario(), basis);
                GapThreshold threshold = type == null ? null : thresholds.get(type);
                if (threshold == null || !sources.containsKey(basis) || item.getCurrentAmount() == null) {
                    continue;
                }
                Aggregate base = values.get(basis).get(new Key(item.getReportItem().getId(), item.getYear(),
                        item.getMonth(), GapRules.baselineScenarioOf(type)));
                if (base == null) {
                    continue;
                }
                BigDecimal current = item.getCurrentAmount();
                boolean over = GapRules.exceeded(threshold.getPercentEnabled(), threshold.getPercent(),
                        threshold.getAmountEnabled(), threshold.getAmount(), base.total(), current);
                gapComparisonRepository.save(GapComparison.of(item, basis, sources.get(basis),
                        GapRules.baselineScenarioOf(type), base.total(), current, GapRules.rate(current, base.total()),
                        threshold, over));
                exceeded |= over;
            }
            item.markGap(exceeded);
        }
    }

    /** Gap 기준 제출본: PLAN = 확정 연간 계획, PREVIOUS = 직전 승인본(월간만) */
    public Map<GapBasis, Submission> sources(Submission submission, SubmissionRequest request) {
        Map<GapBasis, Submission> sources = new EnumMap<>(GapBasis.class);
        if (request.getType() == RequestType.MONTHLY_ACTUAL) {
            planBaselines.confirmedPlan(request.getId(), submission.getOrganizationId())
                    .ifPresent(plan -> sources.put(GapBasis.PLAN, plan));
            Baseline previous = baselineResolver.resolveApproved(submission, request);
            if (previous.kind() == BaselineKind.PREV_MONTH_APPROVED) {
                sources.put(GapBasis.PREVIOUS, previous.submission());
            }
        }
        return sources;
    }

    public Map<GapBasis, Map<Key, Aggregate>> values(Map<GapBasis, Submission> sources) {
        Map<GapBasis, Map<Key, Aggregate>> values = new EnumMap<>(GapBasis.class);
        sources.forEach((basis, source) -> values.put(basis, ChangeDetector.aggregate(
                financialValueRepository.findRequestYearValuesBySubmissionId(source.getId()))));
        return values;
    }
}
