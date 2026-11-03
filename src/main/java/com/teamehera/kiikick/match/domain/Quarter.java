package com.teamehera.kiikick.match.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record Quarter(
        UUID id,
        UUID matchId,
        int number,
        int durationSeconds,
        String status,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
