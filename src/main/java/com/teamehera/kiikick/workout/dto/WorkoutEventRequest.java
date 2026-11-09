package com.teamehera.kiikick.workout.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "골·어시스트 발생 순간의 위치. location.timestamp는 Apple epoch 초")
public record WorkoutEventRequest(
        @NotNull @Pattern(regexp = "goal|assist")
        @Schema(allowableValues = {"goal", "assist"}, example = "goal") String type,
        @NotNull @Valid TrackPointRequest location
) {}
