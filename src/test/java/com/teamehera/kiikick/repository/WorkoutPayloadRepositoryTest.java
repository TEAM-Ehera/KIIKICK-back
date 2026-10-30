package com.teamehera.kiikick.workout.repository;

import com.teamehera.kiikick.workout.domain.HeartRateSample;
import com.teamehera.kiikick.workout.domain.TrackPoint;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/*
 * WorkoutPayloadRepository의 실제 INSERT SQL을 H2 메모리 DB에서 검증한다.
 * 각 테스트는 트랜잭션으로 실행되고 종료 후 자동 롤백된다.
 */
@JdbcTest
@Import(WorkoutPayloadRepository.class)
class WorkoutPayloadRepositoryTest {

    @Autowired
    private WorkoutPayloadRepository workoutPayloadRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 운동_GPS_심박수를_저장한다() {
        UUID userId = UUID.randomUUID();
        UUID workoutPayloadId = UUID.randomUUID();

        insertUser(userId);

        WorkoutPayload workoutPayload = new WorkoutPayload(
                workoutPayloadId,
                userId,
                LocalDateTime.of(2026, 10, 30, 1, 0),
                LocalDateTime.of(2026, 10, 30, 1, 30),
                420.5,
                3182.4
        );

        List<TrackPoint> trackPoints = List.of(
                new TrackPoint(
                        workoutPayloadId,
                        0,
                        37.5665,
                        126.9780,
                        20.1,
                        4.8,
                        2.3,
                        87.5,
                        LocalDateTime.of(
                                2026, 10, 30, 1, 0, 1
                        )
                ),
                new TrackPoint(
                        workoutPayloadId,
                        1,
                        37.5666,
                        126.9781,
                        20.2,
                        5.0,
                        -1.0,
                        -1.0,
                        LocalDateTime.of(
                                2026, 10, 30, 1, 0, 2
                        )
                )
        );

        List<HeartRateSample> heartRateSamples = List.of(
                new HeartRateSample(
                        workoutPayloadId,
                        0,
                        LocalDateTime.of(
                                2026, 10, 30, 1, 0, 1
                        ),
                        142.0
                ),
                new HeartRateSample(
                        workoutPayloadId,
                        1,
                        LocalDateTime.of(
                                2026, 10, 30, 1, 0, 6
                        ),
                        145.0
                )
        );

        workoutPayloadRepository.saveWorkoutPayload(workoutPayload);
        workoutPayloadRepository.saveTrackPoints(trackPoints);
        workoutPayloadRepository.saveHeartRateSamples(
                heartRateSamples
        );

        Long workoutCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM workoutPayloads
                WHERE id = ?
                  AND userId = ?
                """,
                Long.class,
                uuidToBytes(workoutPayloadId),
                uuidToBytes(userId)
        );

        Long trackPointCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM trackPoints
                WHERE workoutPayloadId = ?
                """,
                Long.class,
                uuidToBytes(workoutPayloadId)
        );

        Long heartRateCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM heartRateSamples
                WHERE workoutPayloadId = ?
                """,
                Long.class,
                uuidToBytes(workoutPayloadId)
        );

        Double invalidSpeed = jdbcTemplate.queryForObject(
                """
                SELECT speed
                FROM trackPoints
                WHERE workoutPayloadId = ?
                  AND sampleSeq = 1
                """,
                Double.class,
                uuidToBytes(workoutPayloadId)
        );

        assertAll(
                () -> assertEquals(1L, workoutCount),
                () -> assertEquals(2L, trackPointCount),
                () -> assertEquals(2L, heartRateCount),
                () -> assertEquals(-1.0, invalidSpeed)
        );
    }

    /*
     * workoutPayloads.userId가 users.id를 참조하므로
     * 부모 사용자를 먼저 준비한다.
     */
    private void insertUser(UUID userId) {
        jdbcTemplate.update(
                """
                INSERT INTO users (
                    id,
                    nickname,
                    createdAt,
                    updatedAt
                )
                VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                uuidToBytes(userId),
                "테스트사용자"
        );
    }

    // 실제 Repository와 동일한 UUID → BINARY(16) 변환 방식
    private static byte[] uuidToBytes(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);

        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());

        return buffer.array();
    }
}