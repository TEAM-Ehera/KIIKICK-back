package com.teamehera.kiikick.workout.service;

import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.workout.domain.QuarterSummary;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import com.teamehera.kiikick.workout.repository.HeartRateSampleRepository;
import com.teamehera.kiikick.workout.repository.QuarterSummaryRepository;
import com.teamehera.kiikick.workout.repository.QuarterSummaryRepository.PendingCalculation;
import com.teamehera.kiikick.workout.repository.WorkoutPayloadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final HeartRateSampleRepository heartRates;
    private final QuarterSummaryRepository summaries;
    private final MatchRepository matches;
    private final ObjectMapper json;

    public QuarterSummaryCalculation(WorkoutPayloadRepository workouts, QuarterSummaryRepository summaries,
                                     HeartRateSampleRepository heartRates, MatchRepository matches, ObjectMapper json) {
        this.workouts = workouts;
        this.heartRates = heartRates;
        this.summaries = summaries;
        this.matches = matches;
        this.json = json;
    }

    @Transactional
    public void process(PendingCalculation job) {
        var pending = job.summary();
        var workout = workouts.findById(pending.workoutPayloadId()).orElse(null);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var calculated = validSource(workout, pending) ? calculate(pending, workout) : null;

        // Use the same lock order as upload. The conditional write fences off older or concurrent workers.
        var match = matches.findByIdAndOwnerIdForUpdate(job.matchId(), job.ownerId()).orElse(null);
        if (match == null) {
            return;
        }
        int updated = calculated != null
                ? summaries.updateCalculated(calculated, pending.sourcePayloadHash())
                : summaries.markFailed(pending.quarterId(), pending.sourcePayloadHash(),
                        pending.calculationJobId(), metricStatus(null), now);
        if (updated == 1 && matches.incrementVersion(match.id(), match.ownerId(), match.version()) != 1) {
            throw new IllegalStateException("Could not advance the match version after publishing a summary");
        }
    }

    private static boolean validSource(WorkoutPayload workout, QuarterSummary pending) {
        return workout != null && pending.quarterId().equals(workout.quarterId())
                && Double.isFinite(workout.totalDistance()) && workout.totalDistance() >= 0
                && Double.isFinite(workout.totalCalories()) && workout.totalCalories() >= 0;
    }

    private QuarterSummary calculate(QuarterSummary pending, WorkoutPayload workout) {
        var stored = json.readTree(pending.calculationParameters());
        var parameters = stored instanceof ObjectNode object
                ? object.deepCopy() : json.createObjectNode();
        var input = parameters.path("hrMaxReferenceBpm");
        double reference = input.isNumber() ? input.doubleValue() : Double.NaN;
        Double referenceBpm = Double.isFinite(reference) && reference > 0 ? reference : null;
        var heart = HeartRateSummary.calculate(heartRates.findAllForCalculation(workout.id()), referenceBpm);
        parameters.set("heartRatePolicy", json.valueToTree(Map.of(
                    "minBpm", HeartRateSummary.MIN_BPM, "maxBpm", HeartRateSummary.MAX_BPM,
                    "maxGapSeconds", HeartRateSummary.MAX_GAP_SECONDS, "interpolation", "LINEAR",
                    "duplicateTimestamp", "LAST_VALID", "aboveHrMax", "EXCLUDE_FROM_TRIMP",
                    "zoneBoundaries", "LOWER_INCLUSIVE_UPPER_EXCLUSIVE_LAST_INCLUSIVE")));
        var now = LocalDateTime.now(ZoneOffset.UTC);
        // Active duration and the average-speed denominator depend on the unresolved pause policy.
        return new QuarterSummary(pending.quarterId(), workout.id(), pending.calculationJobId(),
                "PARTIAL", "v2", json.writeValueAsString(parameters), metricStatus(heart),
                pending.sourcePayloadHash(), null, null, workout.totalDistance(), workout.totalCalories(),
                null, null, null, null, null, heart.averageBpm(), heart.maxBpm(), heart.trimp(),
                heart.referenceBpm(), heart.validSeconds(), null, now, now);
    }

    private String metricStatus(HeartRateSummary.Result heart) {
        var statuses = new LinkedHashMap<String, MetricStatus>();
        for (String metric : METRICS) {
            statuses.put(metric, status(metric, heart));
        }
        return json.writeValueAsString(statuses);
    }

    private static MetricStatus status(String metric, HeartRateSummary.Result heart) {
        if (heart == null) {
            return new MetricStatus("FAILED", "INVALID_SOURCE");
        }
        if (BASIC_METRICS.contains(metric)) {
            return new MetricStatus("READY", null);
        }
        return switch (metric) {
            case "averageHeartRateBpm" -> sampleStatus(heart.averageBpm());
            case "maxHeartRateBpm" -> sampleStatus(heart.maxBpm());
            case "validHrDurationSeconds" -> sampleStatus(heart.validSeconds());
            case "hrMaxReferenceBpm" -> referenceStatus(heart);
            case "trimpEdwards" -> heart.referenceBpm() == null ? referenceStatus(heart) : sampleStatus(heart.trimp());
            default -> new MetricStatus("MISSING_INPUT", "CALCULATION_POLICY_OR_VALID_DATA_REQUIRED");
        };
    }

    private static MetricStatus sampleStatus(Double value) {
        return value == null ? new MetricStatus("INSUFFICIENT_DATA", "INSUFFICIENT_VALID_HEART_RATE_DATA")
                : new MetricStatus("READY", null);
    }

    private static MetricStatus referenceStatus(HeartRateSummary.Result heart) {
        return heart.referenceBpm() == null ? new MetricStatus("MISSING_INPUT", "HR_MAX_REFERENCE_REQUIRED")
                : new MetricStatus("READY", null);
    }

    private record MetricStatus(String status, String reason) {}
}
