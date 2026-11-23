package io.skala.backend.domain.submission.dto;

import jakarta.validation.constraints.NotBlank;

/** PROOF-01 요청(StateTokenInput) */
public record ValidationRequest(@NotBlank String expectedStateToken) {
}
