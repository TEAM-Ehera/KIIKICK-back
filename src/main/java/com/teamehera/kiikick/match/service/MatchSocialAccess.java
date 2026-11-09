package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.friend.service.FriendQuery;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.repository.MatchRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MatchSocialAccess {
    private final FriendQuery profiles;
    private final MatchRepository matches;

    public MatchSocialAccess(FriendQuery profiles, MatchRepository matches) {
        this.profiles = profiles;
        this.matches = matches;
    }

    public void requireFriend(UUID actor, UUID friend) {
        // 공개 프로필 조회에서 양방향 차단·탈퇴를 검사하고 수락된 친구만 허용한다.
        if (actor.equals(friend) || !"FRIEND".equals(profiles.profile(actor, friend).relationship())) {
            throw new MatchOwnership.ResourceNotFound();
        }
    }

    public Match requireVisibleMatch(UUID actor, UUID matchId) {
        var match = matches.findById(matchId).orElseThrow(MatchOwnership.ResourceNotFound::new);
        // 소유권 검사와 분리하여 친구에게 경기 수정 권한이 생기지 않게 한다.
        if (!actor.equals(match.ownerId())) {
            requireFriend(actor, match.ownerId());
        }
        return match;
    }
}
