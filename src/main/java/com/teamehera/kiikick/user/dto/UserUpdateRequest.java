package com.teamehera.kiikick.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * 사용자 닉네임 수정 요청 DTO.
 *
 * 수정할 사용자의 UUID는 URL에서 받고,
 * 새로운 닉네임은 요청 본문(JSON)에서 받는다.
 */
@Schema(description = "사용자 닉네임 수정 요청")
public record UserUpdateRequest(

        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(max = 16, message = "닉네임은 16자 이하여야 합니다.")
        @Schema(
                description = "변경할 닉네임",
                example = "새닉네임",
                minLength = 1,
                maxLength = 16,
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String nickname
) {
}
