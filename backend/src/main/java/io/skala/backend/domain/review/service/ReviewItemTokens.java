package io.skala.backend.domain.review.service;

import io.skala.backend.domain.review.entity.ReviewExplanation;
import io.skala.backend.domain.review.entity.ReviewItem;
import io.skala.backend.domain.submission.entity.Submission;
import io.skala.backend.global.util.StateToken;

/** 소명 대상 항목의 stateToken: 재검증(지문·회차)·답변·첨부·제출 상태가 바뀌면 달라진다. */
public final class ReviewItemTokens {

    private ReviewItemTokens() {
    }

    public static String of(ReviewItem item, ReviewExplanation explanation, Submission submission) {
        return StateToken.of("review-item", item.getId(), item.getActive(), item.getSourceFingerprint(),
                item.getContextFingerprint(), item.getCurrentRound(), item.getWorkflowState(), submission.getStatus(),
                explanation == null ? null : explanation.getId(),
                explanation == null ? null : explanation.getVerdict(),
                explanation == null ? null : explanation.getAnswer(),
                explanation == null ? null : explanation.getAttachmentRevision());
    }
}
