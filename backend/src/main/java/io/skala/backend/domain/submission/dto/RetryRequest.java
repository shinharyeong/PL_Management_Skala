package io.skala.backend.domain.submission.dto;

import jakarta.validation.constraints.NotBlank;

/** UP-06 요청(StateTokenInput) */
public record RetryRequest(@NotBlank String expectedStateToken) {
}
