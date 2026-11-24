package io.skala.backend.domain.submission.service;

import io.skala.backend.domain.financial.repository.FinancialLineRepository;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.value.RequestType;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.exception.SubmissionErrorCode;
import io.skala.backend.domain.submission.repository.SubmissionRepository;
import io.skala.backend.domain.submission.value.BaselineKind;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * 소명 비교 기준 버전을 고른다(PROOF-01).
 *
 * <ol>
 *   <li>같은 요청에서 마지막 제출본이 반려(REJECTED)면 → 반려된 제출본(재제출)</li>
 *   <li>같은 요청에 승인본이 있으면 → 그 승인본</li>
 *   <li>연간 계획 → 기준 없음</li>
 *   <li>월간 1월 → 확정 연간 계획(request_target.confirmed_plan_submission_id), 없으면 기준 없음</li>
 *   <li>월간 → 전월 승인본. 전월 요청 대상이었는데 승인본이 없으면 COMPARISON_SOURCE_UNAVAILABLE,
 *       전월 요청 자체가 없으면(첫 달) 기준 없음</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class BaselineResolver {

    private final SubmissionRepository submissionRepository;
    private final FinancialLineRepository financialLineRepository;
    private final PlanBaselines planBaselines;

    public record Baseline(BaselineKind kind, Submission submission) {

        public static final Baseline NONE = new Baseline(BaselineKind.NONE, null);
    }

    public Baseline resolve(Submission submission, SubmissionRequest request) {
        Optional<Submission> latest = submissionRepository.findFirstByRequestIdAndOrganizationIdAndStatusNotOrderBySeqDesc(
                submission.getRequestId(), submission.getOrganizationId(), SubmissionStatus.DRAFT);
        if (latest.isPresent()) {
            Submission last = latest.get();
            if (last.getStatus() == SubmissionStatus.PENDING) {
                // 검토 대기 중인 제출본이 있으면 새 초안을 검증하지 않는다.
                throw SubmissionErrorCode.VALIDATION_NOT_ALLOWED.exception();
            }
            if (last.getStatus() == SubmissionStatus.REJECTED) {
                return checked(new Baseline(BaselineKind.REJECTED, last));
            }
            if (last.getStatus() == SubmissionStatus.APPROVED) {
                return checked(new Baseline(BaselineKind.PREV_MONTH_APPROVED, last));
            }
        }
        if (request.getType() == RequestType.ANNUAL_PLAN || request.getMonth() == null) {
            return Baseline.NONE;
        }
        if (request.getMonth() <= 1) {
            // 1월은 전월이 없어 확정 연간 계획과 비교한다(팀 결정 2026-11-23). 계획이 없는 조직은 기준 없음.
            return planBaselines.confirmedPlan(request.getId(), submission.getOrganizationId())
                    .map(plan -> checked(new Baseline(BaselineKind.ANNUAL_PLAN, plan)))
                    .orElse(Baseline.NONE);
        }
        Long annualPlanId = request.getAnnualPlan().getId();
        int previousMonth = request.getMonth() - 1;
        Optional<Submission> approved = submissionRepository.findApprovedInMonth(annualPlanId,
                RequestType.MONTHLY_ACTUAL, previousMonth, submission.getOrganizationId(), Limit.of(1)).stream().findFirst();
        if (approved.isPresent()) {
            return checked(new Baseline(BaselineKind.PREV_MONTH_APPROVED, approved.get()));
        }
        if (submissionRepository.existsTargetInMonth(annualPlanId, RequestType.MONTHLY_ACTUAL, previousMonth,
                submission.getOrganizationId())) {
            throw SubmissionErrorCode.COMPARISON_SOURCE_UNAVAILABLE.exception();
        }
        return Baseline.NONE;
    }

    /**
     * 화면 표시용 승인 기준. 반려본을 건너뛰고 같은 요청의 최신 승인본 → 전월 승인본 → 없음 순으로 고른다.
     * 재제출 화면에서 '기준값'(승인본)과 '직전 제출값'(반려본)을 함께 보여 줄 때 쓴다. 예외를 던지지 않는다.
     */
    public Baseline resolveApproved(Submission submission, SubmissionRequest request) {
        Optional<Submission> sameRequest = submissionRepository
                .findFirstByRequestIdAndOrganizationIdAndStatusOrderByApprovedSeqDesc(submission.getRequestId(),
                        submission.getOrganizationId(), SubmissionStatus.APPROVED);
        if (sameRequest.isPresent()) {
            return new Baseline(BaselineKind.PREV_MONTH_APPROVED, sameRequest.get());
        }
        if (request.getType() == RequestType.ANNUAL_PLAN || request.getMonth() == null) {
            return Baseline.NONE;
        }
        if (request.getMonth() <= 1) {
            return planBaselines.confirmedPlan(request.getId(), submission.getOrganizationId())
                    .map(plan -> new Baseline(BaselineKind.ANNUAL_PLAN, plan))
                    .orElse(Baseline.NONE);
        }
        return submissionRepository.findApprovedInMonth(request.getAnnualPlan().getId(), RequestType.MONTHLY_ACTUAL,
                        request.getMonth() - 1, submission.getOrganizationId(), Limit.of(1)).stream().findFirst()
                .map(s -> new Baseline(BaselineKind.PREV_MONTH_APPROVED, s))
                .orElse(Baseline.NONE);
    }

    private Baseline checked(Baseline baseline) {
        if (!financialLineRepository.existsBySubmissionId(baseline.submission().getId())) {
            throw SubmissionErrorCode.COMPARISON_SOURCE_UNAVAILABLE.exception();
        }
        return baseline;
    }
}
