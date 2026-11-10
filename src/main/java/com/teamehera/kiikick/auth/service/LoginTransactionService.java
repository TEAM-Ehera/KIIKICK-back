package com.teamehera.kiikick.auth.service;

import com.teamehera.kiikick.auth.domain.AuthSession;
import com.teamehera.kiikick.auth.domain.EmailCredential;
import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import com.teamehera.kiikick.auth.repository.EmailCredentialRepository;
import com.teamehera.kiikick.auth.repository.LoginAttemptRepository;
import com.teamehera.kiikick.auth.security.AccessTokenService;
import com.teamehera.kiikick.auth.security.IssuedAccessToken;
import com.teamehera.kiikick.auth.security.IssuedRefreshToken;
import com.teamehera.kiikick.auth.security.RefreshTokenIssuer;
import com.teamehera.kiikick.user.domain.User;
import com.teamehera.kiikick.user.repository.UserConsentRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class LoginTransactionService {
    private final LoginAttemptRepository attempts;
    private final EmailCredentialRepository credentials;
    private final AuthSessionRepository sessions;
    private final UserRepository users;
    private final UserConsentRepository consents;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokens;
    private final RefreshTokenIssuer refreshTokens;
    private final Clock clock;
    private final String dummyPasswordHash;

    public LoginTransactionService(LoginAttemptRepository attempts, EmailCredentialRepository credentials,
                                   AuthSessionRepository sessions, UserRepository users,
                                   UserConsentRepository consents, PasswordEncoder passwordEncoder,
                                   AccessTokenService accessTokens, RefreshTokenIssuer refreshTokens, Clock clock) {
        this.attempts = attempts;
        this.credentials = credentials;
        this.sessions = sessions;
        this.users = users;
        this.consents = consents;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
        // 미등록 이메일도 BCrypt 검증 비용을 지불하도록 시작 시 한 번 생성한다.
        dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public Attempt authenticate(String email, String password) {
        // 계정별 잠금 안에서 제한 검사·실패 기록·성공 초기화를 직렬화한다.
        attempts.lockAccount(email);
        Instant now = now();
        LocalDateTime blockedUntil = attempts.blockedUntil(email, utc(now));
        if (blockedUntil != null) return new Attempt(null, blockedUntil.toInstant(ZoneOffset.UTC));

        EmailCredential credential = credentials.findByNormalizedEmail(email).orElse(null);
        boolean acceptable = password != null && password.codePointCount(0, password.length()) >= 8
                && password.codePointCount(0, password.length()) <= 64
                && password.getBytes(StandardCharsets.UTF_8).length <= 72;
        boolean matches = passwordEncoder.matches(acceptable ? password : "",
                credential == null ? dummyPasswordHash : credential.passwordHash());
        User user = credential != null && matches && acceptable
                ? users.findByIdForUpdate(credential.userId())
                        .filter(found -> found.deletionRequestedAt() == null).orElse(null)
                : null;
        if (user == null) {
            now = now();
            attempts.recordFailure(email, utc(now));
            // 정상 인증 실패는 예외 대신 결과로 반환하여 실패 이력을 커밋한다.
            LocalDateTime until = attempts.blockedUntil(email, utc(now));
            return new Attempt(null, until == null ? null : until.toInstant(ZoneOffset.UTC));
        }

        UUID sessionId = UUID.randomUUID();
        IssuedRefreshToken refresh = refreshTokens.issue();
        sessions.save(new AuthSession(sessionId, user.id(), null, refresh.tokenHash(), 0,
                LocalDateTime.ofInstant(refresh.expiresAt(), ZoneOffset.UTC), null, utc(now())));
        IssuedAccessToken access = accessTokens.issue(user.id(), sessionId);
        boolean comparisonConsent = consents.findLatest(user.id(), "FRIEND_COMPARISON")
                .map(consent -> consent.granted()).orElse(false);
        // 발급 실패 시 세션 생성과 실패 이력 초기화는 함께 롤백된다.
        attempts.resetFailures(email);
        return new Attempt(new LoginResult(sessionId, access.token(), refresh.token(), access.expiresAt(),
                user, comparisonConsent), null);
    }

    private Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record Attempt(LoginResult result, Instant blockedUntil) { }
}
