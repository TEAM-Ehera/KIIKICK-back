package com.teamehera.kiikick.friend.repository;

import com.teamehera.kiikick.friend.domain.UserBlock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class UserBlockRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserBlockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(UserBlock block) {
        jdbcTemplate.update(
                """
                INSERT INTO user_blocks (blocker_id, blocked_id, created_at)
                VALUES (?, ?, ?)
                """,
                uuidToBytes(block.blockerId()), uuidToBytes(block.blockedId()),
                block.createdAt()
        );
    }

    public boolean exists(UUID blockerId, UUID blockedId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM user_blocks
                WHERE blocker_id = ? AND blocked_id = ?
                """,
                Integer.class, uuidToBytes(blockerId), uuidToBytes(blockedId)
        );
        return count != null && count > 0;
    }

    public boolean existsEitherDirection(UUID first, UUID second) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM user_blocks
                WHERE (blocker_id = ? AND blocked_id = ?)
                   OR (blocker_id = ? AND blocked_id = ?)
                """,
                Integer.class, uuidToBytes(first), uuidToBytes(second),
                uuidToBytes(second), uuidToBytes(first)
        );
        return count != null && count > 0;
    }

    public int delete(UUID blockerId, UUID blockedId) {
        return jdbcTemplate.update(
                "DELETE FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?",
                uuidToBytes(blockerId), uuidToBytes(blockedId)
        );
    }
}
