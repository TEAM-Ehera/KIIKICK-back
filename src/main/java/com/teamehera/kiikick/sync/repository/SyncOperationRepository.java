package com.teamehera.kiikick.sync.repository;

import com.teamehera.kiikick.sync.domain.SyncOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class SyncOperationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<SyncOperation> rowMapper = (rs, rowNum) -> new SyncOperation(
            bytesToUuid(rs.getBytes("id")), bytesToUuid(rs.getBytes("user_id")),
            bytesToUuid(rs.getBytes("device_id")),
            bytesToUuid(rs.getBytes("operation_id")),
            bytesToUuid(rs.getBytes("requested_match_id")),
            bytesToUuid(rs.getBytes("match_id")), rs.getLong("expected_version"),
            rs.getString("type"), rs.getString("payload_hash"),
            rs.getString("status"), rs.getString("result"),
            rs.getTimestamp("processed_at").toLocalDateTime()
    );

    public SyncOperationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(SyncOperation operation) {
        jdbcTemplate.update(
                """
                INSERT INTO sync_operations (
                    id, user_id, device_id, operation_id, requested_match_id,
                    match_id, expected_version, type, payload_hash, status,
                    result, processed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                uuidToBytes(operation.id()), uuidToBytes(operation.userId()),
                uuidToBytes(operation.deviceId()), uuidToBytes(operation.operationId()),
                uuidToBytes(operation.requestedMatchId()), uuidToBytes(operation.matchId()),
                operation.expectedVersion(), operation.type(), operation.payloadHash(),
                operation.status(), operation.result(), operation.processedAt()
        );
    }

    public Optional<SyncOperation> findByUserIdAndOperationId(
            UUID userId, UUID operationId
    ) {
        return jdbcTemplate.query(
                """
                SELECT * FROM sync_operations
                WHERE user_id = ? AND operation_id = ?
                """,
                rowMapper, uuidToBytes(userId), uuidToBytes(operationId)
        ).stream().findFirst();
    }
}
