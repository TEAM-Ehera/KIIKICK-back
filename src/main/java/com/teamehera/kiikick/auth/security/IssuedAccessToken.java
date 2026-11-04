package com.teamehera.kiikick.auth.security;

import java.time.Instant;

public record IssuedAccessToken(String token, Instant expiresAt) {
}
