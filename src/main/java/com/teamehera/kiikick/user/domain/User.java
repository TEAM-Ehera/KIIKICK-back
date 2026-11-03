package com.teamehera.kiikick.user.domain;

import java.time.LocalDateTime;
import java.util.UUID;

/*
 * User는 사용자 데이터를 표현하는 도메인 객체다.
 *
 * JPA를 사용하지 않으므로 @Entity, @Id 같은 어노테이션이 없다.
 * DB의 users 테이블에서 읽은 값을 이 객체에 담는다.
 */
public record User(
        UUID id,
        String nickname,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime deletedAt
) {
}
