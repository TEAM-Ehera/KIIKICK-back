package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.friend.service.FriendCommands;
import com.teamehera.kiikick.friend.service.FriendQuery;
import com.teamehera.kiikick.match.service.MatchMutation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

import java.security.Principal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@MatchApiController
@RequestMapping("/api/v1")
public class FriendController {
    private static final String REQUESTS = "/friend-requests";
    private static final String REQUEST = REQUESTS + "/{requestId}";
    private static final String FRIENDS = "/friends";
    private static final String BLOCK = "/users/{userId}/block";

    private final FriendQuery query;
    private final FriendCommands commands;
    private final ObjectMapper json;
    private final Duration retention;

    public FriendController(FriendQuery query, FriendCommands commands, ObjectMapper json,
                            @Value("${kiikick.match.idempotency-retention:P1D}") Duration retention) {
        this.query = query;
        this.commands = commands;
        this.json = json;
        this.retention = retention;
    }

    @GetMapping("/users/search")
    public ResponseEntity<FriendQuery.Page<FriendQuery.Profile>> search(Principal principal, @RequestParam(required = false) String query,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        var page = this.query.search(MatchController.actor(principal), query, cursor, limit);
        return ok(page, page.meta().requestId());
    }

    @GetMapping("/users/{userId}/profile")
    public ResponseEntity<MatchApiResponse.Success<FriendQuery.Profile>> profile(Principal principal, @PathVariable UUID userId) {
        var result = success(query.profile(MatchController.actor(principal), userId));
        return ok(result, result.meta().requestId());
    }

    @GetMapping(FRIENDS)
    public ResponseEntity<FriendQuery.Page<FriendQuery.Friend>> friends(Principal principal, @RequestParam(required = false) String cursor,
                                                       @RequestParam(required = false) Integer limit) {
        var page = query.friends(MatchController.actor(principal), cursor, limit);
        return ok(page, page.meta().requestId());
    }

    @GetMapping(REQUESTS)
    public ResponseEntity<FriendQuery.Page<FriendQuery.Request>> requests(Principal principal, @RequestParam String direction,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        var page = query.requests(MatchController.actor(principal), direction, cursor, limit);
        return ok(page, page.meta().requestId());
    }

    @PostMapping(path = REQUESTS, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> send(Principal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        var request = request(principal, "POST", REQUESTS, key, ifMatch, body);
        UUID target = target(body);
        return replay(commands.send(request, expiresAt(), target, result -> response(201, result)));
    }

    @PostMapping(REQUEST + "/accept")
    public ResponseEntity<String> accept(Principal principal, @PathVariable UUID requestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        var request = request(principal, "POST", REQUESTS + "/" + requestId + "/accept", key, ifMatch, body);
        requireNoBody(body);
        return replay(commands.accept(request, expiresAt(), requestId, result -> response(200, result)));
    }

    @DeleteMapping(REQUEST)
    public ResponseEntity<String> cancel(Principal principal, @PathVariable UUID requestId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        var request = request(principal, "DELETE", REQUESTS + "/" + requestId, key, ifMatch, body);
        requireNoBody(body);
        return replay(commands.forRequest(request, expiresAt(), requestId, () -> {
            commands.cancel(request.actorUserId(), requestId);
            return response(204, null);
        }));
    }

    @DeleteMapping(FRIENDS + "/{userId}")
    public ResponseEntity<String> remove(Principal principal, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        var request = request(principal, "DELETE", FRIENDS + "/" + userId, key, ifMatch, body);
        requireNoBody(body);
        return replay(commands.forUser(request, expiresAt(), userId, () -> {
            commands.remove(request.actorUserId(), userId);
            return response(204, null);
        }));
    }

    @PutMapping(BLOCK)
    public ResponseEntity<String> block(Principal principal, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        var request = request(principal, "PUT", blockPath(userId), key, ifMatch, body);
        requireNoBody(body);
        return replay(commands.forUser(request, expiresAt(), userId, () -> {
            commands.block(request.actorUserId(), userId);
            return response(204, null);
        }));
    }

    @DeleteMapping(BLOCK)
    public ResponseEntity<String> unblock(Principal principal, @PathVariable UUID userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        var request = request(principal, "DELETE", blockPath(userId), key, ifMatch, body);
        requireNoBody(body);
        return replay(commands.forUser(request, expiresAt(), userId, () -> {
            commands.unblock(request.actorUserId(), userId);
            return response(204, null);
        }));
    }

    private static String blockPath(UUID userId) {
        return "/users/" + userId + "/block";
    }

    private static MatchMutation.Request request(Principal principal, String method, String path, String key,
                                                  String ifMatch, byte[] body) {
        return new MatchMutation.Request(MatchController.actor(principal), null, method, "/api/v1" + path,
                MatchController.key(key), ifMatch, body);
    }

    private LocalDateTime expiresAt() {
        return LocalDateTime.now(ZoneOffset.UTC).plus(retention);
    }

    private UUID target(byte[] body) {
        var input = MatchSettings.readBody(json, body);
        MatchSettings.checkObject(input, "body", Set.of("targetUserId"));
        var target = input.path("targetUserId");
        if (target.isTextual()) {
            String value = target.asText();
            try {
                var id = UUID.fromString(value);
                if (id.toString().equalsIgnoreCase(value)) {
                    return id;
                }
            } catch (IllegalArgumentException ignored) {
                // Return the same validation error for malformed IDs.
            }
        }
        throw new MatchSettings.InvalidSettings(List.of("targetUserId"));
    }

    private static void requireNoBody(byte[] body) {
        if (body != null) {
            throw new MatchController.InvalidRequest();
        }
    }

    private MatchMutation.Response response(int status, FriendQuery.Request request) {
        var meta = MatchApiResponse.Meta.create();
        String headers = json.writeValueAsString(Map.of(MatchApiResponse.REQUEST_ID_HEADER, meta.requestId().toString()));
        return new MatchMutation.Response(status, headers,
                request == null ? null : json.writeValueAsString(new MatchApiResponse.Success<>(request, meta)));
    }

    private static <T> ResponseEntity<T> ok(T body, UUID requestId) {
        return ResponseEntity.ok().header(MatchApiResponse.REQUEST_ID_HEADER, requestId.toString()).body(body);
    }

    private static <T> MatchApiResponse.Success<T> success(T data) {
        return new MatchApiResponse.Success<>(data, MatchApiResponse.Meta.create());
    }

    private ResponseEntity<String> replay(MatchMutation.Response result) {
        var response = ResponseEntity.status(result.status());
        if (result.bodyJson() != null) {
            response.contentType(MediaType.APPLICATION_JSON);
        }
        var headers = json.readTree(result.headersJson());
        String requestId = headers.path(MatchApiResponse.REQUEST_ID_HEADER).asText("");
        if (requestId.isEmpty()) {
            // Older replay rows contain only the response body, or no body for 204.
            requestId = result.bodyJson() == null ? UUID.randomUUID().toString()
                    : json.readTree(result.bodyJson()).at("/meta/requestId").asText();
        }
        return response.header(MatchApiResponse.REQUEST_ID_HEADER, requestId).body(result.bodyJson());
    }
}
