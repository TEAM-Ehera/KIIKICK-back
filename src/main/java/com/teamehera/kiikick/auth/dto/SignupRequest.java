package com.teamehera.kiikick.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@Schema(description = "이메일 검증 후 회원가입 완료")
public record SignupRequest(
        @NotBlank(message = "registrationToken을 입력해주세요.")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY, description = "이메일 검증 API에서 받은 SIGN_UP 전용 일회용 토큰", example = "example-registration-token") String registrationToken,
        @NotNull(message = "비밀번호를 입력해주세요.")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "password", accessMode = Schema.AccessMode.WRITE_ONLY, minLength = 8, maxLength = 64, description = "8~64 Unicode 문자, UTF-8 최대 72바이트. 로컬 차단 목록은 소문자 비교, BCrypt는 원문 사용. 문자 조합 강제 없음", example = "A-safe-example42!") String password
) {
}
