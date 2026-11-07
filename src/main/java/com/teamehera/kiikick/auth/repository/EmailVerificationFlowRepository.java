package com.teamehera.kiikick.auth.repository;

import com.teamehera.kiikick.auth.domain.EmailVerificationFlow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class EmailVerificationFlowRepository {

    private static final int MAX_FAILED_ATTEMPTS = 5;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<EmailVerificationFlow> rowMapper = (rs, rowNum) ->
            new EmailVerificationFlow(
                    bytesToUuid(rs.getBytes("id")), rs.getString("normalized_email"),
                    rs.getString("purpose"), rs.getString("code_hash"),
                    rs.getInt("failed_attempts"),
                    // DATETIME은 시간대 변환 없이 UTC LocalDateTime 값 그대로 읽는다.
                    rs.getObject("code_expires_at", LocalDateTime.class),
                    rs.getObject("last_sent_at", LocalDateTime.class),
                    rs.getObject("verified_at", LocalDateTime.class),
                    rs.getObject("invalidated_at", LocalDateTime.class),
                    rs.getString("action_token_hash"),
                    rs.getObject("action_token_expires_at", LocalDateTime.class),
                    rs.getObject("action_token_consumed_at", LocalDateTime.class),
                    rs.getObject("created_at", LocalDateTime.class),
                    rs.getObject("updated_at", LocalDateTime.class)
            );

    public EmailVerificationFlowRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(EmailVerificationFlow flow) {
        jdbcTemplate.update(
                """
                INSERT INTO email_verification_flows (
                    id, normalized_email, purpose, code_hash, failed_attempts,
                    code_expires_at, last_sent_at, verified_at, invalidated_at,
                    action_token_hash, action_token_expires_at,
                    action_token_consumed_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                uuidToBytes(flow.id()), flow.normalizedEmail(), flow.purpose(),
                flow.codeHash(), flow.failedAttempts(), flow.codeExpiresAt(),
                flow.lastSentAt(), flow.verifiedAt(), flow.invalidatedAt(),
                flow.actionTokenHash(), flow.actionTokenExpiresAt(),
                flow.actionTokenConsumedAt(), flow.createdAt(), flow.updatedAt()
        );
    }

    public Optional<EmailVerificationFlow> findById(UUID id) {
        return queryById(id, false);
    }

    public Optional<EmailVerificationFlow> findByIdForUpdate(UUID id) {
        return queryById(id, true);
    }

    public Optional<EmailVerificationFlow> findLatestForUpdate(
            String normalizedEmail, String purpose
    ) {
        // 인증 상태와 무관하게 마지막 발송을 조회하고, 시각이 같으면 기본키로 순서를 고정한다.
        return jdbcTemplate.query(
                selectColumns() + """
                WHERE normalized_email = ? AND purpose = ?
                ORDER BY last_sent_at DESC, created_at DESC, id DESC
                LIMIT 1
                FOR UPDATE
                """,
                rowMapper, normalizedEmail, purpose
        ).stream().findFirst();
    }

    public Optional<EmailVerificationFlow> findActiveByActionToken(
            String actionTokenHash, String purpose, LocalDateTime now
    ) {
        return jdbcTemplate.query(
                selectColumns() + """
                WHERE action_token_hash = ? AND purpose = ?
                  AND verified_at IS NOT NULL
                  AND action_token_consumed_at IS NULL
                  AND action_token_expires_at > ?
                  AND invalidated_at IS NULL
                """,
                rowMapper, actionTokenHash, purpose, now
        ).stream().findFirst();
    }

    public int resend(
            UUID id, String newCodeHash, LocalDateTime newExpiresAt, LocalDateTime sentAt
    ) {
        // 같은 Flow를 갱신해 재발송 뒤에도 실패 횟수를 유지하고 이전 코드를 덮어쓴다.
        return jdbcTemplate.update(
                """
                UPDATE email_verification_flows
                SET code_hash = ?, code_expires_at = ?, last_sent_at = ?, updated_at = ?
                WHERE id = ? AND verified_at IS NULL AND invalidated_at IS NULL
                  AND failed_attempts < ?
                """,
                newCodeHash, newExpiresAt, sentAt, sentAt, uuidToBytes(id),
                MAX_FAILED_ATTEMPTS
        );
    }

    public int incrementFailedAttempts(UUID id, LocalDateTime now) {
        return jdbcTemplate.update(
                """
                UPDATE email_verification_flows
                SET failed_attempts = failed_attempts + 1, updated_at = ?
                WHERE id = ? AND code_hash IS NOT NULL
                  AND verified_at IS NULL AND invalidated_at IS NULL
                  AND code_expires_at > ? AND failed_attempts < ?
                """,
                now, uuidToBytes(id), now, MAX_FAILED_ATTEMPTS
        );
    }

    public int verifyAndIssueActionToken(
            UUID id, String expectedCodeHash, String actionTokenHash,
            LocalDateTime actionTokenExpiresAt, LocalDateTime verifiedAt
    ) {
        return jdbcTemplate.update(
                """
                UPDATE email_verification_flows
                SET verified_at = ?, code_hash = NULL,
                    action_token_hash = ?, action_token_expires_at = ?, updated_at = ?
                WHERE id = ? AND code_hash = ?
                  AND verified_at IS NULL AND invalidated_at IS NULL
                  AND code_expires_at > ? AND failed_attempts < ?
                """,
                verifiedAt, actionTokenHash, actionTokenExpiresAt, verifiedAt,
                uuidToBytes(id), expectedCodeHash, verifiedAt, MAX_FAILED_ATTEMPTS
        );
    }

    public void invalidateUnconsumedSignUpFlows(String normalizedEmail, LocalDateTime now) {
        jdbcTemplate.update("""
                UPDATE email_verification_flows SET invalidated_at = ?, updated_at = ?
                WHERE normalized_email = ? AND purpose = 'SIGN_UP'
                  AND invalidated_at IS NULL AND action_token_consumed_at IS NULL
                """, now, now, normalizedEmail);
    }

    public int consumeActionToken(
            UUID id, String expectedActionTokenHash, LocalDateTime consumedAt
    ) {
        // 조건부 UPDATE로 동시에 들어온 요청 중 하나만 일회용 토큰을 소비한다.
        return jdbcTemplate.update(
                """
                UPDATE email_verification_flows
                SET action_token_consumed_at = ?, updated_at = ?
                WHERE id = ? AND action_token_hash = ?
                  AND action_token_consumed_at IS NULL
                  AND action_token_expires_at > ? AND invalidated_at IS NULL
                """,
                consumedAt, consumedAt, uuidToBytes(id), expectedActionTokenHash,
                consumedAt
        );
    }

    public int invalidate(UUID id, LocalDateTime invalidatedAt) {
        return jdbcTemplate.update(
                """
                UPDATE email_verification_flows
                SET invalidated_at = ?, code_hash = NULL, updated_at = ?
                WHERE id = ? AND invalidated_at IS NULL
                """,
                invalidatedAt, invalidatedAt, uuidToBytes(id)
        );
    }

    private Optional<EmailVerificationFlow> queryById(UUID id, boolean forUpdate) {
        String lockingClause = forUpdate ? " FOR UPDATE" : "";
        return jdbcTemplate.query(
                selectColumns() + "WHERE id = ?" + lockingClause,
                rowMapper, uuidToBytes(id)
        ).stream().findFirst();
    }

    private String selectColumns() {
        return """
                SELECT id, normalized_email, purpose, code_hash, failed_attempts,
                       code_expires_at, last_sent_at, verified_at, invalidated_at,
                       action_token_hash, action_token_expires_at,
                       action_token_consumed_at, created_at, updated_at
                FROM email_verification_flows
                """;
    }
}
