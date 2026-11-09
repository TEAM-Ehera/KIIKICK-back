package com.teamehera.kiikick.workout.repository;

import com.teamehera.kiikick.workout.domain.HeartRateSample;
import com.teamehera.kiikick.workout.domain.TrackPoint;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class WorkoutPayloadRepository {

    private static final int BATCH_SIZE = 500;
    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final RowMapper<WorkoutPayload> rowMapper = (rs, rowNum) -> new WorkoutPayload(
            bytesToUuid(rs.getBytes("id")),
            bytesToUuid(rs.getBytes("quarter_id")),
            bytesToUuid(rs.getBytes("user_id")),
            rs.getTimestamp("start_date").toLocalDateTime(),
            rs.getTimestamp("end_date").toLocalDateTime(),
            rs.getDouble("total_calories"),
            rs.getDouble("total_distance"),
            rs.getString("events_json"),
            rs.getString("field_snapshot")
    );

    public WorkoutPayloadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    public void save(WorkoutPayload workoutPayload) {
        if (workoutPayload.quarterId() == null) {
            throw new IllegalArgumentException("quarterId is required by the current ERD");
        }
        jdbcTemplate.update(
                """
                INSERT INTO workout_payloads (
                    id, quarter_id, user_id, start_date, end_date,
                    total_calories, total_distance, events_json, field_snapshot
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                uuidToBytes(workoutPayload.id()), uuidToBytes(workoutPayload.quarterId()),
                uuidToBytes(workoutPayload.userId()), workoutPayload.startDate(),
                workoutPayload.endDate(), workoutPayload.totalCalories(),
                workoutPayload.totalDistance(), workoutPayload.eventsJson(), workoutPayload.fieldJson()
        );
    }

    public Optional<WorkoutPayload> findById(UUID id) {
        return jdbcTemplate.query(
                "SELECT * FROM workout_payloads WHERE id = ?",
                rowMapper, uuidToBytes(id)
        ).stream().findFirst();
    }

    public List<WorkoutPayload> findByQuarterIds(List<UUID> quarterIds) {
        return namedJdbc.query(
                "SELECT * FROM workout_payloads WHERE quarter_id IN (:quarterIds)",
                Map.of("quarterIds", quarterIds.stream().map(id -> uuidToBytes(id)).toList()), rowMapper
        );
    }

    public Optional<WorkoutPayload> findByQuarterId(UUID quarterId) {
        return jdbcTemplate.query(
                "SELECT * FROM workout_payloads WHERE quarter_id = ?",
                rowMapper, uuidToBytes(quarterId)
        ).stream().findFirst();
    }

    /* Existing API compatibility until its path supplies quarterId. */
    public void saveWorkoutPayload(WorkoutPayload workoutPayload) {
        if (workoutPayload.quarterId() != null) {
            save(workoutPayload);
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO workoutPayloads (
                    id, userId, startDate, endDate, totalCalories, totalDistance, events_json, field_snapshot
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                legacyUuidToBytes(workoutPayload.id()),
                legacyUuidToBytes(workoutPayload.userId()), workoutPayload.startDate(),
                workoutPayload.endDate(), workoutPayload.totalCalories(),
                workoutPayload.totalDistance(), workoutPayload.eventsJson(), workoutPayload.fieldJson()
        );
    }

    public void saveTrackPoints(List<TrackPoint> trackPoints) {
        if (trackPoints.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO trackPoints (
                    workoutPayloadId, sampleSeq, latitude, longitude, altitude,
                    horizontalAccuracy, speed, course, timestamp
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                trackPoints, BATCH_SIZE,
                (statement, point) -> {
                    statement.setBytes(1, legacyUuidToBytes(point.workoutPayloadId()));
                    statement.setInt(2, point.sampleSeq());
                    statement.setDouble(3, point.latitude());
                    statement.setDouble(4, point.longitude());
                    statement.setDouble(5, point.altitude());
                    statement.setDouble(6, point.horizontalAccuracy());
                    statement.setDouble(7, point.speed());
                    statement.setDouble(8, point.course());
                    statement.setObject(9, point.timestamp());
                }
        );
    }

    public void saveHeartRateSamples(List<HeartRateSample> samples) {
        if (samples.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO heartRateSamples (
                    workoutPayloadId, sampleSeq, timestamp, bpm
                ) VALUES (?, ?, ?, ?)
                """,
                samples, BATCH_SIZE,
                (statement, sample) -> {
                    statement.setBytes(1, legacyUuidToBytes(sample.workoutPayloadId()));
                    statement.setInt(2, sample.sampleSeq());
                    statement.setObject(3, sample.timestamp());
                    statement.setDouble(4, sample.bpm());
                }
        );
    }

    private static byte[] legacyUuidToBytes(UUID uuid) {
        return ByteBuffer.allocate(16)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }
}
