package com.teamehera.kiikick.auth.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthHashEncoderTest {

    private final AuthHashEncoder encoder = new AuthHashEncoder(
            "email-code-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8),
            "ip-address-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8)
    );

    @Test
    void 용도별_비밀키와_challenge를_분리해_해시한다() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertEquals(64, encoder.hashEmailCode(first, "123456").length());
        assertEquals(64, encoder.hashIpAddress("203.0.113.1").length());
        assertEquals(64, encoder.hashActionToken("opaque-token").length());
        assertNotEquals(
                encoder.hashEmailCode(first, "123456"),
                encoder.hashEmailCode(second, "123456")
        );
        assertNotEquals(
                encoder.hashEmailCode(first, "123456"),
                encoder.hashIpAddress("123456")
        );
    }

    @Test
    void 짧은_HMAC_비밀키를_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> new AuthHashEncoder(
                "short".getBytes(StandardCharsets.UTF_8),
                "ip-address-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8)
        ));
    }
}
