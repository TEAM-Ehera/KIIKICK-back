package com.teamehera.kiikick.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "회원가입 전용 10분 일회용 권한")
public record EmailVerificationSuccessResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "인증 이메일과 SIGN_UP에 귀속된 opaque registrationToken. 원문은 최초 검증 성공에만 제공. 문서의 토큰 예시는 실제 자격 증명이 아니다.", example = "example-token-not-a-real-credential") String registrationToken,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date-time", description = "registrationToken 만료: 검증 성공 후 10분 / Asia/Seoul +09:00, 소수초 정밀도 보존", example = "2026-11-06T12:00:00.123456+09:00") OffsetDateTime expiresAt
) {
}
