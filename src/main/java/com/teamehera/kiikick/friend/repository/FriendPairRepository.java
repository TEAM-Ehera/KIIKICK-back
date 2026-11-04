package com.teamehera.kiikick.friend.repository;

import com.teamehera.kiikick.friend.domain.FriendPair;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.nullableDateTime;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class FriendPairRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<FriendPair> rowMapper = (rs, rowNum) -> new FriendPair(
            bytesToUuid(rs.getBytes("id")), bytesToUuid(rs.getBytes("user_low_id")),
            bytesToUuid(rs.getBytes("user_high_id")),
            bytesToUuid(rs.getBytes("requester_id")), rs.getString("status"),
            nullableDateTime(rs, "accepted_at"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    public FriendPairRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(FriendPair pair) {
        jdbcTemplate.update(
                """
                INSERT INTO friend_pairs (
                    id, user_low_id, user_high_id, requester_id,
                    status, accepted_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                uuidToBytes(pair.id()), uuidToBytes(pair.userLowId()),
                uuidToBytes(pair.userHighId()), uuidToBytes(pair.requesterId()),
                pair.status(), pair.acceptedAt(), pair.createdAt()
        );
    }

    public Optional<FriendPair> findByUsers(UUID first, UUID second) {
        UUID[] pair = normalize(first, second);
        return jdbcTemplate.query(
                """
                SELECT * FROM friend_pairs
                WHERE user_low_id = ? AND user_high_id = ?
                """,
                rowMapper, uuidToBytes(pair[0]), uuidToBytes(pair[1])
        ).stream().findFirst();
    }

    public Optional<FriendPair> findByIdAndUserId(UUID id, UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT * FROM friend_pairs
                WHERE id = ? AND (user_low_id = ? OR user_high_id = ?)
                """,
                rowMapper, uuidToBytes(id), uuidToBytes(userId), uuidToBytes(userId)
        ).stream().findFirst();
    }

    public List<FriendPair> findByUserIdAndStatus(UUID userId, String status, int limit) {
        return jdbcTemplate.query(
                """
                SELECT * FROM friend_pairs
                WHERE (user_low_id = ? OR user_high_id = ?) AND status = ?
                ORDER BY created_at DESC, id DESC LIMIT ?
                """,
                rowMapper, uuidToBytes(userId), uuidToBytes(userId), status, limit
        );
    }

    public int accept(UUID id, UUID recipientId, LocalDateTime acceptedAt) {
        return jdbcTemplate.update(
                """
                UPDATE friend_pairs SET status = 'ACCEPTED', accepted_at = ?
                WHERE id = ? AND status = 'PENDING' AND requester_id <> ?
                  AND (user_low_id = ? OR user_high_id = ?)
                """,
                acceptedAt, uuidToBytes(id), uuidToBytes(recipientId),
                uuidToBytes(recipientId), uuidToBytes(recipientId)
        );
    }

    public int deleteByIdAndUserId(UUID id, UUID userId) {
        return jdbcTemplate.update(
                """
                DELETE FROM friend_pairs
                WHERE id = ? AND (user_low_id = ? OR user_high_id = ?)
                """,
                uuidToBytes(id), uuidToBytes(userId), uuidToBytes(userId)
        );
    }

    public int deleteByUsers(UUID first, UUID second) {
        UUID[] pair = normalize(first, second);
        return jdbcTemplate.update(
                "DELETE FROM friend_pairs WHERE user_low_id = ? AND user_high_id = ?",
                uuidToBytes(pair[0]), uuidToBytes(pair[1])
        );
    }

    public static UUID[] normalize(UUID first, UUID second) {
        return first.compareTo(second) <= 0
                ? new UUID[]{first, second}
                : new UUID[]{second, first};
    }
}
