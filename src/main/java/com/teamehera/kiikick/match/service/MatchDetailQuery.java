package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.match.api.MatchApiResponse;
import com.teamehera.kiikick.match.domain.Match;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

import static com.teamehera.kiikick.match.api.MatchApiResponse.fromUtc;

@Service
public class MatchDetailQuery {

    private final MatchOwnership ownership;

    public MatchDetailQuery(MatchOwnership ownership) {
        this.ownership = ownership;
    }

    public ResponseEntity<Detail> find(UUID actorUserId, UUID matchId) {
        var match = ownership.requireMatch(actorUserId, matchId);
        return ResponseEntity.ok().eTag(MatchMutation.etag(match.version())).body(Detail.from(match));
    }

    public record Detail(MatchResponse data, DetailMeta meta) {
        public static Detail from(Match match) {
            var meta = MatchApiResponse.Meta.create();
            return new Detail(MatchResponse.from(match),
                    new DetailMeta(meta.requestId(), meta.serverTime(), match.version()));
        }
    }

    public record DetailMeta(UUID requestId, OffsetDateTime serverTime, long matchVersion) {
    }

    public record VenueSnapshot(String name, String address) {
    }

    public record MatchResponse(UUID id, UUID ownerId, String title, UUID venueId, VenueSnapshot venueSnapshot,
                                OffsetDateTime scheduledAt, String status, OffsetDateTime startedAt,
                                OffsetDateTime endedAt, long version, OffsetDateTime createdAt,
                                OffsetDateTime updatedAt) {
        static MatchResponse from(Match match) {
            var snapshot = match.venueNameSnapshot() == null && match.venueAddressSnapshot() == null
                    ? null : new VenueSnapshot(match.venueNameSnapshot(), match.venueAddressSnapshot());
            return new MatchResponse(match.id(), match.ownerId(), match.title(), match.venueId(),
                    snapshot, fromUtc(match.scheduledAt()), match.status(), fromUtc(match.startedAt()),
                    fromUtc(match.endedAt()), match.version(), fromUtc(match.createdAt()), fromUtc(match.updatedAt()));
        }
    }
}
