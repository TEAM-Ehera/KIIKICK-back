package com.teamehera.kiikick.auth.service;

import com.teamehera.kiikick.user.domain.User;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

public record LoginResult(UUID sessionId, String accessToken, String refreshToken,
                          Instant expiresAt, User user, boolean comparisonConsent) {
    public Instant userCreatedAt() {
        // 공용 User의 UTC LocalDateTime 경계를 Auth의 Instant로 명시적으로 변환한다.
        return user.createdAt().toInstant(ZoneOffset.UTC);
    }

    @Override
    public String toString() {
        return "LoginResult[sessionId=" + sessionId + ", credentials=REDACTED]";
    }
}
