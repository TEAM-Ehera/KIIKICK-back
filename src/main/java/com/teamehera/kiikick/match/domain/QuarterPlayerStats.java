package com.teamehera.kiikick.match.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record QuarterPlayerStats(
        UUID quarterId,
        int goals,
        int assists,
        LocalDateTime updatedAt
) {
}
