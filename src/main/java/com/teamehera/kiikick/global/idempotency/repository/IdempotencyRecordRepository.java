package com.teamehera.kiikick.global.idempotency.repository;

import com.teamehera.kiikick.global.idempotency.domain.IdempotencyRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class IdempotencyRecordRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<IdempotencyRecord> rowMapper = (rs, rowNum) ->
            new IdempotencyRecord(
                    bytesToUuid(rs.getBytes("id")), bytesToUuid(rs.getBytes("user_id")),
                    rs.getString("method"), rs.getString("canonical_path"),
                    bytesToUuid(rs.getBytes("idempotency_key")),
                    rs.getString("request_hash"), rs.getString("status"),
                    rs.getObject("response_status", Integer.class),
                    rs.getString("response_headers"), rs.getString("response_body"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("expires_at").toLocalDateTime()
            );

    public IdempotencyRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(IdempotencyRecord record) {
        jdbcTemplate.update(
                """
                INSERT INTO idempotency_records (
                    id, user_id, method, canonical_path, idempotency_key,
                    request_hash, status, response_status, response_headers,
                    response_body, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                uuidToBytes(record.id()), uuidToBytes(record.userId()), record.method(),
                record.canonicalPath(), uuidToBytes(record.idempotencyKey()),
                record.requestHash(), record.status(), record.responseStatus(),
                record.responseHeaders(), record.responseBody(), record.createdAt(),
                record.expiresAt()
        );
    }

    public Optional<IdempotencyRecord> findByScope(
            UUID userId, String method, String canonicalPath, UUID idempotencyKey
    ) {
        return findByScope(userId, method, canonicalPath, idempotencyKey, "");
    }

    public Optional<IdempotencyRecord> findByScopeForUpdate(
            UUID userId, String method, String canonicalPath, UUID idempotencyKey
    ) {
        return findByScope(userId, method, canonicalPath, idempotencyKey, " FOR UPDATE");
    }

    private Optional<IdempotencyRecord> findByScope(
            UUID userId, String method, String canonicalPath, UUID idempotencyKey, String lockClause
    ) {
        return jdbcTemplate.query(
                """
                SELECT * FROM idempotency_records
                WHERE user_id = ? AND method = ? AND canonical_path = ?
                  AND idempotency_key = ?
                """ + lockClause,
                rowMapper, uuidToBytes(userId), method, canonicalPath,
                uuidToBytes(idempotencyKey)
        ).stream().findFirst();
    }

    public int complete(
            UUID id, String requestHash, int responseStatus,
            String responseHeaders, String responseBody
    ) {
        return jdbcTemplate.update(
                """
                UPDATE idempotency_records
                SET status = 'COMPLETED', response_status = ?,
                    response_headers = ?, response_body = ?
                WHERE id = ? AND request_hash = ? AND status = 'PROCESSING'
                """,
                responseStatus, responseHeaders, responseBody,
                uuidToBytes(id), requestHash
        );
    }
    public int deleteExpiredByScope(UUID userId, String method, String canonicalPath,
                                    UUID key, java.time.LocalDateTime now) {
        return jdbcTemplate.update("""
                DELETE FROM idempotency_records
                WHERE user_id=? AND method=? AND canonical_path=? AND idempotency_key=? AND expires_at<=?
                """, uuidToBytes(userId), method, canonicalPath, uuidToBytes(key), now);
    }
}
