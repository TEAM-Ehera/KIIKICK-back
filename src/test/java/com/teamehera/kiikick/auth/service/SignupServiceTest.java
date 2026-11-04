package com.teamehera.kiikick.auth.service;

import com.teamehera.kiikick.auth.config.EmailVerificationConfig;
import com.teamehera.kiikick.auth.domain.EmailVerificationFlow;
import com.teamehera.kiikick.auth.password.CommonPasswordBlacklist;
import com.teamehera.kiikick.auth.password.PasswordPolicyValidator;
import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import com.teamehera.kiikick.auth.repository.EmailCredentialRepository;
import com.teamehera.kiikick.auth.repository.EmailVerificationFlowRepository;
import com.teamehera.kiikick.auth.security.AccessTokenService;
import com.teamehera.kiikick.auth.security.AuthHashEncoder;
import com.teamehera.kiikick.auth.security.IssuedAccessToken;
import com.teamehera.kiikick.auth.security.IssuedRefreshToken;
import com.teamehera.kiikick.auth.security.RefreshTokenIssuer;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@JdbcTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        SignupService.class,
        SignupTransactionService.class,
        RegistrationTokenService.class,
        PasswordPolicyValidator.class,
        CommonPasswordBlacklist.class,
        EmailVerificationConfig.class,
        UserRepository.class,
        EmailCredentialRepository.class,
        EmailVerificationFlowRepository.class,
        AuthSessionRepository.class,
        SignupServiceTest.TestBeans.class
})
class SignupServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");

    @Autowired SignupService signupService;
    @Autowired EmailVerificationFlowRepository flowRepository;
    @Autowired EmailCredentialRepository credentialRepository;
    @Autowired AuthSessionRepository sessionRepository;
    @Autowired UserRepository userRepository;
    @Autowired AuthHashEncoder hashEncoder;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean AccessTokenService accessTokenService;
    @MockitoBean RefreshTokenIssuer refreshTokenIssuer;

    @BeforeEach
    void setUp() {
        when(refreshTokenIssuer.issue()).thenReturn(new IssuedRefreshToken(
                "refresh-token", "a".repeat(64), NOW.plusSeconds(90L * 24 * 60 * 60)
        ));
        when(accessTokenService.issue(any(UUID.class), any(UUID.class)))
                .thenAnswer(invocation -> new IssuedAccessToken(
                        "access-token",
                        NOW.plusSeconds(4 * 60 * 60)
                ));
    }

    @Test
    void 유효한_registrationToken으로_사용자_Credential_세션을_원자적으로_생성한다() {
        String email = uniqueEmail("success");
        String registrationToken = createRegistrationToken(email, 10);

        SignupResult result = signupService.signup(
                registrationToken, "Valid-local-72!"
        );

        assertThat(userRepository.findById(result.userId())).isPresent();
        assertThat(credentialRepository.findByNormalizedEmail(email))
                .get()
                .satisfies(credential -> {
                    assertThat(credential.passwordHash())
                            .isNotEqualTo("Valid-local-72!");
                    assertThat(passwordEncoder.matches(
                            "Valid-local-72!", credential.passwordHash()
                    )).isTrue();
                });
        assertThat(sessionRepository.findActiveById(
                result.sessionId(), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)
        )).get().satisfies(session -> {
            assertThat(session.refreshTokenHash()).isEqualTo("a".repeat(64));
            assertThat(session.refreshTokenHash()).isNotEqualTo("refresh-token");
            assertThat(session.deviceId()).isNull();
        });
        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    void 사용한_registrationToken과_만료된_토큰은_거부한다() {
        String email = uniqueEmail("token");
        String registrationToken = createRegistrationToken(email, 10);
        signupService.signup(registrationToken, "Valid-token-72!");

        assertThatThrownBy(() -> signupService.signup(
                registrationToken, "Another-token-72!"
        )).isInstanceOf(VerificationApiException.class)
                .extracting(exception -> ((VerificationApiException) exception).code())
                .isEqualTo("INVALID_REGISTRATION_TOKEN");

        String expiredToken = createRegistrationToken(uniqueEmail("expired"), -1);
        assertThatThrownBy(() -> signupService.signup(
                expiredToken, "Valid-expired-72!"
        )).isInstanceOf(VerificationApiException.class);
    }

    @Test
    void SIGN_UP이_아닌_목적의_registrationToken은_거부한다() {
        String registrationToken = createRegistrationToken(
                uniqueEmail("purpose"), "PASSWORD_RESET", 10
        );

        assertThatThrownBy(() -> signupService.signup(
                registrationToken, "Valid-purpose-72!"
        )).isInstanceOf(VerificationApiException.class)
                .extracting(exception -> ((VerificationApiException) exception).code())
                .isEqualTo("INVALID_REGISTRATION_TOKEN");
    }

    @Test
    void 같은_이메일의_중복가입은_차단하고_실패한_토큰소비를_롤백한다() {
        String email = uniqueEmail("duplicate");
        signupService.signup(
                createRegistrationToken(email, 10), "Valid-first-72!"
        );
        String secondToken = createRegistrationToken(email, 10);
        int usersBefore = countRows("users");
        int credentialsBefore = countRows("email_credentials");
        int sessionsBefore = countRows("auth_sessions");

        assertThatThrownBy(() -> signupService.signup(
                secondToken, "Valid-second-72!"
        )).isInstanceOf(SignupApiException.class)
                .extracting(exception -> ((SignupApiException) exception).code())
                .isEqualTo("SIGNUP_NOT_AVAILABLE");

        assertThat(flowRepository.findActiveByActionToken(
                hashEncoder.hashActionToken(secondToken),
                "SIGN_UP",
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)
        )).isPresent();
        assertThat(countRows("users")).isEqualTo(usersBefore);
        assertThat(countRows("email_credentials")).isEqualTo(credentialsBefore);
        assertThat(countRows("auth_sessions")).isEqualTo(sessionsBefore);
    }

    @Test
    void AccessToken_발급실패는_사용자_Credential_세션과_토큰소비를_롤백한다() {
        String email = uniqueEmail("rollback");
        String registrationToken = createRegistrationToken(email, 10);
        int usersBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users", Integer.class
        );
        int credentialsBefore = countRows("email_credentials");
        int sessionsBefore = countRows("auth_sessions");
        when(accessTokenService.issue(any(UUID.class), any(UUID.class)))
                .thenThrow(new JwtEncodingException("encoding failed"));

        assertThatThrownBy(() -> signupService.signup(
                registrationToken, "Valid-rollback-72!"
        )).isInstanceOf(SignupApiException.class)
                .extracting(exception -> ((SignupApiException) exception).code())
                .isEqualTo("TOKEN_ISSUANCE_FAILED");

        assertThat(credentialRepository.findByNormalizedEmail(email)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users", Integer.class
        )).isEqualTo(usersBefore);
        assertThat(countRows("email_credentials")).isEqualTo(credentialsBefore);
        assertThat(countRows("auth_sessions")).isEqualTo(sessionsBefore);
        assertThat(flowRepository.findActiveByActionToken(
                hashEncoder.hashActionToken(registrationToken),
                "SIGN_UP",
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)
        )).isPresent();
    }

    @Test
    void 세션_저장실패는_사용자_Credential과_토큰소비를_롤백한다() {
        String email = uniqueEmail("session-rollback");
        String registrationToken = createRegistrationToken(email, 10);
        int usersBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users", Integer.class
        );
        int credentialsBefore = countRows("email_credentials");
        int sessionsBefore = countRows("auth_sessions");
        when(refreshTokenIssuer.issue()).thenReturn(new IssuedRefreshToken(
                "refresh-token", "a".repeat(65), NOW.plusSeconds(90L * 24 * 60 * 60)
        ));

        assertThatThrownBy(() -> signupService.signup(
                registrationToken, "Valid-session-rollback-72!"
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(credentialRepository.findByNormalizedEmail(email)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users", Integer.class
        )).isEqualTo(usersBefore);
        assertThat(countRows("email_credentials")).isEqualTo(credentialsBefore);
        assertThat(countRows("auth_sessions")).isEqualTo(sessionsBefore);
        assertThat(flowRepository.findActiveByActionToken(
                hashEncoder.hashActionToken(registrationToken),
                "SIGN_UP",
                LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)
        )).isPresent();
    }

    @Test
    void 서로_다른_토큰으로_같은_이메일에_동시가입해도_한_계정만_생성된다()
            throws Exception {
        String email = uniqueEmail("concurrent");
        String firstToken = createRegistrationToken(email, 10);
        String secondToken = createRegistrationToken(email, 10);
        int usersBefore = countRows("users");
        int credentialsBefore = countRows("email_credentials");
        int sessionsBefore = countRows("auth_sessions");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (String token : List.of(firstToken, secondToken)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        signupService.signup(token, "Valid-concurrent-72!");
                        return true;
                    } catch (RuntimeException exception) {
                        return false;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(15, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM email_credentials WHERE normalized_email = ?",
                    Integer.class,
                    email
            )).isEqualTo(1);
            assertThat(countRows("users")).isEqualTo(usersBefore + 1);
            assertThat(countRows("email_credentials")).isEqualTo(credentialsBefore + 1);
            assertThat(countRows("auth_sessions")).isEqualTo(sessionsBefore + 1);
            assertThat(jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*) FROM email_verification_flows
                    WHERE action_token_hash IN (?, ?)
                      AND action_token_consumed_at IS NULL
                    """,
                    Integer.class,
                    hashEncoder.hashActionToken(firstToken),
                    hashEncoder.hashActionToken(secondToken)
            )).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private String createRegistrationToken(String email, long expiresInMinutes) {
        return createRegistrationToken(email, "SIGN_UP", expiresInMinutes);
    }

    private String createRegistrationToken(
            String email, String purpose, long expiresInMinutes
    ) {
        String token = "registration-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        flowRepository.save(new EmailVerificationFlow(
                UUID.randomUUID(), email, purpose, null, 0,
                now.plusMinutes(3), now, now, null,
                hashEncoder.hashActionToken(token),
                now.plusMinutes(expiresInMinutes),
                null, now, now
        ));
        return token;
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    @TestConfiguration
    static class TestBeans {

        @Bean
        Clock signupTestClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        PasswordEncoder signupPasswordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }
}
