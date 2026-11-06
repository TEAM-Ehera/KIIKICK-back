package com.teamehera.kiikick.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "SIGN_UP 이메일 인증번호 발송·재발송")
public record EmailVerificationRequest(
        @NotBlank(message = "이메일을 입력해주세요.")
        @Size(max = 320, message = "이메일은 320자를 초과할 수 없습니다.")
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "email", description = "가입과 동일한 이메일 정규화 적용", example = "player@example.com", maxLength = 320) String email
) {
}
