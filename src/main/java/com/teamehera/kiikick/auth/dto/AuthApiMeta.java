package com.teamehera.kiikick.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "공통 응답 메타")
public record AuthApiMeta(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "요청 추적 ID. 이 필드가 있다고 모든 API의 X-Request-Id 응답 헤더를 보장하지는 않음", example = "req-example") String requestId, @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date-time", description = "서버 응답 시각 / Asia/Seoul +09:00, 소수초 정밀도 보존", example = "2026-11-06T12:00:00.123456+09:00") OffsetDateTime serverTime) {
}
