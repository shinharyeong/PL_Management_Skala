package io.skala.backend.domain.request.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

/** Existing monthly request: dispatch only to organizations not previously selected. */
public record AddRequestTargetsRequest(
        @NotEmpty List<@NotNull Long> organizationIds,
        @NotNull OffsetDateTime dueAt,
        @Size(max = 5000) String message
) {}
