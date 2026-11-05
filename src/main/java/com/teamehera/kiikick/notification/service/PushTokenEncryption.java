package com.teamehera.kiikick.notification.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

@Component
class PushTokenEncryption {
    private final SecretKeySpec key;

    PushTokenEncryption(@Value("${kiikick.notification.push-token-key:}") String configuredKey) {
        if (configuredKey.isBlank()) {
            this.key = null;
            return;
        }
        byte[] bytes = Base64.getDecoder().decode(configuredKey);
        if (bytes.length != 32) {
            throw new IllegalArgumentException("Push token encryption key must be 32 bytes");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    String encrypt(String token) {
        if (key == null) {
            throw new NotificationService.PushTokenUnavailable();
        }
        try {
            // Let the JDK provider generate a fresh GCM IV for each token.
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            var encoding = Base64.getEncoder();
            return "v1:" + encoding.encodeToString(cipher.getIV()) + ":"
                    + encoding.encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt push token", exception);
        }
    }

}
