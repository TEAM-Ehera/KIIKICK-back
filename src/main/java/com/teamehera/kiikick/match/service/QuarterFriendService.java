package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.friend.repository.FriendDirectoryRepository;
import com.teamehera.kiikick.match.api.MatchApiResponse;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterFriendRepository;
import com.teamehera.kiikick.match.service.MatchDetailQuery.DetailMeta;
import com.teamehera.kiikick.match.service.MatchDetailQuery.VersionedResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class QuarterFriendService {
    private final MatchOwnership ownership;
    private final FriendDirectoryRepository directory;
    private final QuarterFriendRepository friends;
    private final MatchRepository matches;

    public QuarterFriendService(MatchOwnership ownership, FriendDirectoryRepository directory,
                               QuarterFriendRepository friends, MatchRepository matches) {
        this.ownership = ownership;
        this.directory = directory;
        this.friends = friends;
        this.matches = matches;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public VersionedResponse<List<QuarterFriendRepository.Friend>> list(UUID actor, UUID matchId, UUID quarterId) {
        var match = ownership.requireMatch(actor, matchId);
        ownership.requireQuarter(actor, matchId, quarterId);
        var meta = MatchApiResponse.Meta.create();
        return new VersionedResponse<>(friends.findByQuarterId(quarterId, actor),
                new DetailMeta(meta.requestId(), meta.serverTime(), match.version()));
    }

    // MatchMutation holds the owner and match locks, also serializing friendship removal/blocking.
    public long add(Match match, UUID quarterId, UUID friend) {
        directory.profile(match.ownerId(), friend).filter(row -> "ACCEPTED".equals(row.status()))
                .orElseThrow(MatchOwnership.ResourceNotFound::new);
        friends.add(quarterId, friend);
        return incrementVersion(match);
    }

    public long remove(Match match, UUID quarterId, UUID friend) {
        // A historical entry remains removable after the friendship has ended or either side blocks.
        friends.remove(quarterId, friend);
        return incrementVersion(match);
    }

    private long incrementVersion(Match match) {
        if (matches.incrementVersion(match.id(), match.ownerId(), match.version()) != 1) {
            throw new IllegalStateException("Could not increment the locked match version");
        }
        return Math.addExact(match.version(), 1);
    }
}
