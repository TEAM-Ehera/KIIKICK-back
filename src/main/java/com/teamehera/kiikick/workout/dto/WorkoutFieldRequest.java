package com.teamehera.kiikick.workout.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "운동 당시 경기장 사본. 보정 코너는 필수, 원본 핀은 선택")
public record WorkoutFieldRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull @Valid Corner cornerA,
        @NotNull @Valid Corner cornerB,
        @NotNull @Valid Corner cornerC,
        @NotNull @Valid Corner cornerD,
        @Valid Corner rawCornerA,
        @Valid Corner rawCornerB,
        @Valid Corner rawCornerC,
        @Valid Corner rawCornerD
) {
    public record Corner(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude
    ) {}
}
