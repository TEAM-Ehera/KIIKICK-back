package com.teamehera.kiikick.auth.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record AuthIdentity(
        UUID id, UUID userId, String provider, String subject, LocalDateTime createdAt
) {
}
