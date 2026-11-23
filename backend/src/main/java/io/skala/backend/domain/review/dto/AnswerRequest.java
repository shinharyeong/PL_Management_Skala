package io.skala.backend.domain.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PROOF-04 요청. 공백만으로는 안 된다. */
public record AnswerRequest(@NotBlank @Size(max = 4000) String answer, @NotBlank String expectedStateToken) {
}
