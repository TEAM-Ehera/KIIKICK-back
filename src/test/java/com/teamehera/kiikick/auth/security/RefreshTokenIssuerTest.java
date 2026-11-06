package com.teamehera.kiikick.auth.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenIssuerTest {

    @Test
    void RefreshToken은_256비트_원문과_SHA256_해시를_90일_만료로_발급한다()
            throws Exception {
        Instant now = Instant.parse("2026-11-05T00:00:00Z");
        RefreshTokenIssuer issuer = new RefreshTokenIssuer(
                Clock.fixed(now, ZoneOffset.UTC)
        );

        IssuedRefreshToken issued = issuer.issue();
        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(issued.token().getBytes(StandardCharsets.UTF_8))
        );

        assertThat(issued.token()).hasSize(43);
        assertThat(issued.tokenHash()).isEqualTo(expectedHash);
        assertThat(issued.tokenHash()).doesNotContain(issued.token());
        assertThat(issued.expiresAt()).isEqualTo(now.plusSeconds(90L * 24 * 60 * 60));
    }
}
