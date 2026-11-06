package com.teamehera.kiikick.auth.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record EmailCredential(
        UUID userId,
        String email,
        String normalizedEmail,
        String passwordHash,
        LocalDateTime emailVerifiedAt,
        LocalDateTime passwordChangedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
