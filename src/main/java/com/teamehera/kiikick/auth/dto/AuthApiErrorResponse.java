package com.teamehera.kiikick.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "공통 Auth 오류 봉투")
public record AuthApiErrorResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) AuthApiError error, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AuthApiMeta meta) {

    public record AuthApiError(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "INVALID_REQUEST") String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "잘못된 요청입니다.") String message,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "동일 요청의 재시도 가능 여부") boolean retryable,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"NOT_SAVED", "SAVED"}, description = "INVALID_REFRESH_TOKEN도 재사용 탐지에 따른 세션 폐기가 커밋되면 SAVED일 수 있음") String persistence,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "오류 세부 정보. 없으면 빈 객체") Map<String, Object> details
    ) {
    }
}
