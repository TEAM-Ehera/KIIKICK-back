package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterRepository;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateRequest;
import com.teamehera.kiikick.workout.service.QuarterWorkoutUpload;
import org.springframework.stereotype.Service;

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
