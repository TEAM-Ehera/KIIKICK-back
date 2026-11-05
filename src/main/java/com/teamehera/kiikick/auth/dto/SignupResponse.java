package com.teamehera.kiikick.auth.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SignupResponse(
        UUID sessionId,
        String accessToken,
        String refreshToken,
        OffsetDateTime expiresAt,
        SignupUserResponse user
) {
}
