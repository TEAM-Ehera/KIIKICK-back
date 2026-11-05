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
import com.teamehera.kiikick.auth.service.VerificationSecretGenerator;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=none",
        "kiikick.workout.summary-worker-enabled=false"
})
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
        assertEquals(27, tableCount);
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
        // 발송 한도는 REQUIRES_NEW로 커밋되므로 테스트 실행마다 독립된 식별자를 쓴다.
        String email = userId + "@example.com";
        identities.save(new AuthIdentity(UUID.randomUUID(), userId, "APPLE", "sub", now));
        emailCredentials.save(new EmailCredential(
                userId, email, email, "$2a$12$hash",
                now, now, now, now
        ));
        UUID flowId = UUID.randomUUID();
        emailFlows.save(new EmailVerificationFlow(
                flowId, email, "SIGN_UP", "a".repeat(64), 0,
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
                        .findByNormalizedEmail(email).orElseThrow().userId()),
                () -> assertEquals(1, emailFlows.verifyAndIssueActionToken(
                        flowId, "a".repeat(64), "b".repeat(64),
                        now.plusMinutes(10), now.plusMinutes(1)
                )),
                () -> assertTrue(emailSendRateLimits.tryAcquire(
                        email, UUID.randomUUID().toString(), now
                ).allowed()),
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
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new java.util.ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                String ipHash = "mysql-ip-" + index;
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
                        "SIGN_UP", verified.expiresAt().minusSeconds(1)
                ).isPresent())
        );
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
