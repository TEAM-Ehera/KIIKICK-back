package com.teamehera.kiikick.auth.service;

import java.time.LocalDateTime;

public record EmailVerificationSuccessResult(
        String registrationToken,
        LocalDateTime expiresAt
) {
}
