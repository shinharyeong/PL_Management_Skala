package io.skala.backend.domain.review.dto;

import io.skala.backend.domain.review.entity.GapThreshold;
import io.skala.backend.domain.review.service.Amounts;
import io.skala.backend.domain.review.value.GapComparisonType;
import java.time.OffsetDateTime;

/** openapi GapThreshold. 아직 적용한 기준이 없으면 id·appliedAt·appliedBy가 null이고 두 조건이 꺼져 있다. */
public record GapThresholdResponse(String id, GapComparisonType comparison, boolean percentEnabled, String percent,
                                   boolean amountEnabled, String amount, OffsetDateTime appliedAt, UserRef appliedBy) {

    public record UserRef(String id, String name) {
    }

    public static GapThresholdResponse of(GapThreshold t) {
        return new GapThresholdResponse(String.valueOf(t.getId()), t.getComparison(), t.getPercentEnabled(),
                Amounts.money(t.getPercent()), t.getAmountEnabled(), Amounts.money(t.getAmount()), t.getAppliedAt(),
                new UserRef(String.valueOf(t.getAppliedBy().getId()), t.getAppliedBy().getName()));
    }

    public static GapThresholdResponse none(GapComparisonType comparison) {
        return new GapThresholdResponse(null, comparison, false, "0", false, "0", null, null);
    }
}
