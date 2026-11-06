package com.teamehera.kiikick.auth.domain;

import java.time.LocalDateTime;

public record SendRateLimitDecision(
        boolean allowed,
        int emailCount,
        int ipCount,
        LocalDateTime retryAt
) {
}
