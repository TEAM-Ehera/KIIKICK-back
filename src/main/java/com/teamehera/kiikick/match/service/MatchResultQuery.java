package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.match.api.MatchApiResponse;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.domain.QuarterPlayerStats;
import com.teamehera.kiikick.match.domain.QuarterPosition;
import com.teamehera.kiikick.match.repository.MatchResultRepository;
import com.teamehera.kiikick.match.repository.QuarterPlayerStatsRepository;
import com.teamehera.kiikick.match.repository.QuarterPositionRepository;
import com.teamehera.kiikick.match.repository.QuarterRepository;
import com.teamehera.kiikick.match.service.MatchDetailQuery.DetailMeta;
import com.teamehera.kiikick.match.service.MatchDetailQuery.MatchResponse;
import com.teamehera.kiikick.match.service.MatchDetailQuery.VersionedResponse;
import com.teamehera.kiikick.match.service.QuarterPositionService.Position;
import com.teamehera.kiikick.match.service.QuarterSettingsService.PageMeta;
import com.teamehera.kiikick.match.service.QuarterSettingsService.Response;
import com.teamehera.kiikick.match.service.QuarterStatsService.Stats;
import com.teamehera.kiikick.match.service.QuarterWorkoutQuery.Summary;
import com.teamehera.kiikick.match.service.QuarterWorkoutQuery.Workout;
import com.teamehera.kiikick.workout.domain.QuarterSummary;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import com.teamehera.kiikick.workout.repository.QuarterSummaryRepository;
import com.teamehera.kiikick.workout.repository.WorkoutPayloadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static java.util.stream.Collectors.toMap;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class MatchResultQuery {
    private final MatchOwnership ownership;
    private final QuarterSettingsService quarterPages;
    private final QuarterRepository quarters;
    private final MatchResultRepository results;
    private final QuarterPlayerStatsRepository stats;
    private final QuarterPositionRepository positions;
    private final WorkoutPayloadRepository workouts;
    private final QuarterSummaryRepository summaries;
    private final ObjectMapper json;

    public MatchResultQuery(MatchOwnership ownership, QuarterSettingsService quarterPages, QuarterRepository quarters,
                            MatchResultRepository results, QuarterPlayerStatsRepository stats,
                            QuarterPositionRepository positions, WorkoutPayloadRepository workouts,
                            QuarterSummaryRepository summaries, ObjectMapper json) {
        this.ownership = ownership;
        this.quarterPages = quarterPages;
        this.quarters = quarters;
        this.results = results;
        this.stats = stats;
        this.positions = positions;
        this.workouts = workouts;
        this.summaries = summaries;
        this.json = json;
    }

    public VersionedResponse<MatchResult> match(UUID actor, UUID matchId) {
        var match = requireCompletedMatch(actor, matchId);
        var rows = results.findTotals(matchId);
        var missingStats = new ArrayList<UUID>();
        var statuses = new HashMap<String, Integer>();
        long goals = 0;
        long assists = 0;
        for (var row : rows) {
            if (row.goals() == null || row.assists() == null) {
                missingStats.add(row.quarterId());
            } else {
                goals += row.goals();
                assists += row.assists();
            }
            statuses.merge(row.summaryStatus(), 1, Integer::sum);
        }

        var availability = new SummaryAvailability(statuses.getOrDefault("READY", 0),
                statuses.getOrDefault("PARTIAL", 0), statuses.getOrDefault("PROCESSING", 0),
                statuses.getOrDefault("FAILED", 0), statuses.getOrDefault(null, 0));
        return versioned(new MatchResult(MatchResponse.from(match), rows.size(),
                missingStats.isEmpty() ? goals : null, missingStats.isEmpty() ? assists : null,
                List.copyOf(missingStats), availability), match.version());
    }

    public QuarterResults quarters(UUID actor, UUID matchId, String cursor, Integer limit) {
        requireCompletedMatch(actor, matchId);
        var page = quarterPages.list(actor, matchId, cursor, limit);
        return new QuarterResults(compose(page.data()), page.meta());
    }

    public VersionedResponse<QuarterResult> quarter(UUID actor, UUID quarterId) {
        var quarter = quarters.findByIdAndOwnerId(quarterId, actor)
                .orElseThrow(MatchOwnership.ResourceNotFound::new);
        requireCompleted(quarter.status());
        var match = ownership.requireMatch(actor, quarter.matchId());
        var data = compose(List.of(Response.from(quarter, match.version()))).getFirst();
        return versioned(data, match.version());
    }

    private List<QuarterResult> compose(List<Response> page) {
        if (page.isEmpty()) {
            return List.of();
        }
        // Load each child table once for the page, preserving the quarter order and read snapshot.
        var ids = page.stream().map(Response::id).toList();
        var statsById = stats.findByQuarterIds(ids).stream().collect(toMap(QuarterPlayerStats::quarterId, Stats::from));
        var positionsById = positions.findByQuarterIds(ids).stream().collect(toMap(QuarterPosition::quarterId, Position::from));
        var workoutsById = workouts.findByQuarterIds(ids).stream().collect(toMap(WorkoutPayload::quarterId, workout -> Workout.from(workout, json)));
        var summariesById = summaries.findByQuarterIds(ids).stream()
                .collect(toMap(QuarterSummary::quarterId, summary -> Summary.from(summary, json)));
        return page.stream().map(quarter -> new QuarterResult(quarter, statsById.get(quarter.id()),
                positionsById.get(quarter.id()), workoutsById.get(quarter.id()), summariesById.get(quarter.id()))).toList();
    }

    private Match requireCompletedMatch(UUID actor, UUID matchId) {
        var match = ownership.requireMatch(actor, matchId);
        requireCompleted(match.status());
        return match;
    }

    private static void requireCompleted(String status) {
        if (!"COMPLETED".equals(status)) {
            throw new ResultNotReady();
        }
    }

    private static <T> VersionedResponse<T> versioned(T data, long version) {
        var meta = MatchApiResponse.Meta.create();
        return new VersionedResponse<>(data, new DetailMeta(meta.requestId(), meta.serverTime(), version));
    }

    public record MatchResult(MatchResponse match, int quarterCount, Long goals, Long assists,
                              List<UUID> missingStatsQuarterIds, SummaryAvailability summaryAvailability) {}

    public record SummaryAvailability(int ready, int partial, int processing, int failed, int missing) {}

    public record QuarterResults(List<QuarterResult> data, PageMeta meta) {}

    public record QuarterResult(Response quarter, Stats stats, Position position, Workout workout, Summary summary) {}

    public static final class ResultNotReady extends RuntimeException {}
}
