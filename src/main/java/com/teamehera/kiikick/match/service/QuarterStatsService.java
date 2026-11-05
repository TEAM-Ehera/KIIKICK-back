package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.match.api.MatchApiResponse;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.domain.QuarterPlayerStats;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterPlayerStatsRepository;
import com.teamehera.kiikick.match.service.MatchDetailQuery.DetailMeta;
import com.teamehera.kiikick.match.service.MatchDetailQuery.VersionedResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class QuarterStatsService {
    private final MatchOwnership ownership;
    private final MatchRepository matches;
    private final QuarterPlayerStatsRepository stats;

    public QuarterStatsService(MatchOwnership ownership, MatchRepository matches, QuarterPlayerStatsRepository stats) {
        this.ownership = ownership;
        this.matches = matches;
        this.stats = stats;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VersionedResponse<Stats> find(UUID actor, UUID matchId, UUID quarterId) {
        var match = ownership.requireMatch(actor, matchId);
        ownership.requireQuarter(actor, matchId, quarterId);
        return response(stats.findByQuarterId(quarterId).map(Stats::from).orElse(null), match.version());
    }

    // The parent lock serializes replacement with quarter state changes and match completion.
    public VersionedResponse<Stats> replace(Match match, UUID quarterId, int goals, int assists) {
        var quarter = ownership.requireQuarter(match.ownerId(), match.id(), quarterId);
        if (!"IN_PROGRESS".equals(match.status()) || quarter.startedAt() == null) {
            throw new MatchSettingsService.InvalidState();
        }

        var now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (stats.upsert(quarterId, goals, assists, now) != 1
                || matches.incrementVersion(match.id(), match.ownerId(), match.version()) != 1) {
            throw new IllegalStateException("Could not replace the locked quarter stats");
        }
        return response(Stats.from(new QuarterPlayerStats(quarterId, goals, assists, now)),
                Math.addExact(match.version(), 1));
    }

    private static VersionedResponse<Stats> response(Stats stats, long version) {
        var meta = MatchApiResponse.Meta.create();
        return new VersionedResponse<>(stats, new DetailMeta(meta.requestId(), meta.serverTime(), version));
    }

    public record Stats(UUID quarterId, int goals, int assists, OffsetDateTime updatedAt) {
        static Stats from(QuarterPlayerStats stats) {
            return new Stats(stats.quarterId(), stats.goals(), stats.assists(), MatchApiResponse.fromUtc(stats.updatedAt()));
        }
    }
}
