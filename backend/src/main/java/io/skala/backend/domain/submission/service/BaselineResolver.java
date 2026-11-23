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
 *   <li>월간 → 전월 승인본. 전월 요청 대상이었는데 승인본이 없으면 COMPARISON_SOURCE_UNAVAILABLE,
 *       전월 요청 자체가 없으면(첫 달) 기준 없음</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class BaselineResolver {

    private final SubmissionRepository submissionRepository;
    private final FinancialLineRepository financialLineRepository;

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
        if (request.getType() == RequestType.ANNUAL_PLAN || request.getMonth() == null || request.getMonth() <= 1) {
            // TODO(PRD 확인): 1월 월간 시트의 비교 기준을 연간 계획 확정본으로 둘지 결정되면 반영
            return Baseline.NONE;
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

    private Baseline checked(Baseline baseline) {
        if (!financialLineRepository.existsBySubmissionId(baseline.submission().getId())) {
            throw SubmissionErrorCode.COMPARISON_SOURCE_UNAVAILABLE.exception();
        }
        return baseline;
    }
}
