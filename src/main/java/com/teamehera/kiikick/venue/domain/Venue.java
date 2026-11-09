package com.teamehera.kiikick.venue.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record Venue(
        UUID id,
        UUID ownerId,
        String name,
        String address,
        FieldCorner cornerA,
        FieldCorner cornerB,
        FieldCorner cornerC,
        FieldCorner cornerD,
        FieldCorner rawCornerA,
        FieldCorner rawCornerB,
        FieldCorner rawCornerC,
        FieldCorner rawCornerD,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    // 기존 좌표 없는 경기장과 Repository 호출의 호환성을 유지한다.
    public Venue(UUID id, UUID ownerId, String name, String address,
                 LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, ownerId, name, address, null, null, null, null,
                null, null, null, null, createdAt, updatedAt);
    }
}
