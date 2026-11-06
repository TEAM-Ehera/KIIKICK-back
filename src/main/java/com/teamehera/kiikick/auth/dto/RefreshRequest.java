package com.teamehera.kiikick.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Map;

@Schema(description = "Access Token 없이 Refresh Token으로 세션 갱신. Grace 없음", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record RefreshRequest(@NotBlank(message="Refresh Token은 필수입니다.") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY, description = "현재 유효한 Refresh Token. 1회 소비·Rotation. 소비 토큰 재시도는 해당 세션 폐기·재로그인 필요 가능", example = "example-refresh-token") String refreshToken) {
    @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
    public static RefreshRequest from(Map<String,Object> body) {
        // 숫자의 문자열 자동 변환과 알 수 없는 필드를 AUTH-02 계약에 따라 거부한다.
        if (body==null || body.size()!=1 || !(body.get("refreshToken") instanceof String token))
            throw new IllegalArgumentException("Refresh Token must be the only string field");
        return new RefreshRequest(token);
    }

    @Override public String toString() { return "RefreshRequest[credentials=REDACTED]"; }
}
