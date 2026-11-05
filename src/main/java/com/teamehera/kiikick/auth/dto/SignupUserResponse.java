package com.teamehera.kiikick.auth.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SignupUserResponse(
        UUID id,
        String nickname,
        String avatarUrl,
        String onboardingStatus,
        boolean comparisonConsent,
        OffsetDateTime createdAt
) {
}
