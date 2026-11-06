package com.teamehera.kiikick.auth.repository;

import com.teamehera.kiikick.auth.domain.EmailCredential;
import com.teamehera.kiikick.auth.domain.EmailVerificationFlow;
import com.teamehera.kiikick.auth.domain.SendRateLimitDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@JdbcTest(properties = "spring.datasource.url=jdbc:h2:mem:email-auth-repository;MODE=MySQL")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        EmailCredentialRepository.class,
        EmailVerificationFlowRepository.class,
        EmailSendRateLimitRepository.class
})
class EmailAuthRepositoryTest {

    @Autowired EmailCredentialRepository credentials;
    @Autowired EmailVerificationFlowRepository flows;
    @Autowired EmailSendRateLimitRepository rateLimits;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void normalized_email은_DB에서_중복을_차단한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 4, 1, 0);
        UUID firstUser = insertUser(now);
        UUID secondUser = insertUser(now);
        credentials.save(credential(firstUser, "User@example.com", "user@example.com", now));

        assertEquals(firstUser, credentials.findByNormalizedEmail("user@example.com")
                .orElseThrow().userId());
        assertThrows(DataIntegrityViolationException.class, () ->
                credentials.save(credential(
                        secondUser, "user@EXAMPLE.com", "user@example.com", now
                ))
        );
    }

    @Test
    void 재발송은_실패횟수를_유지하고_검증된_코드를_즉시_무효화한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 4, 2, 0);
        UUID flowId = UUID.randomUUID();
        flows.save(flow(flowId, "first-hash", now));

        assertEquals(1, flows.incrementFailedAttempts(flowId, now.plusSeconds(10)));
        assertEquals(1, flows.incrementFailedAttempts(flowId, now.plusSeconds(20)));
        assertEquals(1, flows.resend(
                flowId, "second-hash", now.plusMinutes(4), now.plusMinutes(1)
        ));

        EmailVerificationFlow resent = flows.findById(flowId).orElseThrow();
        assertEquals(2, resent.failedAttempts());
        assertEquals(flowId, flows.findLatestForUpdate(
                "user@example.com", "SIGN_UP"
        ).orElseThrow().id());
        assertEquals(0, flows.verifyAndIssueActionToken(
                flowId, "first-hash", "action-token-hash",
                now.plusMinutes(12), now.plusMinutes(2)
        ));
        assertEquals(1, flows.verifyAndIssueActionToken(
                flowId, "second-hash", "action-token-hash",
                now.plusMinutes(12), now.plusMinutes(2)
        ));
        assertNull(flows.findById(flowId).orElseThrow().codeHash());
    }

    @Test
    void registrationToken은_한_번만_소비한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 4, 3, 0);
        UUID flowId = UUID.randomUUID();
        flows.save(flow(flowId, "code-hash", now));
        assertEquals(1, flows.verifyAndIssueActionToken(
                flowId, "code-hash", "token-hash",
                now.plusMinutes(10), now
        ));

        assertTrue(flows.findActiveByActionToken(
                "token-hash", "SIGN_UP", now
        ).isPresent());
        assertEquals(1, flows.consumeActionToken(
                flowId, "token-hash", now.plusMinutes(1)
        ));
        assertEquals(0, flows.consumeActionToken(
                flowId, "token-hash", now.plusMinutes(2)
        ));
    }

    @Test
    void 이메일과_IP_발송제한을_독립적으로_적용한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 4, 4, 0);
        for (int index = 0; index < 5; index++) {
            assertTrue(rateLimits.tryAcquire(
                    "limited@example.com", "ip-hash-" + index, now
            ).allowed());
        }
        SendRateLimitDecision emailBlocked = rateLimits.tryAcquire(
                "limited@example.com", "fresh-ip", now
        );
        assertFalse(emailBlocked.allowed());
        assertEquals(5, emailBlocked.emailCount());
        assertEquals(now.plusHours(24), emailBlocked.retryAt());
        assertTrue(rateLimits.tryAcquire(
                "limited@example.com", "fresh-ip", emailBlocked.retryAt()
        ).allowed());

        for (int index = 0; index < 3; index++) {
            assertTrue(rateLimits.tryAcquire(
                    "ip-user-" + index + "@example.com", "shared-ip", now
            ).allowed());
        }
        SendRateLimitDecision ipBlocked = rateLimits.tryAcquire(
                "another@example.com", "shared-ip", now
        );
        assertFalse(ipBlocked.allowed());
        assertEquals(3, ipBlocked.ipCount());
        assertEquals(now.plusMinutes(1), ipBlocked.retryAt());
        assertTrue(rateLimits.tryAcquire(
                "another@example.com", "shared-ip", ipBlocked.retryAt()
        ).allowed());
    }

    @Test
    void 동시_발송도_이메일_한도_5회를_넘지_않는다() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 11, 4, 5, 0);
        String email = "concurrent-" + UUID.randomUUID() + "@example.com";
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int index = 0; index < 8; index++) {
                String ipHash = "concurrent-ip-" + index;
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return rateLimits.tryAcquire(email, ipHash, now).allowed();
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

    private UUID insertUser(LocalDateTime now) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, created_at, updated_at) VALUES (?, ?, ?)",
                uuidToBytes(id), now, now
        );
        return id;
    }

    private EmailCredential credential(
            UUID userId, String email, String normalizedEmail, LocalDateTime now
    ) {
        return new EmailCredential(
                userId, email, normalizedEmail, "$2a$12$hash", now, now, now, now
        );
    }

    private EmailVerificationFlow flow(
            UUID id, String codeHash, LocalDateTime now
    ) {
        return new EmailVerificationFlow(
                id, "user@example.com", "SIGN_UP", codeHash, 0,
                now.plusMinutes(3), now, null, null,
                null, null, null, now, now
        );
    }
}
