package com.teamehera.kiikick.workout.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "WorkoutPayload 생성 응답")
public record WorkoutPayloadCreateResponse(
        @Schema(description = "서버가 생성한 WorkoutPayload UUID", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID workoutPayloadId
) {
}
