package com.teamehera.kiikick.auth.service;

import java.util.UUID;

public record RegistrationTokenContext(UUID verificationId, String normalizedEmail) {
}
