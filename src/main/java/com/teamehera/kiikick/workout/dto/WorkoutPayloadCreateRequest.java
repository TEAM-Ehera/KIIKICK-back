package com.teamehera.kiikick.workout.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.teamehera.kiikick.workout.validation.FiniteNumber;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Schema(description = "iOS WorkoutPayload 업로드 요청")
public record WorkoutPayloadCreateRequest(
        @Schema(description = "측정 시작 시각. 2001-01-01T00:00:00Z 기준 초 단위", example = "750000000.0")
        @NotNull @FiniteNumber
        Double startDate,

        @Schema(description = "측정 종료 시각. 2001-01-01T00:00:00Z 기준 초 단위", example = "750000600.0")
        @NotNull @FiniteNumber
        Double endDate,

        @Schema(description = "누적 활동 칼로리(kcal)", example = "420.5")
        @NotNull @FiniteNumber
        Double totalCalories,

        @Schema(description = "누적 이동 거리(m)", example = "3182.4")
        @NotNull @FiniteNumber
        Double totalDistance,

        @Schema(description = "시간순 GPS 포인트. 빈 배열 허용")
        @NotNull
        List<@Valid TrackPointRequest> points,

        @Schema(description = "시간순 심박수 샘플. 빈 배열 허용")
        @NotNull
        List<@Valid HeartRateSampleRequest> heartRateSamples
) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "endDate must be greater than or equal to startDate")
    public boolean isDateOrderValid() {
        return startDate == null
                || endDate == null
                || !Double.isFinite(startDate)
                || !Double.isFinite(endDate)
                || endDate >= startDate;
    }
}
