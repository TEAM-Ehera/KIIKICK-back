package com.teamehera.kiikick.auth.dto;

import java.time.Instant;
import java.util.UUID;

public record EmailVerificationResponse(
        UUID verificationId,
        Instant expiresAt,
        Instant resendAvailableAt
) {
}
