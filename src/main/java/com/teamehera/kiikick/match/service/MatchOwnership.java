package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.domain.Quarter;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterRepository;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

@Component
public class MatchOwnership {

    private final MatchRepository matches;
    private final QuarterRepository quarters;

    public MatchOwnership(MatchRepository matches, QuarterRepository quarters) {
        this.matches = matches;
        this.quarters = quarters;
    }

    public Match requireMatch(UUID actorUserId, UUID matchId) {
        return matches.findByIdAndOwnerId(matchId, Objects.requireNonNull(actorUserId))
                .orElseThrow(ResourceNotFound::new);
    }

    public Quarter requireQuarter(UUID actorUserId, UUID matchId, UUID quarterId) {
        return quarters.findByIdAndOwnerId(quarterId, Objects.requireNonNull(actorUserId))
                .filter(quarter -> quarter.matchId().equals(matchId))
                .orElseThrow(ResourceNotFound::new);
    }

    public static final class ResourceNotFound extends RuntimeException {}
}
