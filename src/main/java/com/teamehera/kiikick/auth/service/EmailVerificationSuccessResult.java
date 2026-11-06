package com.teamehera.kiikick.auth.service;

import java.time.Instant;

public record EmailVerificationSuccessResult(
        String registrationToken,
        Instant expiresAt
) {
}
