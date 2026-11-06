package com.teamehera.kiikick.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.UUID;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class RefreshTokenHistoryRepository {
    private final JdbcTemplate jdbc;
    public RefreshTokenHistoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void save(String hash, UUID sessionId, long generation, LocalDateTime consumedAt,
                     LocalDateTime absoluteExpiresAt) {
        jdbc.update("""
                INSERT INTO auth_refresh_token_history
                    (token_hash, session_id, generation, consumed_at, expires_at) VALUES (?, ?, ?, ?, ?)
                """, hash, uuidToBytes(sessionId), generation, consumedAt, absoluteExpiresAt);
    }

    public boolean contains(String hash, UUID sessionId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM auth_refresh_token_history WHERE token_hash=? AND session_id=?",
                Integer.class, hash, uuidToBytes(sessionId)) > 0;
    }

    public int deleteExpired(LocalDateTime now) {
        return jdbc.update("DELETE FROM auth_refresh_token_history WHERE expires_at <= ?", now);
    }
}
