package com.teamehera.kiikick.user.dto;

import com.teamehera.kiikick.user.domain.User;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

/*
 * API 응답 전용 DTO.
 *
 * DB에서 읽어 온 User 객체를
 * 클라이언트에 전달할 응답 형태로 바꾼다.
 */
@Schema(description = "사용자 응답")
public record UserResponse(
        @Schema(
                description = "사용자 UUID",
                example = "550e8400-e29b-41d4-a716-446655440000",
                format = "uuid",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        UUID id,

        @Schema(
                description = "사용자 닉네임",
                example = "키킥",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        String nickname,

        @Schema(
                description = "사용자 생성 시각(UTC)",
                example = "2026-10-30T06:22:51.887409",
                format = "date-time",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        LocalDateTime createdAt,

        @Schema(
                description = "마지막 수정 시각(UTC)",
                example = "2026-10-30T06:22:51.887409",
                format = "date-time",
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        LocalDateTime updatedAt,

        @Schema(
                description = "soft delete 시각(UTC). 활성 사용자는 null",
                example = "2026-10-30T07:00:00.000000",
                format = "date-time",
                nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED
        )
        LocalDateTime deletedAt
) {

    // User 도메인 객체 -> UserResponse DTO 변환
    public static UserResponse from(User user) {
        return new UserResponse(
                user.id(),
                user.nickname(),
                user.createdAt(),
                user.updatedAt(),
                user.deletedAt()
        );
    }
}
