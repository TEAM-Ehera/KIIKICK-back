package com.teamehera.kiikick.workout.service;

import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.workout.repository.QuarterSummaryRepository;
import com.teamehera.kiikick.workout.repository.WorkoutPayloadRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class QuarterSummaryCommands {
    private final WorkoutPayloadRepository workouts;
    private final QuarterSummaryRepository summaries;
    private final MatchRepository matches;
    private final QuarterSummaryCalculation calculation;
    private final ObjectMapper json;

    public QuarterSummaryCommands(WorkoutPayloadRepository workouts, QuarterSummaryRepository summaries,
                                  MatchRepository matches, QuarterSummaryCalculation calculation, ObjectMapper json) {
        this.workouts = workouts;
        this.summaries = summaries;
        this.matches = matches;
        this.calculation = calculation;
        this.json = json;
    }

    // MatchMutation holds the parent lock while a new job replaces the previous result and version.
    public void queue(Match match, UUID quarterId, Double newReference) {
        var workout = workouts.findByQuarterId(quarterId).orElseThrow(WorkoutRequired::new);
        var summary = summaries.findByQuarterId(quarterId)
                .orElseThrow(() -> new IllegalStateException("Stored workout has no source hash"));
        if ("PROCESSING".equals(summary.calculationStatus())) {
            throw new CalculationInProgress();
        }

        var parameters = calculation.currentParameters(summary.calculationParameters());
        if (newReference != null) {
            parameters.put("hrMaxReferenceBpm", newReference);
            parameters.put("hrMaxReferenceSource", "USER_PROVIDED");
        }
        summaries.saveProcessing(quarterId, workout.id(), QuarterSummaryCalculation.VERSION,
                json.writeValueAsString(parameters), "{}", summary.sourcePayloadHash(),
                LocalDateTime.now(ZoneOffset.UTC));
        if (matches.incrementVersion(match.id(), match.ownerId(), match.version()) != 1) {
            throw new IllegalStateException("Could not advance the match version after queuing a summary");
        }
    }

    public static final class WorkoutRequired extends RuntimeException {}

    public static final class CalculationInProgress extends RuntimeException {}
}
