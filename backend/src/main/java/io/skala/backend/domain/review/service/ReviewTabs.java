package io.skala.backend.domain.review.service;

import io.skala.backend.domain.review.entity.ReviewExplanation;
import io.skala.backend.domain.review.entity.ReviewItem;
import io.skala.backend.domain.review.value.ExplanationVerdict;
import io.skala.backend.domain.review.value.WorkflowState;

/**
 * Gap 상태 탭(FR-MONTH-006). 진행 상태(workflow_state)와 현재 소명 회차로 계산한다.
 * <ul>
 *   <li>EXCLUDED: 제외한 항목</li>
 *   <li>ACCEPTED: 현재 회차 소명을 수락함</li>
 *   <li>IN_PROGRESS: 소명을 요청했고 아직 답이 없음</li>
 *   <li>RE_REVIEW: 다시 요청한 소명에 답이 와서 다시 검토할 차례</li>
 *   <li>SELECTED: 검토 대상으로 고름</li>
 *   <li>NEEDS_REVIEW: 그 밖(검토 필요)</li>
 * </ul>
 */
public final class ReviewTabs {

    private ReviewTabs() {
    }

    public static String of(ReviewItem item, ReviewExplanation current) {
        if (item.getWorkflowState() == WorkflowState.EXCLUDED) {
            return "EXCLUDED";
        }
        if (current != null && current.getVerdict() == ExplanationVerdict.ACCEPTED) {
            return "ACCEPTED";
        }
        boolean requested = current != null && current.getRequestedAt() != null
                && current.getVerdict() == ExplanationVerdict.PENDING;
        if (requested && !current.isAnswered()) {
            return "IN_PROGRESS";
        }
        if (requested) {
            return "RE_REVIEW";
        }
        if (item.getWorkflowState() == WorkflowState.SELECTED) {
            return "SELECTED";
        }
        return "NEEDS_REVIEW";
    }
}
