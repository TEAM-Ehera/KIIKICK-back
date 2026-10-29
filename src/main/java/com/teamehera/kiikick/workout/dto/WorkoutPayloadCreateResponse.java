package com.teamehera.kiikick.workout.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/*
 * 운동 본체, GPS 포인트, 심박수 샘플이 하나의 트랜잭션으로
 * 모두 저장된 후에만 이 응답이 반환된다.
 */
@Schema(description = "WorkoutPayload 저장 성공 응답")
public record WorkoutPayloadCreateResponse(
        @Schema(
                description = "서버에서 생성한 운동 기록 UUID",
                example = "550e8400-e29b-41d4-a716-446655440000",
                format = "uuid",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        UUID workoutPayloadId
) {
}
