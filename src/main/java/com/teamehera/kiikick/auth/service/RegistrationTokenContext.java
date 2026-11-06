package com.teamehera.kiikick.auth.service;

import java.util.UUID;
import java.time.Instant;

public record RegistrationTokenContext(
        UUID verificationId,
        String normalizedEmail,
        Instant verifiedAt
) {
}
