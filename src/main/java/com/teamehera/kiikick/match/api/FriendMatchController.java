package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.match.service.*;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@MatchApiController
@RequestMapping("/api/v1/friends/{userId}/matches")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class FriendMatchController {
    private final MatchSocialAccess access;
    private final MatchOwnership ownership;
    private final MatchListQuery list;
    private final MatchDetailQuery detail;
    private final MatchResultQuery results;
    private final QuarterSettingsService quarters;

    public FriendMatchController(MatchSocialAccess access, MatchOwnership ownership, MatchListQuery list,
                                 MatchDetailQuery detail, MatchResultQuery results, QuarterSettingsService quarters) {
        this.access = access;
        this.ownership = ownership;
        this.list = list;
        this.detail = detail;
        this.results = results;
        this.quarters = quarters;
    }

    @Operation(summary = "친구 경기 목록 조회")
    @GetMapping
    public MatchListQuery.Page list(Principal principal, @PathVariable UUID userId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        access.requireFriend(MatchController.actor(principal), userId);
        return list.find(userId, status, cursor, limit);
    }

    @Operation(summary = "친구 경기 상세 조회")
    @GetMapping("/{matchId}")
    public ResponseEntity<MatchDetailQuery.Detail> detail(Principal principal, @PathVariable UUID userId,
                                                         @PathVariable UUID matchId) {
        requireMatch(principal, userId, matchId);
        return detail.find(userId, matchId);
    }

    @Operation(summary = "친구 경기 쿼터 목록 조회")
    @GetMapping("/{matchId}/quarters")
    public ResponseEntity<QuarterSettingsService.Page> quarters(Principal principal, @PathVariable UUID userId,
            @PathVariable UUID matchId, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        requireMatch(principal, userId, matchId);
        var page = quarters.list(userId, matchId, cursor, limit);
        return ResponseEntity.ok().eTag(MatchMutation.etag(page.meta().matchVersion())).body(page);
    }

    @Operation(summary = "친구의 완료 경기 결과 조회")
    @GetMapping("/{matchId}/result")
    public ResponseEntity<MatchDetailQuery.VersionedResponse<MatchResultQuery.MatchResult>> result(
            Principal principal, @PathVariable UUID userId, @PathVariable UUID matchId) {
        requireMatch(principal, userId, matchId);
        var result = results.match(userId, matchId);
        return ResponseEntity.ok().eTag(MatchMutation.etag(result.meta().matchVersion())).body(result);
    }

    @Operation(summary = "친구의 완료 경기 쿼터 결과 조회")
    @GetMapping("/{matchId}/result/quarters")
    public ResponseEntity<MatchResultQuery.QuarterResults> results(Principal principal, @PathVariable UUID userId,
            @PathVariable UUID matchId, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        requireMatch(principal, userId, matchId);
        var result = results.quarters(userId, matchId, cursor, limit);
        return ResponseEntity.ok().eTag(MatchMutation.etag(result.meta().matchVersion())).body(result);
    }

    private void requireMatch(Principal principal, UUID userId, UUID matchId) {
        access.requireFriend(MatchController.actor(principal), userId);
        ownership.requireMatch(userId, matchId);
    }
}
