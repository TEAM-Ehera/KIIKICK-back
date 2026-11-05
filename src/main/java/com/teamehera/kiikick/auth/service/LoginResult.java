package com.teamehera.kiikick.auth.service;

import com.teamehera.kiikick.user.domain.User;

import java.time.Instant;
import java.util.UUID;

public record LoginResult(UUID sessionId, String accessToken, String refreshToken,
                          Instant expiresAt, User user, boolean comparisonConsent) {
    @Override
    public String toString() {
        return "LoginResult[sessionId=" + sessionId + ", credentials=REDACTED]";
    }
}
