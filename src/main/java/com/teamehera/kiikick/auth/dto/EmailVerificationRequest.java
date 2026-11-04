package com.teamehera.kiikick.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailVerificationRequest(
        @NotBlank(message = "이메일을 입력해주세요.")
        @Size(max = 320, message = "이메일은 320자를 초과할 수 없습니다.")
        String email
) {
}
