package com.teamehera.kiikick.auth.security;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class RefreshTokenIssuer {

    private static final int TOKEN_BYTES = 32;
    private static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(90);

    private final SecureRandom secureRandom = new SecureRandom();
    private final Clock clock;

    public RefreshTokenIssuer(Clock clock) {
        this.clock = clock;
    }

    public IssuedRefreshToken issue() {
        byte[] tokenBytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tokenBytes);
        return new IssuedRefreshToken(
                token,
                sha256(token),
                clock.instant().plus(ABSOLUTE_LIFETIME)
        );
    }

    private String sha256(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
