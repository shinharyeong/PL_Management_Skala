package io.skala.backend.domain.submission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PROOF-08 요청 */
public record SubmitRequest(@Size(max = 4000) String replyMessage, @NotBlank String expectedStateToken) {
}
