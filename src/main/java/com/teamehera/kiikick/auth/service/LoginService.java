package com.teamehera.kiikick.auth.service;

import com.teamehera.kiikick.auth.repository.LoginAttemptRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class LoginService {
    private final EmailNormalizer normalizer;
    private final LoginAttemptRepository attempts;
    private final LoginTransactionService transactionService;
    private final Clock clock;

    public LoginService(EmailNormalizer normalizer, LoginAttemptRepository attempts,
                        LoginTransactionService transactionService, Clock clock) {
        this.normalizer = normalizer;
        this.attempts = attempts;
        this.transactionService = transactionService;
        this.clock = clock;
    }

    public LoginResult login(String email, String password) {
        String normalized = normalizer.normalize(email);
        LoginTransactionService.Attempt attempt;
        try {
            // 키 생성은 로그인 트랜잭션 전에 완료해 중첩 연결 대기로 인한 풀 고갈을 피한다.
            attempts.ensureAccountKey(normalized, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            attempt = transactionService.authenticate(normalized, password);
        } catch (DataAccessException | JwtEncodingException exception) {
            throw VerificationApiException.authenticationUnavailable();
        }
        if (attempt.blockedUntil() != null) throw VerificationApiException.rateLimited(attempt.blockedUntil());
        if (attempt.result() == null) throw VerificationApiException.invalidCredentials();
        return attempt.result();
    }
}
