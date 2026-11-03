package com.teamehera.kiikick.workout.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/*
 * iOS의 WorkoutPayload.points 배열 안에 들어 있는 JSON 객체 하나가
 * TrackPointRequest 객체 하나로 변환된다.
 *
 * iOS 명세의 모든 GPS 필드는 optional이 아니므로
 * Java에서도 모든 필드를 Double로 선언하고 @NotNull을 적용한다.
 */
@Schema(description = "GPS 포인트 한 건")
public record TrackPointRequest(

        /*
         * @DecimalMin과 @DecimalMax는 숫자의 허용 범위를 검증한다.
         * inclusive의 기본값이 true이므로 -90과 90도 포함된다.
         *
         * MySQL의 trackPoints.latitude DOUBLE 컬럼과 대응한다.
         */
        @NotNull(message = "latitude는 필수입니다.")
        @DecimalMin(
                value = "-90.0",
                message = "latitude는 -90 이상이어야 합니다."
        )
        @DecimalMax(
                value = "90.0",
                message = "latitude는 90 이하여야 합니다."
        )
        @Schema(
                description = "위도(degrees), -90 이상 90 이하",
                example = "37.5665",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Double latitude,

        /*
         * 유효 범위는 -180도부터 180도까지다.
         * MySQL의 trackPoints.longitude DOUBLE 컬럼과 대응한다.
         */
        @NotNull(message = "longitude는 필수입니다.")
        @DecimalMin(
                value = "-180.0",
                message = "longitude는 -180 이상이어야 합니다."
        )
        @DecimalMax(
                value = "180.0",
                message = "longitude는 180 이하여야 합니다."
        )
        @Schema(
                description = "경도(degrees), -180 이상 180 이하",
                example = "126.978",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Double longitude,

        /*
         * 낮은 장소에서 음수가 될 수 있으므로
         * @Positive나 @PositiveOrZero를 적용하면 안 된다.
         *
         * MySQL의 trackPoints.altitude DOUBLE 컬럼과 대응한다.
         */
        @NotNull
        @Schema(
                description = "해수면 기준 고도(m). 해수면 아래에서는 음수 가능",
                example = "20.1",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Double altitude,

        @NotNull
        @Schema(
                description = "수평 위치 정확도 반경(m). 작을수록 정확",
                example = "4.8",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Double horizontalAccuracy,

        @NotNull
        @Schema(
                description = "순간 속도(m/s). 측정값이 유효하지 않으면 -1",
                example = "2.3",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Double speed,

        @NotNull
        @Schema(
                description = "정북 기준 이동 방향(degrees). 유효 범위는 0~359.9이며 측정값이 유효하지 않으면 -1",
                example = "87.5",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Double course,

        @NotNull
        @Schema(
                description = "GPS 측정 시각. 2001-01-01T00:00:00Z 기준 초 단위 Apple epoch",
                example = "750000001.2",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Double timestamp
) {
}
