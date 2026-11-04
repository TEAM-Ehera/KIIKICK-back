package com.teamehera.kiikick.notification.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record Device(
        UUID id,
        UUID userId,
        String platform,
        String pushTokenCiphertext,
        boolean pushEnabled,
        LocalDateTime lastSeenAt,
        LocalDateTime revokedAt
) {
}
