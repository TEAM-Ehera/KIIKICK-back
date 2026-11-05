package com.teamehera.kiikick.workout.service;

import com.teamehera.kiikick.workout.domain.StoredHeartRateSample;
import com.teamehera.kiikick.workout.domain.StoredTrackPoint;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MovementSummaryTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 4, 0, 0);
    private static final UUID ID = UUID.randomUUID();
    private static final WorkoutPayload WORKOUT = new WorkoutPayload(ID, ID, START, START.plusSeconds(20), 0, 100);

    @Test
    void countsContinuousAccelerationOnceAndBreaksAtGpsGaps() {
        var result = calculate(List.of(point(0, 1, 0), point(500, 2, 0), point(500, 3, 0),
                point(1000, 4, 0), point(2000, 6, 0), point(3000, 3, 0), point(9000, 12, 0),
                point(10000, 10, 0)));
        assertEquals(1, result.accelerations());
        assertEquals(2, result.decelerations());
        assertEquals(12.0, result.maxSpeed());
        assertEquals(4.0, result.validSeconds());
        assertEquals(20.0, result.elapsedSeconds());
        assertEquals(5.0, result.averageSpeed());
    }

    @Test
    void handlesWrappedAnglesAndRequiresMovementAfterTurn() {
        var result = calculate(List.of(point(0, 2, 359), point(1000, 2, 1), point(1500, 2, 60),
                point(2000, 2, 120), point(2500, 2, 180), point(3000, 2, 180),
                point(4000, 0.5, 260), point(5000, 0.5, 20), point(6000, 2, 20),
                point(7000, 2, 100)));
        assertEquals(1, result.turns());
    }

    @Test
    void integratesOnlyOverlappingHighIntensityAndMovingSpeed() {
        var heart = HeartRateSummary.calculate(List.of(heart(0, 140), heart(4000, 180)), 200.0);
        var result = MovementSummary.calculate(WORKOUT,
                List.of(point(0, 0, 0), point(4000, 4, 0)), heart);
        assertEquals(6.0, result.highIntensityDistance(), 1e-10);

        var lowHeart = HeartRateSummary.calculate(List.of(heart(0, 100), heart(4000, 120)), 200.0);
        assertEquals(0.0, MovementSummary.calculate(WORKOUT,
                List.of(point(0, 2, 0), point(4000, 2, 0)), lowHeart).highIntensityDistance());
        assertNull(MovementSummary.calculate(WORKOUT,
                List.of(point(5000, 2, 0), point(6000, 2, 0)), lowHeart).highIntensityDistance());
        assertNull(calculate(List.of(point(0, 2, 0), point(1000, 2, 0))).highIntensityDistance());
    }

    @Test
    void rejectsInvalidSpeedAccuracyAndCourseWithoutTurningMissingDataIntoZero() {
        var result = calculate(List.of(point(0, -1, 0), point(1000, 13, 0),
                new StoredTrackPoint(0, ID, 0, 0, 0, 0, 21, 2, 0, START.plusSeconds(2)),
                point(3000, 2, -1), point(4000, 2, -1)));
        assertEquals(2.0, result.maxSpeed());
        assertEquals(1.0, result.validSeconds());
        assertEquals(0, result.accelerations());
        assertNull(result.turns());
        assertNull(calculate(List.of(point(0, -1, 0))).validSeconds());
    }

    private static MovementSummary.Result calculate(List<StoredTrackPoint> points) {
        return MovementSummary.calculate(WORKOUT, points, HeartRateSummary.calculate(List.of(), null));
    }

    private static StoredTrackPoint point(int milliseconds, double speed, double course) {
        return new StoredTrackPoint(0, ID, 0, 0, 0, 0, 20, speed, course, START.plusNanos(milliseconds * 1_000_000L));
    }

    private static StoredHeartRateSample heart(int milliseconds, double bpm) {
        return new StoredHeartRateSample(0, ID, 0, START.plusNanos(milliseconds * 1_000_000L), bpm);
    }
}
