package com.teamehera.kiikick.auth.service;

import com.teamehera.kiikick.auth.password.PasswordPolicyValidator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.stereotype.Service;

@Service
public class SignupService {

    private final PasswordPolicyValidator passwordPolicyValidator;
    private final PasswordEncoder passwordEncoder;
    private final SignupTransactionService transactionService;

    public SignupService(
            PasswordPolicyValidator passwordPolicyValidator,
            PasswordEncoder passwordEncoder,
            SignupTransactionService transactionService
    ) {
        this.passwordPolicyValidator = passwordPolicyValidator;
        this.passwordEncoder = passwordEncoder;
        this.transactionService = transactionService;
    }

    public SignupResult signup(String registrationToken, String password) {
        passwordPolicyValidator.validate(password);
        // BCrypt 작업은 DB 트랜잭션 밖에서 수행해 잠금 유지 시간을 줄인다.
        String passwordHash = passwordEncoder.encode(password);
        try {
            return transactionService.create(registrationToken, passwordHash);
        } catch (DuplicateKeyException exception) {
            throw SignupApiException.signupNotAvailable(exception);
        } catch (JwtEncodingException exception) {
            throw SignupApiException.tokenIssuanceFailed(exception);
        }
    }
}
