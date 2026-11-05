package com.teamehera.kiikick.auth.security;

import java.time.Instant;

public record IssuedRefreshToken(String token, String tokenHash, Instant expiresAt) {
}
