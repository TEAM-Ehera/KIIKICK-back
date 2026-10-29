package com.teamehera.kiikick.user.dto;

import com.teamehera.kiikick.user.domain.User;

import java.time.LocalDateTime;
import java.util.UUID;

/*
 * API 응답 전용 DTO.
 *
 * DB에서 읽어 온 User 객체를
 * 클라이언트에 전달할 응답 형태로 바꾼다.
 */
public record UserResponse(
        UUID id,
        String nickname,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
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
