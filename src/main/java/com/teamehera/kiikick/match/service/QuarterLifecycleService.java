package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterRepository;
import com.teamehera.kiikick.match.service.MatchSettingsService.InvalidState;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class QuarterLifecycleService {
    private final MatchRepository matches;
    private final QuarterRepository quarters;
    private final MatchOwnership ownership;

    public QuarterLifecycleService(MatchRepository matches, QuarterRepository quarters, MatchOwnership ownership) {
        this.matches = matches;
        this.quarters = quarters;
        this.ownership = ownership;
    }

    // MatchMutation holds the parent row lock, so two quarters cannot pass the active check concurrently.
    public QuarterSettingsService.Response transition(Match match, UUID quarterId, String action) {
        var quarter = ownership.requireQuarter(match.ownerId(), match.id(), quarterId);
        if (!"IN_PROGRESS".equals(match.status())) {
            throw new InvalidState();
        }

        var now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        String nextStatus = "IN_PROGRESS";
        LocalDateTime startedAt = null;
        LocalDateTime endedAt = null;
        switch (action) {
            case "start" -> {
                if (!"READY".equals(quarter.status())) {
                    throw new InvalidState();
                }
                if (quarters.hasQuarterInStatus(match.id(), "IN_PROGRESS")) {
                    throw new ActiveQuarterExists();
                }
                startedAt = now;
            }
            case "end" -> {
                if (!"IN_PROGRESS".equals(quarter.status()) || quarter.endedAt() != null) {
                    throw new InvalidState();
                }
                // A measured quarter stays active until the player confirms completion.
                endedAt = now;
            }
            case "complete" -> {
                if (!"IN_PROGRESS".equals(quarter.status())) {
                    throw new InvalidState();
                }
                if (quarter.endedAt() == null) {
                    throw new QuarterNotEnded();
                }
                nextStatus = "COMPLETED";
            }
            default -> throw new IllegalArgumentException("Unknown quarter lifecycle action");
        }

        if (quarters.updateStatus(quarterId, match.id(), quarter.status(), nextStatus, startedAt, endedAt) != 1
                || matches.incrementVersion(match.id(), match.ownerId(), match.version()) != 1) {
            throw new IllegalStateException("Could not transition the locked quarter");
        }
        return QuarterSettingsService.Response.from(
                ownership.requireQuarter(match.ownerId(), match.id(), quarterId), Math.addExact(match.version(), 1));
    }

    public static final class ActiveQuarterExists extends RuntimeException {}
    public static final class QuarterNotEnded extends RuntimeException {}
}
