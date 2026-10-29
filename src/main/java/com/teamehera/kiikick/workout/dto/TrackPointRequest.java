package com.teamehera.kiikick.workout.dto;

import com.teamehera.kiikick.workout.validation.FiniteNumber;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

@Schema(description = "iOS TrackPoint 원본 데이터")
public record TrackPointRequest(
        @Schema(description = "위도(degrees)", example = "37.5665")
        @NotNull @FiniteNumber @DecimalMin("-90.0") @DecimalMax("90.0")
        Double latitude,

        @Schema(description = "경도(degrees)", example = "126.9780")
        @NotNull @FiniteNumber @DecimalMin("-180.0") @DecimalMax("180.0")
        Double longitude,

        @Schema(description = "고도(m)", example = "20.1")
        @NotNull @FiniteNumber
        Double altitude,

        @Schema(description = "수평 정확도 반경(m)", example = "4.8")
        @NotNull @FiniteNumber
        Double horizontalAccuracy,

        @Schema(description = "순간 속도(m/s). 측정값이 유효하지 않으면 -1", example = "2.3")
        @NotNull @FiniteNumber
        Double speed,

        @Schema(description = "정북 기준 이동 방향(degrees). 측정값이 유효하지 않으면 -1", example = "87.5")
        @NotNull @FiniteNumber
        Double course,

        @Schema(description = "2001-01-01T00:00:00Z 기준 초 단위 Apple epoch", example = "750000001.2")
        @NotNull @FiniteNumber
        Double timestamp
) {
}
