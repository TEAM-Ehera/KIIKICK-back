package com.teamehera.kiikick.friend.service;

import com.teamehera.kiikick.friend.repository.FriendHeatmapRepository;
import com.teamehera.kiikick.match.api.MatchApiResponse;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.service.MatchDetailQuery.DetailMeta;
import com.teamehera.kiikick.match.service.MatchDetailQuery.VersionedResponse;
import com.teamehera.kiikick.match.service.MatchOwnership;
import com.teamehera.kiikick.match.service.MatchResultQuery;
import com.teamehera.kiikick.match.service.MatchSocialAccess;
import com.teamehera.kiikick.venue.domain.FieldCorner;
import com.teamehera.kiikick.venue.repository.VenueRepository;
import com.teamehera.kiikick.workout.repository.WorkoutPayloadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class FriendRecordQuery {
    private final MatchSocialAccess access;
    private final MatchOwnership ownership;
    private final MatchResultQuery results;
    private final WorkoutPayloadRepository workouts;
    private final FriendHeatmapRepository heatmaps;
    private final VenueRepository venues;

    public FriendRecordQuery(MatchSocialAccess access, MatchOwnership ownership, MatchResultQuery results,
                             WorkoutPayloadRepository workouts, FriendHeatmapRepository heatmaps,
                             VenueRepository venues) {
        this.access = access;
        this.ownership = ownership;
        this.results = results;
        this.workouts = workouts;
        this.heatmaps = heatmaps;
        this.venues = venues;
    }

    public VersionedResponse<MatchResultQuery.QuarterResult> quarter(UUID viewer, UUID friend,
                                                                   UUID matchId, UUID quarterId) {
        requireCompletedMatch(viewer, friend, matchId);
        ownership.requireQuarter(friend, matchId, quarterId);
        return results.quarter(friend, quarterId);
    }

    public VersionedResponse<Venue> venue(UUID viewer, UUID friend, UUID matchId) {
        access.requireFriend(viewer, friend);
        var match = ownership.requireMatch(friend, matchId);
        var saved = match.venueId() == null ? null
                : venues.findByIdAndOwnerId(match.venueId(), friend).orElse(null);
        // Names/addresses remain the match snapshot; corners refer to the currently linked saved venue.
        var venue = saved == null && match.venueNameSnapshot() == null && match.venueAddressSnapshot() == null ? null
                : new Venue(match.venueId(), match.venueNameSnapshot(), match.venueAddressSnapshot(),
                saved == null ? null : saved.cornerA(), saved == null ? null : saved.cornerB(),
                saved == null ? null : saved.cornerC(), saved == null ? null : saved.cornerD());
        return versioned(venue, match.version());
    }

    public VersionedResponse<Heatmap> heatmap(UUID viewer, UUID friend, UUID matchId, UUID quarterId) {
        var match = requireCompletedMatch(viewer, friend, matchId);
        var quarter = ownership.requireQuarter(friend, matchId, quarterId);
        if (!"COMPLETED".equals(quarter.status())) {
            throw new MatchResultQuery.ResultNotReady();
        }
        var workout = workouts.findByQuarterId(quarterId);
        var cells = workout.map(value -> heatmaps.cells(value.id())).orElse(List.of());
        String status = workout.isEmpty() ? "MISSING_WORKOUT" : cells.isEmpty() ? "NO_VALID_POINTS" : "READY";
        return versioned(new Heatmap(quarterId, status, FriendHeatmapRepository.CELL_SIZE_DEGREES,
                cells.stream().mapToLong(FriendHeatmapRepository.Cell::sampleCount).sum(), cells), match.version());
    }

    private Match requireCompletedMatch(UUID viewer, UUID friend, UUID matchId) {
        access.requireFriend(viewer, friend);
        var match = ownership.requireMatch(friend, matchId);
        if (!"COMPLETED".equals(match.status())) {
            throw new MatchResultQuery.ResultNotReady();
        }
        return match;
    }

    private static <T> VersionedResponse<T> versioned(T data, long version) {
        var meta = MatchApiResponse.Meta.create();
        return new VersionedResponse<>(data, new DetailMeta(meta.requestId(), meta.serverTime(), version));
    }

    public record Venue(UUID id, String name, String address, FieldCorner cornerA, FieldCorner cornerB,
                        FieldCorner cornerC, FieldCorner cornerD) {}

    public record Heatmap(UUID quarterId, String status, double cellSizeDegrees, long sampleCount,
                          List<FriendHeatmapRepository.Cell> cells) {}
}
