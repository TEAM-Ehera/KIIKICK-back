package com.teamehera.kiikick.workout.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
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
         * 현재 iOS 명세에는 비즈니스 최솟값과 최댓값이 정의되어 있지 않으므로
         * 임의의 수치 범위는 제한하지 않는다.
         *
         * 다만 MySQL DOUBLE에 저장할 수 없는 NaN과 Infinity는
         * 아래 @AssertTrue 메서드에서 차단한다.
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

        @NotNull(message = "points는 필수입니다.")
        @Schema(
                description = "시간순 GPS 포인트 배열. 빈 배열 허용",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        List<
                @NotNull(
                        message = "points 배열 요소는 null일 수 없습니다."
                )
                @Valid TrackPointRequest
                > points,

        @NotNull(message = "heartRateSamples는 필수입니다.")
        @Schema(
                description = "시간순 심박수 샘플 배열. 빈 배열 허용",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        List<
                @NotNull(
                        message = "heartRateSamples 배열 요소는 null일 수 없습니다."
                )
                @Valid HeartRateSampleRequest
                > heartRateSamples,

        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        @Schema(description = "골·어시스트 위치 이벤트. 생략/null은 빈 배열, 순서 보존")
        List<@NotNull @Valid WorkoutEventRequest> events,

        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Valid @Schema(description = "운동 당시 경기장 사본. 생략/null 허용")
        WorkoutFieldRequest field
) {
    public WorkoutPayloadCreateRequest {
        events = events == null ? List.of() : events;
    }

    // 기존 요청·해시의 호환성을 유지한다. 비어 있는 새 필드는 해시 직렬화에서 제외한다.
    public WorkoutPayloadCreateRequest(Double startDate, Double endDate, Double totalCalories,
                                       Double totalDistance, List<TrackPointRequest> points,
                                       List<HeartRateSampleRequest> heartRateSamples) {
        this(startDate, endDate, totalCalories, totalDistance, points, heartRateSamples, List.of(), null);
    }

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

    @JsonIgnore
    @AssertTrue(
            message = "totalCalories는 유한한 숫자여야 합니다."
    )
    public boolean isTotalCaloriesFinite() {
        return totalCalories == null
                || Double.isFinite(totalCalories);
    }

    @JsonIgnore
    @AssertTrue(
            message = "totalDistance는 유한한 숫자여야 합니다."
    )
    public boolean isTotalDistanceFinite() {
        return totalDistance == null
                || Double.isFinite(totalDistance);
    }
}