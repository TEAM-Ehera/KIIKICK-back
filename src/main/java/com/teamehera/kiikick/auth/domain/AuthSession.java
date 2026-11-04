package com.teamehera.kiikick.auth.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record AuthSession(
        UUID id,
        UUID userId,
        UUID deviceId,
        String refreshTokenHash,
        long refreshGeneration,
        LocalDateTime expiresAt,
        LocalDateTime revokedAt,
        LocalDateTime createdAt
) {
}
