package com.teamehera.kiikick.repository;

import com.teamehera.kiikick.auth.domain.EmailCredential;
import com.teamehera.kiikick.auth.email.VerificationEmailSender;
import com.teamehera.kiikick.auth.repository.EmailCredentialRepository;
import com.teamehera.kiikick.auth.security.AccessTokenService;
import com.teamehera.kiikick.auth.service.LoginResult;
import com.teamehera.kiikick.auth.service.LoginService;
import com.teamehera.kiikick.auth.service.VerificationApiException;
import com.teamehera.kiikick.auth.service.VerificationSecretGenerator;
import com.teamehera.kiikick.testsupport.LocalMySqlInitializer;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {
        "spring.sql.init.mode=never",
        "spring.datasource.url=jdbc:mysql://${MYSQL_TEST_DB_HOST}:${MYSQL_TEST_DB_PORT}/${MYSQL_TEST_DB_NAME}"
                + "?sslMode=REQUIRED&connectionTimeZone=UTC"
                + "&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
})
@ContextConfiguration(initializers = LocalMySqlInitializer.class)
@Import(MySqlRepositoryIntegrationTest.ClockConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginMySqlIntegrationTest {
    private static final String PASSWORD = "MySQL-Original-73!";
    private static final Instant NOW = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    @Autowired LoginService loginService;
    @Autowired UserRepository users;
    @Autowired EmailCredentialRepository credentials;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtDecoder decoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired MySqlRepositoryIntegrationTest.TestClock clock;
    @MockitoSpyBean AccessTokenService accessTokens;
    @MockitoBean VerificationEmailSender emailSender;
    @MockitoBean VerificationSecretGenerator verificationSecretGenerator;
    private final Map<String, UUID> fixtures = new LinkedHashMap<>();
    private String passwordHash;

    @BeforeAll
    void verifyDatabase() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("kiikick_repository_test");
        passwordHash = encoder.encode(PASSWORD);
    }

    @BeforeEach
    void resetClock() { clock.set(NOW); }

    @AfterEach
    void cleanup() {
        for (Map.Entry<String, UUID> fixture : fixtures.entrySet()) {
            jdbc.update("DELETE FROM auth_rate_limit_events WHERE rate_action = 'EMAIL_PASSWORD_LOGIN' "
                    + "AND limit_type = 'ACCOUNT' AND key_value = ?", fixture.getKey());
            jdbc.update("DELETE FROM auth_rate_limit_keys WHERE rate_action = 'EMAIL_PASSWORD_LOGIN' "
                    + "AND limit_type = 'ACCOUNT' AND key_value = ?", fixture.getKey());
            if (fixture.getValue() != null) {
                byte[] id = uuidToBytes(fixture.getValue());
                jdbc.update("DELETE FROM auth_sessions WHERE user_id = ?", id);
                jdbc.update("DELETE FROM email_credentials WHERE user_id = ?", id);
                jdbc.update("DELETE FROM users WHERE id = ?", id);
            }
        }
        fixtures.clear();
        verifyNoInteractions(emailSender);
    }

    @Test
    void 실제_JWT_15분과_Refresh_해시_90일_저장을_검증한다() throws Exception {
        String email = email();
        UUID userId = account(email);
        LoginResult result = loginService.login(" " + email.toUpperCase(java.util.Locale.ROOT) + " ", PASSWORD);
        var jwt = decoder.decode(result.accessToken());
        assertThat(jwt.getClaimAsString("uid")).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(result.sessionId().toString());
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(900));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(result.refreshToken().getBytes(StandardCharsets.UTF_8)));
        assertThat(jdbc.queryForObject("SELECT refresh_token_hash FROM auth_sessions WHERE id = ?",
                String.class, uuidToBytes(result.sessionId()))).isEqualTo(hash).isNotEqualTo(result.refreshToken());
        LocalDateTime expiresAt = jdbc.queryForObject("SELECT expires_at FROM auth_sessions WHERE id = ?",
                (rs, rowNum) -> rs.getObject(1, LocalDateTime.class), uuidToBytes(result.sessionId()));
        assertThat(expiresAt).isEqualTo(now().plusDays(90));
    }

    @Test
    void 등록과_미등록_실패를_같이_집계하고_성공은_해당계정만_초기화한다() {
        String registered = email();
        String missing = email();
        account(registered);
        for (int index = 0; index < 9; index++) reject(registered, "wrong-password", "INVALID_CREDENTIALS");
        reject(missing, PASSWORD, "INVALID_CREDENTIALS");
        loginService.login(registered, PASSWORD);
        assertThat(failureCount(registered)).isZero();
        assertThat(failureCount(missing)).isEqualTo(1);
        reject(registered, "wrong-password", "INVALID_CREDENTIALS");
        assertThat(failureCount(registered)).isEqualTo(1);
    }

    @Test
    void 동시_미등록_요청은_10회_제한을_우회하지_못하고_15분후_해제된다() throws Exception {
        String email = email();
        var pool = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 12; index++) futures.add(pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return assertThrows(VerificationApiException.class,
                        () -> loginService.login(email, PASSWORD)).code();
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> codes = new ArrayList<>();
            for (Future<String> future : futures) codes.add(future.get(30, TimeUnit.SECONDS));
            assertThat(codes.stream().filter("INVALID_CREDENTIALS"::equals).count()).isEqualTo(9);
            assertThat(codes.stream().filter("RATE_LIMITED"::equals).count()).isEqualTo(3);
            assertThat(failureCount(email)).isEqualTo(10);
            clock.advance(Duration.ofMinutes(15).minusSeconds(1));
            reject(email, PASSWORD, "RATE_LIMITED");
            assertThat(failureCount(email)).isEqualTo(10);
            clock.advance(Duration.ofSeconds(1));
            reject(email, PASSWORD, "INVALID_CREDENTIALS");
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void 초기실패가_집계창에서_빠져도_제한은_열번째_실패후_15분간_유지된다() {
        String email = email();
        reject(email, PASSWORD, "INVALID_CREDENTIALS");
        clock.advance(Duration.ofMinutes(14));
        for (int index = 0; index < 8; index++) reject(email, PASSWORD, "INVALID_CREDENTIALS");
        reject(email, PASSWORD, "RATE_LIMITED");
        clock.advance(Duration.ofMinutes(1));
        reject(email, PASSWORD, "RATE_LIMITED");
        clock.advance(Duration.ofMinutes(14));
        reject(email, PASSWORD, "INVALID_CREDENTIALS");
    }

    @Test
    void JWT_실패시_MySQL_세션과_실패초기화를_롤백한다() {
        String email = email();
        UUID userId = account(email);
        reject(email, "wrong-password", "INVALID_CREDENTIALS");
        doThrow(new JwtEncodingException("private-jwt-detail"))
                .when(accessTokens).issue(any(UUID.class), any(UUID.class));
        reject(email, PASSWORD, "AUTHENTICATION_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE user_id = ?",
                Integer.class, uuidToBytes(userId))).isZero();
        assertThat(failureCount(email)).isEqualTo(1);
    }

    private String email() {
        String email = "mysql-login-" + UUID.randomUUID() + "@example.com";
        fixtures.put(email, null);
        return email;
    }
    private UUID account(String email) {
        UUID userId = UUID.randomUUID();
        fixtures.put(email, userId);
        users.save(userId, null);
        credentials.save(new EmailCredential(userId, email, email, passwordHash, now(), now(), now(), now()));
        return userId;
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private int failureCount(String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limit_events "
                + "WHERE rate_action = 'EMAIL_PASSWORD_LOGIN' AND key_value = ?", Integer.class, email);
    }
    private void reject(String email, String password, String code) {
        assertThat(assertThrows(VerificationApiException.class,
                () -> loginService.login(email, password)).code()).isEqualTo(code);
    }
}
