package com.teamehera.kiikick.workout.service;

import com.teamehera.kiikick.workout.domain.StoredHeartRateSample;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

final class HeartRateSummary {
    static final int MIN_BPM = 30;
    static final int MAX_BPM = 250;
    static final int MAX_GAP_SECONDS = 10;
    private static final double[] ZONE_BOUNDARIES = {0.5, 0.6, 0.7, 0.8, 0.9, 1.0};

    private HeartRateSummary() {}

    static Result calculate(List<StoredHeartRateSample> samples, Double referenceBpm) {
        var totals = new Totals(referenceBpm);
        Sample previous = null;
        Sample current = null;
        for (var sample : samples) {
            double bpm = sample.bpm();
            boolean valid = Double.isFinite(bpm) && bpm >= MIN_BPM && bpm <= MAX_BPM;
            if (current == null || !current.timestamp().equals(sample.timestamp())) {
                totals.accept(previous, current);
                previous = current;
                current = new Sample(sample.timestamp(), null);
            }
            if (valid) {
                current = new Sample(sample.timestamp(), bpm);
            }
        }
        totals.accept(previous, current);
        return totals.result();
    }

    private record Sample(LocalDateTime timestamp, Double bpm) {}

    record Result(Double averageBpm, Double maxBpm, Double validSeconds, Double trimp, Double referenceBpm) {}

    private static final class Totals {
        private final Double referenceBpm;
        private Double maxBpm;
        private double seconds;
        private double bpmSeconds;
        private double eligibleSeconds;
        private double weightedSeconds;

        private Totals(Double referenceBpm) {
            this.referenceBpm = referenceBpm;
        }

        private void accept(Sample previous, Sample current) {
            if (current == null || current.bpm() == null) {
                return;
            }
            double end = current.bpm();
            maxBpm = maxBpm == null ? end : Math.max(maxBpm, end);
            if (previous == null || previous.bpm() == null) {
                return;
            }
            var interval = Duration.between(previous.timestamp(), current.timestamp());
            if (interval.isNegative() || interval.isZero() || interval.compareTo(Duration.ofSeconds(MAX_GAP_SECONDS)) > 0) {
                return;
            }
            double elapsed = interval.toNanos() / 1_000_000_000.0;
            double start = previous.bpm();
            seconds += elapsed;
            bpmSeconds += elapsed * (start + end) / 2;
            if (referenceBpm != null) {
                accumulateZones(Math.min(start, end), Math.max(start, end), elapsed);
            }
        }

        private void accumulateZones(double low, double high, double elapsed) {
            if (low > referenceBpm) {
                return;
            }
            // For a linear interval, the fraction of its BPM range inside a zone equals its time fraction.
            if (low != high) {
                eligibleSeconds += elapsed * (Math.min(high, referenceBpm) - low) / (high - low);
                for (int zone = 0; zone < ZONE_BOUNDARIES.length - 1; zone++) {
                    double overlap = Math.min(high, referenceBpm * ZONE_BOUNDARIES[zone + 1])
                            - Math.max(low, referenceBpm * ZONE_BOUNDARIES[zone]);
                    weightedSeconds += elapsed * Math.max(0, overlap) / (high - low) * (zone + 1);
                }
                return;
            }
            eligibleSeconds += elapsed;
            for (int zone = ZONE_BOUNDARIES.length - 2; zone >= 0; zone--) {
                if (low >= referenceBpm * ZONE_BOUNDARIES[zone]) {
                    weightedSeconds += elapsed * (zone + 1);
                    break;
                }
            }
        }

        private Result result() {
            return new Result(seconds > 0 ? bpmSeconds / seconds : null, maxBpm,
                    seconds > 0 ? seconds : null, eligibleSeconds > 0 ? weightedSeconds / 60 : null, referenceBpm);
        }
    }
}
