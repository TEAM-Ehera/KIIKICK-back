package com.teamehera.kiikick.notification.repository;

import com.teamehera.kiikick.notification.domain.Device;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.nullableDateTime;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class DeviceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Device> rowMapper = (rs, rowNum) -> new Device(
            bytesToUuid(rs.getBytes("id")), bytesToUuid(rs.getBytes("user_id")),
            rs.getString("platform"), rs.getString("push_token_ciphertext"),
            rs.getBoolean("push_enabled"), rs.getTimestamp("last_seen_at").toLocalDateTime(),
            nullableDateTime(rs, "revoked_at")
    );

    public DeviceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Device device) {
        jdbcTemplate.update(
                """
                INSERT INTO devices (
                    id, user_id, platform, push_token_ciphertext,
                    push_enabled, last_seen_at, revoked_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                uuidToBytes(device.id()), uuidToBytes(device.userId()),
                device.platform(), device.pushTokenCiphertext(), device.pushEnabled(),
                device.lastSeenAt(), device.revokedAt()
        );
    }

    public Optional<Device> findByIdAndUserId(UUID id, UUID userId) {
        return jdbcTemplate.query(
                "SELECT * FROM devices WHERE id = ? AND user_id = ?",
                rowMapper, uuidToBytes(id), uuidToBytes(userId)
        ).stream().findFirst();
    }

    public int updateOwned(Device device) {
        return jdbcTemplate.update(
                """
                UPDATE devices
                SET platform = ?, push_token_ciphertext = ?, push_enabled = ?,
                    last_seen_at = ?, revoked_at = NULL
                WHERE id = ? AND user_id = ?
                """,
                device.platform(), device.pushTokenCiphertext(), device.pushEnabled(),
                device.lastSeenAt(), uuidToBytes(device.id()), uuidToBytes(device.userId())
        );
    }

    public int revoke(UUID id, UUID userId, LocalDateTime revokedAt) {
        return jdbcTemplate.update(
                """
                UPDATE devices
                SET push_token_ciphertext = NULL, push_enabled = FALSE, revoked_at = ?
                WHERE id = ? AND user_id = ? AND revoked_at IS NULL
                """,
                revokedAt, uuidToBytes(id), uuidToBytes(userId)
        );
    }
}
