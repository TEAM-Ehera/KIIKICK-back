package com.teamehera.kiikick.auth.password;

import com.teamehera.kiikick.auth.service.SignupApiException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class PasswordPolicyValidator {

    private static final int MINIMUM_CHARACTERS = 8;
    private static final int MAXIMUM_CHARACTERS = 64;
    private static final int MAXIMUM_UTF8_BYTES = 72;

    private final CommonPasswordBlacklist blacklist;

    public PasswordPolicyValidator(CommonPasswordBlacklist blacklist) {
        this.blacklist = blacklist;
    }

    public void validate(String password) {
        if (password == null) {
            throw SignupApiException.invalidPassword();
        }
        int characterCount = password.codePointCount(0, password.length());
        int byteCount = password.getBytes(StandardCharsets.UTF_8).length;
        if (characterCount < MINIMUM_CHARACTERS
                || characterCount > MAXIMUM_CHARACTERS
                || byteCount > MAXIMUM_UTF8_BYTES
                || blacklist.contains(password)) {
            throw SignupApiException.invalidPassword();
        }
    }
}
