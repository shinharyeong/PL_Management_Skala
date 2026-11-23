package io.skala.backend.domain.submission.service;

import io.skala.backend.domain.file.service.FileStorage;
import io.skala.backend.domain.notification.dto.NotificationEvent;
import io.skala.backend.domain.notification.service.NotificationRecorder;
import io.skala.backend.domain.notification.value.NotificationType;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.repository.SubmissionRequestRepository;
import io.skala.backend.domain.request.value.RequestStatus;
import io.skala.backend.domain.review.entity.ReviewAttachment;
import io.skala.backend.domain.review.entity.ReviewExplanation;
import io.skala.backend.domain.review.entity.ReviewItem;
import io.skala.backend.domain.review.repository.ReviewAttachmentRepository;
import io.skala.backend.domain.review.repository.ReviewExplanationRepository;
import io.skala.backend.domain.review.repository.ReviewItemRepository;
import io.skala.backend.domain.review.service.Amounts;
import io.skala.backend.domain.review.service.ExplanationTargetService;
import io.skala.backend.domain.review.service.GapCalculator;
import io.skala.backend.domain.review.value.ExplanationVerdict;
import io.skala.backend.domain.submission.dto.BaselineResponse;
import io.skala.backend.domain.submission.dto.SubmissionResponse;
import io.skala.backend.domain.submission.dto.SubmitPreviewResponse;
import io.skala.backend.domain.submission.entity.ParsingJob;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.exception.SubmissionErrorCode;
import io.skala.backend.domain.submission.repository.ParsingJobRepository;
import io.skala.backend.domain.submission.repository.SubmissionRepository;
import io.skala.backend.domain.submission.service.BaselineResolver.Baseline;
import io.skala.backend.domain.submission.support.SubmissionAccess;
import io.skala.backend.domain.submission.value.BaselineKind;
import io.skala.backend.domain.submission.value.ParsingJobStatus;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import io.skala.backend.domain.user.entity.Organization;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.domain.user.repository.OrganizationRepository;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.util.StateToken;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PROOF-07 제출 내용 확인, PROOF-08 제출하기 */
@Service
@RequiredArgsConstructor
public class SubmissionSubmitService {

    private final SubmissionAccess submissionAccess;
    private final SubmissionRepository submissionRepository;
    private final SubmissionRequestRepository submissionRequestRepository;
    private final OrganizationRepository organizationRepository;
    private final ParsingJobRepository parsingJobRepository;
    private final ReviewItemRepository reviewItemRepository;
    private final ReviewExplanationRepository reviewExplanationRepository;
    private final ReviewAttachmentRepository reviewAttachmentRepository;
    private final ExplanationTargetService explanationTargetService;
    private final GapCalculator gapCalculator;
    private final BaselineResolver baselineResolver;
    private final SubmissionQueryService submissionQueryService;
    private final AppUserRepository appUserRepository;
    private final FileStorage fileStorage;
    private final NotificationRecorder notificationRecorder;
    private final Clock clock;

    @Transactional(readOnly = true)
    public SubmitPreviewResponse preview(Long submissionId, AuthenticatedUser user) {
        Submission submission = submissionAccess.loadForRead(submissionId, user);
        SubmissionRequest request = request(submission);
        Organization organization = organizationRepository.findById(submission.getOrganizationId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        ParsingJob job = parsingJobRepository.findBySubmissionId(submissionId).orElse(null);

        int nextSeq = submission.getSeq() != null ? submission.getSeq()
                : submissionRepository.findMaxSeq(submission.getRequestId(), submission.getOrganizationId()) + 1;
        String expectedLabel = SubmissionLabels.of(nextSeq, request);
        BaselineResponse baseline = null;
        if (submission.getStatus() == SubmissionStatus.DRAFT) {
            try {
                baseline = toResponse(baselineResolver.resolve(submission, request));
            } catch (BusinessException e) {
                // 기준을 고를 수 없는 상태(검토 대기 중 등)는 제출하기에서 오류로 알린다.
            }
        }

        Map<Long, ReviewExplanation> round1 = round1(submissionId);
        List<SubmitPreviewResponse.Item> items = reviewItemRepository.findAllWithReportItemBySubmissionId(submissionId)
                .stream()
                .filter(i -> Boolean.TRUE.equals(i.getActive()) && round1.containsKey(i.getId())
                        && round1.get(i.getId()).getVerdict() == ExplanationVerdict.PENDING)
                .sorted(Comparator.comparing((ReviewItem i) -> i.getReportItem().getDisplayOrder())
                        .thenComparing(ReviewItem::getMonth).thenComparing(ReviewItem::getScenario))
                .map(i -> new SubmitPreviewResponse.Item(String.valueOf(i.getId()), i.getReportItem().getName(),
                        i.getMonth(), i.getScenario(), Amounts.money(i.getCurrentAmount()),
                        round1.get(i.getId()).isAnswered()))
                .toList();
        boolean canSubmit = submission.getStatus() == SubmissionStatus.DRAFT && job != null && job.passed()
                && explanationTargetService.prepared(submissionId) && items.stream().allMatch(SubmitPreviewResponse.Item::answered);
        return new SubmitPreviewResponse(
                new SubmitPreviewResponse.FileInfo(submission.getOriginalFilename(),
                        organization.getCode() + "_" + expectedLabel + ".xlsx"),
                expectedLabel, baseline, items, canSubmit, SubmissionStateToken.of(submission, job));
    }

    @Transactional
    public SubmissionResponse submit(Long submissionId, AuthenticatedUser user, String replyMessage,
                                     String expectedStateToken) {
        Submission submission = submissionAccess.loadForOrgManager(submissionId, user);
        ParsingJob job = parsingJobRepository.findBySubmissionId(submissionId).orElse(null);
        StateToken.verify(expectedStateToken, SubmissionStateToken.of(submission, job));
        if (submission.getStatus() != SubmissionStatus.DRAFT) {
            throw SubmissionErrorCode.VALIDATION_NOT_ALLOWED.exception();
        }
        SubmissionRequest request = request(submission);
        if (request.getStatus() != RequestStatus.OPEN) {
            throw SubmissionErrorCode.REQUEST_CLOSED.exception();
        }
        if (submissionRepository.existsByRequestIdAndOrganizationIdAndStatus(submission.getRequestId(),
                submission.getOrganizationId(), SubmissionStatus.PENDING)) {
            throw SubmissionErrorCode.PENDING_ALREADY_EXISTS.exception();
        }
        if (job == null || job.getStatus() != ParsingJobStatus.SUCCEEDED) {
            throw SubmissionErrorCode.PARSING_NOT_READY.exception();
        }
        if (!job.passed()) {
            throw SubmissionErrorCode.VALIDATION_FAILED.exception();
        }
        if (!explanationTargetService.prepared(submissionId)) {
            throw SubmissionErrorCode.VALIDATION_REQUIRED.exception();
        }
        Baseline baseline = baselineResolver.resolve(submission, request);
        if (!explanationTargetService.contextMatches(submission, baseline.submission())) {
            throw SubmissionErrorCode.REVIEW_CONTEXT_CHANGED.exception();
        }
        List<Long> unanswered = explanationTargetService.unansweredItemIds(submissionId);
        if (!unanswered.isEmpty()) {
            SubmissionErrorCode code = SubmissionErrorCode.ANSWER_REQUIRED;
            throw new BusinessException(code.getStatus(), code.name(), code.getMessage(),
                    IntStream.range(0, unanswered.size()).mapToObj(i -> new BusinessException.FieldError(
                            "reviewItemIds[" + i + "]", String.valueOf(unanswered.get(i)))).toList());
        }

        // 이 시점의 Gap 기준으로 Gap을 계산해 고정한다(이후 기준을 바꿔도 소급하지 않음).
        gapCalculator.calculate(submission);
        OffsetDateTime now = OffsetDateTime.now(clock);
        int seq = submissionRepository.findMaxSeq(submission.getRequestId(), submission.getOrganizationId()) + 1;
        // 제출이 확정되는 트랜잭션 안에서 보존 위치로 복사한다. 복사에 실패하면 제출도 실패한다.
        for (ReviewAttachment attachment : reviewAttachmentRepository.findAllBySubmissionId(submissionId)) {
            attachment.moveTo(fileStorage.promote(attachment.getStoredObject()));
        }
        submission.submit(seq, appUserRepository.getReferenceById(user.id()), now,
                replyMessage == null || replyMessage.isBlank() ? null : replyMessage.strip(),
                baseline.kind() == BaselineKind.REJECTED ? baseline.submission() : null,
                fileStorage.promote(submission.getSourceObject()));
        try {
            submissionRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 같은 요청·조직에서 동시에 제출해 회차가 겹친 경우
            throw SubmissionErrorCode.PENDING_ALREADY_EXISTS.exception();
        }
        Organization organization = organizationRepository.findById(submission.getOrganizationId()).orElseThrow();
        notificationRecorder.record(new NotificationEvent(NotificationType.SUBMISSION, "submission:" + submission.getId(),
                submission.getRequestId(), submission.getOrganizationId(), submission.getId(), null,
                organization.getName() + " " + SubmissionQueryService.title(request), now));
        return submissionQueryService.toResponse(submission);
    }

    private Map<Long, ReviewExplanation> round1(Long submissionId) {
        return reviewExplanationRepository.findAllBySubmissionId(submissionId).stream()
                .filter(e -> e.getRound() == 1)
                .collect(Collectors.toMap(e -> e.getReviewItem().getId(), Function.identity()));
    }

    private BaselineResponse toResponse(Baseline baseline) {
        if (baseline.submission() == null) {
            return new BaselineResponse(BaselineKind.NONE, null, null);
        }
        Submission base = baseline.submission();
        return new BaselineResponse(baseline.kind(), String.valueOf(base.getId()), SubmissionLabels.of(base, request(base)));
    }

    private SubmissionRequest request(Submission submission) {
        return submissionRequestRepository.findById(submission.getRequestId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
