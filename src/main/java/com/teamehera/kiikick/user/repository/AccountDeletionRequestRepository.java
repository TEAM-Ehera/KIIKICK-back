package com.teamehera.kiikick.user.repository;

import com.teamehera.kiikick.user.domain.AccountDeletionRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class AccountDeletionRequestRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<AccountDeletionRequest> rowMapper = (rs, rowNum) ->
            new AccountDeletionRequest(
                    bytesToUuid(rs.getBytes("id")), bytesToUuid(rs.getBytes("user_id")),
                    rs.getString("status"), rs.getObject("requested_at", LocalDateTime.class),
                    rs.getObject("completed_at", LocalDateTime.class), rs.getString("failure_code")
            );

    public AccountDeletionRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(AccountDeletionRequest request) {
        jdbcTemplate.update(
                """
                INSERT INTO account_deletion_requests (
                    id, user_id, status, requested_at, completed_at, failure_code
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                uuidToBytes(request.id()), uuidToBytes(request.userId()), request.status(),
                request.requestedAt(), request.completedAt(), request.failureCode()
        );
    }

    public Optional<AccountDeletionRequest> findByIdAndUserId(UUID id, UUID userId) {
        return jdbcTemplate.query(
                "SELECT * FROM account_deletion_requests WHERE id = ? AND user_id = ?",
                rowMapper, uuidToBytes(id), uuidToBytes(userId)
        ).stream().findFirst();
    }

    public Optional<AccountDeletionRequest> findActiveByUserId(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT * FROM account_deletion_requests
                WHERE user_id = ? AND status IN ('PENDING', 'PROCESSING')
                ORDER BY requested_at DESC, id DESC LIMIT 1
                """,
                rowMapper, uuidToBytes(userId)
        ).stream().findFirst();
    }

    public int updateStatus(
            UUID id, String currentStatus, String nextStatus,
            LocalDateTime completedAt, String failureCode
    ) {
        return jdbcTemplate.update(
                """
                UPDATE account_deletion_requests
                SET status = ?, completed_at = ?, failure_code = ?
                WHERE id = ? AND status = ?
                """,
                nextStatus, completedAt, failureCode, uuidToBytes(id), currentStatus
        );
    }
}
