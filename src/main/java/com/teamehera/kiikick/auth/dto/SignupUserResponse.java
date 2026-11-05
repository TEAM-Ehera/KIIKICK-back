package com.teamehera.kiikick.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record SignupUserResponse(
        UUID id,
        String nickname,
        String avatarUrl,
        String onboardingStatus,
        boolean comparisonConsent,
        Instant createdAt
) {
}
