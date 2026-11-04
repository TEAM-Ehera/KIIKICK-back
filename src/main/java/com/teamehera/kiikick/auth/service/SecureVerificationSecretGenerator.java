package com.teamehera.kiikick.auth.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

@Component
public class SecureVerificationSecretGenerator implements VerificationSecretGenerator {

    private static final int CODE_BOUND = 1_000_000;
    private static final int ACTION_TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String generateCode() {
        return String.format(Locale.ROOT, "%06d", secureRandom.nextInt(CODE_BOUND));
    }

    @Override
    public String generateActionToken() {
        byte[] bytes = new byte[ACTION_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
