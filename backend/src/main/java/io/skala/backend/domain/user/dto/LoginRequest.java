package io.skala.backend.domain.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record LoginRequest(@NotBlank @Email String email, @NotBlank @Pattern(regexp = "^[0-9]{6}$") String code) {
}
