package com.teamehera.kiikick.workout.service;

import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.workout.domain.QuarterSummary;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import com.teamehera.kiikick.workout.repository.HeartRateSampleRepository;
import com.teamehera.kiikick.workout.repository.QuarterSummaryRepository;
import com.teamehera.kiikick.workout.repository.QuarterSummaryRepository.PendingCalculation;
import com.teamehera.kiikick.workout.repository.TrackPointRepository;
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
    private final TrackPointRepository tracks;
    private final QuarterSummaryRepository summaries;
    private final MatchRepository matches;
    private final ObjectMapper json;

    public QuarterSummaryCalculation(WorkoutPayloadRepository workouts, QuarterSummaryRepository summaries,
                                     HeartRateSampleRepository heartRates, TrackPointRepository tracks,
                                     MatchRepository matches, ObjectMapper json) {
        this.workouts = workouts;
        this.heartRates = heartRates;
        this.tracks = tracks;
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
                        pending.calculationJobId(), json.writeValueAsString(metricStatuses(null, null)), now);
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
        var movement = MovementSummary.calculate(workout, tracks.findAllForCalculation(workout.id()), heart);
        parameters.set("movementPolicy", json.valueToTree(Map.of(
                "maxAccuracyM", MovementSummary.MAX_ACCURACY_M, "maxSpeedMps", MovementSummary.MAX_SPEED_MPS,
                "maxGapSeconds", MovementSummary.MAX_GAP_SECONDS, "minMovingSpeedMps", MovementSummary.MIN_MOVING_SPEED_MPS,
                "accelerationThresholdMps2", MovementSummary.ACCELERATION_THRESHOLD,
                "turnDegrees", MovementSummary.TURN_DEGREES, "minEventSeconds", MovementSummary.MIN_EVENT_SECONDS,
                "highIntensityHrRatio", HeartRateSummary.HIGH_INTENSITY_HR_RATIO,
                "averageSpeedTime", "ELAPSED_INCLUDING_PAUSES", "duplicateTimestamp", "LAST_VALID")));
        var now = LocalDateTime.now(ZoneOffset.UTC);
        var statuses = metricStatuses(heart, movement);
        boolean ready = statuses.values().stream().allMatch(status -> "READY".equals(status.status()));
        return new QuarterSummary(pending.quarterId(), workout.id(), pending.calculationJobId(),
                ready ? "READY" : "PARTIAL", "v3", json.writeValueAsString(parameters), json.writeValueAsString(statuses),
                pending.sourcePayloadHash(), movement.elapsedSeconds(), movement.maxSpeed(),
                workout.totalDistance(), workout.totalCalories(), movement.averageSpeed(), movement.highIntensityDistance(),
                movement.accelerations(), movement.decelerations(), movement.turns(), heart.averageBpm(),
                heart.maxBpm(), heart.trimp(), heart.referenceBpm(), heart.validSeconds(), movement.validSeconds(), now, now);
    }

    private static Map<String, MetricStatus> metricStatuses(HeartRateSummary.Result heart, MovementSummary.Result movement) {
        var statuses = new LinkedHashMap<String, MetricStatus>();
        for (String metric : METRICS) {
            statuses.put(metric, status(metric, heart, movement));
        }
        return statuses;
    }

    private static MetricStatus status(String metric, HeartRateSummary.Result heart, MovementSummary.Result movement) {
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
            case "durationSeconds" -> available(movement.elapsedSeconds(), "INVALID_WORKOUT_DURATION");
            case "averageSpeedMps" -> available(movement.averageSpeed(), "INVALID_WORKOUT_DURATION");
            case "maxSpeedMps" -> available(movement.maxSpeed(), "INSUFFICIENT_VALID_GPS_DATA");
            case "validGpsDurationSeconds" -> available(movement.validSeconds(), "INSUFFICIENT_VALID_GPS_DATA");
            case "accelerationCount" -> available(movement.accelerations(), "INSUFFICIENT_VALID_GPS_DATA");
            case "decelerationCount" -> available(movement.decelerations(), "INSUFFICIENT_VALID_GPS_DATA");
            case "directionChangeCount" -> available(movement.turns(), "INSUFFICIENT_VALID_COURSE_DATA");
            case "highIntensityDistanceM" -> heart.referenceBpm() == null ? referenceStatus(heart)
                    : available(movement.highIntensityDistance(), "INSUFFICIENT_OVERLAPPING_GPS_AND_HEART_RATE_DATA");
            default -> throw new IllegalArgumentException("Unknown summary metric: " + metric);
        };
    }

    private static MetricStatus available(Number value, String reason) {
        return value == null ? new MetricStatus("INSUFFICIENT_DATA", reason) : new MetricStatus("READY", null);
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
