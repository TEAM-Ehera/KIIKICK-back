package com.teamehera.kiikick.notification.repository;

import com.teamehera.kiikick.notification.domain.Notification;
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
public class NotificationRepository {
    private static final String MATCH_TARGET = """
            EXISTS (SELECT 1 FROM matches m WHERE m.id = n.target_id AND m.owner_id = n.recipient_id)
            """;
    private static final String FRIEND_TARGET = """
            EXISTS (
                SELECT 1 FROM friend_pairs p
                JOIN users sender ON sender.id = p.requester_id AND sender.deleted_at IS NULL
                WHERE p.id = n.target_id AND p.status = 'PENDING' AND p.requester_id <> n.recipient_id
                  AND (p.user_low_id = n.recipient_id OR p.user_high_id = n.recipient_id)
                  AND NOT EXISTS (
                      SELECT 1 FROM user_blocks b
                      WHERE (b.blocker_id = n.recipient_id AND b.blocked_id = p.requester_id)
                         OR (b.blocked_id = n.recipient_id AND b.blocker_id = p.requester_id)
                  )
            )
            """;
    private static final String RECIPIENT = "FROM notifications n WHERE n.recipient_id = ?";
    private static final String VISIBLE = RECIPIENT + " AND ((n.target_type = 'MATCH' AND " + MATCH_TARGET
            + ") OR (n.target_type = 'FRIEND_REQUEST' AND " + FRIEND_TARGET + "))";

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Notification> rowMapper = (rs, rowNum) -> new Notification(
            bytesToUuid(rs.getBytes("id")), bytesToUuid(rs.getBytes("recipient_id")),
            rs.getString("type"), rs.getString("title"), rs.getString("body"),
            rs.getString("target_type"), bytesToUuid(rs.getBytes("target_id")),
            rs.getString("dedup_key"), nullableDateTime(rs, "read_at"),
            rs.getObject("created_at", LocalDateTime.class)
    );

    public NotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Notification notification) {
        jdbcTemplate.update(
                """
                INSERT INTO notifications (
                    id, recipient_id, type, title, body, target_type,
                    target_id, dedup_key, read_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                uuidToBytes(notification.id()), uuidToBytes(notification.recipientId()),
                notification.type(), notification.title(), notification.body(),
                notification.targetType(), uuidToBytes(notification.targetId()),
                notification.dedupKey(), notification.readAt(), notification.createdAt()
        );
    }

    public Optional<Notification> findByIdAndRecipientId(UUID id, UUID recipientId) {
        return jdbcTemplate.query(
                "SELECT * FROM notifications WHERE id = ? AND recipient_id = ?",
                rowMapper, uuidToBytes(id), uuidToBytes(recipientId)
        ).stream().findFirst();
    }

    public List<Notification> findPageByRecipientId(UUID recipientId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT * FROM notifications WHERE recipient_id = ?
                ORDER BY created_at DESC, id DESC LIMIT ?
                """,
                rowMapper, uuidToBytes(recipientId), limit
        );
    }

    public int countUnread(UUID recipientId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_id = ? AND read_at IS NULL",
                Integer.class, uuidToBytes(recipientId)
        );
        return count == null ? 0 : count;
    }

    public Optional<Notification> findVisible(UUID id, UUID recipient) {
        return jdbcTemplate.query("SELECT n.* " + VISIBLE + " AND n.id = ?",
                rowMapper, uuidToBytes(recipient), uuidToBytes(id)).stream().findFirst();
    }

    public List<Notification> findVisiblePage(UUID recipient, boolean unread, LocalDateTime before,
                                              UUID beforeId, int limit) {
        String sql = "SELECT n.* " + VISIBLE + (unread ? " AND n.read_at IS NULL" : "");
        String order = " ORDER BY n.created_at DESC, n.id DESC LIMIT ?";
        if (before == null) {
            return jdbcTemplate.query(sql + order, rowMapper, uuidToBytes(recipient), limit);
        }
        return jdbcTemplate.query(sql + " AND (n.created_at < ? OR (n.created_at = ? AND n.id < ?))" + order,
                rowMapper, uuidToBytes(recipient), before, before, uuidToBytes(beforeId), limit);
    }

    public long countVisibleUnread(UUID recipient) {
        // 표시 규칙은 목록과 공유하되 타입별로 분리해 서로 다른 대상 테이블의 OR 검사를 피한다.
        String match = "SELECT COUNT(*) AS total " + RECIPIENT
                + " AND n.read_at IS NULL AND n.target_type = 'MATCH' AND " + MATCH_TARGET;
        String request = "SELECT COUNT(*) AS total " + RECIPIENT
                + " AND n.read_at IS NULL AND n.target_type = 'FRIEND_REQUEST' AND " + FRIEND_TARGET;
        return jdbcTemplate.queryForObject("SELECT SUM(total) FROM ((" + match + ") UNION ALL ("
                        + request + ")) counts", Long.class, uuidToBytes(recipient), uuidToBytes(recipient));
    }

    public int markRead(UUID id, UUID recipientId, LocalDateTime readAt) {
        return jdbcTemplate.update(
                """
                UPDATE notifications SET read_at = ?
                WHERE id = ? AND recipient_id = ? AND read_at IS NULL
                """,
                readAt, uuidToBytes(id), uuidToBytes(recipientId)
        );
    }
}
