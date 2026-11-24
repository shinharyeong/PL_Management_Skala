package io.skala.backend.domain.review.service;

import io.skala.backend.domain.review.dto.ReviewActionRequest;
import io.skala.backend.domain.review.dto.ReviewActionRequest.Action;
import io.skala.backend.domain.review.dto.ReviewActionResponse;
import io.skala.backend.domain.review.entity.ReviewExplanation;
import io.skala.backend.domain.review.entity.ReviewItem;
import io.skala.backend.domain.review.entity.ReviewWorkflowEvent;
import io.skala.backend.domain.review.exception.ReviewErrorCode;
import io.skala.backend.domain.review.repository.ReviewExplanationRepository;
import io.skala.backend.domain.review.repository.ReviewItemRepository;
import io.skala.backend.domain.review.repository.ReviewWorkflowEventRepository;
import io.skala.backend.domain.review.value.ExplanationVerdict;
import io.skala.backend.domain.review.value.WorkflowAction;
import io.skala.backend.domain.review.value.WorkflowState;
import io.skala.backend.domain.submission.entity.Submission;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * GAP-07 선택 항목 처리. 같은 제출 요청의 검토 대기(PENDING) 제출본 항목만 한 번에 처리하며, 한 항목이라도 안 되면 전부 취소한다.
 * <ul>
 *   <li>EXCLUDE: 검토 필요·선정 → 제외(사유 기록)</li>
 *   <li>SELECT: 검토 필요 → 선정</li>
 *   <li>REQUEST_EXPLANATION(기한·메시지 필수): 소명이 없으면 현재 회차로 요청, 판단 전인 소명이 있으면 그 회차를 닫고 다음 회차로 요청</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReviewActionService {

    private final ReviewItemRepository reviewItemRepository;
    private final ReviewExplanationRepository reviewExplanationRepository;
    private final ReviewWorkflowEventRepository reviewWorkflowEventRepository;
    private final ReviewTreeService reviewTreeService;
    private final SubmissionAccess submissionAccess;
    private final AppUserRepository appUserRepository;
    private final Clock clock;

    @Transactional
    public ReviewActionResponse act(AuthenticatedUser user, ReviewActionRequest request) {
        user.requireRole(UserRole.PLANNER);
        Set<Long> ids = new HashSet<>();
        request.items().forEach(t -> ids.add(t.reviewItemId()));
        if (ids.size() != request.items().size()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 항목이 두 번 들어 있습니다.");
        }
        String message = blankToNull(request.message());
        if (request.action() == Action.REQUEST_EXPLANATION && (request.dueAt() == null || message == null)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "소명 요청에는 회신 기한과 메시지가 필요합니다.");
        }

        // 제출본 행을 ID 순서로 잠근 뒤 항목을 읽는다(교착 방지).
        Set<Long> submissionIds = new TreeSet<>();
        for (Long id : ids) {
            submissionIds.add(reviewItemRepository.findSubmissionId(id)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND)));
        }
        Long requestId = null;
        Map<Long, Submission> submissions = new HashMap<>();
        for (Long submissionId : submissionIds) {
            Submission submission = submissionAccess.lockForRead(submissionId, user);
            if (requestId != null && !requestId.equals(submission.getRequestId())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "같은 제출 요청의 항목만 함께 처리할 수 있습니다.");
            }
            requestId = submission.getRequestId();
            if (submission.getStatus() != SubmissionStatus.PENDING) {
                throw ReviewErrorCode.REVIEW_LOCKED.exception();
            }
            submissions.put(submissionId, submission);
        }

        AppUser planner = appUserRepository.getReferenceById(user.id());
        OffsetDateTime now = OffsetDateTime.now(clock);
        String note = blankToNull(request.note());
        List<ReviewActionResponse.Item> results = new ArrayList<>();
        for (ReviewActionRequest.Target target : request.items()) {
            ReviewItem item = reviewItemRepository.findById(target.reviewItemId()).orElseThrow();
            Submission submission = submissions.get(item.getSubmission().getId());
            List<ReviewExplanation> explanations = reviewExplanationRepository.findAllByReviewItemIdOrderByRoundAsc(item.getId());
            ReviewExplanation current = ReviewItemQueryService.currentExplanation(explanations, item);
            StateToken.verify(target.expectedStateToken(), ReviewItemTokens.of(item, current, submission));
            if (!Boolean.TRUE.equals(item.getActive())) {
                throw ReviewErrorCode.REVIEW_STAGE_CONFLICT.exception();
            }
            if (item.getWorkflowState() == WorkflowState.EXCLUDED) {
                throw ReviewErrorCode.REVIEW_EXCLUDED.exception();
            }
            String tab = ReviewTabs.of(item, current);
            ReviewExplanation after = current;
            switch (request.action()) {
                case EXCLUDE -> {
                    requireTab(tab, "NEEDS_REVIEW", "SELECTED");
                    item.exclude(note);
                    event(item, WorkflowAction.EXCLUDE, item.getCurrentRound(), note, null, planner, now);
                }
                case SELECT -> {
                    requireTab(tab, "NEEDS_REVIEW");
                    item.select();
                    event(item, WorkflowAction.SELECT, item.getCurrentRound(), note, null, planner, now);
                }
                case REQUEST_EXPLANATION -> {
                    requireTab(tab, "NEEDS_REVIEW", "SELECTED");
                    after = request(item, current, message, request.dueAt(), planner, now);
                    event(item, WorkflowAction.REQUEST_EXPLANATION, after.getRound(), message, request.dueAt(),
                            planner, now);
                }
            }
            reviewExplanationRepository.flush();
            results.add(new ReviewActionResponse.Item(String.valueOf(item.getId()), ReviewTabs.of(item, after),
                    ReviewItemTokens.of(item, after, submission)));
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        submissionIds.forEach(id -> reviewTreeService.tabCounts(id).forEach((k, v) -> counts.merge(k, v, Integer::sum)));
        return new ReviewActionResponse(results, counts);
    }

    /** 소명이 없으면 현재 회차로, 판단 전인 소명이 있으면 그 회차를 닫고 다음 회차로 요청한다. */
    private ReviewExplanation request(ReviewItem item, ReviewExplanation current, String message, OffsetDateTime dueAt,
                                      AppUser planner, OffsetDateTime now) {
        if (current == null) {
            item.markRequested();
            return reviewExplanationRepository.save(
                    ReviewExplanation.requested(item, item.getCurrentRound(), message, planner, now, dueAt));
        }
        if (current.getVerdict() != ExplanationVerdict.PENDING || current.getRequestedAt() != null) {
            // 이미 판단했거나(수락) 요청해 둔 회차는 GAP-11에서 처리한다.
            throw ReviewErrorCode.REVIEW_STAGE_CONFLICT.exception();
        }
        current.decide(ExplanationVerdict.REJECTED, message, planner, now);
        int round = item.openNextRound();
        return reviewExplanationRepository.save(ReviewExplanation.requested(item, round, message, planner, now, dueAt));
    }

    private void event(ReviewItem item, WorkflowAction action, int round, String note, OffsetDateTime dueAt,
                       AppUser planner, OffsetDateTime now) {
        reviewWorkflowEventRepository.save(ReviewWorkflowEvent.of(item, action, round, note, dueAt, planner, now));
    }

    private static void requireTab(String tab, String... allowed) {
        for (String a : allowed) {
            if (a.equals(tab)) {
                return;
            }
        }
        throw ReviewErrorCode.REVIEW_STAGE_CONFLICT.exception();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
