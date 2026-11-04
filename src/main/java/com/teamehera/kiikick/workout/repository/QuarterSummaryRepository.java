package com.teamehera.kiikick.workout.repository;

import com.teamehera.kiikick.workout.domain.QuarterSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
                    bytesToUuid(rs.getBytes("calculation_job_id")),
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

    @Transactional
    public UUID saveProcessing(
            UUID quarterId, UUID workoutPayloadId, String calculationVersion,
            String calculationParameters, String metricStatus,
            String sourcePayloadHash, LocalDateTime updatedAt
    ) {
        // 계산을 시작할 때마다 새 작업 ID를 발급해 이전 작업과 구분한다.
        UUID calculationJobId = UUID.randomUUID();
        int updated = restartProcessing(
                quarterId, workoutPayloadId, calculationJobId,
                calculationVersion, calculationParameters, metricStatus,
                sourcePayloadHash, updatedAt
        );

        if (updated > 0) {
            return calculationJobId;
        }

        try {
            jdbcTemplate.update(
                """
                INSERT INTO quarter_summaries (
                    quarter_id, workout_payload_id, calculation_job_id, calculation_status,
                    calculation_version, calculation_parameters, metric_status,
                    source_payload_hash, updated_at
                ) VALUES (?, ?, ?, 'PROCESSING', ?, ?, ?, ?, ?)
                """,
                uuidToBytes(quarterId), uuidToBytes(workoutPayloadId),
                uuidToBytes(calculationJobId),
                calculationVersion, calculationParameters, metricStatus,
                sourcePayloadHash, updatedAt
            );
        } catch (DuplicateKeyException exception) {
            // 최초 행을 동시에 만든 경우, 마지막으로 시작한 작업 ID를 현재 작업으로 만든다.
            restartProcessing(
                    quarterId, workoutPayloadId, calculationJobId,
                    calculationVersion, calculationParameters, metricStatus,
                    sourcePayloadHash, updatedAt
            );
        }
        return calculationJobId;
    }

    private int restartProcessing(
            UUID quarterId,
            UUID workoutPayloadId,
            UUID calculationJobId,
            String calculationVersion,
            String calculationParameters,
            String metricStatus,
            String sourcePayloadHash,
            LocalDateTime updatedAt
    ) {
        // 새 계산이 시작되면 이전 결과를 비워 PROCESSING 결과로 노출되지 않게 한다.
        return jdbcTemplate.update(
                """
                UPDATE quarter_summaries SET
                    workout_payload_id = ?,
                    calculation_job_id = ?,
                    calculation_status = 'PROCESSING',
                    calculation_version = ?,
                    calculation_parameters = ?,
                    metric_status = ?,
                    source_payload_hash = ?,
                    duration_seconds = NULL,
                    max_speed_mps = NULL,
                    total_distance_m = NULL,
                    total_calories_kcal = NULL,
                    average_speed_mps = NULL,
                    high_intensity_distance_m = NULL,
                    acceleration_count = NULL,
                    deceleration_count = NULL,
                    direction_change_count = NULL,
                    average_heart_rate_bpm = NULL,
                    max_heart_rate_bpm = NULL,
                    trimp_edwards = NULL,
                    hr_max_reference_bpm = NULL,
                    valid_hr_duration_seconds = NULL,
                    valid_gps_duration_seconds = NULL,
                    calculated_at = NULL,
                    updated_at = ?
                WHERE quarter_id = ?
                """,
                uuidToBytes(workoutPayloadId), uuidToBytes(calculationJobId),
                calculationVersion, calculationParameters, metricStatus,
                sourcePayloadHash, updatedAt, uuidToBytes(quarterId)
        );
    }

    public Optional<QuarterSummary> findByQuarterId(UUID quarterId) {
        return jdbcTemplate.query(
                "SELECT * FROM quarter_summaries WHERE quarter_id = ?",
                rowMapper, uuidToBytes(quarterId)
        ).stream().findFirst();
    }

    public int updateCalculated(
            QuarterSummary summary,
            String expectedSourceHash
    ) {
        return jdbcTemplate.update(
                """
                UPDATE quarter_summaries SET
                    calculation_status = ?, metric_status = ?,
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
                  AND calculation_job_id = ?
                  AND calculation_status = 'PROCESSING'
                """,
                summary.calculationStatus(), summary.metricStatus(),
                summary.durationSeconds(), summary.maxSpeedMps(),
                summary.totalDistanceM(), summary.totalCaloriesKcal(),
                summary.averageSpeedMps(), summary.highIntensityDistanceM(),
                summary.accelerationCount(), summary.decelerationCount(),
                summary.directionChangeCount(), summary.averageHeartRateBpm(),
                summary.maxHeartRateBpm(), summary.trimpEdwards(),
                summary.hrMaxReferenceBpm(), summary.validHrDurationSeconds(),
                summary.validGpsDurationSeconds(), summary.calculatedAt(),
                summary.updatedAt(), uuidToBytes(summary.quarterId()),
                uuidToBytes(summary.workoutPayloadId()), expectedSourceHash,
                uuidToBytes(summary.calculationJobId())
        );
    }

    public int markFailed(
            UUID quarterId,
            String expectedSourceHash,
            UUID expectedCalculationJobId,
            LocalDateTime updatedAt
    ) {
        return jdbcTemplate.update(
                """
                UPDATE quarter_summaries
                SET calculation_status = 'FAILED', updated_at = ?
                WHERE quarter_id = ? AND source_payload_hash = ?
                  AND calculation_job_id = ?
                  AND calculation_status = 'PROCESSING'
                """,
                updatedAt, uuidToBytes(quarterId), expectedSourceHash,
                uuidToBytes(expectedCalculationJobId)
        );
    }
}
