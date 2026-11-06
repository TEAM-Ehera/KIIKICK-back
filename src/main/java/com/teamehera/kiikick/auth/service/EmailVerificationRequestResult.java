package com.teamehera.kiikick.auth.service;

import java.time.Instant;
import java.util.UUID;

public record EmailVerificationRequestResult(
        UUID verificationId,
        Instant expiresAt,
        Instant resendAvailableAt
) {
}
