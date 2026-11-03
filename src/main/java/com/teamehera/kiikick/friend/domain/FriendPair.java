package com.teamehera.kiikick.friend.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record FriendPair(
        UUID id,
        UUID userLowId,
        UUID userHighId,
        UUID requesterId,
        String status,
        LocalDateTime acceptedAt,
        LocalDateTime createdAt
) {
}
