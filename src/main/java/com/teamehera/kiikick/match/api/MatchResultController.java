package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.match.service.MatchDetailQuery.VersionedResponse;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.match.service.MatchResultQuery;
import com.teamehera.kiikick.match.service.MatchResultQuery.MatchResult;
import com.teamehera.kiikick.match.service.MatchResultQuery.QuarterResult;
import com.teamehera.kiikick.match.service.MatchResultQuery.QuarterResults;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.util.UUID;

@MatchApiController
public class MatchResultController {
    private final MatchResultQuery results;

    public MatchResultController(MatchResultQuery results) {
        this.results = results;
    }

    @Operation(summary = "완료 경기 결과 조회")
    @GetMapping("/api/v1/matches/{matchId}/result")
    public ResponseEntity<VersionedResponse<MatchResult>> match(Principal principal, @PathVariable UUID matchId) {
        var result = results.match(MatchController.actor(principal), matchId);
        return ResponseEntity.ok().eTag(MatchMutation.etag(result.meta().matchVersion())).body(result);
    }

    @Operation(summary = "완료 경기의 쿼터 결과 목록 조회")
    @GetMapping("/api/v1/matches/{matchId}/result/quarters")
    public ResponseEntity<QuarterResults> quarters(Principal principal, @PathVariable UUID matchId,
                                                   @RequestParam(required = false) String cursor,
                                                   @RequestParam(required = false) Integer limit) {
        var result = results.quarters(MatchController.actor(principal), matchId, cursor, limit);
        return ResponseEntity.ok().eTag(MatchMutation.etag(result.meta().matchVersion())).body(result);
    }

    @Operation(summary = "완료 쿼터 결과 조회")
    @GetMapping("/api/v1/quarters/{quarterId}/result")
    public ResponseEntity<VersionedResponse<QuarterResult>> quarter(Principal principal, @PathVariable UUID quarterId) {
        var result = results.quarter(MatchController.actor(principal), quarterId);
        return ResponseEntity.ok().eTag(MatchMutation.etag(result.meta().matchVersion())).body(result);
    }
}
