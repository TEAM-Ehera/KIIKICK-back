package com.teamehera.kiikick.repository;

import com.teamehera.kiikick.auth.domain.AuthIdentity;
import com.teamehera.kiikick.auth.domain.AuthSession;
import com.teamehera.kiikick.auth.domain.EmailCredential;
import com.teamehera.kiikick.auth.domain.EmailVerificationFlow;
import com.teamehera.kiikick.auth.repository.AuthIdentityRepository;
import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import com.teamehera.kiikick.auth.repository.EmailCredentialRepository;
import com.teamehera.kiikick.auth.repository.EmailSendRateLimitRepository;
import com.teamehera.kiikick.auth.repository.EmailVerificationFlowRepository;
import com.teamehera.kiikick.auth.email.VerificationEmailSender;
import com.teamehera.kiikick.auth.service.EmailVerificationRequestResult;
import com.teamehera.kiikick.auth.service.EmailVerificationService;
import com.teamehera.kiikick.auth.service.EmailVerificationSuccessResult;
import com.teamehera.kiikick.auth.service.RegistrationTokenService;
import com.teamehera.kiikick.auth.service.VerificationApiException;
import com.teamehera.kiikick.auth.service.VerificationSecretGenerator;
import com.teamehera.kiikick.testsupport.LocalMySqlInitializer;
import com.teamehera.kiikick.auth.security.AuthHashEncoder;
import com.teamehera.kiikick.auth.service.SignupResult;
import com.teamehera.kiikick.auth.service.SignupService;
import com.teamehera.kiikick.friend.domain.FriendPair;
import com.teamehera.kiikick.friend.domain.UserBlock;
import com.teamehera.kiikick.friend.repository.FriendPairRepository;
import com.teamehera.kiikick.friend.repository.UserBlockRepository;
import com.teamehera.kiikick.global.idempotency.domain.IdempotencyRecord;
import com.teamehera.kiikick.global.idempotency.repository.IdempotencyRecordRepository;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.domain.Quarter;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterPlayerStatsRepository;
import com.teamehera.kiikick.match.repository.QuarterPositionRepository;
import com.teamehera.kiikick.match.repository.QuarterRepository;
import com.teamehera.kiikick.notification.domain.Device;
import com.teamehera.kiikick.notification.domain.Notification;
import com.teamehera.kiikick.notification.repository.DeviceRepository;
import com.teamehera.kiikick.notification.repository.NotificationRepository;
import com.teamehera.kiikick.sync.domain.SyncOperation;
import com.teamehera.kiikick.sync.repository.SyncOperationRepository;
import com.teamehera.kiikick.user.domain.AccountDeletionRequest;
import com.teamehera.kiikick.user.domain.UserConsent;
import com.teamehera.kiikick.user.repository.AccountDeletionRequestRepository;
import com.teamehera.kiikick.user.repository.UserConsentRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import com.teamehera.kiikick.venue.domain.Venue;
import com.teamehera.kiikick.venue.repository.VenueRepository;
import com.teamehera.kiikick.workout.domain.HeartRateSample;
import com.teamehera.kiikick.workout.domain.QuarterSummary;
import com.teamehera.kiikick.workout.domain.TrackPoint;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import com.teamehera.kiikick.workout.dto.HeartRateSampleRequest;
import com.teamehera.kiikick.workout.dto.TrackPointRequest;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateRequest;
import com.teamehera.kiikick.workout.repository.HeartRateSampleRepository;
import com.teamehera.kiikick.workout.repository.QuarterSummaryRepository;
import com.teamehera.kiikick.workout.repository.TrackPointRepository;
import com.teamehera.kiikick.workout.repository.WorkoutPayloadRepository;
import com.teamehera.kiikick.workout.service.WorkoutPayloadService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.datasource.url=jdbc:mysql://${MYSQL_TEST_DB_HOST}:${MYSQL_TEST_DB_PORT}/${MYSQL_TEST_DB_NAME}"
                + "?sslMode=REQUIRED&connectionTimeZone=UTC"
                + "&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8"
})
@ContextConfiguration(initializers = LocalMySqlInitializer.class)
@Import(MySqlRepositoryIntegrationTest.ClockConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlRepositoryIntegrationTest {

    private static final String SAFE_DATABASE = "kiikick_repository_test";

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired VenueRepository venues;
    @Autowired MatchRepository matches;
    @Autowired QuarterRepository quarters;
    @Autowired WorkoutPayloadRepository workouts;
    @Autowired WorkoutPayloadService legacyWorkoutUploads;
    @Autowired TrackPointRepository trackPoints;
    @Autowired HeartRateSampleRepository heartRates;
    @Autowired QuarterSummaryRepository summaries;
    @Autowired QuarterPlayerStatsRepository stats;
    @Autowired QuarterPositionRepository positions;
    @Autowired AuthIdentityRepository identities;
    @Autowired EmailCredentialRepository emailCredentials;
    @Autowired EmailVerificationFlowRepository emailFlows;
    @Autowired EmailSendRateLimitRepository emailSendRateLimits;
    @Autowired EmailVerificationService emailVerificationService;
    @Autowired RegistrationTokenService registrationTokenService;
    @Autowired TestClock clock;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired AuthHashEncoder authHashEncoder;
    @Autowired SignupService signupService;
    @Autowired UserConsentRepository consents;
    @Autowired AuthSessionRepository sessions;
    @Autowired DeviceRepository devices;
    @Autowired AccountDeletionRequestRepository deletionRequests;
    @Autowired FriendPairRepository friends;
    @Autowired UserBlockRepository blocks;
    @Autowired NotificationRepository notifications;
    @Autowired IdempotencyRecordRepository idempotency;
    @Autowired SyncOperationRepository syncOperations;

    @MockitoBean VerificationEmailSender verificationEmailSender;
    @MockitoBean VerificationSecretGenerator verificationSecretGenerator;

    @BeforeEach
    void resetClock() {
        clock.set(Instant.parse("2026-11-05T00:00:00Z"));
    }

    @BeforeAll
    void verifyIsolatedDatabaseBeforeAnyWrite() {
        assertEquals(SAFE_DATABASE, jdbc.queryForObject("SELECT DATABASE()", String.class));
        Integer tableCount = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                """,
                Integer.class
        );
        assertEquals(28, tableCount);
    }

    @Test
    @Transactional
    void legacyUploadRemainsAvailableAfterMigration() {
        UUID userId = UUID.randomUUID();
        users.save(userId, "legacy-player");
        var request = new WorkoutPayloadCreateRequest(
                1000.0, 1060.0, 5.25, 12.5,
                List.of(new TrackPointRequest(37.5, 127.0, 10.5, 1.25, -1.0, -1.0, 1000.0)),
                List.of(new HeartRateSampleRequest(1000.0, 135.5))
        );

        UUID workoutId = legacyWorkoutUploads.create(userId, request).workoutPayloadId();
        byte[] storedId = uuidToBytes(workoutId);

        assertAll(
                () -> assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM workoutPayloads WHERE id = ? AND userId = ?",
                        Integer.class, storedId, uuidToBytes(userId))),
                () -> assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM trackPoints WHERE workoutPayloadId = ? AND sampleSeq = 0",
                        Integer.class, storedId)),
                () -> assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM heartRateSamples WHERE workoutPayloadId = ? AND sampleSeq = 0",
                        Integer.class, storedId)),
                () -> assertTrue(workouts.findById(workoutId).isEmpty())
        );
    }

    @Test
    @Transactional
    void p0_사용자_경기장_경기_쿼터와_잠금_SET_NULL을_검증한다() {
        LocalDateTime precise = LocalDateTime.of(2026, 11, 3, 1, 2, 3, 123_456_000);
        UUID userId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID quarterId = UUID.randomUUID();
        users.save(userId, null);
        venues.save(new Venue(venueId, userId, "MySQL 경기장", null, precise, precise));
        matches.save(new Match(
                matchId, userId, venueId, "통합 경기", "MySQL 경기장", null,
                precise, "DRAFT", null, null, 1, precise, precise
        ));
        quarters.save(new Quarter(
                quarterId, matchId, 1, 1200, "READY", null, null, precise, precise
        ));

        assertAll(
                () -> assertEquals(precise, venues.findByIdAndOwnerId(venueId, userId).orElseThrow().createdAt()),
                () -> assertEquals(matchId, matches.findByIdAndOwnerIdForUpdate(matchId, userId).orElseThrow().id()),
                () -> assertEquals(matchId, quarters.findByIdAndOwnerId(quarterId, userId).orElseThrow().matchId()),
                () -> assertEquals(1, quarters.updateDuration(quarterId, matchId, 1500)),
                () -> assertEquals(1, quarters.deleteUnusedLast(quarterId, matchId)),
                () -> assertEquals(1, venues.deleteByIdAndOwnerId(venueId, userId)),
                () -> assertEquals(null, matches.findByIdAndOwnerId(matchId, userId).orElseThrow().venueId())
        );
    }

    @Test
    @Transactional
    void p0_p1_운동_batch_JSON_복합FK_unique_통계_upsert를_검증한다() {
        Fixture f = fixture(LocalDateTime.of(2026, 11, 3, 2, 0, 0, 654_321_000));
        UUID workoutId = UUID.randomUUID();
        workouts.save(new WorkoutPayload(
                workoutId, f.quarterId(), f.userId(), f.now(), f.now().plusMinutes(20), 20, 200
        ));
        trackPoints.saveAll(List.of(
                new TrackPoint(workoutId, 0, 37, 127, 1, 2, 3, 4, f.now()),
                new TrackPoint(workoutId, 1, 38, 128, 2, 3, 4, 5, f.now().plusSeconds(1))
        ));
        heartRates.saveAll(List.of(
                new HeartRateSample(workoutId, 0, f.now(), 140),
                new HeartRateSample(workoutId, 1, f.now().plusSeconds(1), 145)
        ));
        summaries.saveProcessing(f.quarterId(), workoutId, "v1", "{\"hr\":190}",
                "{\"distance\":\"READY\"}", "hash", f.now());
        stats.upsert(f.quarterId(), 1, 2, f.now());
        stats.upsert(f.quarterId(), 3, 4, f.now().plusSeconds(1));
        positions.upsert(f.quarterId(), "FW", f.now());

        assertAll(
                () -> assertEquals(2, trackPoints.findAllForCalculation(workoutId).size()),
                () -> assertEquals(2, heartRates.findPage(workoutId, 0, 10).size()),
                () -> assertTrue(summaries.findByQuarterId(f.quarterId()).orElseThrow()
                        .calculationParameters().contains("hr")),
                () -> assertEquals(3, stats.findByQuarterId(f.quarterId()).orElseThrow().goals()),
                () -> assertEquals("FW", positions.findByQuarterId(f.quarterId()).orElseThrow().positionCode()),
                () -> assertThrows(DataIntegrityViolationException.class, () ->
                        workouts.save(new WorkoutPayload(
                                UUID.randomUUID(), f.quarterId(), f.userId(), f.now(),
                                f.now().plusMinutes(1), 1, 1
                        )))
        );
    }

    @Test
    @Transactional
    void p2_인증_동의_기기_세션_탈퇴를_검증한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 3, 0);
        UUID userId = UUID.randomUUID();
        users.save(userId, "인증사용자");
        identities.save(new AuthIdentity(UUID.randomUUID(), userId, "APPLE", "sub", now));
        emailCredentials.save(new EmailCredential(
                userId, "User@example.com", "user@example.com", "$2a$12$hash",
                now, now, now, now
        ));
        UUID flowId = UUID.randomUUID();
        emailFlows.save(new EmailVerificationFlow(
                flowId, "user@example.com", "SIGN_UP", "a".repeat(64), 0,
                now.plusMinutes(3), now, null, null,
                null, null, null, now, now
        ));
        consents.save(new UserConsent(UUID.randomUUID(), userId, "FRIEND_COMPARISON", "v1", true, now));
        UUID deviceId = UUID.randomUUID();
        devices.save(new Device(deviceId, userId, "IOS", "cipher", true, now, null));
        UUID sessionId = UUID.randomUUID();
        sessions.save(new AuthSession(
                sessionId, userId, deviceId, "old", 0, now.plusDays(1), null, now
        ));
        UUID deletionId = UUID.randomUUID();
        deletionRequests.save(new AccountDeletionRequest(
                deletionId, userId, "PENDING", now, null, null
        ));

        assertAll(
                () -> assertEquals(userId, identities.findByProviderAndSubject("APPLE", "sub").orElseThrow().userId()),
                () -> assertEquals(userId, emailCredentials
                        .findByNormalizedEmail("user@example.com").orElseThrow().userId()),
                () -> assertEquals(1, emailFlows.verifyAndIssueActionToken(
                        flowId, "a".repeat(64), "b".repeat(64),
                        now.plusMinutes(10), now.plusMinutes(1)
                )),
                () -> assertIsolatedRateLimitAcquisition(now),
                () -> assertTrue(consents.findLatest(userId, "FRIEND_COMPARISON").orElseThrow().granted()),
                () -> assertEquals(1, sessions.rotateRefreshToken(sessionId, 0, "new", now.plusDays(2))),
                () -> assertEquals(0, sessions.rotateRefreshToken(sessionId, 0, "stale", now.plusDays(3))),
                () -> assertEquals(1, deletionRequests.updateStatus(
                        deletionId, "PENDING", "COMPLETED", now.plusHours(1), null
                )),
                () -> assertEquals(1, devices.revoke(deviceId, userId, now.plusMinutes(1)))
        );
    }

    @Test
    void p2_동시_이메일_발송은_5회까지만_허용한다() throws Exception {
        LocalDateTime now = LocalDateTime.now().withNano(123_456_000);
        String email = "mysql-concurrent-" + UUID.randomUUID() + "@example.com";
        String ipPrefix = "mysql-ip-" + UUID.randomUUID() + "-";
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new java.util.ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                String ipHash = ipPrefix + index;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return emailSendRateLimits.tryAcquire(email, ipHash, now).allowed();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int allowed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    allowed++;
                }
            }
            assertEquals(5, allowed);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            jdbc.update("DELETE FROM auth_rate_limit_events WHERE rate_action = 'EMAIL_VERIFICATION_SEND' "
                    + "AND ((limit_type = 'EMAIL' AND key_value = ?) OR (limit_type = 'IP' AND key_value LIKE ?))",
                    email, ipPrefix + "%");
            jdbc.update("DELETE FROM auth_rate_limit_keys WHERE rate_action = 'EMAIL_VERIFICATION_SEND' "
                    + "AND ((limit_type = 'EMAIL' AND key_value = ?) OR (limit_type = 'IP' AND key_value LIKE ?))",
                    email, ipPrefix + "%");
        }
    }

    @Test
    @Transactional
    void p2_이메일_인증_서비스가_MySQL에서_발송과_검증을_처리한다() {
        String email = "mysql-verification-" + UUID.randomUUID() + "@example.com";
        when(verificationSecretGenerator.generateCode()).thenReturn("654321");
        when(verificationSecretGenerator.generateActionToken())
                .thenReturn("mysql-registration-token-256-bit-value");
        doNothing().when(verificationEmailSender)
                .sendSignUpCode(anyString(), anyString(), anyLong());

        EmailVerificationRequestResult requested = emailVerificationService.requestCode(
                email, "203.0.113.200"
        );
        EmailVerificationSuccessResult verified = emailVerificationService.verifyCode(
                requested.verificationId(), "654321"
        );

        assertAll(
                () -> assertEquals("mysql-registration-token-256-bit-value",
                        verified.registrationToken()),
                () -> assertTrue(emailFlows.findActiveByActionToken(
                        authHashEncoder.hashActionToken(verified.registrationToken()),
                        "SIGN_UP", LocalDateTime.ofInstant(verified.expiresAt().minusSeconds(1), ZoneOffset.UTC)
                ).isPresent())
        );
    }

    @Test
    void p2_인증완료_후_59초_재요청을_거부한다() {
        String email = "mysql-cooldown-" + UUID.randomUUID() + "@example.com";
        String ip = uniqueTestIp();
        try {
            EmailVerificationRequestResult first = requestVerifiedFlow(email, ip);
            clock.advance(Duration.ofSeconds(59));
            VerificationApiException error = assertThrows(VerificationApiException.class,
                    () -> emailVerificationService.requestCode(email, ip));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.status());
            assertEquals("RATE_LIMITED", error.code());
            assertEquals(first.resendAvailableAt(), error.retryAt());
            verify(verificationEmailSender, times(1)).sendSignUpCode(email, "654321", 3);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM email_verification_flows WHERE normalized_email = ?", Integer.class, email));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM auth_rate_limit_events WHERE limit_type = 'EMAIL' AND key_value = ?",
                    Integer.class, email));
        } finally {
            cleanupEmailFixture(email, ip);
        }
    }

    @Test
    void p2_정확히_60초에_새_Flow를_생성하고_기존_토큰을_보존한다() {
        String email = "mysql-token-" + UUID.randomUUID() + "@example.com";
        String ip = uniqueTestIp();
        try {
            EmailVerificationRequestResult first = requestVerifiedFlow(email, ip);
            EmailVerificationFlow original = emailFlows.findById(first.verificationId()).orElseThrow();
            clock.advance(Duration.ofSeconds(60));
            EmailVerificationRequestResult second = emailVerificationService.requestCode(email, ip);
            assertTrue(!first.verificationId().equals(second.verificationId()));
            assertEquals(original, emailFlows.findById(first.verificationId()).orElseThrow());
            assertEquals(first.resendAvailableAt(), emailFlows.findById(second.verificationId())
                    .orElseThrow().lastSentAt().toInstant(ZoneOffset.UTC));
            verify(verificationEmailSender, times(2)).sendSignUpCode(email, "654321", 3);
            assertEquals(first.verificationId(), registrationTokenService.consume("mysql-token-" + email)
                    .verificationId());
        } finally {
            cleanupEmailFixture(email, ip);
        }
    }

    @Test
    void p2_인증완료_후_동시_재요청도_60초_제한을_우회하지_못한다() throws Exception {
        String email = "mysql-race-" + UUID.randomUUID() + "@example.com";
        String ip = uniqueTestIp();
        try {
            // 테스트 트랜잭션 없이 준비하여 작업 스레드에서 인증 완료 Flow를 볼 수 있게 한다.
            requestVerifiedFlow(email, ip);
            clock.advance(Duration.ofSeconds(59));
            assertEquals(0, concurrentRequests(email, ip));
            verify(verificationEmailSender, times(1)).sendSignUpCode(email, "654321", 3);
            clock.advance(Duration.ofSeconds(1));
            assertEquals(1, concurrentRequests(email, ip));
            verify(verificationEmailSender, times(2)).sendSignUpCode(email, "654321", 3);
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM email_verification_flows WHERE normalized_email = ?", Integer.class, email));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM auth_rate_limit_events WHERE limit_type = 'EMAIL' AND key_value = ?",
                    Integer.class, email));
        } finally {
            cleanupEmailFixture(email, ip);
        }
    }

    private EmailVerificationRequestResult requestVerifiedFlow(String email, String ip) {
        when(verificationSecretGenerator.generateCode()).thenReturn("654321");
        when(verificationSecretGenerator.generateActionToken()).thenReturn("mysql-token-" + email);
        EmailVerificationRequestResult first = emailVerificationService.requestCode(email, ip);
        emailVerificationService.verifyCode(first.verificationId(), "654321");
        return first;
    }

    @Test
    void p2_동시_검증과_토큰소비는_각각_한번만_성공한다() throws Exception {
        String email = "mysql-verify-race-" + UUID.randomUUID() + "@example.com";
        String ip = uniqueTestIp();
        try {
            when(verificationSecretGenerator.generateCode()).thenReturn("654321");
            when(verificationSecretGenerator.generateActionToken()).thenReturn("mysql-token-" + email);
            EmailVerificationRequestResult first = emailVerificationService.requestCode(email, ip);
            assertEquals(1, concurrentCalls(() -> {
                try {
                    emailVerificationService.verifyCode(first.verificationId(), "654321");
                    return true;
                } catch (VerificationApiException exception) {
                    assertEquals("INVALID_VERIFICATION", exception.code());
                    return false;
                }
            }));
            assertEquals(null, emailFlows.findById(first.verificationId()).orElseThrow().codeHash());
            assertEquals(1, concurrentCalls(() -> {
                try {
                    registrationTokenService.consume("mysql-token-" + email);
                    return true;
                } catch (VerificationApiException exception) {
                    assertEquals("INVALID_REGISTRATION_TOKEN", exception.code());
                    return false;
                }
            }));
        } finally {
            cleanupEmailFixture(email, ip);
        }
    }

    @Test
    void p2_IP_발송한도는_서로다른_이메일에도_독립적으로_적용된다() {
        String suffix = UUID.randomUUID().toString();
        String ip = uniqueTestIp();
        List<String> emails = java.util.stream.IntStream.range(0, 4)
                .mapToObj(index -> "mysql-ip-" + index + "-" + suffix + "@example.com").toList();
        try {
            when(verificationSecretGenerator.generateCode()).thenReturn("654321");
            for (int index = 0; index < 3; index++) {
                emailVerificationService.requestCode(emails.get(index), ip);
            }
            VerificationApiException error = assertThrows(VerificationApiException.class,
                    () -> emailVerificationService.requestCode(emails.get(3), ip));
            assertEquals("RATE_LIMITED", error.code());
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.status());
            verify(verificationEmailSender, times(3)).sendSignUpCode(anyString(), anyString(), anyLong());
            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limit_events "
                    + "WHERE limit_type = 'IP' AND key_value = ?", Integer.class, authHashEncoder.hashIpAddress(ip)));
        } finally {
            for (String email : emails) cleanupEmailFixture(email, ip);
        }
    }

    private void assertIsolatedRateLimitAcquisition(LocalDateTime now) {
        String email = "mysql-fixture-" + UUID.randomUUID() + "@example.com";
        String ipHash = UUID.randomUUID().toString();
        try {
            assertTrue(emailSendRateLimits.tryAcquire(email, ipHash, now).allowed());
        } finally {
            // Repository가 별도 커밋한 이벤트는 테스트 롤백과 분리해 정리한다.
            TransactionTemplate cleanup = new TransactionTemplate(transactionManager);
            cleanup.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            cleanup.executeWithoutResult(status -> cleanupRateLimitFixture(email, ipHash));
        }
    }

    private int concurrentRequests(String email, String ip) throws Exception {
        return concurrentCalls(() -> {
            try {
                emailVerificationService.requestCode(email, ip);
                return true;
            } catch (VerificationApiException exception) {
                assertEquals("RATE_LIMITED", exception.code());
                return false;
            }
        });
    }

    private int concurrentCalls(Callable<Boolean> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        try {
            for (int index = 0; index < 4; index++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return request.call();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int successes = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(15, TimeUnit.SECONDS)) successes++;
            }
            return successes;
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void cleanupEmailFixture(String email, String ip) {
        jdbc.update("DELETE FROM email_verification_flows WHERE normalized_email = ?", email);
        String ipHash = authHashEncoder.hashIpAddress(ip);
        cleanupRateLimitFixture(email, ipHash);
    }

    private void cleanupRateLimitFixture(String email, String ipHash) {
        // 고유 이메일과 테스트 전용 IP에 해당하는 행만 정리한다.
        jdbc.update("DELETE FROM auth_rate_limit_events WHERE rate_action = 'EMAIL_VERIFICATION_SEND' "
                + "AND ((limit_type = 'EMAIL' AND key_value = ?) OR (limit_type = 'IP' AND key_value = ?))",
                email, ipHash);
        jdbc.update("DELETE FROM auth_rate_limit_keys WHERE rate_action = 'EMAIL_VERIFICATION_SEND' "
                + "AND ((limit_type = 'EMAIL' AND key_value = ?) OR (limit_type = 'IP' AND key_value = ?))",
                email, ipHash);
    }

    private String uniqueTestIp() {
        byte[] address = uuidToBytes(UUID.randomUUID());
        address[0] = 0x20;
        address[1] = 0x01;
        address[2] = 0x0d;
        address[3] = (byte) 0xb8;
        try {
            return InetAddress.getByAddress(address).getHostAddress();
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("Invalid test IPv6 address", exception);
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        TestClock mysqlTestClock() {
            return new TestClock();
        }
    }

    static final class TestClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(Instant.EPOCH);

        void set(Instant value) { instant.set(value); }
        void advance(Duration duration) { instant.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() { return instant.get(); }
    }

    @Test
    @Transactional
    void p2_회원가입이_MySQL에서_사용자_Credential_세션을_원자적으로_생성한다() {
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        String email = "mysql-signup-" + UUID.randomUUID() + "@example.com";
        String registrationToken = "mysql-signup-token-" + UUID.randomUUID();
        emailFlows.save(new EmailVerificationFlow(
                UUID.randomUUID(), email, "SIGN_UP", null, 0,
                now.plusMinutes(3), now, now, null,
                authHashEncoder.hashActionToken(registrationToken),
                now.plusMinutes(10), null, now, now
        ));

        SignupResult result = signupService.signup(
                registrationToken, "Mysql-valid-72!"
        );

        assertAll(
                () -> assertEquals(result.userId(), emailCredentials
                        .findByNormalizedEmail(email).orElseThrow().userId()),
                () -> assertEquals(result.userId(), sessions
                        .findActiveById(result.sessionId(), now).orElseThrow().userId()),
                () -> assertTrue(result.accessToken().length() > 100),
                () -> assertTrue(result.refreshToken().length() >= 43)
        );
    }

    @Test
    @Transactional
    void p2_친구_차단_알림_멱등성_동기화를_검증한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 4, 0);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        users.save(first, "첫째");
        users.save(second, "둘째");
        UUID[] normalized = FriendPairRepository.normalize(first, second);
        UUID pairId = UUID.randomUUID();
        friends.save(new FriendPair(pairId, normalized[0], normalized[1], first,
                "PENDING", null, now));
        assertEquals(1, friends.accept(pairId, second, now.plusSeconds(1)));
        blocks.save(new UserBlock(first, second, now));
        UUID notificationId = UUID.randomUUID();
        notifications.save(new Notification(notificationId, second, "FRIEND_REQUEST",
                "요청", null, "USER", first, "dedup-" + notificationId, null, now));

        UUID key = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        idempotency.save(new IdempotencyRecord(recordId, first, "POST", "/matches",
                key, "request", "PROCESSING", null, null, null, now, now.plusDays(1)));
        assertEquals(1, idempotency.complete(recordId, "request", 201, "{}", "{}"));
        UUID operationId = UUID.randomUUID();
        syncOperations.save(new SyncOperation(UUID.randomUUID(), first, null,
                operationId, UUID.randomUUID(), null, 1, "START_MATCH", "payload",
                "REJECTED", "{\"code\":\"NOT_FOUND\"}", now));

        assertAll(
                () -> assertEquals("ACCEPTED", friends.findByUsers(first, second).orElseThrow().status()),
                () -> assertTrue(blocks.exists(first, second)),
                () -> assertEquals(1, notifications.countUnread(second)),
                () -> assertEquals(1, notifications.markRead(notificationId, second, now.plusMinutes(1))),
                () -> assertEquals("COMPLETED", idempotency.findByScope(first, "POST", "/matches", key).orElseThrow().status()),
                () -> assertNotNull(syncOperations.findByUserIdAndOperationId(first, operationId).orElseThrow().result())
        );
    }

    @Test
    void p1_동시_최초_upsert는_한_행만_남긴다() throws Exception {
        LocalDateTime now = LocalDateTime.now().withNano(111_111_000);
        UUID userId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID quarterId = UUID.randomUUID();
        insertCommittedParents(userId, matchId, quarterId, now);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger uniqueConflicts = new AtomicInteger();
        try {
            Runnable writer = () -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    stats.upsert(quarterId, 1, 1, now);
                    completed.incrementAndGet();
                } catch (DataIntegrityViolationException exception) {
                    uniqueConflicts.incrementAndGet();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            };
            pool.submit(writer);
            pool.submit(writer);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            Integer rows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM quarter_player_stats WHERE quarter_id = ?",
                    Integer.class, uuidToBytes(quarterId)
            );
            assertEquals(1, rows);
            assertEquals(2, completed.get() + uniqueConflicts.get());
            assertTrue(uniqueConflicts.get() <= 1);
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM quarter_player_stats WHERE quarter_id = ?", uuidToBytes(quarterId));
            jdbc.update("DELETE FROM quarters WHERE id = ?", uuidToBytes(quarterId));
            jdbc.update("DELETE FROM matches WHERE id = ?", uuidToBytes(matchId));
            jdbc.update("DELETE FROM users WHERE id = ?", uuidToBytes(userId));
        }
    }

    private Fixture fixture(LocalDateTime now) {
        UUID userId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID quarterId = UUID.randomUUID();
        users.save(userId, "운동사용자");
        matches.save(new Match(matchId, userId, null, null, null, null, null,
                "COMPLETED", now, now, 1, now, now));
        quarters.save(new Quarter(quarterId, matchId, 1, 1200, "COMPLETED",
                now, now.plusMinutes(20), now, now));
        return new Fixture(userId, matchId, quarterId, now);
    }

    private void insertCommittedParents(
            UUID userId, UUID matchId, UUID quarterId, LocalDateTime now
    ) {
        jdbc.update("INSERT INTO users (id, created_at, updated_at) VALUES (?, ?, ?)",
                uuidToBytes(userId), now, now);
        jdbc.update("""
                INSERT INTO matches (id, owner_id, status, version, created_at, updated_at)
                VALUES (?, ?, 'COMPLETED', 1, ?, ?)
                """, uuidToBytes(matchId), uuidToBytes(userId), now, now);
        jdbc.update("""
                INSERT INTO quarters (id, match_id, number, duration_seconds,
                    status, created_at, updated_at)
                VALUES (?, ?, 1, 1200, 'COMPLETED', ?, ?)
                """, uuidToBytes(quarterId), uuidToBytes(matchId), now, now);
    }

    private record Fixture(
            UUID userId, UUID matchId, UUID quarterId, LocalDateTime now
    ) {
    }
}
