package com.teamehera.kiikick.match.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class MatchResultRepository {
    private final JdbcTemplate jdbc;

    public MatchResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<QuarterTotals> findTotals(UUID matchId) {
        return jdbc.query("""
                SELECT q.id, stats.goals, stats.assists, summary.calculation_status
                FROM quarters q
                LEFT JOIN quarter_player_stats stats ON stats.quarter_id = q.id
                LEFT JOIN quarter_summaries summary ON summary.quarter_id = q.id
                WHERE q.match_id = ? AND q.status = 'COMPLETED'
                ORDER BY q.number, q.id
                """,
                (row, index) -> new QuarterTotals(bytesToUuid(row.getBytes("id")),
                        row.getObject("goals", Integer.class), row.getObject("assists", Integer.class),
                        row.getString("calculation_status")),
                uuidToBytes(matchId));
    }

    public record QuarterTotals(UUID quarterId, Integer goals, Integer assists, String summaryStatus) {}
}
