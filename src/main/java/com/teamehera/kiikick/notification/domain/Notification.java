package com.teamehera.kiikick.notification.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record Notification(
        UUID id,
        UUID recipientId,
        String type,
        String title,
        String body,
        String targetType,
        UUID targetId,
        String dedupKey,
        LocalDateTime readAt,
        LocalDateTime createdAt
) {
}
