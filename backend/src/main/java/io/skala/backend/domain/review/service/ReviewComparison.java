package io.skala.backend.domain.review.service;

import io.skala.backend.domain.financial.entity.FinancialValue;
import io.skala.backend.domain.financial.repository.FinancialValueRepository;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.repository.SubmissionRequestRepository;
import io.skala.backend.domain.review.service.ChangeDetector.Aggregate;
import io.skala.backend.domain.review.service.ChangeDetector.Key;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.service.BaselineResolver;
import io.skala.backend.domain.submission.service.BaselineResolver.Baseline;
import io.skala.backend.domain.submission.value.BaselineKind;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 조직 화면에 보여 줄 비교 값 묶음: 현재 제출본, 승인 기준(기준값), 재제출이면 반려된 직전 제출본(직전 제출값).
 * 변경 감지(PROOF-01)는 재제출이면 반려본과 비교하지만, 화면의 '기준값'은 승인본을 보여 준다(api-spec 10절).
 */
@Component
@RequiredArgsConstructor
public class ReviewComparison {

    private final SubmissionRequestRepository submissionRequestRepository;
    private final BaselineResolver baselineResolver;
    private final FinancialValueRepository financialValueRepository;

    public record View(Submission submission, SubmissionRequest request, BaselineKind kind, Submission approved,
                       Submission previous, Map<Key, Aggregate> current, Map<Key, Aggregate> baseline,
                       Map<Key, Aggregate> previousValues, List<FinancialValue> currentCells,
                       List<FinancialValue> baselineCells, List<FinancialValue> previousCells) {

        public BigDecimal baselineOf(Key key) {
            Aggregate a = baseline.get(key);
            return a == null ? null : a.total();
        }

        public BigDecimal previousOf(Key key) {
            Aggregate a = previousValues.get(key);
            return a == null ? null : a.total();
        }

        public BigDecimal currentOf(Key key) {
            Aggregate a = current.get(key);
            return a == null ? null : a.total();
        }
    }

    public View build(Submission submission) {
        SubmissionRequest request = submissionRequestRepository.findById(submission.getRequestId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Submission previous = null;
        if (submission.getStatus() == SubmissionStatus.DRAFT) {
            try {
                Baseline resolved = baselineResolver.resolve(submission, request);
                if (resolved.kind() == BaselineKind.REJECTED) {
                    previous = resolved.submission();
                }
            } catch (BusinessException e) {
                // 검토 대기 중 등 기준을 고를 수 없는 상태면 직전 제출값 없이 보여 준다.
            }
        } else {
            previous = submission.getPreviousSubmission();
        }
        Baseline approvedBaseline = baselineResolver.resolveApproved(submission, request);
        Submission approved = approvedBaseline.submission();
        BaselineKind kind = previous != null ? BaselineKind.REJECTED : approvedBaseline.kind();

        List<FinancialValue> currentCells = financialValueRepository.findRequestYearValuesBySubmissionId(submission.getId());
        List<FinancialValue> baselineCells = approved == null ? List.of()
                : financialValueRepository.findRequestYearValuesBySubmissionId(approved.getId());
        List<FinancialValue> previousCells = previous == null ? List.of()
                : financialValueRepository.findRequestYearValuesBySubmissionId(previous.getId());
        return new View(submission, request, kind, approved, previous, ChangeDetector.aggregate(currentCells),
                ChangeDetector.aggregate(baselineCells), ChangeDetector.aggregate(previousCells), currentCells,
                baselineCells, previousCells);
    }
}
