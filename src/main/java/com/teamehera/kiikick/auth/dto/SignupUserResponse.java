package com.teamehera.kiikick.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "본인 사용자 정보. nickname·avatarUrl은 키를 포함하고 null 가능")
public record SignupUserResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "uuid") UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "온보딩 전 null 가능", example = "준") String nickname,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "미등록 시 null", example = "https://example.com/avatar.png") String avatarUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"REQUIRED", "COMPLETED"}, description = "DB 온보딩 완료 시각에서 파생") String onboardingStatus,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "최신 FRIEND_COMPARISON 동의에서 파생, 이력 없으면 false") boolean comparisonConsent,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date-time", description = "DB UTC 계정 생성 시각 / Asia/Seoul +09:00, 소수초 정밀도 보존", example = "2026-11-06T12:00:00.123456+09:00") OffsetDateTime createdAt
) {
}
