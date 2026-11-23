package io.skala.backend.domain.submission.dto;

import io.skala.backend.domain.submission.entity.ParsingJob;
import io.skala.backend.domain.submission.value.ParsingJobStatus;
import java.time.OffsetDateTime;

/**
 * openapi ParsingJob. errorCode는 FAILED 사유(PARSER_TIMEOUT 등)이거나, 성공했지만 수정이 필요하면 VALIDATION_FAILED.
 */
public record ParsingJobResponse(ParsingJobStatus status, int attemptNo, String errorCode, OffsetDateTime createdAt,
                                 OffsetDateTime finishedAt) {

    public static ParsingJobResponse of(ParsingJob job) {
        return new ParsingJobResponse(job.getStatus(), job.getAttemptNo(), job.getErrorCode(), job.getCreatedAt(),
                job.getFinishedAt());
    }
}
