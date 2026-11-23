package io.skala.backend.domain.review.service;

import io.skala.backend.domain.review.dto.ExplanationDecisionRequest;
import io.skala.backend.domain.review.dto.ExplanationDecisionResponse;
import io.skala.backend.domain.review.entity.ReviewExplanation;
import io.skala.backend.domain.review.entity.ReviewItem;
import io.skala.backend.domain.review.entity.ReviewWorkflowEvent;
import io.skala.backend.domain.review.exception.ReviewErrorCode;
import io.skala.backend.domain.review.repository.ReviewExplanationRepository;
import io.skala.backend.domain.review.repository.ReviewWorkflowEventRepository;
import io.skala.backend.domain.review.value.ExplanationVerdict;
import io.skala.backend.domain.review.value.WorkflowAction;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.domain.submission.value.SubmissionStatus;
import io.skala.backend.domain.user.entity.AppUser;
import io.skala.backend.domain.user.repository.AppUserRepository;
import io.skala.backend.domain.user.value.UserRole;
import io.skala.backend.global.security.AuthenticatedUser;
import io.skala.backend.global.util.StateToken;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GAP-11 소명 수락 / 재요청. 검토 대기(PENDING) 제출본의 현재 회차 소명을 판단한다.
 * 재요청이면 현재 회차를 REJECTED로 닫고 다음 회차를 요청 메모·기한과 함께 연다(review_item.current_round 증가).
 */
@Service
@RequiredArgsConstructor
public class ExplanationDecisionService {

    private final ReviewItemQueryService reviewItemQueryService;
    private final ReviewExplanationRepository reviewExplanationRepository;
    private final ReviewWorkflowEventRepository reviewWorkflowEventRepository;
    private final AppUserRepository appUserRepository;
    private final Clock clock;

    @Transactional
    public ExplanationDecisionResponse decide(Long reviewItemId, AuthenticatedUser user,
                                             ExplanationDecisionRequest request) {
        user.requireRole(UserRole.PLANNER);
        ReviewItem item = reviewItemQueryService.lockForCommand(reviewItemId, user);
        Submission submission = item.getSubmission();
        List<ReviewExplanation> explanations = reviewExplanationRepository.findAllByReviewItemIdOrderByRoundAsc(item.getId());
        ReviewExplanation current = ReviewItemQueryService.currentExplanation(explanations, item);
        StateToken.verify(request.expectedStateToken(), ReviewItemTokens.of(item, current, submission));
        if (submission.getStatus() != SubmissionStatus.PENDING || current == null
                || current.getVerdict() != ExplanationVerdict.PENDING) {
            throw ReviewErrorCode.EXPLANATION_LOCKED.exception();
        }
        if (!current.isAnswered()) {
            throw ReviewErrorCode.ANSWER_MISSING.exception();
        }
        AppUser planner = appUserRepository.getReferenceById(user.id());
        OffsetDateTime now = OffsetDateTime.now(clock);
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();

        ReviewExplanation result;
        if (request.verdict() == ExplanationDecisionRequest.Verdict.ACCEPT) {
            current.decide(ExplanationVerdict.ACCEPTED, note, planner, now);
            result = current;
        } else {
            if (note == null) {
                throw ReviewErrorCode.NOTE_REQUIRED.exception();
            }
            current.decide(ExplanationVerdict.REJECTED, note, planner, now);
            int round = item.openNextRound();
            result = reviewExplanationRepository.save(
                    ReviewExplanation.requested(item, round, note, planner, now, request.dueAt()));
            reviewWorkflowEventRepository.save(ReviewWorkflowEvent.of(item, WorkflowAction.REQUEST_EXPLANATION, round,
                    note, request.dueAt(), planner, now));
        }
        reviewExplanationRepository.flush();
        return new ExplanationDecisionResponse(reviewItemQueryService.toResponse(result),
                ReviewTabs.of(item, result), ReviewItemTokens.of(item, result, submission));
    }
}
