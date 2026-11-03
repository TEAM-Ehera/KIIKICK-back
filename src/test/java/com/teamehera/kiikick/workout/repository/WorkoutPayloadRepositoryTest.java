package com.teamehera.kiikick.workout.repository;

import com.teamehera.kiikick.workout.domain.HeartRateSample;
import com.teamehera.kiikick.workout.domain.QuarterSummary;
import com.teamehera.kiikick.workout.domain.TrackPoint;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@JdbcTest
@Import({
        WorkoutPayloadRepository.class,
        TrackPointRepository.class,
        HeartRateSampleRepository.class,
        QuarterSummaryRepository.class
})
class WorkoutPayloadRepositoryTest {

    @Autowired WorkoutPayloadRepository workoutRepository;
    @Autowired TrackPointRepository trackPointRepository;
    @Autowired HeartRateSampleRepository heartRateRepository;
    @Autowired QuarterSummaryRepository summaryRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 운동원본과_샘플과_요약을_저장하고_조회한다() {
        UUID userId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID quarterId = UUID.randomUUID();
        UUID workoutId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 1, 0);
        insertParents(userId, matchId, quarterId, now);

        WorkoutPayload workout = new WorkoutPayload(
                workoutId, quarterId, userId, now, now.plusMinutes(30), 420.5, 3182.4
        );
        workoutRepository.save(workout);
        trackPointRepository.saveAll(List.of(
                new TrackPoint(workoutId, 0, 37.5, 126.9, 20, 4, 2.3, 87, now),
                new TrackPoint(workoutId, 1, 37.6, 127.0, 21, 5, -1, -1, now.plusSeconds(1))
        ));
        heartRateRepository.saveAll(List.of(
                new HeartRateSample(workoutId, 0, now, 142),
                new HeartRateSample(workoutId, 1, now.plusSeconds(5), 145)
        ));
        summaryRepository.saveProcessing(
                quarterId, workoutId, "v1", "{}", "{}", "hash", now
        );

        QuarterSummary processing = summaryRepository.findByQuarterId(quarterId).orElseThrow();
        QuarterSummary ready = new QuarterSummary(
                quarterId, workoutId, "READY", "v1", "{}", "{}", "hash",
                1800.0, 7.2, 3182.4, 420.5, 1.76, null,
                3, 2, 4, 143.5, 171.0, 55.0, 190.0,
                1700.0, 1750.0, now.plusMinutes(31), now.plusMinutes(31)
        );

        assertEquals(1, summaryRepository.updateCalculated(ready, "hash"));
        assertAll(
                () -> assertEquals(quarterId, workoutRepository.findByQuarterId(quarterId).orElseThrow().quarterId()),
                () -> assertEquals(2, trackPointRepository.findPage(workoutId, 0, 10).size()),
                () -> assertEquals(2, heartRateRepository.findAllForCalculation(workoutId).size()),
                () -> assertEquals("PROCESSING", processing.calculationStatus()),
                () -> assertEquals("READY", summaryRepository.findByQuarterId(quarterId).orElseThrow().calculationStatus())
        );
    }

    @Test
    void 쿼터당_운동원본은_하나만_저장한다() {
        UUID userId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID quarterId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        insertParents(userId, matchId, quarterId, now);

        workoutRepository.save(new WorkoutPayload(
                UUID.randomUUID(), quarterId, userId, now, now.plusMinutes(1), 1, 1
        ));

        assertThrows(DataIntegrityViolationException.class, () ->
                workoutRepository.save(new WorkoutPayload(
                        UUID.randomUUID(), quarterId, userId,
                        now, now.plusMinutes(2), 2, 2
                ))
        );
    }

    @Test
    void 같은_운동의_sampleSeq_중복은_거절한다() {
        UUID userId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID quarterId = UUID.randomUUID();
        UUID workoutId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        insertParents(userId, matchId, quarterId, now);
        workoutRepository.save(new WorkoutPayload(
                workoutId, quarterId, userId, now, now.plusMinutes(1), 1, 1
        ));
        TrackPoint point = new TrackPoint(
                workoutId, 0, 37.5, 126.9, 0, 1, 1, 1, now
        );
        trackPointRepository.saveAll(List.of(point));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> trackPointRepository.saveAll(List.of(point))
        );
    }

    private void insertParents(UUID userId, UUID matchId, UUID quarterId, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO users (id, created_at, updated_at) VALUES (?, ?, ?)",
                uuidToBytes(userId), now, now
        );
        jdbcTemplate.update(
                """
                INSERT INTO matches (id, owner_id, status, version, created_at, updated_at)
                VALUES (?, ?, 'IN_PROGRESS', 1, ?, ?)
                """,
                uuidToBytes(matchId), uuidToBytes(userId), now, now
        );
        jdbcTemplate.update(
                """
                INSERT INTO quarters (
                    id, match_id, number, duration_seconds, status, created_at, updated_at
                ) VALUES (?, ?, 1, 1200, 'COMPLETED', ?, ?)
                """,
                uuidToBytes(quarterId), uuidToBytes(matchId), now, now
        );
    }
}
