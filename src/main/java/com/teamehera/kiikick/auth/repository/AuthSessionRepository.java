package com.teamehera.kiikick.auth.repository;

import com.teamehera.kiikick.auth.domain.AuthSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class AuthSessionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<AuthSession> rowMapper = (rs, rowNum) -> new AuthSession(
            bytesToUuid(rs.getBytes("id")), bytesToUuid(rs.getBytes("user_id")),
            bytesToUuid(rs.getBytes("device_id")), rs.getString("refresh_token_hash"),
            rs.getLong("refresh_generation"), rs.getObject("expires_at", LocalDateTime.class),
            rs.getObject("revoked_at", LocalDateTime.class),
            rs.getObject("created_at", LocalDateTime.class)
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
    public Optional<UUID> findSessionIdForRefresh(String hash) {
        return jdbcTemplate.query("""
                SELECT id FROM auth_sessions WHERE refresh_token_hash = ?
                UNION
                SELECT session_id FROM auth_refresh_token_history WHERE token_hash = ?
                """, (rs, n) -> bytesToUuid(rs.getBytes(1)), hash, hash).stream().findFirst();
    }

    public Optional<AuthSession> findByIdForUpdate(UUID id) {
        return jdbcTemplate.query("SELECT * FROM auth_sessions WHERE id = ? FOR UPDATE",
                rowMapper, uuidToBytes(id)).stream().findFirst();
    }

    public int rotateCurrentToken(UUID id, long generation, String expectedHash, String newHash,
                                  LocalDateTime now) {
        // 만료일은 변경하지 않고 현재 세대·해시·활성 상태를 함께 검증한다.
        return jdbcTemplate.update("""
                UPDATE auth_sessions SET refresh_token_hash = ?, refresh_generation = refresh_generation + 1
                WHERE id = ? AND refresh_generation = ? AND refresh_token_hash = ?
                  AND revoked_at IS NULL AND expires_at > ?
                """, newHash, uuidToBytes(id), generation, expectedHash, now);
    }
}
