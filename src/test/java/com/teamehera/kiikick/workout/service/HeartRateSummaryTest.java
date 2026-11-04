package com.teamehera.kiikick.workout.service;

import com.teamehera.kiikick.workout.domain.StoredHeartRateSample;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HeartRateSummaryTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 4, 0, 0);
    private static final UUID WORKOUT = UUID.randomUUID();

    @Test
    void weightsIrregularIntervalsAndDoesNotBridgeInvalidOrLongGaps() {
        var samples = List.of(sample(0, 100), sample(2, 250), sample(2, 120), sample(2, 251),
                sample(10, 160), sample(21, 200), sample(22, 29), sample(23, 180));
        var result = HeartRateSummary.calculate(samples, null);

        assertEquals(134.0, result.averageBpm());
        assertEquals(200.0, result.maxBpm());
        assertEquals(10.0, result.validSeconds());
        assertNull(result.trimp());
        assertNull(result.referenceBpm());
    }

    @Test
    void splitsLinearIntervalsAcrossZonesAndExcludesAboveReference() {
        // Over six seconds, each 20 bpm zone occupies one second in either direction.
        var result = HeartRateSummary.calculate(
                List.of(sample(0, 90), sample(12, 210)), 200.0);
        assertNull(result.trimp()); // 12 seconds exceeds the allowed gap.
        var rising = HeartRateSummary.calculate(List.of(sample(0, 90), sample(6, 210)), 200.0);
        var falling = HeartRateSummary.calculate(List.of(sample(0, 210), sample(6, 90)), 200.0);
        assertEquals(15.0 / 60, rising.trimp(), 1e-10);
        assertEquals(rising.trimp(), falling.trimp(), 1e-10);
        assertEquals(150.0, rising.averageBpm());
        assertEquals(210.0, rising.maxBpm());
    }

    @Test
    void assignsConstantBoundaryValuesToTheHigherZoneAndIncludesExactlyHrMax() {
        double[] values = {99, 100, 120, 140, 160, 180, 200};
        int[] weights = {0, 1, 2, 3, 4, 5, 5};
        for (int i = 0; i < values.length; i++) {
            var result = HeartRateSummary.calculate(List.of(sample(0, values[i]), sample(10, values[i])), 200.0);
            assertEquals(weights[i] * 10.0 / 60, result.trimp(), 1e-10);
        }
        assertNull(HeartRateSummary.calculate(List.of(sample(0, 201), sample(10, 201)), 200.0).trimp());
    }

    @Test
    void isolatedValidSampleHasMaximumButNoIntervalMetrics() {
        var result = HeartRateSummary.calculate(List.of(sample(0, 30), sample(2, Double.NaN)), 180.0);
        assertEquals(30.0, result.maxBpm());
        assertNull(result.averageBpm());
        assertNull(result.validSeconds());
        assertNull(result.trimp());
    }

    private static StoredHeartRateSample sample(int seconds, double bpm) {
        return new StoredHeartRateSample(0, WORKOUT, 0, START.plusSeconds(seconds), bpm);
    }
}
