package io.skala.backend.domain.review.dto;

import io.skala.backend.domain.review.value.ExplanationVerdict;
import java.time.OffsetDateTime;
import java.util.List;

/** openapi Explanation */
public record ExplanationResponse(
        String id,
        int round,
        String requestNote,
        UserRef requestedBy,
        OffsetDateTime requestedAt,
        OffsetDateTime dueAt,
        String answer,
        UserRef answeredBy,
        OffsetDateTime answeredAt,
        ExplanationVerdict verdict,
        String reviewNote,
        String approvedLabel,
        InheritedFrom inheritedFrom,
        List<AttachmentResponse> attachments
) {

    public record UserRef(String id, String name) {
    }

    public record InheritedFrom(String explanationId, String label) {
    }
}
