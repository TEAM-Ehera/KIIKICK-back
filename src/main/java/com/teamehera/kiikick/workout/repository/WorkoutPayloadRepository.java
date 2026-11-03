package com.teamehera.kiikick.workout.repository;

import com.teamehera.kiikick.workout.domain.HeartRateSample;
import com.teamehera.kiikick.workout.domain.TrackPoint;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

@Repository
public class WorkoutPayloadRepository {

    private static final int BATCH_SIZE = 500;
    private final JdbcTemplate jdbcTemplate;
    public WorkoutPayloadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveWorkoutPayload(WorkoutPayload workoutPayload) {

        String sql = """
                INSERT INTO workoutPayloads (
                    id,
                    userId,
                    startDate,
                    endDate,
                    totalCalories,
                    totalDistance
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        jdbcTemplate.update(
                sql,
                uuidToBytes(workoutPayload.id()),
                uuidToBytes(workoutPayload.userId()),
                workoutPayload.startDate(),
                workoutPayload.endDate(),
                workoutPayload.totalCalories(),
                workoutPayload.totalDistance()
        );
    }

    public void saveTrackPoints(List<TrackPoint> trackPoints) {

        if (trackPoints.isEmpty()) {
            return;
        }

        String sql = """
                INSERT INTO trackPoints (
                    workoutPayloadId,
                    sampleSeq,
                    latitude,
                    longitude,
                    altitude,
                    horizontalAccuracy,
                    speed,
                    course,
                    timestamp
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        jdbcTemplate.batchUpdate(
                sql,
                trackPoints,
                BATCH_SIZE,
                (preparedStatement, trackPoint) -> {
                    preparedStatement.setBytes(
                            1,
                            uuidToBytes(trackPoint.workoutPayloadId())
                    );
                    preparedStatement.setInt(2, trackPoint.sampleSeq());
                    preparedStatement.setDouble(3, trackPoint.latitude());
                    preparedStatement.setDouble(4, trackPoint.longitude());
                    preparedStatement.setDouble(5, trackPoint.altitude());
                    preparedStatement.setDouble(
                            6,
                            trackPoint.horizontalAccuracy()
                    );
                    preparedStatement.setDouble(7, trackPoint.speed());
                    preparedStatement.setDouble(8, trackPoint.course());

                    preparedStatement.setObject(
                            9,
                            trackPoint.timestamp()
                    );
                }
        );
    }

    public void saveHeartRateSamples(
            List<HeartRateSample> heartRateSamples
    ) {

        if (heartRateSamples.isEmpty()) {
            return;
        }

        String sql = """
                INSERT INTO heartRateSamples (
                    workoutPayloadId,
                    sampleSeq,
                    timestamp,
                    bpm
                )
                VALUES (?, ?, ?, ?)
                """;

        jdbcTemplate.batchUpdate(
                sql,
                heartRateSamples,
                BATCH_SIZE,
                (preparedStatement, heartRateSample) -> {
                    preparedStatement.setBytes(
                            1,
                            uuidToBytes(
                                    heartRateSample.workoutPayloadId()
                            )
                    );
                    preparedStatement.setInt(
                            2,
                            heartRateSample.sampleSeq()
                    );
                    preparedStatement.setObject(
                            3,
                            heartRateSample.timestamp()
                    );
                    preparedStatement.setDouble(
                            4,
                            heartRateSample.bpm()
                    );
                }
        );
    }

    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);

        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());

        return buffer.array();
    }
}