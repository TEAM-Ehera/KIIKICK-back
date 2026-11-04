package com.teamehera.kiikick.auth.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record EmailVerificationFlow(
        UUID id,
        String normalizedEmail,
        String purpose,
        String codeHash,
        int failedAttempts,
        LocalDateTime codeExpiresAt,
        LocalDateTime lastSentAt,
        LocalDateTime verifiedAt,
        LocalDateTime invalidatedAt,
        String actionTokenHash,
        LocalDateTime actionTokenExpiresAt,
        LocalDateTime actionTokenConsumedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
