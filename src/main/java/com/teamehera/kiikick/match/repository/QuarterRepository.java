package com.teamehera.kiikick.match.repository;

import com.teamehera.kiikick.match.domain.Quarter;
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
public class QuarterRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Quarter> rowMapper = (rs, rowNum) -> new Quarter(
            bytesToUuid(rs.getBytes("id")), bytesToUuid(rs.getBytes("match_id")),
            rs.getInt("number"), rs.getInt("duration_seconds"),
            rs.getString("status"), nullableDateTime(rs, "started_at"),
            nullableDateTime(rs, "ended_at"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );

    public QuarterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Quarter quarter) {
        jdbcTemplate.update(
                """
                INSERT INTO quarters (
                    id, match_id, number, duration_seconds, status,
                    started_at, ended_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                uuidToBytes(quarter.id()), uuidToBytes(quarter.matchId()),
                quarter.number(), quarter.durationSeconds(), quarter.status(),
                quarter.startedAt(), quarter.endedAt(), quarter.createdAt(), quarter.updatedAt()
        );
    }

    public Optional<Quarter> findByIdAndMatchId(UUID id, UUID matchId) {
        return jdbcTemplate.query(
                "SELECT * FROM quarters WHERE id = ? AND match_id = ?",
                rowMapper, uuidToBytes(id), uuidToBytes(matchId)
        ).stream().findFirst();
    }

    public Optional<Quarter> findByIdAndOwnerId(UUID id, UUID ownerId) {
        return jdbcTemplate.query(
                """
                SELECT q.* FROM quarters q
                JOIN matches m ON m.id = q.match_id
                WHERE q.id = ? AND m.owner_id = ?
                """,
                rowMapper, uuidToBytes(id), uuidToBytes(ownerId)
        ).stream().findFirst();
    }

    public List<Quarter> findByMatchId(UUID matchId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT * FROM quarters WHERE match_id = ?
                ORDER BY number ASC, id ASC LIMIT ?
                """,
                rowMapper, uuidToBytes(matchId), limit
        );
    }

    public List<Quarter> findPageAfter(UUID matchId, int number, UUID id, int limit) {
        return jdbcTemplate.query(
                """
                SELECT * FROM quarters WHERE match_id = ?
                  AND (number > ? OR (number = ? AND id > ?))
                ORDER BY number ASC, id ASC LIMIT ?
                """,
                rowMapper, uuidToBytes(matchId), number, number, uuidToBytes(id), limit
        );
    }

    public int findNextNumber(UUID matchId) {
        Integer number = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(number), 0) + 1 FROM quarters WHERE match_id = ?",
                Integer.class, uuidToBytes(matchId)
        );
        return number == null ? 1 : number;
    }

    public boolean hasReadyQuarter(UUID matchId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM quarters WHERE match_id = ? AND status = 'READY')",
                Boolean.class, uuidToBytes(matchId)
        ));
    }

    public boolean hasOnlyCompletedQuarters(UUID matchId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (SELECT 1 FROM quarters WHERE match_id = ?)
                   AND NOT EXISTS (
                       SELECT 1 FROM quarters WHERE match_id = ? AND status <> 'COMPLETED'
                   )
                """,
                Boolean.class, uuidToBytes(matchId), uuidToBytes(matchId)
        ));
    }

    public int updateDuration(UUID id, UUID matchId, int durationSeconds) {
        return jdbcTemplate.update(
                """
                UPDATE quarters
                SET duration_seconds = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND match_id = ? AND status = 'READY'
                """,
                durationSeconds, uuidToBytes(id), uuidToBytes(matchId)
        );
    }

    public int updateStatus(
            UUID id, UUID matchId, String currentStatus, String nextStatus,
            LocalDateTime startedAt, LocalDateTime endedAt
    ) {
        return jdbcTemplate.update(
                """
                UPDATE quarters
                SET status = ?, started_at = COALESCE(?, started_at),
                    ended_at = COALESCE(?, ended_at),
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND match_id = ? AND status = ?
                """,
                nextStatus, startedAt, endedAt, uuidToBytes(id),
                uuidToBytes(matchId), currentStatus
        );
    }

    public int deleteUnusedLast(UUID id, UUID matchId) {
        return jdbcTemplate.update(
                """
                DELETE FROM quarters
                WHERE id = ? AND match_id = ? AND status = 'READY'
                  AND number = (SELECT max_number FROM (
                      SELECT MAX(number) AS max_number FROM quarters WHERE match_id = ?
                  ) last_quarter)
                  AND started_at IS NULL AND ended_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM quarter_player_stats WHERE quarter_id = ?)
                  AND NOT EXISTS (SELECT 1 FROM quarter_positions WHERE quarter_id = ?)
                  AND NOT EXISTS (SELECT 1 FROM workout_payloads WHERE quarter_id = ?)
                  AND NOT EXISTS (SELECT 1 FROM quarter_summaries WHERE quarter_id = ?)
                """,
                uuidToBytes(id), uuidToBytes(matchId), uuidToBytes(matchId),
                uuidToBytes(id), uuidToBytes(id), uuidToBytes(id), uuidToBytes(id)
        );
    }
}
