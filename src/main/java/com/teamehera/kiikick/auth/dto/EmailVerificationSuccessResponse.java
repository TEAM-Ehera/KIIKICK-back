package com.teamehera.kiikick.auth.dto;

import java.time.Instant;

public record EmailVerificationSuccessResponse(
        String registrationToken,
        Instant expiresAt
) {
}
