package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.match.api.MatchApiResponse;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.domain.Quarter;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.teamehera.kiikick.match.api.MatchApiResponse.fromUtc;

@Service
public class QuarterSettingsService {
    private static final Set<String> EDITABLE_MATCHES = Set.of("DRAFT", "READY", "IN_PROGRESS");
    private final MatchOwnership ownership;
    private final QuarterRepository quarters;
    private final MatchRepository matches;

    public QuarterSettingsService(MatchOwnership ownership, QuarterRepository quarters, MatchRepository matches) {
        this.ownership = ownership;
        this.quarters = quarters;
        this.matches = matches;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(UUID actor, UUID matchId, String cursor, Integer limit) {
        var match = ownership.requireMatch(actor, matchId);
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100) {
            throw new MatchListQuery.InvalidListRequest("INVALID_REQUEST");
        }
        var boundary = cursor == null ? null : decode(cursor, match);
        var rows = boundary == null ? quarters.findByMatchId(matchId, size + 1)
                : quarters.findPageAfter(matchId, boundary.number(), boundary.id(), size + 1);
        boolean hasNext = rows.size() > size;
        var page = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (hasNext) {
            var last = page.getLast();
            String value = matchId + "|" + match.version() + "|" + last.number() + "|" + last.id();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }
        var meta = MatchApiResponse.Meta.create();
        return new Page(page.stream().map(row -> Response.from(row, match.version())).toList(),
                new PageMeta(meta.requestId(), meta.serverTime(), match.version(),
                        new MatchListQuery.Pagination(next, hasNext)));
    }

    private Boundary decode(String cursor, Match match) {
        if (cursor.length() <= 160) {
            try {
                var parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                        .split("\\|", -1);
                if (parts.length == 4 && match.id().toString().equals(parts[0])) {
                    long version = Long.parseLong(parts[1]);
                    int number = Integer.parseInt(parts[2]);
                    UUID id = UUID.fromString(parts[3]);
                    if (version > 0 && number > 0 && id.toString().equals(parts[3])) {
                        if (version != match.version()) {
                            throw new CursorSnapshotChanged();
                        }
                        return new Boundary(number, id);
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // Malformed cursors share the same error response.
            }
        }
        throw new MatchListQuery.InvalidListRequest("INVALID_CURSOR");
    }

    // The caller holds the parent match lock inside MatchMutation.
    public Response add(Match match, int durationSeconds) {
        requireEditableMatch(match);
        var now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var quarter = new Quarter(UUID.randomUUID(), match.id(), quarters.findNextNumber(match.id()),
                durationSeconds, "READY", null, null, now, now);
        quarters.save(quarter);
        return Response.from(quarter, incrementVersion(match));
    }

    public Response update(Match match, UUID quarterId, int durationSeconds) {
        var quarter = ownership.requireQuarter(match.ownerId(), match.id(), quarterId);
        if (quarter.startedAt() != null || quarter.endedAt() != null
                || quarters.updateDuration(quarterId, match.id(), durationSeconds) != 1) {
            throw new MatchSettingsService.InvalidState();
        }
        return Response.from(ownership.requireQuarter(match.ownerId(), match.id(), quarterId), incrementVersion(match));
    }

    public long delete(Match match, UUID quarterId) {
        ownership.requireQuarter(match.ownerId(), match.id(), quarterId);
        requireEditableMatch(match);
        if (quarters.deleteUnusedLast(quarterId, match.id()) != 1) {
            throw new MatchSettingsService.InvalidState();
        }
        return incrementVersion(match);
    }

    private void requireEditableMatch(Match match) {
        if (!EDITABLE_MATCHES.contains(match.status())) {
            throw new MatchSettingsService.InvalidState();
        }
    }

    private long incrementVersion(Match match) {
        if (matches.incrementVersion(match.id(), match.ownerId(), match.version()) != 1) {
            throw new IllegalStateException("Could not increment the locked match version");
        }
        return Math.addExact(match.version(), 1);
    }

    private record Boundary(int number, UUID id) {}

    public record Response(UUID id, UUID matchId, int number, int durationSeconds, String status,
                           OffsetDateTime startedAt, OffsetDateTime endedAt, long matchVersion,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        static Response from(Quarter quarter, long version) {
            return new Response(quarter.id(), quarter.matchId(), quarter.number(), quarter.durationSeconds(),
                    quarter.status(), fromUtc(quarter.startedAt()), fromUtc(quarter.endedAt()), version,
                    fromUtc(quarter.createdAt()), fromUtc(quarter.updatedAt()));
        }
    }

    public record Page(List<Response> data, PageMeta meta) {}

    public record PageMeta(UUID requestId, OffsetDateTime serverTime, long matchVersion,
                           MatchListQuery.Pagination pagination) {}

    public static final class CursorSnapshotChanged extends RuntimeException {}
}
