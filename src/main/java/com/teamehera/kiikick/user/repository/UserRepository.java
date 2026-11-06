package com.teamehera.kiikick.user.repository;

import com.teamehera.kiikick.user.domain.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<User> rowMapper = (rs, rowNum) -> new User(
            bytesToUuid(rs.getBytes("id")),
            rs.getString("nickname"),
            rs.getString("avatar_url"),
            // UTC DATETIME을 JVM 시간대 변환 없이 읽고 도메인의 LocalDateTime 경계를 유지한다.
            rs.getObject("onboarding_completed_at", LocalDateTime.class),
            rs.getObject("deletion_requested_at", LocalDateTime.class),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class),
            rs.getObject("deleted_at", LocalDateTime.class)
    );

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(UUID id, String nickname) {
        jdbcTemplate.update(
                """
                INSERT INTO users (id, nickname, created_at, updated_at)
                VALUES (?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """,
                uuidToBytes(id), nickname
        );
    }

    public Optional<User> findById(UUID id) {
        return findById(id, "");
    }

    public Optional<User> findByIdForUpdate(UUID id) {
        return findById(id, " FOR UPDATE");
    }

    private Optional<User> findById(UUID id, String lockClause) {
        return jdbcTemplate.query(
                """
                SELECT id, nickname, avatar_url, onboarding_completed_at,
                       deletion_requested_at, created_at, updated_at, deleted_at
                FROM users
                WHERE id = ? AND deleted_at IS NULL
                """ + lockClause,
                rowMapper,
                uuidToBytes(id)
        ).stream().findFirst();
    }

    public List<User> findAll() {
        return jdbcTemplate.query(
                """
                SELECT id, nickname, avatar_url, onboarding_completed_at,
                       deletion_requested_at, created_at, updated_at, deleted_at
                FROM users
                WHERE deleted_at IS NULL
                ORDER BY created_at DESC, id ASC
                """,
                rowMapper
        );
    }

    public List<User> searchByNickname(String query, int limit) {
        return jdbcTemplate.query(
                """
                SELECT id, nickname, avatar_url, onboarding_completed_at,
                       deletion_requested_at, created_at, updated_at, deleted_at
                FROM users
                WHERE deleted_at IS NULL
                  AND nickname LIKE ? ESCAPE '!'
                ORDER BY nickname ASC, id ASC
                LIMIT ?
                """,
                rowMapper,
                "%" + escapeLike(query) + "%", limit
        );
    }

    public int updateNickname(UUID id, String nickname) {
        return jdbcTemplate.update(
                """
                UPDATE users
                SET nickname = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND deleted_at IS NULL
                """,
                nickname, uuidToBytes(id)
        );
    }

    public void updateAvatar(UUID id, String avatarUrl) {
        jdbcTemplate.update(
                """
                UPDATE users
                SET avatar_url = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND deleted_at IS NULL
                """,
                avatarUrl, uuidToBytes(id)
        );
    }

    public int updateProfile(UUID id, String nickname, String avatarUrl) {
        return jdbcTemplate.update(
                """
                UPDATE users
                SET nickname = ?, avatar_url = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND deleted_at IS NULL
                """,
                nickname, avatarUrl, uuidToBytes(id)
        );
    }

    public int completeOnboarding(UUID id, String nickname, LocalDateTime completedAt) {
        return jdbcTemplate.update(
                """
                UPDATE users
                SET nickname = ?, onboarding_completed_at = ?,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND deleted_at IS NULL
                  AND onboarding_completed_at IS NULL
                """,
                nickname, completedAt, uuidToBytes(id)
        );
    }

    public int markDeletionRequested(UUID id, LocalDateTime requestedAt) {
        return jdbcTemplate.update(
                """
                UPDATE users
                SET deletion_requested_at = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND deleted_at IS NULL
                  AND deletion_requested_at IS NULL
                """,
                requestedAt, uuidToBytes(id)
        );
    }

    public int softDelete(UUID id) {
        return jdbcTemplate.update(
                """
                UPDATE users
                SET deleted_at = CURRENT_TIMESTAMP(6),
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND deleted_at IS NULL
                """,
                uuidToBytes(id)
        );
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }
}
