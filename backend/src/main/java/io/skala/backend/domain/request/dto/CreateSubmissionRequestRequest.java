package io.skala.backend.domain.request.dto;

import io.skala.backend.domain.request.value.RequestType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/** REQ-02. month는 월간 요청만 필수, organizationIds는 실제로 요청을 보낼 조직(나머지 사용 중 조직은 미선택 대상). */
public record CreateSubmissionRequestRequest(
        @NotNull RequestType type,
        @NotNull @Min(2000) @Max(9999) Integer year,
        @Min(1) @Max(12) Integer month,
        @NotNull OffsetDateTime dueAt,
        @Size(max = 5000) String message,
        @NotEmpty List<@NotNull Long> organizationIds
) {
}
