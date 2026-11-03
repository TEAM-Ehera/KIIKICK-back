package com.teamehera.kiikick.workout.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "심박수 샘플 한 건")
public record HeartRateSampleRequest(
        @NotNull(message = "heartRateSamples의 timestamp는 필수입니다.")
        @Schema(
                description = "심박수 측정 시각. 2001-01-01T00:00:00Z 기준 초 단위 Apple epoch",
                example = "750000001.0",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Double timestamp,

        /*
         * 현재 iOS 명세에는 bpm의 허용 최솟값과 최댓값,
         * 잘못된 측정값을 나타내는 특별한 값이 정의되어 있지 않다.
         * 따라서 서버가 임의로 범위를 정하지 않고 필수 여부만 검증한다.
         */
        @NotNull(message = "bpm은 필수입니다.")
        @Schema(
                description = "분당 심박수(bpm)",
                example = "142.0",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        Double bpm
) {
}
