package com.teamehera.kiikick.match.repository;

import com.teamehera.kiikick.match.domain.QuarterPosition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class QuarterPositionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final RowMapper<QuarterPosition> rowMapper = (rs, rowNum) ->
            new QuarterPosition(
                    bytesToUuid(rs.getBytes("quarter_id")),
                    rs.getString("position_code"),
                    rs.getTimestamp("updated_at").toLocalDateTime()
            );

    public QuarterPositionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    public List<QuarterPosition> findByQuarterIds(List<UUID> quarterIds) {
        return namedJdbc.query(
                "SELECT * FROM quarter_positions WHERE quarter_id IN (:quarterIds)",
                Map.of("quarterIds", quarterIds.stream().map(id -> uuidToBytes(id)).toList()), rowMapper
        );
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

        //완료된 경기에서 완료된 쿼터들의 포지션별 횟수 조회
        return jdbcTemplate.query(
                """
                SELECT p.position_code, COUNT(*) AS position_count
                FROM quarters q
                JOIN matches m ON m.id = q.match_id
               -- LEFT JOIN -> 포지션 기록이 없어도 쿼터가 조회결과에 남음
               -- 포지션 미입력 시 position_code는 NULL 이 됨.
                LEFT JOIN quarter_positions p ON p.quarter_id = q.id
                WHERE m.owner_id = ?
                  AND m.status = 'COMPLETED'
                  AND q.status = 'COMPLETED'
                GROUP BY p.position_code
                """,
                rs -> {
                    Map<String, Integer> counts = new HashMap<>();
                    while (rs.next()) {
                        counts.put(
                                rs.getString("position_code"),
                                rs.getInt("position_count")
                        );
                    }
                    return counts;
                },
                uuidToBytes(ownerId)
        );
    }
}
