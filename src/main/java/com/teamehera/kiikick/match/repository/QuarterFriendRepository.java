package com.teamehera.kiikick.match.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class QuarterFriendRepository {
    private final JdbcTemplate jdbc;

    public QuarterFriendRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Friend> findByQuarterId(UUID quarterId, UUID actor) {
        return jdbc.query("""
                SELECT u.id, u.nickname, u.avatar_url
                FROM quarter_friends qf JOIN users u ON u.id = qf.friend_user_id
                WHERE qf.quarter_id = ? AND u.deleted_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM user_blocks b
                      WHERE (b.blocker_id = ? AND b.blocked_id = u.id)
                         OR (b.blocked_id = ? AND b.blocker_id = u.id)
                  )
                ORDER BY qf.created_at, qf.friend_user_id
                """, (rs, index) -> new Friend(bytesToUuid(rs.getBytes("id")),
                rs.getString("nickname"), rs.getString("avatar_url")),
                uuidToBytes(quarterId), uuidToBytes(actor), uuidToBytes(actor));
    }

    public void add(UUID quarterId, UUID friend) {
        // The parent match lock serializes duplicate additions, including requests with different keys.
        jdbc.update("""
                INSERT INTO quarter_friends (quarter_id, friend_user_id, created_at)
                SELECT ?, ?, CURRENT_TIMESTAMP(6)
                WHERE NOT EXISTS (
                    SELECT 1 FROM quarter_friends WHERE quarter_id = ? AND friend_user_id = ?
                )
                """, uuidToBytes(quarterId), uuidToBytes(friend), uuidToBytes(quarterId), uuidToBytes(friend));
    }

    public void remove(UUID quarterId, UUID friend) {
        jdbc.update("DELETE FROM quarter_friends WHERE quarter_id = ? AND friend_user_id = ?",
                uuidToBytes(quarterId), uuidToBytes(friend));
    }

    public record Friend(UUID userId, String nickname, String avatarUrl) {}
}
