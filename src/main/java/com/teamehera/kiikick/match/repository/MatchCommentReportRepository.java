package com.teamehera.kiikick.match.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.*;

@Repository
public class MatchCommentReportRepository {
    private final JdbcTemplate jdbc;
    private final RowMapper<Report> mapper = (rs, row) -> {
        String resolution = rs.getString("resolution");
        return new Report(
                bytesToUuid(rs.getBytes("id")), bytesToUuid(rs.getBytes("reporter_id")),
                bytesToUuid(rs.getBytes("comment_author_id")), bytesToUuid(rs.getBytes("comment_id")),
                rs.getString("comment_content"), rs.getString("reason"), rs.getString("detail"),
                rs.getTimestamp("reported_at").toLocalDateTime(), nullableDateTime(rs, "processed_at"),
                bytesToUuid(rs.getBytes("processed_by")),
                resolution == null ? null : Resolution.valueOf(resolution)
        );
    };

    public MatchCommentReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Report report) {
        jdbc.update("""
                INSERT INTO match_comment_reports
                    (id, reporter_id, comment_author_id, comment_id, comment_content, reason, detail, reported_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                uuidToBytes(report.id()), uuidToBytes(report.reporterId()), uuidToBytes(report.commentAuthorId()),
                uuidToBytes(report.commentId()), report.commentContent(), report.reason(), report.detail(),
                report.reportedAt()
        );
    }

    public Optional<Report> findById(UUID reportId) {
        return jdbc.query("SELECT * FROM match_comment_reports WHERE id = ?", mapper, uuidToBytes(reportId))
                .stream().findFirst();
    }

    public List<Report> page(Status status, LocalDateTime before, UUID beforeId, int limit) {
        String sql = "SELECT * FROM match_comment_reports WHERE 1 = 1";
        sql += switch (status) {
            case ALL -> "";
            case PENDING -> " AND processed_at IS NULL";
            case PROCESSED -> " AND processed_at IS NOT NULL";
        };
        var args = new ArrayList<>();
        if (before != null) {
            sql += " AND (reported_at < ? OR (reported_at = ? AND id < ?))";
            args.add(before);
            args.add(before);
            args.add(uuidToBytes(beforeId));
        }
        sql += " ORDER BY reported_at DESC, id DESC LIMIT ?";
        args.add(limit);
        return jdbc.query(sql, mapper, args.toArray());
    }

    public int markProcessed(UUID reportId, UUID processedBy, LocalDateTime processedAt, Resolution resolution) {
        return jdbc.update("""
                UPDATE match_comment_reports
                SET processed_by = ?, processed_at = ?, resolution = ?
                WHERE id = ? AND processed_at IS NULL
                """,
                uuidToBytes(processedBy), processedAt, resolution.name(), uuidToBytes(reportId)
        );
    }

    public enum Status { ALL, PENDING, PROCESSED }

    public enum Resolution { NO_ACTION, HIDDEN, DELETED }

    public record Report(
            UUID id, UUID reporterId, UUID commentAuthorId, UUID commentId, String commentContent,
            String reason, String detail, LocalDateTime reportedAt, LocalDateTime processedAt,
            UUID processedBy, Resolution resolution
    ) {}
}
