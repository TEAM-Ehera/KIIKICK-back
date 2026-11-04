package com.teamehera.kiikick.sync.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record SyncOperation(
        UUID id,
        UUID userId,
        UUID deviceId,
        UUID operationId,
        UUID requestedMatchId,
        UUID matchId,
        long expectedVersion,
        String type,
        String payloadHash,
        String status,
        String result,
        LocalDateTime processedAt
) {
}
