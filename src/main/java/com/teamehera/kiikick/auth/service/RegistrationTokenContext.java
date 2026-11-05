package com.teamehera.kiikick.auth.service;

import java.util.UUID;
import java.time.LocalDateTime;

public record RegistrationTokenContext(
        UUID verificationId,
        String normalizedEmail,
        LocalDateTime verifiedAt
) {
}
