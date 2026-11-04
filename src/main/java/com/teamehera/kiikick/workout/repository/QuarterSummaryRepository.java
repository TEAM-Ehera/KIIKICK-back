package com.teamehera.kiikick.workout.repository;

import com.teamehera.kiikick.workout.domain.QuarterSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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

    @Transactional
    public LocalDateTime saveProcessing(
            UUID quarterId, UUID workoutPayloadId, String calculationVersion,
            String calculationParameters, String metricStatus,
            String sourcePayloadHash, LocalDateTime updatedAt
    ) {
        LocalDateTime requestedAttemptAt = toMicroseconds(updatedAt);

        if (findUpdatedAt(quarterId).isPresent()) {
            return restartExistingProcessing(
                    quarterId, workoutPayloadId, calculationVersion,
                    calculationParameters, metricStatus, sourcePayloadHash,
                    requestedAttemptAt
            );
        }

        try {
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
                sourcePayloadHash, requestedAttemptAt
            );
            return requestedAttemptAt;
        } catch (DuplicateKeyException exception) {
            // 동시 최초 INSERT에서 다른 요청이 만든 행을 잠근 뒤 새 시도 시각을 발급한다.
        }

        return restartExistingProcessing(
                quarterId, workoutPayloadId, calculationVersion,
                calculationParameters, metricStatus, sourcePayloadHash,
                requestedAttemptAt
        );
    }

    private LocalDateTime restartExistingProcessing(
            UUID quarterId,
            UUID workoutPayloadId,
            String calculationVersion,
            String calculationParameters,
            String metricStatus,
            String sourcePayloadHash,
            LocalDateTime requestedAttemptAt
    ) {
        LocalDateTime currentUpdatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM quarter_summaries WHERE quarter_id = ? FOR UPDATE",
                LocalDateTime.class,
                uuidToBytes(quarterId)
        );
        LocalDateTime attemptUpdatedAt = nextTimestamp(requestedAttemptAt, currentUpdatedAt);
        int updated = restartProcessing(
                quarterId, workoutPayloadId, calculationVersion,
                calculationParameters, metricStatus, sourcePayloadHash,
                currentUpdatedAt, attemptUpdatedAt
        );
        if (updated != 1) {
            throw new IllegalStateException("계산 시도 식별값 갱신에 실패했습니다.");
        }
        return attemptUpdatedAt;
    }

    private Optional<LocalDateTime> findUpdatedAt(UUID quarterId) {
        return jdbcTemplate.query(
                "SELECT updated_at FROM quarter_summaries WHERE quarter_id = ?",
                (rs, rowNum) -> rs.getTimestamp("updated_at").toLocalDateTime(),
                uuidToBytes(quarterId)
        ).stream().findFirst();
    }

    private int restartProcessing(
            UUID quarterId,
            UUID workoutPayloadId,
            String calculationVersion,
            String calculationParameters,
            String metricStatus,
            String sourcePayloadHash,
            LocalDateTime expectedUpdatedAt,
            LocalDateTime attemptUpdatedAt
    ) {
        // 새 계산이 시작되면 이전 결과를 비워 PROCESSING 결과로 노출되지 않게 한다.
        return jdbcTemplate.update(
                """
                UPDATE quarter_summaries SET
                    workout_payload_id = ?,
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
                WHERE quarter_id = ? AND updated_at = ?
                """,
                uuidToBytes(workoutPayloadId),
                calculationVersion, calculationParameters, metricStatus,
                sourcePayloadHash, attemptUpdatedAt, uuidToBytes(quarterId),
                expectedUpdatedAt
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
            String expectedSourceHash,
            LocalDateTime expectedAttemptUpdatedAt
    ) {
        LocalDateTime attemptUpdatedAt = toMicroseconds(expectedAttemptUpdatedAt);
        LocalDateTime completedUpdatedAt = nextTimestamp(summary.updatedAt(), attemptUpdatedAt);
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
                  AND updated_at = ?
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
                completedUpdatedAt, uuidToBytes(summary.quarterId()),
                uuidToBytes(summary.workoutPayloadId()), expectedSourceHash,
                attemptUpdatedAt
        );
    }

    public int markFailed(
            UUID quarterId,
            String expectedSourceHash,
            LocalDateTime expectedAttemptUpdatedAt,
            LocalDateTime updatedAt
    ) {
        LocalDateTime attemptUpdatedAt = toMicroseconds(expectedAttemptUpdatedAt);
        LocalDateTime failedUpdatedAt = nextTimestamp(updatedAt, attemptUpdatedAt);
        return jdbcTemplate.update(
                """
                UPDATE quarter_summaries
                SET calculation_status = 'FAILED', updated_at = ?
                WHERE quarter_id = ? AND source_payload_hash = ?
                  AND updated_at = ?
                  AND calculation_status = 'PROCESSING'
                """,
                failedUpdatedAt, uuidToBytes(quarterId), expectedSourceHash,
                attemptUpdatedAt
        );
    }

    private static LocalDateTime nextTimestamp(
            LocalDateTime requestedUpdatedAt,
            LocalDateTime currentUpdatedAt
    ) {
        LocalDateTime requested = toMicroseconds(requestedUpdatedAt);
        LocalDateTime current = toMicroseconds(currentUpdatedAt);
        return requested.isAfter(current) ? requested : current.plusNanos(1_000);
    }

    private static LocalDateTime toMicroseconds(LocalDateTime value) {
        return value.truncatedTo(ChronoUnit.MICROS);
    }
}
