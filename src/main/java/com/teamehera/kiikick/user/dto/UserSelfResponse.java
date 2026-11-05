package com.teamehera.kiikick.user.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserSelfResponse(
        UUID id,
        String nickname,
        String avatarUrl,
        String onboardingStatus,
        boolean comparisonConsent,
        OffsetDateTime createdAt
) {}
