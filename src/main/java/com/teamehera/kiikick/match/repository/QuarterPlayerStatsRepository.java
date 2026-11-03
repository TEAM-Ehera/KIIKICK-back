package com.teamehera.kiikick.match.repository;

import com.teamehera.kiikick.match.domain.QuarterPlayerStats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class QuarterPlayerStatsRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<QuarterPlayerStats> rowMapper = (rs, rowNum) ->
            new QuarterPlayerStats(
                    bytesToUuid(rs.getBytes("quarter_id")), rs.getInt("goals"),
                    rs.getInt("assists"), rs.getTimestamp("updated_at").toLocalDateTime()
            );

    public QuarterPlayerStatsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<QuarterPlayerStats> findByQuarterId(UUID quarterId) {
        return jdbcTemplate.query(
                "SELECT * FROM quarter_player_stats WHERE quarter_id = ?",
                rowMapper, uuidToBytes(quarterId)
        ).stream().findFirst();
    }

    public int upsert(UUID quarterId, int goals, int assists, LocalDateTime updatedAt) {
        int updated = jdbcTemplate.update(
                """
                UPDATE quarter_player_stats
                SET goals = ?, assists = ?, updated_at = ?
                WHERE quarter_id = ?
                """,
                goals, assists, updatedAt, uuidToBytes(quarterId)
        );
        if (updated > 0) {
            return updated;
        }
        return jdbcTemplate.update(
                """
                INSERT INTO quarter_player_stats (quarter_id, goals, assists, updated_at)
                VALUES (?, ?, ?, ?)
                """,
                uuidToBytes(quarterId), goals, assists, updatedAt
        );
    }

    public int sumGoalsByMatchId(UUID matchId) {
        Integer value = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(s.goals), 0)
                FROM quarter_player_stats s
                JOIN quarters q ON q.id = s.quarter_id
                WHERE q.match_id = ? AND q.status = 'COMPLETED'
                """,
                Integer.class, uuidToBytes(matchId)
        );
        return value == null ? 0 : value;
    }

    public int sumAssistsByMatchId(UUID matchId) {
        Integer value = jdbcTemplate.queryForObject(
                """
                SELECT COALESCE(SUM(s.assists), 0)
                FROM quarter_player_stats s
                JOIN quarters q ON q.id = s.quarter_id
                WHERE q.match_id = ? AND q.status = 'COMPLETED'
                """,
                Integer.class, uuidToBytes(matchId)
        );
        return value == null ? 0 : value;
    }
}
