package io.skala.backend.domain.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthCodeRequest(@NotBlank @Email @Size(max = 320) String email) {
}
