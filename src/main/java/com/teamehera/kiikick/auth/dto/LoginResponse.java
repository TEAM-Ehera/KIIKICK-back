package com.teamehera.kiikick.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record LoginResponse(UUID sessionId, String accessToken, String refreshToken,
                            Instant expiresAt, SignupUserResponse user) {
    @Override
    public String toString() {
        return "LoginResponse[sessionId=" + sessionId + ", credentials=REDACTED]";
    }
}
