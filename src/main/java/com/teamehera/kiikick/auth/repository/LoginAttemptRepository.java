package com.teamehera.kiikick.auth.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class LoginAttemptRepository {

    private static final String ACTION = "EMAIL_PASSWORD_LOGIN";
    private static final String ACCOUNT = "ACCOUNT";
    private static final int FAILURE_LIMIT = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate keyTransaction;

    public LoginAttemptRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        keyTransaction = new TransactionTemplate(transactionManager);
        keyTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void ensureAccountKey(String email, LocalDateTime now) {
        keyTransaction.executeWithoutResult(status -> {
            try {
                jdbc.update("""
                        INSERT INTO auth_rate_limit_keys (rate_action, limit_type, key_value, created_at)
                        VALUES (?, ?, ?, ?)
                        """, ACTION, ACCOUNT, email, now);
            } catch (DuplicateKeyException ignored) {
                // 등록되지 않은 이메일도 같은 키를 사용해 계정 존재 여부를 숨긴다.
            }
        });
    }

    public void lockAccount(String email) {
        jdbc.queryForObject("""
                SELECT key_value FROM auth_rate_limit_keys
                WHERE rate_action = ? AND limit_type = ? AND key_value = ? FOR UPDATE
                """, String.class, ACTION, ACCOUNT, email);
    }

    public LocalDateTime blockedUntil(String email, LocalDateTime now) {
        List<LocalDateTime> failures = jdbc.query("""
                SELECT occurred_at FROM auth_rate_limit_events
                WHERE rate_action = ? AND limit_type = ? AND key_value = ?
                ORDER BY occurred_at DESC, id DESC LIMIT 10
                """, (rs, rowNum) -> rs.getObject("occurred_at", LocalDateTime.class), ACTION, ACCOUNT, email);
        if (failures.size() < FAILURE_LIMIT) return null;
        LocalDateTime latest = failures.get(0);
        // 열 번째 실패 이후 초기 실패가 집계 창에서 빠져도 제한은 15분간 유지한다.
        if (!failures.get(FAILURE_LIMIT - 1).isAfter(latest.minus(WINDOW))) return null;
        LocalDateTime until = latest.plus(WINDOW);
        return now.isBefore(until) ? until : null;
    }

    public void recordFailure(String email, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO auth_rate_limit_events (id, rate_action, limit_type, key_value, occurred_at)
                VALUES (?, ?, ?, ?, ?)
                """, uuidToBytes(UUID.randomUUID()), ACTION, ACCOUNT, email, now);
    }

    public void resetFailures(String email) {
        jdbc.update("""
                DELETE FROM auth_rate_limit_events
                WHERE rate_action = ? AND limit_type = ? AND key_value = ?
                """, ACTION, ACCOUNT, email);
    }
}
