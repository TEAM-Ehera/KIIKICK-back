package com.teamehera.kiikick.global.idempotency.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record IdempotencyRecord(
        UUID id,
        UUID userId,
        String method,
        String canonicalPath,
        UUID idempotencyKey,
        String requestHash,
        String status,
        Integer responseStatus,
        String responseHeaders,
        String responseBody,
        LocalDateTime createdAt,
        LocalDateTime expiresAt
) {
}
