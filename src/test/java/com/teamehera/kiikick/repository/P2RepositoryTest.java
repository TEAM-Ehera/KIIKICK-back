package com.teamehera.kiikick.repository;

import com.teamehera.kiikick.auth.domain.AuthIdentity;
import com.teamehera.kiikick.auth.domain.AuthSession;
import com.teamehera.kiikick.auth.repository.AuthIdentityRepository;
import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import com.teamehera.kiikick.friend.domain.FriendPair;
import com.teamehera.kiikick.friend.domain.UserBlock;
import com.teamehera.kiikick.friend.repository.FriendPairRepository;
import com.teamehera.kiikick.friend.repository.UserBlockRepository;
import com.teamehera.kiikick.global.idempotency.domain.IdempotencyRecord;
import com.teamehera.kiikick.global.idempotency.repository.IdempotencyRecordRepository;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@JdbcTest
@Import({
        AuthIdentityRepository.class, UserConsentRepository.class,
        AuthSessionRepository.class, DeviceRepository.class,
        AccountDeletionRequestRepository.class, FriendPairRepository.class,
        UserBlockRepository.class, NotificationRepository.class,
        IdempotencyRecordRepository.class, SyncOperationRepository.class
})
class P2RepositoryTest {

    @Autowired AuthIdentityRepository identityRepository;
    @Autowired UserConsentRepository consentRepository;
    @Autowired AuthSessionRepository sessionRepository;
    @Autowired DeviceRepository deviceRepository;
    @Autowired AccountDeletionRequestRepository deletionRepository;
    @Autowired FriendPairRepository friendRepository;
    @Autowired UserBlockRepository blockRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired IdempotencyRecordRepository idempotencyRepository;
    @Autowired SyncOperationRepository syncRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 인증계정_unique와_최신동의_이력을_검증한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 5, 0);
        UUID userId = insertUser(now);
        identityRepository.save(new AuthIdentity(
                UUID.randomUUID(), userId, "APPLE", "subject", now
        ));
        assertThrows(DataIntegrityViolationException.class, () ->
                identityRepository.save(new AuthIdentity(
                        UUID.randomUUID(), userId, "APPLE", "subject", now
                ))
        );

        consentRepository.save(new UserConsent(
                UUID.randomUUID(), userId, "FRIEND_COMPARISON", "v1", false, now
        ));
        consentRepository.save(new UserConsent(
                UUID.randomUUID(), userId, "FRIEND_COMPARISON", "v2", true,
                now.plusSeconds(1)
        ));

        assertAll(
                () -> assertEquals(userId, identityRepository
                        .findByProviderAndSubject("APPLE", "subject").orElseThrow().userId()),
                () -> assertTrue(consentRepository
                        .findLatest(userId, "FRIEND_COMPARISON").orElseThrow().granted()),
                () -> assertEquals(1, consentRepository.findLatestByUserId(userId).size())
        );
    }

    @Test
    void 기기_세션_탈퇴요청을_저장하고_상태를_변경한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 6, 0);
        UUID userId = insertUser(now);
        UUID deviceId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID deletionId = UUID.randomUUID();
        Device device = new Device(
                deviceId, userId, "IOS", "cipher", true, now, null
        );
        deviceRepository.save(device);
        sessionRepository.save(new AuthSession(
                sessionId, userId, deviceId, "old", 0,
                now.plusDays(1), null, now
        ));
        deletionRepository.save(new AccountDeletionRequest(
                deletionId, userId, "PENDING", now, null, null
        ));

        assertAll(
                () -> assertTrue(sessionRepository
                        .findActiveById(sessionId, now).isPresent()),
                () -> assertTrue(sessionRepository
                        .findActiveById(sessionId, now.plusDays(2)).isEmpty())
        );

        assertAll(
                () -> assertEquals(1, deviceRepository.updateOwned(
                        new Device(deviceId, userId, "IOS", "new", true,
                                now.plusMinutes(1), null)
                )),
                () -> assertEquals(1, sessionRepository.rotateRefreshToken(
                        sessionId, 0, "new-hash", now.plusDays(2)
                )),
                () -> assertEquals(0, sessionRepository.rotateRefreshToken(
                        sessionId, 0, "stale", now.plusDays(3)
                )),
                () -> assertEquals(1, deletionRepository.updateStatus(
                        deletionId, "PENDING", "COMPLETED", now.plusHours(1), null
                )),
                () -> assertEquals("COMPLETED", deletionRepository
                        .findByIdAndUserId(deletionId, userId).orElseThrow().status()),
                () -> assertEquals(1, sessionRepository.revoke(sessionId, userId, now)),
                () -> assertEquals(1, deviceRepository.revoke(deviceId, userId, now))
        );
        assertTrue(sessionRepository
                .findActiveById(sessionId, now.plusMinutes(1)).isEmpty());
    }

    @Test
    void 친구_차단_알림을_저장하고_조회수정삭제한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 7, 0);
        UUID requesterId = insertUser(now);
        UUID recipientId = insertUser(now);
        UUID pairId = UUID.randomUUID();
        UUID[] normalized = FriendPairRepository.normalize(requesterId, recipientId);
        friendRepository.save(new FriendPair(
                pairId, normalized[0], normalized[1], requesterId,
                "PENDING", null, now
        ));
        assertEquals(1, friendRepository.accept(pairId, recipientId, now.plusSeconds(1)));

        blockRepository.save(new UserBlock(requesterId, recipientId, now));
        UUID notificationId = UUID.randomUUID();
        notificationRepository.save(new Notification(
                notificationId, recipientId, "FRIEND_REQUEST", "친구 요청", null,
                "USER", requesterId, "dedup", null, now
        ));

        assertAll(
                () -> assertEquals("ACCEPTED", friendRepository
                        .findByUsers(requesterId, recipientId).orElseThrow().status()),
                () -> assertTrue(blockRepository.existsEitherDirection(requesterId, recipientId)),
                () -> assertEquals(1, notificationRepository.countUnread(recipientId)),
                () -> assertEquals(1, notificationRepository.markRead(
                        notificationId, recipientId, now.plusMinutes(1)
                )),
                () -> assertEquals(0, notificationRepository.markRead(
                        notificationId, recipientId, now.plusMinutes(2)
                )),
                () -> assertEquals(1, friendRepository.deleteByUsers(requesterId, recipientId)),
                () -> assertEquals(1, blockRepository.delete(requesterId, recipientId)),
                () -> assertFalse(blockRepository.exists(requesterId, recipientId))
        );
    }

    @Test
    void 멱등성과_동기화_operation_unique를_검증한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 8, 0);
        UUID userId = insertUser(now);
        UUID key = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        IdempotencyRecord record = new IdempotencyRecord(
                recordId, userId, "POST", "/matches", key, "request-hash",
                "PROCESSING", null, null, null, now, now.plusDays(1)
        );
        idempotencyRepository.save(record);
        assertEquals(1, idempotencyRepository.complete(
                recordId, "request-hash", 201, "{}", "{}"
        ));

        UUID operationId = UUID.randomUUID();
        SyncOperation operation = new SyncOperation(
                UUID.randomUUID(), userId, null, operationId, UUID.randomUUID(),
                null, 1, "START_MATCH", "payload-hash", "REJECTED", "{}", now
        );
        syncRepository.save(operation);

        assertAll(
                () -> assertEquals("COMPLETED", idempotencyRepository
                        .findByScope(userId, "POST", "/matches", key)
                        .orElseThrow().status()),
                () -> assertEquals("REJECTED", syncRepository
                        .findByUserIdAndOperationId(userId, operationId)
                        .orElseThrow().status()),
                () -> assertThrows(DataIntegrityViolationException.class,
                        () -> syncRepository.save(new SyncOperation(
                                UUID.randomUUID(), userId, null, operationId,
                                UUID.randomUUID(), null, 1, "START_MATCH",
                                "other", "REJECTED", "{}", now
                        )))
        );
    }

    private UUID insertUser(LocalDateTime now) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, created_at, updated_at) VALUES (?, ?, ?)",
                uuidToBytes(id), now, now
        );
        return id;
    }
}
