package com.teamehera.kiikick.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

@Schema(description = "이메일·비밀번호 로그인")
public record LoginRequest(@NotBlank(message = "이메일은 필수입니다.") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "email", description = "가입과 동일한 이메일 정규화 적용", example = "player@example.com") String email,
                           @NotBlank(message = "비밀번호는 필수입니다.") @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "password", accessMode = Schema.AccessMode.WRITE_ONLY, minLength = 8, maxLength = 64, description = "8~64 Unicode 문자, UTF-8 최대 72바이트. 로컬 차단 목록은 소문자 비교, BCrypt는 원문 사용. 문자 조합 강제 없음", example = "A-safe-example42!") String password) {
    @Override
    public String toString() {
        return "LoginRequest[credentials=REDACTED]";
    }
}
