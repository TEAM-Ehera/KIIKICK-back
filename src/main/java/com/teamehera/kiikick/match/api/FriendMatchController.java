package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.friend.service.FriendRecordQuery;
import com.teamehera.kiikick.match.service.MatchDetailQuery;
import com.teamehera.kiikick.match.service.MatchDetailQuery.VersionedResponse;
import com.teamehera.kiikick.match.service.MatchListQuery;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.match.service.MatchOwnership;
import com.teamehera.kiikick.match.service.MatchResultQuery;
import com.teamehera.kiikick.match.service.MatchSocialAccess;
import com.teamehera.kiikick.match.service.QuarterSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.util.UUID;

@MatchApiController
@RequestMapping("/api/v1/friends/{userId}/matches")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class FriendMatchController {
    private final FriendRecordQuery records;
    private final MatchSocialAccess access;
    private final MatchOwnership ownership;
    private final MatchListQuery list;
    private final MatchDetailQuery detail;
    private final MatchResultQuery results;
    private final QuarterSettingsService quarters;

    public FriendMatchController(FriendRecordQuery records, MatchSocialAccess access, MatchOwnership ownership,
                                 MatchListQuery list, MatchDetailQuery detail, MatchResultQuery results,
                                 QuarterSettingsService quarters) {
        this.records = records;
        this.access = access;
        this.ownership = ownership;
        this.list = list;
        this.detail = detail;
        this.results = results;
        this.quarters = quarters;
    }

    @Operation(summary = "친구 경기 목록 조회")
    @GetMapping
    public ResponseEntity<MatchListQuery.Page> list(Principal principal, @PathVariable UUID userId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        access.requireFriend(MatchController.actor(principal), userId);
        var result = list.find(userId, status, cursor, limit);
        return ok(result, result.meta().requestId());
    }

    @Operation(summary = "친구 경기 상세 조회")
    @GetMapping("/{matchId}")
    public ResponseEntity<MatchDetailQuery.Detail> detail(Principal principal, @PathVariable UUID userId,
                                                         @PathVariable UUID matchId) {
        requireMatch(principal, userId, matchId);
        var result = detail.find(userId, matchId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(MatchMutation.etag(result.getBody().meta().matchVersion()))
                .header(MatchApiResponse.REQUEST_ID_HEADER, result.getBody().meta().requestId().toString())
                .body(result.getBody());
    }

    @Operation(summary = "친구 경기 쿼터 목록 조회")
    @GetMapping("/{matchId}/quarters")
    public ResponseEntity<QuarterSettingsService.Page> quarters(Principal principal, @PathVariable UUID userId,
            @PathVariable UUID matchId, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        requireMatch(principal, userId, matchId);
        var page = quarters.list(userId, matchId, cursor, limit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(MatchMutation.etag(page.meta().matchVersion()))
                .header(MatchApiResponse.REQUEST_ID_HEADER, page.meta().requestId().toString()).body(page);
    }

    @Operation(summary = "친구의 완료 경기 결과 조회")
    @GetMapping("/{matchId}/result")
    public ResponseEntity<MatchDetailQuery.VersionedResponse<MatchResultQuery.MatchResult>> result(
            Principal principal, @PathVariable UUID userId, @PathVariable UUID matchId) {
        requireMatch(principal, userId, matchId);
        var result = results.match(userId, matchId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(MatchMutation.etag(result.meta().matchVersion()))
                .header(MatchApiResponse.REQUEST_ID_HEADER, result.meta().requestId().toString()).body(result);
    }

    @Operation(summary = "친구의 완료 경기 쿼터 결과 조회")
    @GetMapping("/{matchId}/result/quarters")
    public ResponseEntity<MatchResultQuery.QuarterResults> results(Principal principal, @PathVariable UUID userId,
            @PathVariable UUID matchId, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        requireMatch(principal, userId, matchId);
        var result = results.quarters(userId, matchId, cursor, limit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(MatchMutation.etag(result.meta().matchVersion()))
                .header(MatchApiResponse.REQUEST_ID_HEADER, result.meta().requestId().toString()).body(result);
    }

    @Operation(summary = "친구 경기장 이름·주소·필드 좌표")
    @GetMapping("/{matchId}/venue")
    public ResponseEntity<VersionedResponse<FriendRecordQuery.Venue>> venue(Principal principal,
            @PathVariable UUID userId, @PathVariable UUID matchId) {
        var result = records.venue(MatchController.actor(principal), userId, matchId);
        return ok(result, result.meta().requestId());
    }

    @Operation(summary = "친구의 완료 쿼터 상세 스탯")
    @GetMapping("/{matchId}/quarters/{quarterId}/result")
    public ResponseEntity<VersionedResponse<MatchResultQuery.QuarterResult>> quarter(Principal principal,
            @PathVariable UUID userId, @PathVariable UUID matchId, @PathVariable UUID quarterId) {
        var result = records.quarter(MatchController.actor(principal), userId, matchId, quarterId);
        return ok(result, result.meta().requestId());
    }

    @Operation(summary = "친구의 완료 쿼터 히트맵")
    @GetMapping("/{matchId}/quarters/{quarterId}/heatmap")
    public ResponseEntity<VersionedResponse<FriendRecordQuery.Heatmap>> heatmap(Principal principal,
            @PathVariable UUID userId, @PathVariable UUID matchId, @PathVariable UUID quarterId) {
        var result = records.heatmap(MatchController.actor(principal), userId, matchId, quarterId);
        return ok(result, result.meta().requestId());
    }

    private static <T> ResponseEntity<T> ok(T body, UUID requestId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(MatchApiResponse.REQUEST_ID_HEADER, requestId.toString()).body(body);
    }

    private void requireMatch(Principal principal, UUID userId, UUID matchId) {
        access.requireFriend(MatchController.actor(principal), userId);
        ownership.requireMatch(userId, matchId);
    }
}
