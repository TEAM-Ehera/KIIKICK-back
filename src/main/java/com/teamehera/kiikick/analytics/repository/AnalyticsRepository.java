package com.teamehera.kiikick.analytics.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.nullableDateTime;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class AnalyticsRepository {
    private static final Map<String, String> METRIC_COLUMNS = Map.ofEntries(
            Map.entry("totalDistanceM", "total_distance_m"), Map.entry("totalCaloriesKcal", "total_calories_kcal"),
            Map.entry("highIntensityDistanceM", "high_intensity_distance_m"),
            Map.entry("accelerationCount", "acceleration_count"), Map.entry("decelerationCount", "deceleration_count"),
            Map.entry("directionChangeCount", "direction_change_count"), Map.entry("maxSpeedMps", "max_speed_mps"),
            Map.entry("maxHeartRateBpm", "max_heart_rate_bpm"), Map.entry("averageSpeedMps", "average_speed_mps"),
            Map.entry("averageHeartRateBpm", "average_heart_rate_bpm"), Map.entry("trimpEdwards", "trimp_edwards"));

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public AnalyticsRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.json = json;
    }

    public List<QuarterData> quarters(UUID owner, UUID match, LocalDateTime from, LocalDateTime to) {
        String sql = """
                SELECT m.id AS match_id, q.ended_at, p.position_code, stats.goals, stats.assists,
                       s.calculation_status, s.calculation_version, s.metric_status, s.total_distance_m,
                       s.total_calories_kcal, s.high_intensity_distance_m, s.acceleration_count,
                       s.deceleration_count, s.direction_change_count, s.max_speed_mps, s.max_heart_rate_bpm,
                       s.average_speed_mps, s.average_heart_rate_bpm, s.trimp_edwards, s.duration_seconds,
                       s.valid_hr_duration_seconds, s.hr_max_reference_bpm
                FROM matches m
                JOIN quarters q ON q.match_id = m.id AND q.status = 'COMPLETED'
                LEFT JOIN quarter_player_stats stats ON stats.quarter_id = q.id
                LEFT JOIN quarter_positions p ON p.quarter_id = q.id
                LEFT JOIN quarter_summaries s ON s.quarter_id = q.id
                WHERE m.owner_id = :owner AND m.status = 'COMPLETED'
                """;
        var args = new MapSqlParameterSource("owner", uuidToBytes(owner));
        if (match != null) {
            sql += " AND m.id = :match";
            args.addValue("match", uuidToBytes(match));
        }
        if (from != null) {
            sql += " AND q.ended_at >= :from";
            args.addValue("from", from);
        }
        if (to != null) {
            sql += " AND q.ended_at < :to";
            args.addValue("to", to);
        }
        return jdbc.query(sql, args, (row, index) -> {
            var values = new HashMap<String, Double>();
            String status = row.getString("calculation_status");
            String metadata = row.getString("metric_status");
            if (("READY".equals(status) || "PARTIAL".equals(status)) && metadata != null) {
                JsonNode statuses = json.readTree(metadata);
                for (var metric : METRIC_COLUMNS.entrySet()) {
                    var state = statuses.path(metric.getKey()).path("status");
                    if (state.isTextual() && "READY".equals(state.stringValue())) {
                        var value = row.getObject(metric.getValue(), Double.class);
                        if (value != null && Double.isFinite(value)) {
                            values.put(metric.getKey(), value);
                        }
                    }
                }
            }
            return new QuarterData(bytesToUuid(row.getBytes("match_id")), nullableDateTime(row, "ended_at"),
                    row.getString("position_code"), row.getObject("goals", Integer.class),
                    row.getObject("assists", Integer.class), row.getString("calculation_version"), Map.copyOf(values),
                    row.getObject("duration_seconds", Double.class), row.getObject("valid_hr_duration_seconds", Double.class),
                    row.getObject("hr_max_reference_bpm", Double.class));
        });
    }

    public Extent extent(UUID owner, LocalDateTime from, LocalDateTime to) {
        String sql = """
                SELECT MIN(q.ended_at) AS first_end, MAX(q.ended_at) AS last_end
                FROM matches m JOIN quarters q ON q.match_id = m.id
                WHERE m.owner_id = :owner AND m.status = 'COMPLETED' AND q.status = 'COMPLETED'
                """;
        var args = new MapSqlParameterSource("owner", uuidToBytes(owner));
        if (from != null) {
            sql += " AND q.ended_at >= :from";
            args.addValue("from", from);
        }
        if (to != null) {
            sql += " AND q.ended_at < :to";
            args.addValue("to", to);
        }
        return jdbc.queryForObject(sql, args, (row, index) ->
                new Extent(nullableDateTime(row, "first_end"), nullableDateTime(row, "last_end")));
    }

    public record Extent(LocalDateTime first, LocalDateTime last) {}

    public Optional<UUID> previous(UUID owner, UUID currentId, LocalDateTime endedAt) {
        return jdbc.query("""
                SELECT id FROM matches
                WHERE owner_id = :owner AND status = 'COMPLETED'
                  AND (ended_at < :ended OR (ended_at = :ended AND id < :id))
                ORDER BY ended_at DESC, id DESC LIMIT 1
                """,
                new MapSqlParameterSource("owner", uuidToBytes(owner)).addValue("ended", endedAt)
                        .addValue("id", uuidToBytes(currentId)),
                (row, index) -> bytesToUuid(row.getBytes("id"))).stream().findFirst();
    }

    public record QuarterData(UUID matchId, LocalDateTime endedAt, String position, Integer goals, Integer assists,
                              String version, Map<String, Double> values, Double elapsedSeconds,
                              Double validHeartSeconds, Double referenceBpm) {}
}
