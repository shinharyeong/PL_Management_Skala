package io.skala.backend.domain.review.service;

import io.skala.backend.domain.financial.repository.FinancialValueRepository;
import io.skala.backend.domain.financial.value.Scenario;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.repository.SubmissionRequestRepository;
import io.skala.backend.domain.request.value.RequestType;
import io.skala.backend.domain.review.service.ChangeDetector.*;
import io.skala.backend.domain.review.value.*;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.service.BaselineResolver;
import io.skala.backend.domain.submission.service.PlanBaselines;
import java.math.BigDecimal;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Monthly review scope and comparison sources shared by validation, submission and queries. */
@Component
@RequiredArgsConstructor
public class MonthlyReviewPolicy {
    public static final String VERSION = "monthly-comparison/2.0";
    private final SubmissionRequestRepository requests;
    private final FinancialValueRepository values;
    private final BaselineResolver resolver;
    private final PlanBaselines plans;

    public record Comparison(GapBasis basis, GapComparisonType type, Submission source, Scenario scenario,
                             BigDecimal amount) {}
    public record Context(SubmissionRequest request, Submission baseline, Submission plan,
                          boolean resubmission, Map<Key, Aggregate> current, Map<Key, Aggregate> previous,
                          Map<Key, Aggregate> planned) {
        public List<Comparison> comparisons(Key key) {
            List<Comparison> result = new ArrayList<>();
            if (!inScope(request, key)) return result;
            if (!resubmission && key.scenario() == Scenario.ACTUAL && plan != null) {
                Aggregate value = planned.get(new Key(key.reportItemId(), key.year(), key.month(), Scenario.PLAN));
                if (value != null) result.add(new Comparison(GapBasis.PLAN, GapComparisonType.ACTUAL_VS_PLAN,
                        plan, Scenario.PLAN, value.total()));
            }
            if (baseline != null && !baseline.getId().equals(plan == null ? null : plan.getId())) {
                Scenario scenario = resubmission ? key.scenario() : Scenario.ESTIMATE;
                Aggregate value = previous.get(new Key(key.reportItemId(), key.year(), key.month(), scenario));
                if (value != null) result.add(new Comparison(GapBasis.PREVIOUS,
                        key.scenario() == Scenario.ACTUAL ? GapComparisonType.ACTUAL_VS_PREVIOUS : GapComparisonType.ESTIMATE_VS_PREVIOUS,
                        baseline, scenario, value.total()));
            }
            return result;
        }
        public List<Detection> detections() {
            Set<Key> keys = new LinkedHashSet<>(current.keySet());
            for (Key key : previous.keySet()) {
                if (!resubmission && key.scenario() == Scenario.ESTIMATE && key.month() == request.getMonth())
                    keys.add(new Key(key.reportItemId(), key.year(), key.month(), Scenario.ACTUAL));
                else keys.add(key);
            }
            List<Detection> result = new ArrayList<>();
            for (Key key : keys) {
                if (!inScope(request, key)) continue;
                Aggregate now = current.get(key);
                List<Comparison> comparisons = comparisons(key);
                // The direct previous value is the primary change baseline; plan is an additional first-actual comparison.
                Comparison primary = comparisons.stream().filter(c -> c.basis() == GapBasis.PREVIOUS).findFirst()
                        .orElse(comparisons.isEmpty() ? null : comparisons.get(0));
                Aggregate before = previous.get(new Key(key.reportItemId(), key.year(), key.month(),
                        resubmission ? key.scenario() : Scenario.ESTIMATE));
                if (now == null && before == null) continue;
                BigDecimal amount = now == null ? null : now.total();
                boolean changed = comparisons.stream().anyMatch(c -> !ChangeDetector.sameAmount(amount, c.amount()))
                        || baseline != null && (plan == null || !baseline.getId().equals(plan.getId())) && now != null && before == null;
                result.add(new Detection(key, now == null ? before.reportItem() : now.reportItem(), amount,
                        primary == null ? null : primary.amount(), changed,
                        now == null ? ChangeDetector.sha256("") : now.fingerprint()));
            }
            return result;
        }
        public BigDecimal primary(Key key) {
            List<Comparison> rows = comparisons(key);
            Comparison primary = rows.stream().filter(c -> c.basis() == GapBasis.PREVIOUS).findFirst()
                    .orElse(rows.isEmpty() ? null : rows.get(0));
            return primary == null ? null : primary.amount();
        }
    }
    public static boolean inScope(SubmissionRequest request, Key key) {
        return request.getType() != RequestType.MONTHLY_ACTUAL || (key.year() == request.getAnnualPlan().getYear()
                && (key.scenario() == Scenario.ACTUAL && key.month() == request.getMonth()
                || key.scenario() == Scenario.ESTIMATE && key.month() > request.getMonth()));
    }
    public SubmissionRequest request(Submission submission) { return requests.findById(submission.getRequestId()).orElseThrow(); }
    public Context load(Submission submission) {
        SubmissionRequest request = request(submission);
        return load(submission, resolver.comparisonBaseline(submission, request).submission());
    }
    public Context load(Submission submission, Submission baseline) {
        SubmissionRequest request = request(submission);
        Submission plan = plans.confirmedPlan(request.getId(), submission.getOrganizationId()).orElse(null);
        return new Context(request, baseline, plan, baseline != null && baseline.getRequestId().equals(request.getId()),
                aggregate(submission), aggregate(baseline), aggregate(plan));
    }
    public Map<Key, Aggregate> previousForecast(Context context) {
        Submission source = context.baseline();
        Set<Long> visited = new HashSet<>();
        while (source != null && source.getRequestId().equals(context.request().getId()) && visited.add(source.getId()))
            source = source.getReviewBaseline();
        return source == null || source.getRequestId().equals(context.request().getId()) ? Map.of() : aggregate(source);
    }
    public static List<io.skala.backend.domain.review.dto.ReviewTreeResponse.ComparisonValue> response(Context context, Key key, BigDecimal current) {
        return context.comparisons(key).stream().map(c -> new io.skala.backend.domain.review.dto.ReviewTreeResponse.ComparisonValue(
            (c.basis() == GapBasis.PLAN ? "계획" : context.resubmission() ? "직전 " + (key.scenario() == Scenario.ACTUAL ? "실적" : "추정") : "이전 추정")
                    + " 대비 " + (key.scenario() == Scenario.ACTUAL ? "실적" : "추정"), c.basis().name(),
            String.valueOf(c.source().getId()), c.scenario().name(), Amounts.money(c.amount()), Amounts.money(current),
            Amounts.money(Amounts.diff(current, c.amount())), Amounts.rate(current, c.amount()))).toList();
    }
    private Map<Key, Aggregate> aggregate(Submission submission) {
        return submission == null ? Map.of() : ChangeDetector.aggregate(values.findRequestYearValuesBySubmissionId(submission.getId()));
    }
}
