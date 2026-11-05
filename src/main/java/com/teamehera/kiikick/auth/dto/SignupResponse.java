package com.teamehera.kiikick.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record SignupResponse(
        UUID sessionId,
        String accessToken,
        String refreshToken,
        Instant expiresAt,
        SignupUserResponse user
) {
}
