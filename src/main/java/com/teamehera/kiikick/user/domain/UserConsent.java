package com.teamehera.kiikick.user.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserConsent(
        UUID id,
        UUID userId,
        String purpose,
        String policyVersion,
        boolean granted,
        LocalDateTime recordedAt
) {
}
