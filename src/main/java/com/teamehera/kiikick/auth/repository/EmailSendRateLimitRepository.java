package com.teamehera.kiikick.auth.repository;

import com.teamehera.kiikick.auth.domain.SendRateLimitDecision;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class EmailSendRateLimitRepository {

    private static final String ACTION = "EMAIL_VERIFICATION_SEND";
    private static final String EMAIL = "EMAIL";
    private static final String IP = "IP";
    private static final int EMAIL_LIMIT = 5;
    private static final int IP_LIMIT = 3;
    private static final Duration EMAIL_WINDOW = Duration.ofHours(24);
    private static final Duration IP_WINDOW = Duration.ofMinutes(1);

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public EmailSendRateLimitRepository(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
    }

    public SendRateLimitDecision tryAcquire(
            String normalizedEmail, String ipHash, LocalDateTime now
    ) {
        return transactionTemplate.execute(status -> acquireWithLockedKeys(
                normalizedEmail, ipHash, now
        ));
    }

    public SendRateLimitDecision tryAcquireInCurrentTransaction(
            String normalizedEmail, String ipHash, LocalDateTime now
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An active transaction is required");
        }
        // 호출자 트랜잭션이 끝날 때까지 잠금을 유지해 발송·재발송도 함께 직렬화한다.
        return acquireWithLockedKeys(normalizedEmail, ipHash, now);
    }

    private SendRateLimitDecision acquireWithLockedKeys(
            String normalizedEmail, String ipHash, LocalDateTime now
    ) {
        // UPSERT로 키를 생성하거나 배타 잠금을 획득한다. 이메일 → IP 순서를 유지한다.
        ensureAndLockKey(EMAIL, normalizedEmail, now);
        ensureAndLockKey(IP, ipHash, now);

        LocalDateTime emailCutoff = now.minus(EMAIL_WINDOW);
        LocalDateTime ipCutoff = now.minus(IP_WINDOW);
        int emailCount = countRecent(EMAIL, normalizedEmail, emailCutoff);
        int ipCount = countRecent(IP, ipHash, ipCutoff);

        if (emailCount >= EMAIL_LIMIT || ipCount >= IP_LIMIT) {
            return new SendRateLimitDecision(
                    false, emailCount, ipCount,
                    retryAt(normalizedEmail, ipHash, emailCutoff, ipCutoff,
                            emailCount, ipCount)
            );
        }

        insertEvent(EMAIL, normalizedEmail, now);
        insertEvent(IP, ipHash, now);
        return new SendRateLimitDecision(true, emailCount + 1, ipCount + 1, null);
    }

    public void lockEmailForAccountChange(String normalizedEmail, LocalDateTime now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An active transaction is required");
        }
        ensureAndLockKey(EMAIL, normalizedEmail, now);
    }

    private void ensureAndLockKey(String limitType, String keyValue, LocalDateTime now) {
        jdbcTemplate.update(
                """
                INSERT INTO auth_rate_limit_keys (
                    rate_action, limit_type, key_value, created_at
                ) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE created_at = auth_rate_limit_keys.created_at
                """,
                ACTION, limitType, keyValue, now
        );
    }

    private int countRecent(String limitType, String keyValue, LocalDateTime cutoff) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM auth_rate_limit_events
                WHERE rate_action = ? AND limit_type = ? AND key_value = ?
                  AND occurred_at > ?
                """,
                Integer.class, ACTION, limitType, keyValue, cutoff
        );
        return count == null ? 0 : count;
    }

    private LocalDateTime retryAt(
            String normalizedEmail, String ipHash,
            LocalDateTime emailCutoff, LocalDateTime ipCutoff,
            int emailCount, int ipCount
    ) {
        List<LocalDateTime> candidates = new java.util.ArrayList<>();
        if (emailCount >= EMAIL_LIMIT) {
            candidates.add(oldestEvent(EMAIL, normalizedEmail, emailCutoff)
                    .plus(EMAIL_WINDOW));
        }
        if (ipCount >= IP_LIMIT) {
            candidates.add(oldestEvent(IP, ipHash, ipCutoff).plus(IP_WINDOW));
        }
        return candidates.stream().max(Comparator.naturalOrder()).orElseThrow();
    }

    private LocalDateTime oldestEvent(
            String limitType, String keyValue, LocalDateTime cutoff
    ) {
        return jdbcTemplate.queryForObject(
                """
                SELECT MIN(occurred_at) FROM auth_rate_limit_events
                WHERE rate_action = ? AND limit_type = ? AND key_value = ?
                  AND occurred_at > ?
                """,
                LocalDateTime.class, ACTION, limitType, keyValue, cutoff
        );
    }

    private void insertEvent(String limitType, String keyValue, LocalDateTime occurredAt) {
        jdbcTemplate.update(
                """
                INSERT INTO auth_rate_limit_events (
                    id, rate_action, limit_type, key_value, occurred_at
                ) VALUES (?, ?, ?, ?, ?)
                """,
                uuidToBytes(UUID.randomUUID()), ACTION, limitType, keyValue, occurredAt
        );
    }
}
