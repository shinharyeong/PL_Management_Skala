package io.skala.backend.domain.submission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** REQ-06 요청. REJECT면 comment 필수, rejectedReviewItemIds는 반려 항목(선택) */
public record DecisionRequest(@NotNull Decision decision, @Size(max = 4000) String comment,
                              @Size(max = 1000) List<Long> rejectedReviewItemIds, @NotBlank String expectedStateToken) {

    public enum Decision {
        APPROVE,
        REJECT
    }
}
