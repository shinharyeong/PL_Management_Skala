package io.skala.backend.domain.request.dto;

import io.skala.backend.domain.request.value.RequestStatus;
import io.skala.backend.domain.request.value.RequestType;
import java.time.OffsetDateTime;

/** 제출 요청 요약(docs/api/openapi.yaml SubmissionRequest). */
public record SubmissionRequestResponse(
        String id,
        RequestType type,
        int year,
        Integer month,
        String title,
        RequestStatus status,
        String message,
        OffsetDateTime dueAt,
        OffsetDateTime requestedAt,
        UserRef requestedBy,
        long submittedCount,
        long targetCount,
        String stateToken
) {
    public record UserRef(String id, String name) {
    }
}
