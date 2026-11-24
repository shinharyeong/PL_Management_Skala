package io.skala.backend.domain.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/** GAP-11 요청. verdict: ACCEPT / REQUEST_AGAIN(note 필수) */
public record ExplanationDecisionRequest(@NotNull Verdict verdict, @Size(max = 4000) String note, OffsetDateTime dueAt,
                                         @NotBlank String expectedStateToken) {

    public enum Verdict {
        ACCEPT,
        REQUEST_AGAIN
    }
}
