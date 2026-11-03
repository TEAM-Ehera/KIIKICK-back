package com.teamehera.kiikick.match.repository;

import com.teamehera.kiikick.match.domain.QuarterPosition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class QuarterPositionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<QuarterPosition> rowMapper = (rs, rowNum) ->
            new QuarterPosition(
                    bytesToUuid(rs.getBytes("quarter_id")),
                    rs.getString("position_code"),
                    rs.getTimestamp("updated_at").toLocalDateTime()
            );

    public QuarterPositionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<QuarterPosition> findByQuarterId(UUID quarterId) {
        return jdbcTemplate.query(
                "SELECT * FROM quarter_positions WHERE quarter_id = ?",
                rowMapper, uuidToBytes(quarterId)
        ).stream().findFirst();
    }

    public int upsert(UUID quarterId, String positionCode, LocalDateTime updatedAt) {
        int updated = jdbcTemplate.update(
                """
                UPDATE quarter_positions
                SET position_code = ?, updated_at = ?
                WHERE quarter_id = ?
                """,
                positionCode, updatedAt, uuidToBytes(quarterId)
        );
        if (updated > 0) {
            return updated;
        }
        return jdbcTemplate.update(
                """
                INSERT INTO quarter_positions (quarter_id, position_code, updated_at)
                VALUES (?, ?, ?)
                """,
                uuidToBytes(quarterId), positionCode, updatedAt
        );
    }

    public Map<String, Integer> countByOwnerId(UUID ownerId) {
        List<Map.Entry<String, Integer>> entries = jdbcTemplate.query(
                """
                SELECT p.position_code, COUNT(*) AS position_count
                FROM quarter_positions p
                JOIN quarters q ON q.id = p.quarter_id
                JOIN matches m ON m.id = q.match_id
                WHERE m.owner_id = ? AND q.status = 'COMPLETED'
                GROUP BY p.position_code
                """,
                (rs, rowNum) -> Map.entry(
                        rs.getString("position_code"), rs.getInt("position_count")
                ),
                uuidToBytes(ownerId)
        );
        return entries.stream().collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue
        ));
    }
}
