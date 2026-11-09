package com.teamehera.kiikick.match.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.*;

@Repository
public class MatchCommentRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<Comment> mapper = (rs, row) -> new Comment(bytesToUuid(rs.getBytes("id")),
            bytesToUuid(rs.getBytes("match_id")), bytesToUuid(rs.getBytes("author_id")), rs.getString("content"),
            rs.getTimestamp("created_at").toLocalDateTime());

    public MatchCommentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Comment comment) {
        jdbc.update("INSERT INTO match_comments (id, match_id, author_id, content, created_at) VALUES (?, ?, ?, ?, ?)",
                uuidToBytes(comment.id()), uuidToBytes(comment.matchId()), uuidToBytes(comment.authorId()),
                comment.content(), comment.createdAt());
    }

    public List<Comment> page(UUID matchId, UUID actor, LocalDateTime before, UUID beforeId, int limit) {
        // 차단한 사용자와 나를 차단한 사용자의 댓글은 양쪽 모두 숨긴다.
        String sql = """
                SELECT c.* FROM match_comments c
                WHERE c.match_id = ? AND NOT EXISTS (
                    SELECT 1 FROM user_blocks b
                    WHERE (b.blocker_id = ? AND b.blocked_id = c.author_id)
                       OR (b.blocked_id = ? AND b.blocker_id = c.author_id)
                )
                """;
        var args = new java.util.ArrayList<Object>();
        args.add(uuidToBytes(matchId));
        args.add(uuidToBytes(actor));
        args.add(uuidToBytes(actor));
        if (before != null) {
            sql += " AND (c.created_at < ? OR (c.created_at = ? AND c.id < ?))";
            args.add(before);
            args.add(before);
            args.add(uuidToBytes(beforeId));
        }
        sql += " ORDER BY c.created_at DESC, c.id DESC LIMIT ?";
        args.add(limit);
        return jdbc.query(sql, mapper, args.toArray());
    }

    public int delete(UUID matchId, UUID commentId, UUID actor) {
        return jdbc.update("DELETE FROM match_comments WHERE id = ? AND match_id = ? AND author_id = ?",
                uuidToBytes(commentId), uuidToBytes(matchId), uuidToBytes(actor));
    }

    public record Comment(UUID id, UUID matchId, UUID authorId, String content, LocalDateTime createdAt) {}
}
