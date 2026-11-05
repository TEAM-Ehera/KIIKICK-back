package com.teamehera.kiikick.workout.repository;

import com.teamehera.kiikick.workout.domain.HeartRateSample;
import com.teamehera.kiikick.workout.domain.StoredHeartRateSample;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class HeartRateSampleRepository {

    private static final int BATCH_SIZE = 500;
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<StoredHeartRateSample> rowMapper = (rs, rowNum) ->
            new StoredHeartRateSample(
                    rs.getLong("id"), bytesToUuid(rs.getBytes("workout_payload_id")),
                    rs.getInt("sample_seq"),
                    rs.getTimestamp("timestamp").toLocalDateTime(), rs.getDouble("bpm")
            );

    public HeartRateSampleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveAll(List<HeartRateSample> samples) {
        if (samples.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO heart_rate_samples (
                    workout_payload_id, sample_seq, timestamp, bpm
                ) VALUES (?, ?, ?, ?)
                """,
                samples, BATCH_SIZE,
                (statement, sample) -> {
                    statement.setBytes(1, uuidToBytes(sample.workoutPayloadId()));
                    statement.setInt(2, sample.sampleSeq());
                    statement.setObject(3, sample.timestamp());
                    statement.setDouble(4, sample.bpm());
                }
        );
    }

    public List<StoredHeartRateSample> findPage(
            UUID workoutPayloadId, long afterId, int limit
    ) {
        return jdbcTemplate.query(
                """
                SELECT * FROM heart_rate_samples
                WHERE workout_payload_id = ? AND id > ?
                ORDER BY id ASC LIMIT ?
                """,
                rowMapper, uuidToBytes(workoutPayloadId), afterId, limit
        );
    }

    public List<StoredHeartRateSample> findPageBySampleSeq(UUID workoutPayloadId, int afterSampleSeq, int limit) {
        return jdbcTemplate.query(
                """
                SELECT * FROM heart_rate_samples
                WHERE workout_payload_id = ? AND sample_seq > ?
                ORDER BY sample_seq ASC LIMIT ?
                """,
                rowMapper, uuidToBytes(workoutPayloadId), afterSampleSeq, limit
        );
    }

    public List<StoredHeartRateSample> findAllForCalculation(UUID workoutPayloadId) {
        return jdbcTemplate.query(
                """
                SELECT * FROM heart_rate_samples WHERE workout_payload_id = ?
                ORDER BY sample_seq ASC
                """,
                rowMapper, uuidToBytes(workoutPayloadId)
        );
    }
}
