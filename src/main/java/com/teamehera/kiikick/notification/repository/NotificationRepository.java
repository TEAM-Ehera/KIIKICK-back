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

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Notification> rowMapper = (rs, rowNum) -> new Notification(
            bytesToUuid(rs.getBytes("id")), bytesToUuid(rs.getBytes("recipient_id")),
            rs.getString("type"), rs.getString("title"), rs.getString("body"),
            rs.getString("target_type"), bytesToUuid(rs.getBytes("target_id")),
            rs.getString("dedup_key"), nullableDateTime(rs, "read_at"),
            rs.getTimestamp("created_at").toLocalDateTime()
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
