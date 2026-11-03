package com.teamehera.kiikick.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/*
 * 클라이언트가 사용자 생성을 요청할 때 보내는 데이터.
 *
 * 예시 JSON:
 * {
 *   "nickname": "키킥"
 * }
 */
@Schema(description = "사용자 생성 요청")
public record UserCreateRequest(

        // 공백만 있는 닉네임은 허용하지 않는다.
        @NotBlank(message = "닉네임은 필수입니다.")

        // DB의 VARCHAR(16)과 길이 제한을 맞춘다.
        @Size(max = 16, message = "닉네임은 16자 이하여야 합니다.")
        @Schema(
                description = "사용자 닉네임",
                example = "키킥",
                minLength = 1,
                maxLength = 16,
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String nickname
) {
}
