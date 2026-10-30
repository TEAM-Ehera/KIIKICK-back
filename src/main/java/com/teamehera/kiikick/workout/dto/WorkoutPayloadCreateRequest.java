package com.teamehera.kiikick.workout.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/*
 * iOS가 전송한 WorkoutPayload JSON 전체를 받는 요청 DTO다.
 *
 * DB에 직접 저장되는 객체가 아니라, iOS와 서버 사이에서 데이터를 전달하는 역할을 한다.
 *
 * userId는 POST /api/v1/user/{userId}/workout-payloads의 URL에서 별도로 받는다.
 */
@Schema(description = "iOS WorkoutPayload 업로드 요청")
public record WorkoutPayloadCreateRequest(
        @NotNull
        @Schema(
                description = "운동 시작 시각. 2001-01-01T00:00:00Z 기준 초 단위 Apple epoch",
                example = "750000000.0",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Double startDate,

        @NotNull
        @Schema(
                description = "운동 종료 시각. startDate보다 빠를 수 없는 Apple epoch 초",
                example = "750000600.0",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Double endDate,

        /*
         * 현재 iOS 명세에는 정확한 최솟값과 최댓값이 정의되어 있지 않으므로
         * 여기서는 임의의 범위 검증을 추가하지 않고 필수 여부만 검증한다.
         */
        @NotNull
        @Schema(
                description = "측정 종료 시점까지 누적된 활동 칼로리(kcal)",
                example = "420.5",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Double totalCalories,

        @NotNull
        @Schema(
                description = "측정 종료 시점까지 누적된 이동 거리(m)",
                example = "3182.4",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Double totalDistance,

        @NotNull
        @Schema(
                description = "시간순 GPS 포인트 배열. 빈 배열 허용",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        List<@Valid TrackPointRequest> points,

        /*
         * @Valid를 붙였기 때문에 HeartRateSampleRequest 내부의
         * timestamp, bpm 검증도 함께 실행된다.
         */
        @NotNull
        @Schema(
                description = "시간순 심박수 샘플 배열. 빈 배열 허용",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        List<@Valid HeartRateSampleRequest> heartRateSamples
) {
    @JsonIgnore
    @AssertTrue(
            message = "startDate와 endDate는 유효한 숫자여야 하며, endDate는 startDate보다 빠를 수 없습니다."
    )
    public boolean isDateOrderValid() {
        if (startDate == null || endDate == null) {
            return true;
        }

        return Double.isFinite(startDate)
                && Double.isFinite(endDate)
                && endDate >= startDate;
    }
}
