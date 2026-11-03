package com.teamehera.kiikick.workout.repository;

import com.teamehera.kiikick.workout.domain.StoredTrackPoint;
import com.teamehera.kiikick.workout.domain.TrackPoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class TrackPointRepository {

    private static final int BATCH_SIZE = 500;
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<StoredTrackPoint> rowMapper = (rs, rowNum) ->
            new StoredTrackPoint(
                    rs.getLong("id"), bytesToUuid(rs.getBytes("workout_payload_id")),
                    rs.getInt("sample_seq"), rs.getDouble("latitude"),
                    rs.getDouble("longitude"), rs.getDouble("altitude"),
                    rs.getDouble("horizontal_accuracy"), rs.getDouble("speed"),
                    rs.getDouble("course"), rs.getTimestamp("timestamp").toLocalDateTime()
            );

    public TrackPointRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveAll(List<TrackPoint> points) {
        if (points.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO track_points (
                    workout_payload_id, sample_seq, latitude, longitude, altitude,
                    horizontal_accuracy, speed, course, timestamp
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                points, BATCH_SIZE,
                (statement, point) -> {
                    statement.setBytes(1, uuidToBytes(point.workoutPayloadId()));
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

    public List<StoredTrackPoint> findPage(UUID workoutPayloadId, long afterId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT * FROM track_points
                WHERE workout_payload_id = ? AND id > ?
                ORDER BY id ASC LIMIT ?
                """,
                rowMapper, uuidToBytes(workoutPayloadId), afterId, limit
        );
    }

    public List<StoredTrackPoint> findAllForCalculation(UUID workoutPayloadId) {
        return jdbcTemplate.query(
                """
                SELECT * FROM track_points WHERE workout_payload_id = ?
                ORDER BY sample_seq ASC
                """,
                rowMapper, uuidToBytes(workoutPayloadId)
        );
    }
}
