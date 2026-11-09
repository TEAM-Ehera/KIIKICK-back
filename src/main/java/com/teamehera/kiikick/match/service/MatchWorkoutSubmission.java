package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.domain.Quarter;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterRepository;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateRequest;
import com.teamehera.kiikick.workout.service.AppleEpochTimeConverter;
import com.teamehera.kiikick.workout.service.QuarterWorkoutUpload;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
public class MatchWorkoutSubmission {
    private final MatchRepository matches;
    private final QuarterRepository quarters;
    private final QuarterPositionService positions;
    private final QuarterWorkoutUpload workouts;

    public MatchWorkoutSubmission(MatchRepository matches, QuarterRepository quarters,
                                  QuarterPositionService positions, QuarterWorkoutUpload workouts) {
        this.matches = matches;
        this.quarters = quarters;
        this.positions = positions;
        this.workouts = workouts;
    }

    // Called inside MatchMutation's owner lock and idempotency transaction.
    public Match create(UUID actor, UUID matchId, Map<UUID, Entry> entries) {
        var now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var imported = entries.entrySet().stream().map(entry -> {
            var workout = entry.getValue().workout();
            var start = localTime(workout.startDate(), "startDate");
            var end = localTime(workout.endDate(), "endDate");
            double seconds = Math.ceil(workout.endDate() - workout.startDate());
            if (!end.isAfter(start) || seconds < 1 || seconds > Integer.MAX_VALUE) {
                throw new QuarterWorkoutUpload.InvalidInput("quarters");
            }
            return new Quarter(entry.getKey(), matchId, 0, (int) seconds, "COMPLETED", start, end, now, now);
        }).toList();
        var start = imported.stream().map(Quarter::startedAt).min(LocalDateTime::compareTo).orElseThrow();
        var end = imported.stream().map(Quarter::endedAt).max(LocalDateTime::compareTo).orElseThrow();
        var match = new Match(matchId, actor, null, null, null, null, null,
                "COMPLETED", start, end, 1, now, now);
        try {
            matches.save(match);
            int number = 1;
            // The array order supplied by iOS defines quarter numbering.
            for (var quarter : imported) {
                quarters.save(new Quarter(quarter.id(), matchId, number++, quarter.durationSeconds(),
                        quarter.status(), quarter.startedAt(), quarter.endedAt(), now, now));
            }
        } catch (DuplicateKeyException exception) {
            // IDs may collide with another owner's import; roll back without exposing their records.
            throw new MatchOwnership.ResourceNotFound();
        }
        return match;
    }

    private static LocalDateTime localTime(double timestamp, String field) {
        try {
            var time = AppleEpochTimeConverter.toUtcLocalDateTime(timestamp);
            if (time.getYear() < 1000 || time.getYear() > 9999) {
                throw new IllegalArgumentException("Date outside MySQL DATETIME range");
            }
            return time;
        } catch (IllegalArgumentException exception) {
            throw new QuarterWorkoutUpload.InvalidInput(field);
        }
    }

    // MatchMutation holds the parent lock and rolls back all quarters if one fails.
    public long save(Match match, Map<UUID, Entry> entries) {
        if (!"COMPLETED".equals(match.status())) {
            throw new MatchSettingsService.InvalidState();
        }

        var matchQuarters = quarters.findByMatchId(match.id(), Math.addExact(entries.size(), 1));
        if (matchQuarters.size() != entries.size()
                || matchQuarters.stream().anyMatch(quarter -> !entries.containsKey(quarter.id()))) {
            throw new QuarterWorkoutUpload.InvalidInput("quarters");
        }
        for (var quarter : matchQuarters) {
            var entry = entries.get(quarter.id());
            positions.store(quarter.id(), entry.position());
            workouts.store(match, quarter, entry.workout());
        }
        if (matches.incrementVersion(match.id(), match.ownerId(), match.version()) != 1) {
            throw new IllegalStateException("Could not advance the locked match version");
        }
        return Math.addExact(match.version(), 1);
    }

    public record Entry(String position, WorkoutPayloadCreateRequest workout) {}
}
