package io.skala.backend.domain.user.dto;

import java.time.OffsetDateTime;

public record LoginResponse(String token, OffsetDateTime expiresAt, MeResponse me) {
}
