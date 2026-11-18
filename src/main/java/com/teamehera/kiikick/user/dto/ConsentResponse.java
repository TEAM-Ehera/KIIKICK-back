package com.teamehera.kiikick.user.dto;

import com.teamehera.kiikick.auth.domain.ConsentPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "지원 목적의 최신 실제 동의 이력. 업로드 허용·삭제 완료 상태를 나타내지 않음")
public record ConsentResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {ConsentPolicy.GPS_COLLECTION, ConsentPolicy.HEALTH_DATA}) String purpose,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "저장된 실제 안내문 버전", example = "1.0") String policyVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "최신 이력의 실제 선택값") boolean granted,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date-time", description = "DB UTC 기록 시각의 Asia/Seoul 표현. 마이크로초 보존", example = "2026-11-07T12:00:00.123456+09:00") OffsetDateTime recordedAt
) {}
