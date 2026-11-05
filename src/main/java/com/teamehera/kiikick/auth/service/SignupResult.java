package com.teamehera.kiikick.auth.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

public record SignupResult(
        UUID sessionId,
        String accessToken,
        String refreshToken,
        Instant accessTokenExpiresAt,
        UUID userId,
        LocalDateTime userCreatedAt
) {
}
