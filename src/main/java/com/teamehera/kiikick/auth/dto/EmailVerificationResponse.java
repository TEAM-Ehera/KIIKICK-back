package com.teamehera.kiikick.auth.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record EmailVerificationResponse(
        UUID verificationId,
        OffsetDateTime expiresAt,
        OffsetDateTime resendAvailableAt
) {
}
