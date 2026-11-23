package io.skala.backend.domain.review.dto;

import io.skala.backend.domain.financial.value.Scenario;
import java.util.List;

/** HIST-02 제출본 상세의 소명 부분 */
public record SubmissionReviewDetail(List<Written> explanations, List<ExplanationResponse> inheritedExplanations,
                                     List<Rejected> rejectedItems) {

    /** 이 제출본에서 조직이 쓴 1회차 소명 */
    public record Written(String reviewItemId, String item, int month, Scenario scenario, String answer) {
    }

    /**
     * 재제출 요청 때 고른 반려 항목.
     * reason은 재제출 요청 코멘트(항목별 사유는 따로 저장하지 않음), previousExplanation은 그 항목의 마지막 소명.
     */
    public record Rejected(String reviewItemId, String item, String sourceCell, String reason,
                           ExplanationResponse previousExplanation) {
    }
}
