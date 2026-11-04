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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        UUID calculationJobId = summaryRepository.saveProcessing(
                quarterId, workoutId, "v1", "{}", "{}", "hash", now
        );

        QuarterSummary processing = summaryRepository.findByQuarterId(quarterId).orElseThrow();
        QuarterSummary ready = new QuarterSummary(
                quarterId, workoutId, calculationJobId,
                "READY", "v1", "{}", "{}", "hash",
                1800.0, 7.2, 3182.4, 420.5, 1.76, null,
                3, 2, 4, 143.5, 171.0, 55.0, 190.0,
                1700.0, 1750.0, now.plusMinutes(31), now.plusMinutes(31)
        );

        assertEquals(1, summaryRepository.updateCalculated(ready, "hash"));
        assertAll(
                () -> assertEquals(quarterId, workoutRepository.findByQuarterId(quarterId).orElseThrow().quarterId()),
                () -> assertEquals(2, trackPointRepository.findPage(workoutId, 0, 10).size()),
                () -> assertEquals(2, heartRateRepository.findAllForCalculation(workoutId).size()),
                () -> assertEquals(calculationJobId, processing.calculationJobId()),
                () -> assertEquals("PROCESSING", processing.calculationStatus()),
                () -> assertEquals("READY", summaryRepository.findByQuarterId(quarterId).orElseThrow().calculationStatus())
        );
    }

    @Test
    void 이전_작업이_늦게_완료되어도_최신_결과를_덮어쓰지_않는다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 2, 0);
        SummaryFixture fixture = createSummaryFixture(now);
        UUID oldJobId = summaryRepository.saveProcessing(
                fixture.quarterId(), fixture.workoutId(), "v1", "{}", "{}", "hash", now
        );
        UUID latestJobId = summaryRepository.saveProcessing(
                fixture.quarterId(), fixture.workoutId(), "v2", "{}", "{}", "hash", now.plusSeconds(1)
        );

        assertEquals(0, summaryRepository.updateCalculated(
                readySummary(fixture, oldJobId, "v1", 100.0, now.plusSeconds(3)), "hash"
        ));
        assertEquals(1, summaryRepository.updateCalculated(
                readySummary(fixture, latestJobId, "v2", 200.0, now.plusSeconds(2)), "hash"
        ));

        QuarterSummary saved = summaryRepository.findByQuarterId(fixture.quarterId()).orElseThrow();
        assertAll(
                () -> assertEquals(latestJobId, saved.calculationJobId()),
                () -> assertEquals("v2", saved.calculationVersion()),
                () -> assertEquals(200.0, saved.totalDistanceM())
        );
    }

    @Test
    void 이전_작업의_실패가_최신_작업을_실패로_바꾸지_않는다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 2, 0);
        SummaryFixture fixture = createSummaryFixture(now);
        UUID oldJobId = summaryRepository.saveProcessing(
                fixture.quarterId(), fixture.workoutId(), "v1", "{}", "{}", "hash", now
        );
        UUID latestJobId = summaryRepository.saveProcessing(
                fixture.quarterId(), fixture.workoutId(), "v2", "{}", "{}", "hash", now.plusSeconds(1)
        );

        assertEquals(0, summaryRepository.markFailed(
                fixture.quarterId(), "hash", oldJobId, now.plusSeconds(2)
        ));
        assertEquals("PROCESSING", summaryRepository.findByQuarterId(fixture.quarterId())
                .orElseThrow().calculationStatus());
        assertEquals(1, summaryRepository.markFailed(
                fixture.quarterId(), "hash", latestJobId, now.plusSeconds(3)
        ));
        assertEquals("FAILED", summaryRepository.findByQuarterId(fixture.quarterId())
                .orElseThrow().calculationStatus());
    }

    @Test
    void 같은_원본과_버전으로_재계산해도_이전_작업을_거절한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 2, 0);
        SummaryFixture fixture = createSummaryFixture(now);
        UUID oldJobId = summaryRepository.saveProcessing(
                fixture.quarterId(), fixture.workoutId(), "v1", "{}", "{}", "hash", now
        );
        UUID latestJobId = summaryRepository.saveProcessing(
                fixture.quarterId(), fixture.workoutId(), "v1", "{}", "{}", "hash", now.plusSeconds(1)
        );

        assertNotEquals(oldJobId, latestJobId);
        assertEquals(0, summaryRepository.updateCalculated(
                readySummary(fixture, oldJobId, "v1", 100.0, now.plusSeconds(2)), "hash"
        ));
        assertEquals(1, summaryRepository.updateCalculated(
                readySummary(fixture, latestJobId, "v1", 200.0, now.plusSeconds(3)), "hash"
        ));
    }

    @Test
    void 정상적인_PROCESSING_FAILED_전이만_허용한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 2, 0);
        SummaryFixture fixture = createSummaryFixture(now);
        UUID calculationJobId = summaryRepository.saveProcessing(
                fixture.quarterId(), fixture.workoutId(), "v1", "{}", "{}", "hash", now
        );

        assertEquals(1, summaryRepository.markFailed(
                fixture.quarterId(), "hash", calculationJobId, now.plusSeconds(1)
        ));
        assertEquals("FAILED", summaryRepository.findByQuarterId(fixture.quarterId())
                .orElseThrow().calculationStatus());
    }

    @Test
    void 완료된_작업의_중복_완료와_실패를_거절한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 2, 0);
        SummaryFixture fixture = createSummaryFixture(now);
        UUID calculationJobId = summaryRepository.saveProcessing(
                fixture.quarterId(), fixture.workoutId(), "v1", "{}", "{}", "hash", now
        );
        QuarterSummary ready = readySummary(
                fixture, calculationJobId, "v1", 100.0, now.plusSeconds(1)
        );

        assertEquals(1, summaryRepository.updateCalculated(ready, "hash"));
        assertEquals(0, summaryRepository.updateCalculated(ready, "hash"));
        assertEquals(0, summaryRepository.markFailed(
                fixture.quarterId(), "hash", calculationJobId, now.plusSeconds(2)
        ));
        assertEquals("READY", summaryRepository.findByQuarterId(fixture.quarterId())
                .orElseThrow().calculationStatus());
    }

    @Test
    void 재계산을_시작하면_이전_결과를_초기화한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 2, 0);
        SummaryFixture fixture = createSummaryFixture(now);
        UUID oldJobId = summaryRepository.saveProcessing(
                fixture.quarterId(), fixture.workoutId(), "v1", "{}", "{}", "hash", now
        );
        assertEquals(1, summaryRepository.updateCalculated(
                readySummary(fixture, oldJobId, "v1", 100.0, now.plusSeconds(1)), "hash"
        ));

        UUID latestJobId = summaryRepository.saveProcessing(
                fixture.quarterId(), fixture.workoutId(), "v2", "{}", "{}", "hash", now.plusSeconds(2)
        );
        QuarterSummary processing = summaryRepository.findByQuarterId(fixture.quarterId()).orElseThrow();

        assertAll(
                () -> assertEquals(latestJobId, processing.calculationJobId()),
                () -> assertEquals("PROCESSING", processing.calculationStatus()),
                () -> assertEquals("v2", processing.calculationVersion()),
                () -> assertNull(processing.durationSeconds()),
                () -> assertNull(processing.totalDistanceM()),
                () -> assertNull(processing.calculatedAt())
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

    private SummaryFixture createSummaryFixture(LocalDateTime now) {
        UUID userId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID quarterId = UUID.randomUUID();
        UUID workoutId = UUID.randomUUID();
        insertParents(userId, matchId, quarterId, now);
        workoutRepository.save(new WorkoutPayload(
                workoutId, quarterId, userId, now, now.plusMinutes(30), 10, 100
        ));
        return new SummaryFixture(quarterId, workoutId);
    }

    private QuarterSummary readySummary(
            SummaryFixture fixture,
            UUID calculationJobId,
            String calculationVersion,
            double totalDistanceM,
            LocalDateTime calculatedAt
    ) {
        return new QuarterSummary(
                fixture.quarterId(), fixture.workoutId(), calculationJobId,
                "READY", calculationVersion, "{}", "{}", "hash",
                1800.0, 7.2, totalDistanceM, 420.5, 1.76, null,
                3, 2, 4, 143.5, 171.0, 55.0, 190.0,
                1700.0, 1750.0, calculatedAt, calculatedAt
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

    private record SummaryFixture(UUID quarterId, UUID workoutId) {
    }
}
