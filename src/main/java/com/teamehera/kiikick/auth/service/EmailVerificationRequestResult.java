package com.teamehera.kiikick.auth.service;

import java.time.LocalDateTime;
import java.util.UUID;

public record EmailVerificationRequestResult(
        UUID verificationId,
        LocalDateTime expiresAt,
        LocalDateTime resendAvailableAt
) {
}
