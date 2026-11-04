package com.teamehera.kiikick.match.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record QuarterPosition(
        UUID quarterId,
        String positionCode,
        LocalDateTime updatedAt
) {
}
