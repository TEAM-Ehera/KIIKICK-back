package com.teamehera.kiikick.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "6자리 인증번호 검증")
public record EmailVerificationCodeRequest(
        @NotBlank(message = "인증번호를 입력해주세요.")
        @Pattern(regexp = "\\d{6}", message = "인증번호는 6자리 숫자여야 합니다.")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "\\d{6}", minLength = 6, maxLength = 6, example = "123456") String code
) {
}
