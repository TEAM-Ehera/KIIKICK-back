package com.teamehera.kiikick.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "로그인 세션과 본인 프로필. Refresh는 최초 발급 기준 최대 90일 절대 만료")
public record LoginResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID sessionId, @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "15분 HS256 JWT. 보호 API Bearer 인증에 사용. 문서의 토큰 예시는 실제 자격 증명이 아니다.", example = "example-token-not-a-real-credential") String accessToken, @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "DB에는 해시만 저장. 갱신마다 회전하며 최초 발급 기준 최대 90일. 문서의 토큰 예시는 실제 자격 증명이 아니다.", example = "example-token-not-a-real-credential") String refreshToken,
                            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date-time", description = "Access Token 만료 시각 / Asia/Seoul +09:00, 소수초 정밀도 보존", example = "2026-11-06T12:00:00.123456+09:00") OffsetDateTime expiresAt, @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "본인 프로필 및 온보딩·동의 파생값") SignupUserResponse user) {
    @Override
    public String toString() {
        return "LoginResponse[sessionId=" + sessionId + ", credentials=REDACTED]";
    }
}
