package com.teamehera.kiikick.auth.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LoginResponse(UUID sessionId, String accessToken, String refreshToken,
                            OffsetDateTime expiresAt, SignupUserResponse user) {
    @Override
    public String toString() {
        return "LoginResponse[sessionId=" + sessionId + ", credentials=REDACTED]";
    }
}
