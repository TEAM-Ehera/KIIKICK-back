package com.teamehera.kiikick.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "발송 결과. 인증번호 자체는 응답하지 않음")
public record EmailVerificationResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID verificationId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date-time", description = "인증코드 만료: 발송 후 3분 / Asia/Seoul +09:00, 소수초 정밀도 보존", example = "2026-11-06T12:00:00.123456+09:00") OffsetDateTime expiresAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date-time", description = "재발송 가능 시각: 마지막 발송 후 60초 / Asia/Seoul +09:00, 소수초 정밀도 보존", example = "2026-11-06T12:00:00.123456+09:00") OffsetDateTime resendAvailableAt
) {
}
