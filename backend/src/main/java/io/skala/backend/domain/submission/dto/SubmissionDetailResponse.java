package io.skala.backend.domain.submission.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.skala.backend.domain.review.dto.ExplanationResponse;
import io.skala.backend.domain.review.dto.SubmissionReviewDetail;
import java.time.OffsetDateTime;
import java.util.List;

/** UP-04 · HIST-02 제출본 상세: Submission 필드 + 소명·반려 항목·처리 이력(한 단계로 펼쳐 내려준다) */
public record SubmissionDetailResponse(
        @JsonUnwrapped SubmissionResponse submission,
        List<SubmissionReviewDetail.Written> explanations,
        List<ExplanationResponse> inheritedExplanations,
        List<SubmissionReviewDetail.Rejected> rejectedItems,
        List<HistoryEvent> history
) {

    /** event: SUBMITTED · RESUBMIT_REQUESTED · RESUBMITTED · APPROVED */
    public record HistoryEvent(String event, SubmissionResponse.UserRef actor, OffsetDateTime at, String label) {
    }
}
