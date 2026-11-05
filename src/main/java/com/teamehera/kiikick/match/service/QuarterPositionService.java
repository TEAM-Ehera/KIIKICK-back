package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.match.api.MatchApiResponse;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.domain.QuarterPosition;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterPositionRepository;
import com.teamehera.kiikick.match.service.MatchDetailQuery.DetailMeta;
import com.teamehera.kiikick.match.service.MatchDetailQuery.VersionedResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
public class QuarterPositionService {
    private final MatchOwnership ownership;
    private final QuarterPositionRepository positions;

    private final MatchRepository matches;
    private final Set<String> allowedCodes;

    public QuarterPositionService(MatchOwnership ownership, QuarterPositionRepository positions,
                                  MatchRepository matches,
                                  @Value("${kiikick.match.position-codes:}") String configuredCodes) {
        this.ownership = ownership;
        this.positions = positions;
        this.matches = matches;
        this.allowedCodes = Arrays.stream(configuredCodes.split(","))
                .map(String::strip).filter(code -> !code.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VersionedResponse<Position> find(UUID actor, UUID matchId, UUID quarterId) {
        // The position and its parent version come from the same read snapshot.
        var match = ownership.requireMatch(actor, matchId);
        ownership.requireQuarter(actor, matchId, quarterId);
        var position = positions.findByQuarterId(quarterId).map(Position::from).orElse(null);
        return response(position, match.version());
    }

    // The parent lock serializes position changes with quarter and match completion.
    public VersionedResponse<Position> replace(Match match, UUID quarterId, String code) {
        var quarter = ownership.requireQuarter(match.ownerId(), match.id(), quarterId);
        if (!"IN_PROGRESS".equals(match.status()) || "READY".equals(quarter.status())) {
            throw new MatchSettingsService.InvalidState();
        }
        if (quarter.endedAt() == null) {
            throw new QuarterLifecycleService.QuarterNotEnded();
        }
        if (code == null || code.length() > 32 || !allowedCodes.contains(code)) {
            throw new InvalidPositionCode();
        }

        var now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        if (positions.upsert(quarterId, code, now) != 1
                || matches.incrementVersion(match.id(), match.ownerId(), match.version()) != 1) {
            throw new IllegalStateException("Could not replace the locked quarter position");
        }
        return response(Position.from(new QuarterPosition(quarterId, code, now)), Math.addExact(match.version(), 1));
    }

    private static VersionedResponse<Position> response(Position position, long version) {
        var meta = MatchApiResponse.Meta.create();
        return new VersionedResponse<>(position, new DetailMeta(meta.requestId(), meta.serverTime(), version));
    }

    public static final class InvalidPositionCode extends RuntimeException {
    }

    public record Position(UUID quarterId, String positionCode, OffsetDateTime updatedAt) {
        static Position from(QuarterPosition position) {
            return new Position(position.quarterId(), position.positionCode(),
                    MatchApiResponse.fromUtc(position.updatedAt()));
        }
    }
}
