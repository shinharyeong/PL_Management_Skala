package io.skala.backend.domain.review.dto;

import io.skala.backend.domain.review.value.GapComparisonType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** GAP-04 미리보기·GAP-05 적용 요청(GapThresholdInput). 적용할 때만 expectedStateToken이 필요하다. */
public record GapThresholdRequest(
        @NotNull GapComparisonType comparison,
        @NotNull Boolean percentEnabled,
        @NotNull @Pattern(regexp = "^[0-9]{1,24}(\\.[0-9]{1,6})?$") String percent,
        @NotNull Boolean amountEnabled,
        @NotNull @Pattern(regexp = "^[0-9]{1,24}$") String amount,
        String expectedStateToken
) {
}
