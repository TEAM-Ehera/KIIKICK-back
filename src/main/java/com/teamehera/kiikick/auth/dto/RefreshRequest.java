package com.teamehera.kiikick.auth.dto;

import jakarta.validation.constraints.NotBlank;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Map;

public record RefreshRequest(@NotBlank(message="Refresh Token은 필수입니다.") String refreshToken) {
    @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
    public static RefreshRequest from(Map<String,Object> body) {
        // 숫자의 문자열 자동 변환과 알 수 없는 필드를 AUTH-02 계약에 따라 거부한다.
        if (body==null || body.size()!=1 || !(body.get("refreshToken") instanceof String token))
            throw new IllegalArgumentException("Refresh Token must be the only string field");
        return new RefreshRequest(token);
    }

    @Override public String toString() { return "RefreshRequest[credentials=REDACTED]"; }
}
