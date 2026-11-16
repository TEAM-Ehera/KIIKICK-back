package com.teamehera.kiikick.match.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class MatchCommentRepository {
    private static final String VISIBLE_COMMENT = """
            %1$s.hidden_at IS NULL AND NOT EXISTS (
                SELECT 1 FROM user_blocks b
                WHERE (b.blocker_id = ? AND b.blocked_id = %1$s.author_id)
                   OR (b.blocked_id = ? AND b.blocker_id = %1$s.author_id)
            )
            """;
    // A deleted parent still anchors its visible replies. A hidden parent does not.
    private static final String VISIBLE_PARENT = """
            SELECT 1 FROM match_comments p
            WHERE p.match_id = c.match_id AND p.id = c.parent_comment_id
              AND p.parent_comment_id IS NULL AND %s
            """.formatted(VISIBLE_COMMENT.formatted("p"));

    private final JdbcTemplate jdbc;
    private final RowMapper<Comment> mapper = (rs, row) -> {
        // MySQL DATETIME stores UTC wall-clock values without an offset.
        var deletedAt = rs.getObject("deleted_at", LocalDateTime.class);
        return new Comment(bytesToUuid(rs.getBytes("id")), bytesToUuid(rs.getBytes("match_id")),
                bytesToUuid(rs.getBytes("author_id")), deletedAt == null ? rs.getString("content") : null,
                rs.getObject("created_at", LocalDateTime.class), bytesToUuid(rs.getBytes("parent_comment_id")),
                deletedAt, rs.getObject("hidden_at", LocalDateTime.class));
    };

    public MatchCommentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Comment comment) {
        jdbc.update("""
                INSERT INTO match_comments
                    (id, match_id, author_id, content, created_at, parent_comment_id, deleted_at, hidden_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, uuidToBytes(comment.id()), uuidToBytes(comment.matchId()), uuidToBytes(comment.authorId()),
                comment.content(), comment.createdAt(), uuidToBytes(comment.parentCommentId()), comment.deletedAt(),
                comment.hiddenAt());
    }

    public List<Comment> page(UUID matchId, UUID actor, LocalDateTime before, UUID beforeId, int limit) {
        return page(matchId, actor, null, false, before, beforeId, limit);
    }

    public List<Comment> pageTopLevel(UUID matchId, UUID actor, LocalDateTime before, UUID beforeId, int limit) {
        return page(matchId, actor, null, true, before, beforeId, limit);
    }

    public List<Comment> pageReplies(UUID matchId, UUID parentCommentId, UUID actor,
                                     LocalDateTime before, UUID beforeId, int limit) {
        return page(matchId, actor, Objects.requireNonNull(parentCommentId), false, before, beforeId, limit);
    }

    private List<Comment> page(UUID matchId, UUID actor, UUID parentCommentId, boolean includeDeleted,
                               LocalDateTime before, UUID beforeId, int limit) {
        String sql = "SELECT c.* FROM match_comments c WHERE c.match_id = ? AND " + VISIBLE_COMMENT.formatted("c");
        var args = new ArrayList<Object>();
        var actorId = uuidToBytes(actor);
        args.add(uuidToBytes(matchId));
        args.add(actorId);
        args.add(actorId);
        if (parentCommentId == null) {
            sql += " AND c.parent_comment_id IS NULL";
        } else {
            sql += " AND c.parent_comment_id = ? AND EXISTS (" + VISIBLE_PARENT + ")";
            args.add(uuidToBytes(parentCommentId));
            args.add(actorId);
            args.add(actorId);
        }
        if (!includeDeleted) {
            sql += " AND c.deleted_at IS NULL";
        }
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

    // Call and insert the reply in the same transaction to serialize with parent deletion.
    public Optional<Comment> findReplyParentForUpdate(UUID matchId, UUID parentCommentId, UUID actor) {
        String sql = """
                SELECT c.* FROM match_comments c
                WHERE c.match_id = ? AND c.id = ? AND c.parent_comment_id IS NULL
                  AND c.deleted_at IS NULL AND %s
                FOR UPDATE
                """.formatted(VISIBLE_COMMENT.formatted("c"));
        var actorId = uuidToBytes(actor);
        return jdbc.query(sql, mapper, uuidToBytes(matchId), uuidToBytes(parentCommentId), actorId, actorId)
                .stream().findFirst();
    }

    // Lock the target and any parent in the transaction that saves the report snapshot.
    public Optional<Comment> findReportableForUpdate(UUID matchId, UUID commentId, UUID actor) {
        String sql = """
                SELECT c.* FROM match_comments c
                WHERE c.match_id = ? AND c.id = ? AND c.deleted_at IS NULL AND %s
                  AND (c.parent_comment_id IS NULL OR EXISTS (%s FOR UPDATE))
                FOR UPDATE
                """.formatted(VISIBLE_COMMENT.formatted("c"), VISIBLE_PARENT);
        var actorId = uuidToBytes(actor);
        return jdbc.query(sql, mapper, uuidToBytes(matchId), uuidToBytes(commentId), actorId, actorId, actorId, actorId)
                .stream().findFirst();
    }

    public Optional<Comment> findByIdForUpdate(UUID commentId) {
        return jdbc.query("SELECT * FROM match_comments WHERE id = ? FOR UPDATE", mapper, uuidToBytes(commentId))
                .stream().findFirst();
    }

    public int hide(UUID commentId, LocalDateTime hiddenAt) {
        return jdbc.update("""
                UPDATE match_comments SET hidden_at = ?
                WHERE id = ? AND hidden_at IS NULL AND deleted_at IS NULL
                """, Objects.requireNonNull(hiddenAt), uuidToBytes(commentId));
    }

    public int softDeleteById(UUID commentId, LocalDateTime deletedAt) {
        return jdbc.update("""
                UPDATE match_comments SET deleted_at = ? WHERE id = ? AND deleted_at IS NULL
                """, Objects.requireNonNull(deletedAt), uuidToBytes(commentId));
    }

    public int delete(UUID matchId, UUID commentId, UUID actor) {
        return softDelete(matchId, commentId, actor, LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
    }

    public int softDelete(UUID matchId, UUID commentId, UUID actor, LocalDateTime deletedAt) {
        return jdbc.update("""
                UPDATE match_comments SET deleted_at = ?
                WHERE id = ? AND match_id = ? AND author_id = ? AND deleted_at IS NULL
                """, Objects.requireNonNull(deletedAt), uuidToBytes(commentId), uuidToBytes(matchId), uuidToBytes(actor));
    }

    public record Comment(UUID id, UUID matchId, UUID authorId, String content, LocalDateTime createdAt,
                          UUID parentCommentId, LocalDateTime deletedAt, LocalDateTime hiddenAt) {
        public Comment(UUID id, UUID matchId, UUID authorId, String content, LocalDateTime createdAt) {
            this(id, matchId, authorId, content, createdAt, null, null, null);
        }

        public Comment(UUID id, UUID matchId, UUID authorId, String content, LocalDateTime createdAt,
                       UUID parentCommentId, LocalDateTime deletedAt) {
            this(id, matchId, authorId, content, createdAt, parentCommentId, deletedAt, null);
        }
    }
}
