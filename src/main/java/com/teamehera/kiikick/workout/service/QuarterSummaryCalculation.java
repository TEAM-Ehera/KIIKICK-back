package com.teamehera.kiikick.workout.service;

import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.workout.domain.QuarterSummary;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import com.teamehera.kiikick.workout.repository.QuarterSummaryRepository;
import com.teamehera.kiikick.workout.repository.QuarterSummaryRepository.PendingCalculation;
import com.teamehera.kiikick.workout.repository.WorkoutPayloadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

@Service
public class QuarterSummaryCalculation {
    private static final Set<String> BASIC_METRICS = Set.of("totalDistanceM", "totalCaloriesKcal");
    private static final List<String> METRICS = List.of(
            "durationSeconds", "maxSpeedMps", "totalDistanceM", "totalCaloriesKcal", "averageSpeedMps",
            "highIntensityDistanceM", "accelerationCount", "decelerationCount", "directionChangeCount",
            "averageHeartRateBpm", "maxHeartRateBpm", "trimpEdwards", "hrMaxReferenceBpm",
            "validHrDurationSeconds", "validGpsDurationSeconds"
    );

    private final WorkoutPayloadRepository workouts;
    private final QuarterSummaryRepository summaries;
    private final MatchRepository matches;
    private final ObjectMapper json;

    public QuarterSummaryCalculation(WorkoutPayloadRepository workouts, QuarterSummaryRepository summaries,
                                     MatchRepository matches, ObjectMapper json) {
        this.workouts = workouts;
        this.summaries = summaries;
        this.matches = matches;
        this.json = json;
    }

    @Transactional
    public void process(PendingCalculation job) {
        var pending = job.summary();
        var workout = workouts.findById(pending.workoutPayloadId()).orElse(null);
        var now = LocalDateTime.now(ZoneOffset.UTC);

        // Use the same lock order as upload. The conditional write fences off older or concurrent workers.
        var match = matches.findByIdAndOwnerIdForUpdate(job.matchId(), job.ownerId()).orElse(null);
        if (match == null) {
            return;
        }
        int updated = validSource(workout, pending)
                ? summaries.updateCalculated(calculate(pending, workout, now), pending.sourcePayloadHash())
                : summaries.markFailed(pending.quarterId(), pending.sourcePayloadHash(),
                        pending.calculationJobId(), metricStatus(false), now);
        if (updated == 1 && matches.incrementVersion(match.id(), match.ownerId(), match.version()) != 1) {
            throw new IllegalStateException("Could not advance the match version after publishing a summary");
        }
    }

    private static boolean validSource(WorkoutPayload workout, QuarterSummary pending) {
        return workout != null && pending.quarterId().equals(workout.quarterId())
                && Double.isFinite(workout.totalDistance()) && workout.totalDistance() >= 0
                && Double.isFinite(workout.totalCalories()) && workout.totalCalories() >= 0;
    }

    private QuarterSummary calculate(QuarterSummary pending, WorkoutPayload workout, LocalDateTime now) {
        // Active duration and the average-speed denominator depend on the unresolved pause policy.
        return new QuarterSummary(pending.quarterId(), workout.id(), pending.calculationJobId(),
                "PARTIAL", pending.calculationVersion(), pending.calculationParameters(), metricStatus(true),
                pending.sourcePayloadHash(), null, null, workout.totalDistance(), workout.totalCalories(),
                null, null, null, null, null, null, null, null, null, null, null, now, now);
    }

    private String metricStatus(boolean valid) {
        var statuses = new LinkedHashMap<String, MetricStatus>();
        for (String metric : METRICS) {
            var status = !valid ? new MetricStatus("FAILED", "INVALID_SOURCE")
                    : BASIC_METRICS.contains(metric) ? new MetricStatus("READY", null)
                    : new MetricStatus("MISSING_INPUT", "CALCULATION_POLICY_OR_VALID_DATA_REQUIRED");
            statuses.put(metric, status);
        }
        return json.writeValueAsString(statuses);
    }

    private record MetricStatus(String status, String reason) {}
}
