package io.skala.backend.domain.user.dto;

import java.time.OffsetDateTime;

public record AuthCodeResponse(String email, OffsetDateTime expiresAt, OffsetDateTime resendAvailableAt) {
}
