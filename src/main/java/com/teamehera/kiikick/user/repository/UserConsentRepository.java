package com.teamehera.kiikick.user.repository;

import com.teamehera.kiikick.user.domain.UserConsent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class UserConsentRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<UserConsent> rowMapper = (rs, rowNum) -> new UserConsent(
            bytesToUuid(rs.getBytes("id")), bytesToUuid(rs.getBytes("user_id")),
            rs.getString("purpose"), rs.getString("policy_version"),
            rs.getBoolean("granted"), rs.getObject("recorded_at", java.time.LocalDateTime.class)
    );

    public UserConsentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(UserConsent consent) {
        jdbcTemplate.update(
                """
                INSERT INTO user_consents (
                    id, user_id, purpose, policy_version, granted, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                uuidToBytes(consent.id()), uuidToBytes(consent.userId()),
                consent.purpose(), consent.policyVersion(), consent.granted(),
                consent.recordedAt()
        );
    }

    public Optional<UserConsent> findLatest(UUID userId, String purpose) {
        return jdbcTemplate.query(
                """
                SELECT * FROM user_consents
                WHERE user_id = ? AND purpose = ?
                ORDER BY recorded_at DESC, id DESC LIMIT 1
                """,
                rowMapper, uuidToBytes(userId), purpose
        ).stream().findFirst();
    }

    public List<UserConsent> findLatestByUserId(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT c.* FROM user_consents c
                WHERE c.user_id = ? AND NOT EXISTS (
                    SELECT 1 FROM user_consents newer
                    WHERE newer.user_id = c.user_id AND newer.purpose = c.purpose
                      AND (newer.recorded_at > c.recorded_at
                        OR (newer.recorded_at = c.recorded_at AND newer.id > c.id))
                )
                ORDER BY c.purpose ASC
                """,
                rowMapper, uuidToBytes(userId)
        );
    }
}
