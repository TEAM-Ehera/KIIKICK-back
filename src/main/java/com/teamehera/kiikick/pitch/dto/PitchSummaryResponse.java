package com.teamehera.kiikick.pitch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "운동장 목록 항목")
public record PitchSummaryResponse(
        UUID id,
        UUID userId,
        UUID clientPitchId,
        String name,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
