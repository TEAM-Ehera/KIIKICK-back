package com.teamehera.kiikick.match.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record Match(
        UUID id,
        UUID ownerId,
        UUID venueId,
        String title,
        String venueNameSnapshot,
        String venueAddressSnapshot,
        LocalDateTime scheduledAt,
        String status,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
