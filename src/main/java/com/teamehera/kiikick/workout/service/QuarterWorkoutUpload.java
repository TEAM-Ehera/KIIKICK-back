package com.teamehera.kiikick.workout.service;

import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.domain.Quarter;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.service.QuarterLifecycleService.QuarterNotEnded;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateRequest;
import com.teamehera.kiikick.workout.repository.HeartRateSampleRepository;
import com.teamehera.kiikick.workout.repository.QuarterSummaryRepository;
import com.teamehera.kiikick.workout.repository.TrackPointRepository;
import com.teamehera.kiikick.workout.repository.WorkoutPayloadRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.IntToDoubleFunction;

@Service
public class QuarterWorkoutUpload {
    private final WorkoutPayloadRepository workouts;
    private final TrackPointRepository tracks;
    private final HeartRateSampleRepository heartRates;
    private final QuarterSummaryRepository summaries;
    private final MatchRepository matches;
    private final ObjectMapper json;

    public QuarterWorkoutUpload(WorkoutPayloadRepository workouts, TrackPointRepository tracks,
                                HeartRateSampleRepository heartRates, QuarterSummaryRepository summaries,
                                MatchRepository matches, ObjectMapper json) {
        this.workouts = workouts;
        this.tracks = tracks;
        this.heartRates = heartRates;
        this.summaries = summaries;
        this.matches = matches;
        this.json = json;
    }

    // MatchMutation locks the parent across deduplication, sample inserts and publication of PROCESSING.
    public Result save(Match match, Quarter quarter, WorkoutPayloadCreateRequest request) {
        var stored = store(match, quarter, request);
        return new Result(stored.workout(), incrementVersion(match), stored.created());
    }

    // The caller owns the match lock and advances its version once for the entire mutation.
    public StoredWorkout store(Match match, Quarter quarter, WorkoutPayloadCreateRequest request) {
        if (quarter.endedAt() == null) {
            throw new QuarterNotEnded();
        }
        var start = WorkoutPayloadService.convertDate("startDate", request.startDate());
        var end = WorkoutPayloadService.convertDate("endDate", request.endDate());
        if (request.endDate() <= request.startDate() || !end.isAfter(start)
                || request.totalCalories() < 0 || request.totalDistance() < 0) {
            throw new InvalidInput("body");
        }
        validateTimes(request, "points", request.points().size(), i -> request.points().get(i).timestamp());
        validateTimes(request, "heartRateSamples", request.heartRateSamples().size(),
                i -> request.heartRateSamples().get(i).timestamp());
        for (int i = 0; i < request.points().size(); i++) {
            var point = request.points().get(i);
            if (Math.abs(point.latitude()) > 90 || Math.abs(point.longitude()) > 180
                    || (point.speed() < 0 && point.speed() != -1)
                    || (point.course() != -1 && (point.course() < 0 || point.course() >= 360))) {
                throw new InvalidInput("points[" + i + "]");
            }
        }

        // Re-encoding the typed payload normalizes object key order and numeric spelling while retaining sample order.
        String hash = hash(json.writeValueAsBytes(request));
        var existing = workouts.findByQuarterId(quarter.id());
        if (existing.isPresent()) {
            var existingHash = summaries.findByQuarterId(quarter.id()).map(summary -> summary.sourcePayloadHash());
            if (existingHash.filter(hash::equals).isEmpty()) {
                throw new WorkoutAlreadyExists();
            }
            return new StoredWorkout(existing.orElseThrow(), false);
        }

        var workout = new WorkoutPayload(UUID.randomUUID(), quarter.id(), match.ownerId(), start, end,
                request.totalCalories(), request.totalDistance());
        workouts.save(workout);
        tracks.saveAll(WorkoutPayloadService.createTrackPoints(workout.id(), request.points()));
        heartRates.saveAll(WorkoutPayloadService.createHeartRateSamples(workout.id(), request.heartRateSamples()));
        summaries.saveProcessing(quarter.id(), workout.id(), "v1", "{}", "{}", hash, LocalDateTime.now(ZoneOffset.UTC));
        return new StoredWorkout(workout, true);
    }

    private long incrementVersion(Match match) {
        if (matches.incrementVersion(match.id(), match.ownerId(), match.version()) != 1) {
            throw new IllegalStateException("Could not advance the locked match version");
        }
        return Math.addExact(match.version(), 1);
    }

    private static void validateTimes(WorkoutPayloadCreateRequest request, String field, int size,
                                      IntToDoubleFunction timestamp) {
        double previous = request.startDate();
        for (int i = 0; i < size; i++) {
            double value = timestamp.applyAsDouble(i);
            if (value < previous || value > request.endDate()) {
                throw new InvalidInput(field + "[" + i + "].timestamp");
            }
            previous = value;
        }
    }

    private static String hash(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record StoredWorkout(WorkoutPayload workout, boolean created) {}

    public record Result(WorkoutPayload workout, long matchVersion, boolean created) {}

    public static final class WorkoutAlreadyExists extends RuntimeException {}

    public static final class InvalidInput extends RuntimeException {
        public InvalidInput(String field) {
            super(field);
        }
    }
}
