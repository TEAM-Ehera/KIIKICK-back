package com.teamehera.kiikick.user.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record AccountDeletionRequest(
        UUID id,
        UUID userId,
        String status,
        LocalDateTime requestedAt,
        LocalDateTime completedAt,
        String failureCode
) {
}
