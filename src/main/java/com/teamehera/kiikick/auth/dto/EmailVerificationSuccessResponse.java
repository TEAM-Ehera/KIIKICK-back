package com.teamehera.kiikick.auth.dto;

import java.time.OffsetDateTime;

public record EmailVerificationSuccessResponse(
        String registrationToken,
        OffsetDateTime expiresAt
) {
}
