package io.skala.backend.domain.submission.service;

import io.skala.backend.domain.master.dto.PublishedMaster;
import io.skala.backend.domain.master.repository.AnnualMasterRepository;
import io.skala.backend.domain.master.repository.MasterVersionRepository;
import io.skala.backend.domain.master.service.MasterPublisher;
import io.skala.backend.domain.request.entity.SubmissionRequest;
import io.skala.backend.domain.request.repository.SubmissionRequestRepository;
import io.skala.backend.domain.request.value.RequestType;
import io.skala.backend.domain.review.entity.ReviewExplanation;
import io.skala.backend.domain.review.entity.ReviewItem;
import io.skala.backend.domain.review.repository.ReviewExplanationRepository;
import io.skala.backend.domain.review.repository.ReviewItemRepository;
import io.skala.backend.domain.review.value.ExplanationVerdict;
import io.skala.backend.domain.review.value.WorkflowState;
import io.skala.backend.domain.submission.dto.DecisionRequest;
import io.skala.backend.domain.submission.dto.DecisionResponse;
import io.skala.backend.domain.submission.entity.ParsingJob;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.exception.SubmissionErrorCode;
import io.skala.backend.domain.submission.repository.ParsingJobRepository;
import io.skala.backend.domain.submission.repository.SubmissionRepository;
import io.skala.backend.domain.submission.support.SubmissionAccess;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.exception.BusinessException;
import io.skala.backend.global.exception.ErrorCode;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.util.StateToken;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * REQ-06 회신 수락 / 재제출 요청.
 * <ul>
 *   <li>수락: 제외하지 않은 항목의 현재 회차 소명이 모두 수락돼야 한다. 승인 순번을 붙이고 같은 트랜잭션에서 master 발행을 확인한다.</li>
 *   <li>재제출 요청: 코멘트 필수. 고른 항목은 review_item.rejected로 표시해 재제출 때 다시 소명하게 한다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SubmissionDecisionService {

    private final SubmissionAccess submissionAccess;
    private final SubmissionRepository submissionRepository;
    private final SubmissionRequestRepository submissionRequestRepository;
    private final ParsingJobRepository parsingJobRepository;
    private final ReviewItemRepository reviewItemRepository;
    private final ReviewExplanationRepository reviewExplanationRepository;
    private final AppUserRepository appUserRepository;
    private final MasterPublisher masterPublisher;
    private final MasterVersionRepository masterVersionRepository;
    private final AnnualMasterRepository annualMasterRepository;
    private final SubmissionQueryService submissionQueryService;
    private final Clock clock;

    @Transactional
    public DecisionResponse decide(Long submissionId, AuthenticatedUser user, DecisionRequest request) {
        user.requireRole(UserRole.PLANNER);
        Submission submission = submissionAccess.lockForRead(submissionId, user);
        ParsingJob job = parsingJobRepository.findBySubmissionId(submissionId).orElse(null);
        StateToken.verify(request.expectedStateToken(), SubmissionStateToken.of(submission, job));
        if (submission.getStatus() != SubmissionStatus.PENDING) {
            throw SubmissionErrorCode.INVALID_SUBMISSION_STATE.exception();
        }
        AppUser planner = appUserRepository.getReferenceById(user.id());
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (request.decision() == DecisionRequest.Decision.REJECT) {
            reject(submission, request, planner, now);
            return new DecisionResponse(submissionQueryService.toResponse(submission), null);
        }
        List<ReviewItem> items = reviewItemRepository.findAllWithReportItemBySubmissionId(submissionId);
        Map<Long, ReviewItem> byId = items.stream().collect(Collectors.toMap(ReviewItem::getId, Function.identity()));
        boolean pendingExplanation = reviewExplanationRepository.findAllBySubmissionId(submissionId).stream()
                .anyMatch(e -> notAccepted(byId.get(e.getReviewItem().getId()), e));
        if (pendingExplanation) {
            throw SubmissionErrorCode.EXPLANATIONS_NOT_ACCEPTED.exception();
        }
        int approvedSeq = submissionRepository.findMaxApprovedSeq(submission.getRequestId(),
                submission.getOrganizationId()) + 1;
        submission.approve(approvedSeq, planner, now);
        DecisionResponse.MasterRef master = masterPublisher.publishIfComplete(submissionId)
                .map(published -> masterRef(published, submission))
                .orElse(null);
        return new DecisionResponse(submissionQueryService.toResponse(submission), master);
    }

    /** 제외하지 않은 활성 항목의 현재 회차 소명이 수락되지 않았는지 */
    private static boolean notAccepted(ReviewItem item, ReviewExplanation e) {
        return item != null && Boolean.TRUE.equals(item.getActive())
                && item.getWorkflowState() != WorkflowState.EXCLUDED
                && item.getWorkflowState() != WorkflowState.APPROVED
                && Objects.equals(e.getRound(), item.getCurrentRound())
                && e.getVerdict() != ExplanationVerdict.ACCEPTED;
    }

    private void reject(Submission submission, DecisionRequest request, AppUser planner, OffsetDateTime now) {
        String comment = request.comment() == null ? "" : request.comment().strip();
        if (comment.isEmpty()) {
            throw SubmissionErrorCode.COMMENT_REQUIRED.exception();
        }
        List<Long> ids = request.rejectedReviewItemIds() == null ? List.of() : request.rejectedReviewItemIds();
        if (!ids.isEmpty()) {
            Map<Long, ReviewItem> items = reviewItemRepository.findAllWithReportItemBySubmissionId(submission.getId())
                    .stream().collect(Collectors.toMap(ReviewItem::getId, Function.identity()));
            Set<Long> unique = new HashSet<>(ids);
            if (!items.keySet().containsAll(unique)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "이 제출본의 항목이 아닌 반려 항목이 있습니다.");
            }
            unique.forEach(id -> items.get(id).markRejected());
        }
        submission.reject(comment, planner, now);
    }

    private DecisionResponse.MasterRef masterRef(PublishedMaster published, Submission submission) {
        SubmissionRequest request = submissionRequestRepository.findById(submission.getRequestId()).orElseThrow();
        int year = request.getAnnualPlan().getYear();
        if (published.type() == RequestType.ANNUAL_PLAN) {
            return annualMasterRepository.findById(published.id())
                    .map(m -> new DecisionResponse.MasterRef(String.valueOf(m.getId()), year, null, m.getSeq(),
                            published.label(), "CONFIRMED", m.getCreatedAt()))
                    .orElseThrow();
        }
        return masterVersionRepository.findById(published.id())
                .map(m -> new DecisionResponse.MasterRef(String.valueOf(m.getId()), year, request.getMonth(), m.getSeq(),
                        published.label(), "CONFIRMED", m.getCreatedAt()))
                .orElseThrow();
    }
}
