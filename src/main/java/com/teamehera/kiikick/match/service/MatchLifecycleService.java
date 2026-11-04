package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Service
public class MatchLifecycleService {
    private final MatchRepository matches;
    private final QuarterRepository quarters;
    private final MatchOwnership ownership;

    public MatchLifecycleService(MatchRepository matches, QuarterRepository quarters, MatchOwnership ownership) {
        this.matches = matches;
        this.quarters = quarters;
        this.ownership = ownership;
    }

    // MatchMutation holds the parent row lock while checking quarter state and advancing the version.
    public Match transition(Match match, String action) {
        String status = match.status();
        String nextStatus = switch (action) {
            case "ready" -> "DRAFT".equals(status) && quarters.hasReadyQuarter(match.id()) ? "READY" : null;
            case "start" -> "READY".equals(status) ? "IN_PROGRESS" : null;
            case "cancel" -> "DRAFT".equals(status) || "READY".equals(status) ? "CANCELLED" : null;
            case "end" -> "IN_PROGRESS".equals(status) && quarters.hasOnlyCompletedQuarters(match.id())
                    ? "COMPLETED" : null;
            default -> throw new IllegalArgumentException("Unknown match lifecycle action");
        };
        if (nextStatus == null) {
            throw new MatchSettingsService.InvalidState();
        }

        var now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var startedAt = "IN_PROGRESS".equals(nextStatus) ? now : null;
        var endedAt = "COMPLETED".equals(nextStatus) ? now : null;
        if (matches.updateStatus(match.id(), match.ownerId(), match.version(), status,
                nextStatus, startedAt, endedAt) != 1) {
            throw new IllegalStateException("Could not transition the locked match");
        }
        return ownership.requireMatch(match.ownerId(), match.id());
    }
}
