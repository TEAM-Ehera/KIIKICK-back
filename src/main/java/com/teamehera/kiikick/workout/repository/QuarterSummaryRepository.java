package com.teamehera.kiikick.workout.repository;

import com.teamehera.kiikick.workout.domain.QuarterSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.nullableDateTime;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class QuarterSummaryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<QuarterSummary> rowMapper = (rs, rowNum) ->
            new QuarterSummary(
                    bytesToUuid(rs.getBytes("quarter_id")),
                    bytesToUuid(rs.getBytes("workout_payload_id")),
                    rs.getString("calculation_status"),
                    rs.getString("calculation_version"),
                    rs.getString("calculation_parameters"),
                    rs.getString("metric_status"),
                    rs.getString("source_payload_hash"),
                    rs.getObject("duration_seconds", Double.class),
                    rs.getObject("max_speed_mps", Double.class),
                    rs.getObject("total_distance_m", Double.class),
                    rs.getObject("total_calories_kcal", Double.class),
                    rs.getObject("average_speed_mps", Double.class),
                    rs.getObject("high_intensity_distance_m", Double.class),
                    rs.getObject("acceleration_count", Integer.class),
                    rs.getObject("deceleration_count", Integer.class),
                    rs.getObject("direction_change_count", Integer.class),
                    rs.getObject("average_heart_rate_bpm", Double.class),
                    rs.getObject("max_heart_rate_bpm", Double.class),
                    rs.getObject("trimp_edwards", Double.class),
                    rs.getObject("hr_max_reference_bpm", Double.class),
                    rs.getObject("valid_hr_duration_seconds", Double.class),
                    rs.getObject("valid_gps_duration_seconds", Double.class),
                    nullableDateTime(rs, "calculated_at"),
                    rs.getTimestamp("updated_at").toLocalDateTime()
            );

    public QuarterSummaryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveProcessing(
            UUID quarterId, UUID workoutPayloadId, String calculationVersion,
            String calculationParameters, String metricStatus,
            String sourcePayloadHash, LocalDateTime updatedAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO quarter_summaries (
                    quarter_id, workout_payload_id, calculation_status,
                    calculation_version, calculation_parameters, metric_status,
                    source_payload_hash, updated_at
                ) VALUES (?, ?, 'PROCESSING', ?, ?, ?, ?, ?)
                """,
                uuidToBytes(quarterId), uuidToBytes(workoutPayloadId),
                calculationVersion, calculationParameters, metricStatus,
                sourcePayloadHash, updatedAt
        );
    }

    public Optional<QuarterSummary> findByQuarterId(UUID quarterId) {
        return jdbcTemplate.query(
                "SELECT * FROM quarter_summaries WHERE quarter_id = ?",
                rowMapper, uuidToBytes(quarterId)
        ).stream().findFirst();
    }

    public int updateCalculated(QuarterSummary summary, String expectedSourceHash) {
        return jdbcTemplate.update(
                """
                UPDATE quarter_summaries SET
                    calculation_status = ?, calculation_version = ?,
                    calculation_parameters = ?, metric_status = ?,
                    duration_seconds = ?, max_speed_mps = ?, total_distance_m = ?,
                    total_calories_kcal = ?, average_speed_mps = ?,
                    high_intensity_distance_m = ?, acceleration_count = ?,
                    deceleration_count = ?, direction_change_count = ?,
                    average_heart_rate_bpm = ?, max_heart_rate_bpm = ?,
                    trimp_edwards = ?, hr_max_reference_bpm = ?,
                    valid_hr_duration_seconds = ?, valid_gps_duration_seconds = ?,
                    calculated_at = ?, updated_at = ?
                WHERE quarter_id = ? AND workout_payload_id = ?
                  AND source_payload_hash = ?
                """,
                summary.calculationStatus(), summary.calculationVersion(),
                summary.calculationParameters(), summary.metricStatus(),
                summary.durationSeconds(), summary.maxSpeedMps(),
                summary.totalDistanceM(), summary.totalCaloriesKcal(),
                summary.averageSpeedMps(), summary.highIntensityDistanceM(),
                summary.accelerationCount(), summary.decelerationCount(),
                summary.directionChangeCount(), summary.averageHeartRateBpm(),
                summary.maxHeartRateBpm(), summary.trimpEdwards(),
                summary.hrMaxReferenceBpm(), summary.validHrDurationSeconds(),
                summary.validGpsDurationSeconds(), summary.calculatedAt(),
                summary.updatedAt(), uuidToBytes(summary.quarterId()),
                uuidToBytes(summary.workoutPayloadId()), expectedSourceHash
        );
    }

    public int markFailed(UUID quarterId, String expectedSourceHash, LocalDateTime updatedAt) {
        return jdbcTemplate.update(
                """
                UPDATE quarter_summaries
                SET calculation_status = 'FAILED', updated_at = ?
                WHERE quarter_id = ? AND source_payload_hash = ?
                """,
                updatedAt, uuidToBytes(quarterId), expectedSourceHash
        );
    }
}
