package io.skala.backend.domain.submission.service;

import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.repository.SubmissionRequestRepository;
import io.skala.backend.domain.review.service.ExplanationTargetService;
import io.skala.backend.domain.submission.dto.BaselineResponse;
import io.skala.backend.domain.submission.dto.ValidationResponse;
import io.skala.backend.domain.submission.entity.ParsingJob;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.exception.SubmissionErrorCode;
import io.skala.backend.domain.submission.repository.ParsingJobRepository;
import io.skala.backend.domain.submission.service.BaselineResolver.Baseline;
import io.skala.backend.domain.submission.support.SubmissionAccess;
import io.skala.backend.domain.submission.value.BaselineKind;
import io.skala.backend.domain.submission.value.ParsingJobStatus;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.util.StateToken;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PROOF-01 비교 기준 대비 변경 감지·소명 대상 생성. */
@Service
@RequiredArgsConstructor
public class SubmissionValidationService {

    private final SubmissionAccess submissionAccess;
    private final ParsingJobRepository parsingJobRepository;
    private final SubmissionRequestRepository submissionRequestRepository;
    private final BaselineResolver baselineResolver;
    private final ExplanationTargetService explanationTargetService;

    @Transactional
    public ValidationResponse validate(Long submissionId, String expectedStateToken, AppUser actor) {
        Submission submission = submissionAccess.loadForOrgManager(submissionId, actor);
        ParsingJob job = parsingJobRepository.findBySubmissionId(submissionId).orElse(null);
        StateToken.verify(expectedStateToken, SubmissionStateToken.of(submission, job));
        if (submission.getStatus() != SubmissionStatus.DRAFT) {
            throw SubmissionErrorCode.VALIDATION_NOT_ALLOWED.exception();
        }
        if (job == null || job.getStatus() != ParsingJobStatus.SUCCEEDED) {
            throw SubmissionErrorCode.PARSING_NOT_READY.exception();
        }
        if (!job.passed()) {
            // 수정 필요 항목이 남아 있으면 소명 단계로 넘어가지 않는다(UP-05에서 확인).
            throw SubmissionErrorCode.VALIDATION_FAILED.exception();
        }

        SubmissionRequest request = submissionRequestRepository.findById(submission.getRequestId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Baseline baseline = baselineResolver.resolve(submission, request);
        ExplanationTargetService.Progress progress = explanationTargetService.prepare(
                submission, baseline.submission(), baseline.kind() == BaselineKind.REJECTED);

        return new ValidationResponse(
                toResponse(baseline),
                new ValidationResponse.Progress(progress.answered(), progress.required()),
                progress.complete(),
                SubmissionStateToken.of(submission, job));
    }

    private BaselineResponse toResponse(Baseline baseline) {
        if (baseline.submission() == null) {
            return new BaselineResponse(BaselineKind.NONE, null, null);
        }
        Submission base = baseline.submission();
        SubmissionRequest baseRequest = submissionRequestRepository.findById(base.getRequestId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return new BaselineResponse(baseline.kind(), String.valueOf(base.getId()),
                SubmissionLabels.of(base, baseRequest));
    }
}
