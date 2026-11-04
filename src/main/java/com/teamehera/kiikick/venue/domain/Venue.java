package com.teamehera.kiikick.venue.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record Venue(
        UUID id,
        UUID ownerId,
        String name,
        String address,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
