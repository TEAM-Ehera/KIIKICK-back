package com.teamehera.kiikick.auth.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class AuthHashEncoder {

    public static final String EMAIL_CODE_SECRET_ENV = "AUTH_EMAIL_CODE_HMAC_SECRET";
    public static final String IP_SECRET_ENV = "AUTH_IP_HMAC_SECRET";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MINIMUM_SECRET_BYTES = 32;

    private final byte[] emailCodeSecret;
    private final byte[] ipSecret;

    public AuthHashEncoder(byte[] emailCodeSecret, byte[] ipSecret) {
        this.emailCodeSecret = validateSecret(emailCodeSecret, EMAIL_CODE_SECRET_ENV);
        this.ipSecret = validateSecret(ipSecret, IP_SECRET_ENV);
    }

    public static AuthHashEncoder fromEnvironment() {
        return new AuthHashEncoder(
                requiredEnvironmentSecret(EMAIL_CODE_SECRET_ENV),
                requiredEnvironmentSecret(IP_SECRET_ENV)
        );
    }

    public String hashEmailCode(UUID challengeId, String code) {
        // 짧은 인증코드는 단순 해시로 보호할 수 없어 Challenge별 HMAC을 사용한다.
        return hmacHex(emailCodeSecret, challengeId + ":" + code);
    }

    public String hashIpAddress(String canonicalIpAddress) {
        return hmacHex(ipSecret, canonicalIpAddress);
    }

    public String hashActionToken(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String hmacHex(byte[] secret, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return HexFormat.of().formatHex(
                    mac.doFinal(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA-256 hashing failed", exception);
        }
    }

    private static byte[] requiredEnvironmentSecret(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " environment variable is required");
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] validateSecret(byte[] secret, String name) {
        if (secret == null || secret.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException(name + " must contain at least 32 bytes");
        }
        return secret.clone();
    }
}
