package com.teamehera.kiikick.friend.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserBlock(
        UUID blockerId, UUID blockedId, LocalDateTime createdAt
) {
}
