package com.teamehera.kiikick.auth.service;

import com.teamehera.kiikick.auth.domain.AuthSession;
import com.teamehera.kiikick.auth.domain.EmailCredential;
import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import com.teamehera.kiikick.auth.repository.EmailCredentialRepository;
import com.teamehera.kiikick.auth.repository.EmailSendRateLimitRepository;
import com.teamehera.kiikick.auth.repository.LoginAttemptRepository;
import com.teamehera.kiikick.auth.security.AccessTokenService;
import com.teamehera.kiikick.auth.security.IssuedAccessToken;
import com.teamehera.kiikick.auth.security.RefreshTokenIssuer;
import com.teamehera.kiikick.user.repository.UserConsentRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@JdbcTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({LoginService.class, LoginTransactionService.class, EmailNormalizer.class,
        LoginAttemptRepository.class, EmailCredentialRepository.class, AuthSessionRepository.class,
        UserRepository.class, UserConsentRepository.class, RefreshTokenIssuer.class,
        EmailSendRateLimitRepository.class, LoginServiceTest.TestBeans.class})
class LoginServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final String PASSWORD = "Original-Case-73!";
    @Autowired LoginService loginService;
    @Autowired UserRepository users;
    @Autowired EmailCredentialRepository credentials;
    @Autowired EmailSendRateLimitRepository emailSendLimits;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @MockitoSpyBean PasswordEncoder encoder;
    @MockitoSpyBean AuthSessionRepository sessions;
    @MockitoBean AccessTokenService accessTokens;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        when(accessTokens.issue(any(UUID.class), any(UUID.class)))
                .thenReturn(new IssuedAccessToken("login-access-token", NOW.plusSeconds(900)));
        clearInvocations(encoder);
    }

    @Test
    void 정규화_이메일과_원문_비밀번호로_세션을_발급하고_Refresh는_해시만_저장한다() {
        String email = email();
        UUID userId = account(email);
        LoginResult result = loginService.login(" " + email.toUpperCase(java.util.Locale.ROOT) + " ", PASSWORD);
        assertThat(result.user().id()).isEqualTo(userId);
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(result.comparisonConsent()).isFalse();
        AuthSession stored = sessions.findActiveById(result.sessionId(), now()).orElseThrow();
        assertThat(stored.refreshTokenHash()).hasSize(64).isNotEqualTo(result.refreshToken());
        assertThat(stored.expiresAt()).isEqualTo(now().plusDays(90));
        assertThat(failureCount(email)).isZero();
        assertThat(result.toString()).doesNotContain(result.accessToken(), result.refreshToken());
    }

    @Test
    void 미등록과_잘못된_비밀번호는_동일한_오류와_BCrypt_검증을_사용한다() {
        String registered = email();
        String missing = email();
        account(registered);
        clearInvocations(encoder);
        assertRejected(registered, "wrong-password", "INVALID_CREDENTIALS");
        assertRejected(missing, "wrong-password", "INVALID_CREDENTIALS");
        verify(encoder, times(2)).matches(anyString(), anyString());
        assertThat(failureCount(registered)).isEqualTo(1);
        assertThat(failureCount(missing)).isEqualTo(1);
    }

    @Test
    void 실패_10회부터_15분간_제한하고_정확히_만료되면_다시_시도한다() {
        String email = email();
        for (int index = 0; index < 9; index++) assertRejected(email, PASSWORD, "INVALID_CREDENTIALS");
        assertRejected(email, PASSWORD, "RATE_LIMITED");
        clock.advance(Duration.ofMinutes(15).minusSeconds(1));
        assertRejected(email, PASSWORD, "RATE_LIMITED");
        assertThat(failureCount(email)).isEqualTo(10);
        clock.advance(Duration.ofSeconds(1));
        assertRejected(email, PASSWORD, "INVALID_CREDENTIALS");
        assertThat(failureCount(email)).isEqualTo(11);
    }

    @Test
    void 초기_실패가_집계창에서_빠져도_열번째_실패부터_15분간_제한한다() {
        String email = email();
        assertRejected(email, PASSWORD, "INVALID_CREDENTIALS");
        clock.advance(Duration.ofMinutes(14));
        for (int index = 0; index < 8; index++) assertRejected(email, PASSWORD, "INVALID_CREDENTIALS");
        assertRejected(email, PASSWORD, "RATE_LIMITED");
        clock.advance(Duration.ofMinutes(1));
        assertRejected(email, PASSWORD, "RATE_LIMITED");
        clock.advance(Duration.ofMinutes(14));
        assertRejected(email, PASSWORD, "INVALID_CREDENTIALS");
    }

    @Test
    void 정확히_15분_전의_실패는_새_집계에서_제외한다() {
        String email = email();
        for (int index = 0; index < 9; index++) assertRejected(email, PASSWORD, "INVALID_CREDENTIALS");
        clock.advance(Duration.ofMinutes(15));
        assertRejected(email, PASSWORD, "INVALID_CREDENTIALS");
        for (int index = 0; index < 8; index++) assertRejected(email, PASSWORD, "INVALID_CREDENTIALS");
        assertRejected(email, PASSWORD, "RATE_LIMITED");
    }

    @Test
    void 성공하면_해당_계정의_실패만_초기화하고_이메일_발송이력은_유지한다() {
        String email = email();
        String other = email();
        account(email);
        for (int index = 0; index < 9; index++) assertRejected(email, "wrong-password", "INVALID_CREDENTIALS");
        assertRejected(other, PASSWORD, "INVALID_CREDENTIALS");
        assertThat(emailSendLimits.tryAcquire(email, UUID.randomUUID().toString(), now()).allowed()).isTrue();
        loginService.login(email, PASSWORD);
        assertThat(failureCount(email)).isZero();
        assertThat(failureCount(other)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limit_events "
                + "WHERE rate_action = 'EMAIL_VERIFICATION_SEND' AND limit_type = 'EMAIL' AND key_value = ?",
                Integer.class, email)).isEqualTo(1);
        assertRejected(email, "wrong-password", "INVALID_CREDENTIALS");
        assertThat(failureCount(email)).isEqualTo(1);
    }

    @Test
    void JWT_발급실패는_세션과_실패_초기화를_롤백한다() {
        String email = email();
        UUID userId = account(email);
        assertRejected(email, "wrong-password", "INVALID_CREDENTIALS");
        when(accessTokens.issue(any(UUID.class), any(UUID.class)))
                .thenThrow(new JwtEncodingException("private-token-do-not-expose"));
        assertRejected(email, PASSWORD, "AUTHENTICATION_UNAVAILABLE");
        assertThat(sessionCount(userId)).isZero();
        assertThat(failureCount(email)).isEqualTo(1);
    }

    @Test
    void 세션저장_실패는_공통503으로_처리하고_실패이력을_유지한다() {
        String email = email();
        UUID userId = account(email);
        assertRejected(email, "wrong-password", "INVALID_CREDENTIALS");
        doThrow(new DataAccessResourceFailureException("private-storage-detail"))
                .when(sessions).save(any(AuthSession.class));
        assertRejected(email, PASSWORD, "AUTHENTICATION_UNAVAILABLE");
        assertThat(sessionCount(userId)).isZero();
        assertThat(failureCount(email)).isEqualTo(1);
    }

    @Test
    void 과도한_UTF8_비밀번호는_예외노출_없이_인증실패로_집계한다() {
        String email = email();
        account(email);
        assertRejected(email, "한".repeat(25), "INVALID_CREDENTIALS");
        assertRejected(email, "a".repeat(65), "INVALID_CREDENTIALS");
        assertThat(failureCount(email)).isEqualTo(2);
    }

    @Test
    void 동시_미등록_로그인도_10회_실패_제한을_우회하지_못한다() throws Exception {
        String email = email();
        var pool = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int index = 0; index < 12; index++) results.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                try {
                    loginService.login(email, PASSWORD);
                    return "unexpected-success";
                } catch (VerificationApiException exception) {
                    return exception.code();
                }
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> codes = new ArrayList<>();
            for (Future<String> result : results) codes.add(result.get(20, TimeUnit.SECONDS));
            assertThat(codes.stream().filter("INVALID_CREDENTIALS"::equals).count()).isEqualTo(9);
            assertThat(codes.stream().filter("RATE_LIMITED"::equals).count()).isEqualTo(3);
            assertThat(failureCount(email)).isEqualTo(10);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private UUID account(String email) {
        UUID userId = UUID.randomUUID();
        users.save(userId, null);
        credentials.save(new EmailCredential(userId, email, email, encoder.encode(PASSWORD),
                now(), now(), now(), now()));
        return userId;
    }

    private String email() { return "login-" + UUID.randomUUID() + "@example.com"; }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private int failureCount(String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limit_events "
                + "WHERE rate_action = 'EMAIL_PASSWORD_LOGIN' AND key_value = ?", Integer.class, email);
    }
    private int sessionCount(UUID userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE user_id = ?",
                Integer.class, com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes(userId));
    }
    private void assertRejected(String email, String password, String code) {
        assertThatThrownBy(() -> loginService.login(email, password))
                .isInstanceOfSatisfying(VerificationApiException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(code);
                    assertThat(exception.status()).isEqualTo(switch (code) {
                        case "RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
                        case "AUTHENTICATION_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
                        default -> HttpStatus.UNAUTHORIZED;
                    });
                    assertThat(exception.getMessage()).doesNotContain(password, "private-");
                });
    }

    @TestConfiguration
    static class TestBeans {
        @Bean MutableClock loginClock() { return new MutableClock(NOW, ZoneOffset.UTC); }
        @Bean PasswordEncoder loginPasswordEncoder() { return new BCryptPasswordEncoder(4); }
    }
}
