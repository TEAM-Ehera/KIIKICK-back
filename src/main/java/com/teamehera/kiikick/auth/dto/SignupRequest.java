package com.teamehera.kiikick.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SignupRequest(
        @NotBlank(message = "registrationToken을 입력해주세요.")
        String registrationToken,
        @NotNull(message = "비밀번호를 입력해주세요.")
        String password
) {
}
