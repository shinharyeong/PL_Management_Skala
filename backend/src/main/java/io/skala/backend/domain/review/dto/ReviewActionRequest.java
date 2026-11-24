package io.skala.backend.domain.review.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/** GAP-07 선택 항목 처리 요청 */
public record ReviewActionRequest(
        @NotNull Action action,
        @NotNull @Size(min = 1, max = 500) List<@Valid Target> items,
        @Size(max = 4000) String note,
        OffsetDateTime dueAt,
        @Size(max = 5000) String message
) {

    public enum Action {
        EXCLUDE,
        SELECT,
        REQUEST_EXPLANATION
    }

    public record Target(@NotNull Long reviewItemId, @NotBlank String expectedStateToken) {
    }
}
