package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.match.api.MatchApiResponse;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.repository.QuarterRepository;
import com.teamehera.kiikick.match.service.MatchDetailQuery.DetailMeta;
import com.teamehera.kiikick.match.service.MatchDetailQuery.VersionedResponse;
import com.teamehera.kiikick.match.service.MatchListQuery.InvalidListRequest;
import com.teamehera.kiikick.match.service.MatchListQuery.Pagination;
import com.teamehera.kiikick.workout.domain.QuarterSummary;
import com.teamehera.kiikick.workout.domain.StoredHeartRateSample;
import com.teamehera.kiikick.workout.domain.StoredTrackPoint;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import com.teamehera.kiikick.workout.repository.HeartRateSampleRepository;
import com.teamehera.kiikick.workout.repository.QuarterSummaryRepository;
import com.teamehera.kiikick.workout.repository.TrackPointRepository;
import com.teamehera.kiikick.workout.repository.WorkoutPayloadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import static com.teamehera.kiikick.match.api.MatchApiResponse.fromUtc;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class QuarterWorkoutQuery {
    private final QuarterRepository quarters;
    private final MatchOwnership ownership;
    private final WorkoutPayloadRepository workouts;
    private final QuarterSummaryRepository summaries;
    private final TrackPointRepository tracks;
    private final HeartRateSampleRepository heartRates;
    private final ObjectMapper json;

    public QuarterWorkoutQuery(QuarterRepository quarters, MatchOwnership ownership, WorkoutPayloadRepository workouts,
                               QuarterSummaryRepository summaries, TrackPointRepository tracks,
                               HeartRateSampleRepository heartRates, ObjectMapper json) {
        this.quarters = quarters;
        this.ownership = ownership;
        this.workouts = workouts;
        this.summaries = summaries;
        this.tracks = tracks;
        this.heartRates = heartRates;
        this.json = json;
    }

    public VersionedResponse<Workout> workout(UUID actor, UUID quarterId) {
        var owned = requireWorkout(actor, quarterId);
        var meta = MatchApiResponse.Meta.create();
        return new VersionedResponse<>(Workout.from(owned.workout()),
                new DetailMeta(meta.requestId(), meta.serverTime(), owned.matchVersion()));
    }

    public VersionedResponse<Summary> summary(UUID actor, UUID quarterId) {
        var match = requireParent(actor, quarterId);
        var data = summaries.findByQuarterId(quarterId).map(value -> Summary.from(value, json)).orElse(null);
        var meta = MatchApiResponse.Meta.create();
        return new VersionedResponse<>(data, new DetailMeta(meta.requestId(), meta.serverTime(), match.version()));
    }

    public Page<TrackSample> trackPoints(UUID actor, UUID quarterId, String cursor, Integer limit) {
        return page(actor, quarterId, cursor, limit, "track", tracks::findPageBySampleSeq,
                StoredTrackPoint::sampleSeq, TrackSample::from);
    }

    public Page<HeartRate> heartRateSamples(UUID actor, UUID quarterId, String cursor, Integer limit) {
        return page(actor, quarterId, cursor, limit, "heart", heartRates::findPageBySampleSeq,
                StoredHeartRateSample::sampleSeq, HeartRate::from);
    }

    private OwnedWorkout requireWorkout(UUID actor, UUID quarterId) {
        var match = requireParent(actor, quarterId);
        var workout = workouts.findByQuarterId(quarterId).orElseThrow(MatchOwnership.ResourceNotFound::new);
        return new OwnedWorkout(workout, match.version());
    }

    private Match requireParent(UUID actor, UUID quarterId) {
        var quarter = quarters.findByIdAndOwnerId(quarterId, actor).orElseThrow(MatchOwnership.ResourceNotFound::new);
        return ownership.requireMatch(actor, quarter.matchId());
    }

    private <R, T> Page<T> page(UUID actor, UUID quarterId, String cursor, Integer limit, String kind,
                               SampleQuery<R> query, ToIntFunction<R> sequence, Function<R, T> mapper) {
        var owned = requireWorkout(actor, quarterId);
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100) {
            throw new InvalidListRequest("INVALID_REQUEST");
        }
        UUID workoutId = owned.workout().id();
        String hash = summaries.findByQuarterId(quarterId).map(QuarterSummary::sourcePayloadHash).orElse("-");
        // The snapshot binds pagination to this immutable source and sample type, independently of match edits.
        String scope = "1|" + kind + "|" + actor + "|" + quarterId + "|";
        String snapshot = workoutId + "|" + hash + "|";
        int after = cursor == null ? -1 : decode(cursor, scope, snapshot);
        var rows = query.find(workoutId, after, size + 1);
        boolean hasNext = rows.size() > size;
        var visible = rows.subList(0, Math.min(size, rows.size()));
        String next = hasNext ? encode(scope + snapshot + sequence.applyAsInt(visible.getLast())) : null;
        var meta = MatchApiResponse.Meta.create();
        return new Page<>(visible.stream().map(mapper).toList(),
                new PageMeta(meta.requestId(), meta.serverTime(), owned.matchVersion(), new Pagination(next, hasNext)));
    }

    private static int decode(String cursor, String scope, String snapshot) {
        if (cursor.length() <= 256) {
            try {
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                if (decoded.startsWith(scope)) {
                    String tail = decoded.substring(scope.length());
                    String[] fields = tail.split("\\|", -1);
                    if (fields.length == 3 && UUID.fromString(fields[0]).toString().equals(fields[0])
                            && fields[1].matches("-|[0-9a-f]{64}")) {
                        int sequence = Integer.parseInt(fields[2]);
                        if (sequence >= 0 && Integer.toString(sequence).equals(fields[2])) {
                            if (!tail.startsWith(snapshot)) {
                                throw new QuarterSettingsService.CursorSnapshotChanged();
                            }
                            return sequence;
                        }
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // Malformed input uses the same response as a cursor from a different sample stream.
            }
        }
        throw new InvalidListRequest("INVALID_CURSOR");
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface SampleQuery<R> {
        List<R> find(UUID workoutId, int afterSampleSeq, int limit);
    }

    private record OwnedWorkout(WorkoutPayload workout, long matchVersion) {}

    public record Page<T>(List<T> data, PageMeta meta) {}

    public record PageMeta(UUID requestId, OffsetDateTime serverTime, long matchVersion, Pagination pagination) {}

    public record Workout(UUID id, UUID quarterId, OffsetDateTime startDate, OffsetDateTime endDate,
                          double totalCalories, double totalDistance) {
        public static Workout from(WorkoutPayload workout) {
            return new Workout(workout.id(), workout.quarterId(), fromUtc(workout.startDate()),
                    fromUtc(workout.endDate()), workout.totalCalories(), workout.totalDistance());
        }
    }

    public record Summary(UUID quarterId, UUID workoutPayloadId, String calculationStatus, String calculationVersion,
                          JsonNode metricStatus, Double durationSeconds, Double maxSpeedMps, Double totalDistanceM,
                          Double totalCaloriesKcal, Double averageSpeedMps, Double highIntensityDistanceM,
                          Integer accelerationCount, Integer decelerationCount, Integer directionChangeCount,
                          Double averageHeartRateBpm, Double maxHeartRateBpm, Double trimpEdwards,
                          Double hrMaxReferenceBpm, Double validHrDurationSeconds, Double validGpsDurationSeconds,
                          OffsetDateTime calculatedAt, OffsetDateTime updatedAt) {
        static Summary from(QuarterSummary summary, ObjectMapper json) {
            return new Summary(summary.quarterId(), summary.workoutPayloadId(), summary.calculationStatus(),
                    summary.calculationVersion(), json.readTree(summary.metricStatus()), summary.durationSeconds(),
                    summary.maxSpeedMps(), summary.totalDistanceM(), summary.totalCaloriesKcal(), summary.averageSpeedMps(),
                    summary.highIntensityDistanceM(), summary.accelerationCount(), summary.decelerationCount(),
                    summary.directionChangeCount(), summary.averageHeartRateBpm(), summary.maxHeartRateBpm(),
                    summary.trimpEdwards(), summary.hrMaxReferenceBpm(), summary.validHrDurationSeconds(),
                    summary.validGpsDurationSeconds(), fromUtc(summary.calculatedAt()), fromUtc(summary.updatedAt()));
        }
    }

    public record TrackSample(int sampleSeq, double latitude, double longitude, double altitude,
                              double horizontalAccuracy, double speed, double course, OffsetDateTime timestamp) {
        static TrackSample from(StoredTrackPoint point) {
            return new TrackSample(point.sampleSeq(), point.latitude(), point.longitude(), point.altitude(),
                    point.horizontalAccuracy(), point.speed(), point.course(), fromUtc(point.timestamp()));
        }
    }

    public record HeartRate(int sampleSeq, OffsetDateTime timestamp, double bpm) {
        static HeartRate from(StoredHeartRateSample sample) {
            return new HeartRate(sample.sampleSeq(), fromUtc(sample.timestamp()), sample.bpm());
        }
    }
}
