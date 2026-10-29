package com.teamehera.kiikick.pitch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Schema(description = "운동장 상세 응답")
public record PitchResponse(
        UUID id,
        UUID userId,
        UUID clientPitchId,
        String name,
        List<PitchCornerResponse> corners,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
