package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.match.repository.QuarterFriendRepository;
import com.teamehera.kiikick.match.service.MatchDetailQuery.VersionedResponse;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.match.service.MatchOwnership;
import com.teamehera.kiikick.match.service.QuarterFriendService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import tools.jackson.databind.ObjectMapper;

import java.security.Principal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@MatchApiController
@RequestMapping("/api/v1/matches/{matchId}/quarters/{quarterId}/friends")
public class QuarterFriendController {
    private final QuarterFriendService friends;
    private final MatchMutation mutation;
    private final MatchOwnership ownership;
    private final ObjectMapper json;
    private final Duration retention;

    public QuarterFriendController(QuarterFriendService friends, MatchMutation mutation, MatchOwnership ownership,
                                   ObjectMapper json,
                                   @Value("${kiikick.match.idempotency-retention:P1D}") Duration retention) {
        this.friends = friends;
        this.mutation = mutation;
        this.ownership = ownership;
        this.json = json;
        this.retention = retention;
    }

    @GetMapping
    public ResponseEntity<VersionedResponse<List<QuarterFriendRepository.Friend>>> list(
            Principal principal, @PathVariable UUID matchId, @PathVariable UUID quarterId) {
        var result = friends.list(MatchController.actor(principal), matchId, quarterId);
        return ResponseEntity.ok().eTag(MatchMutation.etag(result.meta().matchVersion()))
                .header(MatchApiResponse.REQUEST_ID_HEADER, result.meta().requestId().toString()).body(result);
    }

    @PutMapping("/{friendUserId}")
    public ResponseEntity<Void> add(Principal principal, @PathVariable UUID matchId, @PathVariable UUID quarterId,
            @PathVariable UUID friendUserId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true)
            @RequestBody(required = false) byte[] body) {
        return change(principal, matchId, quarterId, friendUserId, key, ifMatch, body, true);
    }

    @DeleteMapping("/{friendUserId}")
    public ResponseEntity<Void> remove(Principal principal, @PathVariable UUID matchId, @PathVariable UUID quarterId,
            @PathVariable UUID friendUserId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true)
            @RequestBody(required = false) byte[] body) {
        return change(principal, matchId, quarterId, friendUserId, key, ifMatch, body, false);
    }

    private ResponseEntity<Void> change(Principal principal, UUID matchId, UUID quarterId, UUID friend,
                                       String key, String ifMatch, byte[] body, boolean add) {
        UUID actor = MatchController.actor(principal);
        if (body != null) {
            throw new MatchController.InvalidRequest();
        }
        String path = "/api/v1/matches/" + matchId + "/quarters/" + quarterId + "/friends/" + friend;
        var request = new MatchMutation.Request(actor, matchId, add ? "PUT" : "DELETE", path,
                MatchController.key(key), ifMatch, null);
        var result = mutation.execute(request, LocalDateTime.now(ZoneOffset.UTC).plus(retention),
                match -> ownership.requireQuarter(actor, matchId, quarterId), match -> {
                    long version = add ? friends.add(match, quarterId, friend) : friends.remove(match, quarterId, friend);
                    return new MatchMutation.Response(204, json.writeValueAsString(Map.of(
                            HttpHeaders.ETAG, MatchMutation.etag(version),
                            MatchApiResponse.REQUEST_ID_HEADER, UUID.randomUUID().toString())), null);
                });
        var response = ResponseEntity.status(result.status());
        json.readTree(result.headersJson()).properties()
                .forEach(entry -> response.header(entry.getKey(), entry.getValue().asText()));
        return response.build();
    }
}
