package com.teamehera.kiikick.workout.dto;

import com.teamehera.kiikick.workout.validation.FiniteNumber;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "iOS HeartRateSample 원본 데이터")
public record HeartRateSampleRequest(
        @Schema(description = "2001-01-01T00:00:00Z 기준 초 단위 Apple epoch", example = "750000001.0")
        @NotNull @FiniteNumber
        Double timestamp,

        @Schema(description = "분당 심박수", example = "142.0")
        @NotNull @FiniteNumber
        Double bpm
) {
}
