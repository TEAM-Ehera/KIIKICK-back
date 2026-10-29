package com.teamehera.kiikick.pitch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "운동장 모서리 응답")
public record PitchCornerResponse(
        UUID id,
        Byte cornerOrder,
        String cornerRole,
        BigDecimal latitude,
        BigDecimal longitude
) {
}
