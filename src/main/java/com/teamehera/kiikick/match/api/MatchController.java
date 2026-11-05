package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.service.MatchDetailQuery;
import com.teamehera.kiikick.match.service.MatchListQuery;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.match.service.MatchSettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

import java.security.Principal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.UUID;

@MatchApiController
@RequestMapping(MatchController.PATH)
public class MatchController {

    static final String PATH = "/api/v1/matches";
    private final MatchListQuery list;
    private final MatchDetailQuery detail;
    private final MatchSettingsService settings;
    private final MatchMutation mutation;
    private final ObjectMapper json;
    private final Duration retention;

    public MatchController(MatchListQuery list, MatchDetailQuery detail, MatchSettingsService settings,
                           MatchMutation mutation, ObjectMapper json,
                           @Value("${kiikick.match.idempotency-retention:P1D}") Duration retention) {
        this.list = list;
        this.detail = detail;
        this.settings = settings;
        this.mutation = mutation;
        this.json = json;
        this.retention = retention;
    }

    @GetMapping
    public MatchListQuery.Page list(Principal principal, @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String cursor,
                                   @RequestParam(required = false) Integer limit) {
        return list.find(actor(principal), status, cursor, limit);
    }

    @GetMapping("/{matchId}")
    public ResponseEntity<MatchDetailQuery.Detail> detail(Principal principal, @PathVariable UUID matchId) {
        return detail.find(actor(principal), matchId);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> create(Principal principal,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                         @RequestBody(required = false) byte[] body) {
        UUID actor = actor(principal);
        var request = new MatchMutation.Request(actor, null, "POST", PATH, key(key), ifMatch, body);
        return replay(mutation.create(request, expiresAt(), () ->
                serialize(settings.create(actor, parse(body)), 201)));
    }

    @PatchMapping(path = "/{matchId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> update(Principal principal, @PathVariable UUID matchId,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                         @RequestBody(required = false) byte[] body) {
        var request = new MatchMutation.Request(actor(principal), matchId, "PATCH",
                PATH + "/" + matchId, key(key), ifMatch, body);
        return replay(mutation.execute(request, expiresAt(), match -> {}, match ->
                serialize(settings.update(match, parse(body)), 200)));
    }

    private MatchSettings parse(byte[] body) {
        if (body == null) {
            throw new InvalidRequest();
        }
        return MatchSettings.parse(json, body);
    }

    private MatchMutation.Response serialize(Match match, int status) {
        var headers = new LinkedHashMap<String, String>();
        headers.put(HttpHeaders.ETAG, MatchMutation.etag(match.version()));
        if (status == 201) {
            headers.put(HttpHeaders.LOCATION, PATH + "/" + match.id());
        }
        return new MatchMutation.Response(status, json.writeValueAsString(headers),
                json.writeValueAsString(MatchDetailQuery.Detail.from(match)));
    }

    private ResponseEntity<String> replay(MatchMutation.Response response) {
        var result = ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON);
        var headers = json.readTree(response.headersJson());
        headers.properties().forEach(entry -> result.header(entry.getKey(), entry.getValue().asText()));
        return result.body(response.bodyJson());
    }

    private LocalDateTime expiresAt() {
        return LocalDateTime.now(ZoneOffset.UTC).plus(retention);
    }

    // The Bearer adapter must expose the verified internal UUID as the servlet principal name.
    private static UUID actor(Principal principal) {
        UUID id = principal == null ? null : canonicalUuid(principal.getName());
        if (id == null) {
            throw new Unauthenticated();
        }
        return id;
    }

    private static UUID key(String value) {
        UUID id = canonicalUuid(value);
        if (id == null) {
            throw new InvalidRequest();
        }
        return id;
    }

    private static UUID canonicalUuid(String value) {
        if (value != null && value.length() == 36) {
            try {
                UUID id = UUID.fromString(value);
                if (id.toString().equalsIgnoreCase(value)) {
                    return id;
                }
            } catch (IllegalArgumentException ignored) {
                // The caller maps invalid principals and invalid request headers separately.
            }
        }
        return null;
    }

    static final class InvalidRequest extends RuntimeException {}
    static final class Unauthenticated extends RuntimeException {}
}
