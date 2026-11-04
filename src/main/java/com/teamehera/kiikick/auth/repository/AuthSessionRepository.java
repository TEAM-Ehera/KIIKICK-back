package com.teamehera.kiikick.auth.repository;

import com.teamehera.kiikick.auth.domain.AuthSession;
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
public class AuthSessionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<AuthSession> rowMapper = (rs, rowNum) -> new AuthSession(
            bytesToUuid(rs.getBytes("id")), bytesToUuid(rs.getBytes("user_id")),
            bytesToUuid(rs.getBytes("device_id")), rs.getString("refresh_token_hash"),
            rs.getLong("refresh_generation"), rs.getTimestamp("expires_at").toLocalDateTime(),
            nullableDateTime(rs, "revoked_at"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    public AuthSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(AuthSession session) {
        jdbcTemplate.update(
                """
                INSERT INTO auth_sessions (
                    id, user_id, device_id, refresh_token_hash, refresh_generation,
                    expires_at, revoked_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                uuidToBytes(session.id()), uuidToBytes(session.userId()),
                uuidToBytes(session.deviceId()), session.refreshTokenHash(),
                session.refreshGeneration(), session.expiresAt(), session.revokedAt(),
                session.createdAt()
        );
    }

    public Optional<AuthSession> findActiveById(UUID id, LocalDateTime now) {
        return jdbcTemplate.query(
                """
                SELECT * FROM auth_sessions
                WHERE id = ? AND revoked_at IS NULL AND expires_at > ?
                """,
                rowMapper, uuidToBytes(id), now
        ).stream().findFirst();
    }

    public Optional<AuthSession> findActiveByRefreshTokenHash(
            String tokenHash, LocalDateTime now
    ) {
        return jdbcTemplate.query(
                """
                SELECT * FROM auth_sessions
                WHERE refresh_token_hash = ? AND revoked_at IS NULL AND expires_at > ?
                """,
                rowMapper, tokenHash, now
        ).stream().findFirst();
    }

    public int rotateRefreshToken(
            UUID id, long expectedGeneration, String newTokenHash,
            LocalDateTime newExpiresAt
    ) {
        return jdbcTemplate.update(
                """
                UPDATE auth_sessions
                SET refresh_token_hash = ?, refresh_generation = refresh_generation + 1,
                    expires_at = ?
                WHERE id = ? AND refresh_generation = ? AND revoked_at IS NULL
                """,
                newTokenHash, newExpiresAt, uuidToBytes(id), expectedGeneration
        );
    }

    public int revoke(UUID id, UUID userId, LocalDateTime revokedAt) {
        return jdbcTemplate.update(
                """
                UPDATE auth_sessions SET revoked_at = COALESCE(revoked_at, ?)
                WHERE id = ? AND user_id = ?
                """,
                revokedAt, uuidToBytes(id), uuidToBytes(userId)
        );
    }
}
