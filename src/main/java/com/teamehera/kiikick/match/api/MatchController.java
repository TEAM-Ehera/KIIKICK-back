package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.service.MatchDetailQuery;
import com.teamehera.kiikick.match.service.MatchListQuery;
import com.teamehera.kiikick.match.service.MatchLifecycleService;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.match.service.MatchSettingsService;
import com.teamehera.kiikick.match.service.MatchOwnership;
import com.teamehera.kiikick.match.service.QuarterLifecycleService;
import com.teamehera.kiikick.match.service.QuarterPositionService;
import com.teamehera.kiikick.match.service.QuarterSettingsService;
import com.teamehera.kiikick.match.service.QuarterStatsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.Principal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@MatchApiController
@RequestMapping(MatchController.PATH)
public class MatchController {

    static final String PATH = "/api/v1/matches";
    private static final String QUARTERS = "/{matchId}/quarters";
    private static final String QUARTER = QUARTERS + "/{quarterId}";
    private static final String QUARTER_POSITION = QUARTER + "/position";
    private static final String QUARTER_STATS = QUARTER + "/stats";

    private final MatchListQuery list;
    private final MatchDetailQuery detail;
    private final MatchSettingsService settings;
    private final MatchLifecycleService lifecycle;
    private final MatchMutation mutation;
    private final MatchOwnership ownership;
    private final QuarterSettingsService quarters;
    private final QuarterLifecycleService quarterLifecycle;
    private final QuarterStatsService quarterStats;
    private final QuarterPositionService quarterPosition;
    private final ObjectMapper json;
    private final Duration retention;

    public MatchController(MatchListQuery list, MatchDetailQuery detail, MatchSettingsService settings,
                           MatchMutation mutation, MatchOwnership ownership, QuarterSettingsService quarters,
                           MatchLifecycleService lifecycle, QuarterLifecycleService quarterLifecycle,
                           QuarterStatsService quarterStats, QuarterPositionService quarterPosition, ObjectMapper json,
                           @Value("${kiikick.match.idempotency-retention:P1D}") Duration retention) {
        this.list = list;
        this.detail = detail;
        this.settings = settings;
        this.lifecycle = lifecycle;
        this.mutation = mutation;
        this.ownership = ownership;
        this.quarters = quarters;
        this.quarterLifecycle = quarterLifecycle;
        this.quarterStats = quarterStats;
        this.quarterPosition = quarterPosition;
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
                serialize(settings.create(actor, MatchSettings.parse(json, body)), 201)));
    }

    @PatchMapping(path = "/{matchId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> update(Principal principal, @PathVariable UUID matchId,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                         @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                         @RequestBody(required = false) byte[] body) {
        var request = new MatchMutation.Request(actor(principal), matchId, "PATCH",
                PATH + "/" + matchId, key(key), ifMatch, body);
        return replay(mutation.execute(request, expiresAt(), match -> {}, match ->
                serialize(settings.update(match, MatchSettings.parse(json, body)), 200)));
    }

    @PostMapping("/{matchId}/{action:ready|start|cancel|end}")
    public ResponseEntity<String> transition(
            Principal principal, @PathVariable UUID matchId, @PathVariable String action,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        UUID actor = actor(principal);
        if (body != null) {
            throw new InvalidRequest();
        }
        var request = new MatchMutation.Request(actor, matchId, "POST",
                PATH + "/" + matchId + "/" + action, key(key), ifMatch, null);
        return replay(mutation.execute(request, expiresAt(), match -> {}, match ->
                serialize(lifecycle.transition(match, action), 200)));
    }

    @GetMapping(QUARTERS)
    public ResponseEntity<QuarterSettingsService.Page> listQuarters(
            Principal principal, @PathVariable UUID matchId, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        var page = quarters.list(actor(principal), matchId, cursor, limit);
        return ResponseEntity.ok().eTag(MatchMutation.etag(page.meta().matchVersion())).body(page);
    }

    @PostMapping(path = QUARTERS, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> addQuarter(
            Principal principal, @PathVariable UUID matchId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        String path = quarterPath(matchId);
        var request = new MatchMutation.Request(actor(principal), matchId, "POST", path, key(key), ifMatch, body);
        return replay(mutation.execute(request, expiresAt(), match -> {}, match -> {
            var quarter = quarters.add(match, duration(body));
            return serializeQuarter(quarter, 201, path + "/" + quarter.id());
        }));
    }

    @PatchMapping(path = QUARTER, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> updateQuarter(
            Principal principal, @PathVariable UUID matchId, @PathVariable UUID quarterId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        UUID actor = actor(principal);
        var request = new MatchMutation.Request(actor, matchId, "PATCH", quarterPath(matchId) + "/" + quarterId,
                key(key), ifMatch, body);
        return replay(mutation.execute(request, expiresAt(),
                match -> ownership.requireQuarter(actor, matchId, quarterId),
                match -> serializeQuarter(quarters.update(match, quarterId, duration(body)), 200, null)));
    }

    @PostMapping(QUARTER + "/{action:start|end|complete}")
    public ResponseEntity<String> transitionQuarter(
            Principal principal, @PathVariable UUID matchId, @PathVariable UUID quarterId,
            @PathVariable String action,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        UUID actor = actor(principal);
        if (body != null) {
            throw new InvalidRequest();
        }
        var request = new MatchMutation.Request(actor, matchId, "POST",
                quarterPath(matchId) + "/" + quarterId + "/" + action, key(key), ifMatch, null);
        return replay(mutation.execute(request, expiresAt(),
                match -> ownership.requireQuarter(actor, matchId, quarterId),
                match -> serializeQuarter(quarterLifecycle.transition(match, quarterId, action), 200, null)));
    }

    @GetMapping(QUARTER_POSITION)
    public ResponseEntity<MatchDetailQuery.VersionedResponse<QuarterPositionService.Position>> getQuarterPosition(
            Principal principal, @PathVariable UUID matchId, @PathVariable UUID quarterId) {
        var response = quarterPosition.find(actor(principal), matchId, quarterId);
        return ResponseEntity.ok().eTag(MatchMutation.etag(response.meta().matchVersion())).body(response);
    }

    @PutMapping(path = QUARTER_POSITION, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> replaceQuarterPosition(
            Principal principal, @PathVariable UUID matchId, @PathVariable UUID quarterId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        UUID actor = actor(principal);
        var request = new MatchMutation.Request(actor, matchId, "PUT",
                quarterPath(matchId) + "/" + quarterId + "/position", key(key), ifMatch, body);
        return replay(mutation.execute(request, expiresAt(),
                match -> ownership.requireQuarter(actor, matchId, quarterId), match -> {
                    var input = MatchSettings.readBody(json, body);
                    MatchSettings.checkObject(input, "body", Set.of("positionCode"));
                    var response = quarterPosition.replace(match, quarterId, input.path("positionCode").stringValue());
                    return serialize(response, response.meta().matchVersion(), 200, null);
                }));
    }

    @GetMapping(QUARTER_STATS)
    public ResponseEntity<MatchDetailQuery.VersionedResponse<QuarterStatsService.Stats>> getQuarterStats(
            Principal principal, @PathVariable UUID matchId, @PathVariable UUID quarterId) {
        var response = quarterStats.find(actor(principal), matchId, quarterId);
        return ResponseEntity.ok().eTag(MatchMutation.etag(response.meta().matchVersion())).body(response);
    }

    @PutMapping(path = QUARTER_STATS, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> replaceQuarterStats(
            Principal principal, @PathVariable UUID matchId, @PathVariable UUID quarterId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        UUID actor = actor(principal);
        var request = new MatchMutation.Request(actor, matchId, "PUT",
                quarterPath(matchId) + "/" + quarterId + "/stats", key(key), ifMatch, body);
        return replay(mutation.execute(request, expiresAt(),
                match -> ownership.requireQuarter(actor, matchId, quarterId), match -> {
                    var input = MatchSettings.readBody(json, body);
                    MatchSettings.checkObject(input, "body", Set.of("goals", "assists"));
                    var invalidFields = List.of("goals", "assists").stream()
                            .filter(field -> !isNonNegativeInteger(input.path(field))).toList();
                    if (!invalidFields.isEmpty()) {
                        throw new MatchSettings.InvalidSettings(invalidFields);
                    }
                    var response = quarterStats.replace(match, quarterId,
                            input.path("goals").intValue(), input.path("assists").intValue());
                    return serialize(response, response.meta().matchVersion(), 200, null);
                }));
    }

    private static boolean isNonNegativeInteger(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0;
    }

    @DeleteMapping(QUARTER)
    public ResponseEntity<String> deleteQuarter(
            Principal principal, @PathVariable UUID matchId, @PathVariable UUID quarterId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        UUID actor = actor(principal);
        if (body != null) {
            throw new InvalidRequest();
        }
        var request = new MatchMutation.Request(actor, matchId, "DELETE", quarterPath(matchId) + "/" + quarterId,
                key(key), ifMatch, null);
        // A successful DELETE replay can only recheck the parent because the quarter is gone.
        return replay(mutation.execute(request, expiresAt(), match -> {},
                match -> ownership.requireQuarter(actor, matchId, quarterId), match -> {
                    long version = quarters.delete(match, quarterId);
                    return new MatchMutation.Response(204,
                            json.writeValueAsString(Map.of(HttpHeaders.ETAG, MatchMutation.etag(version))), null);
                }));
    }

    private static String quarterPath(UUID matchId) {
        return PATH + "/" + matchId + "/quarters";
    }

    private int duration(byte[] body) {
        var root = MatchSettings.readBody(json, body);
        MatchSettings.checkObject(root, "body", Set.of("durationSeconds"));
        var duration = root.path("durationSeconds");
        if (!duration.isIntegralNumber() || !duration.canConvertToInt() || duration.asInt() <= 0) {
            throw new MatchSettings.InvalidSettings(List.of("durationSeconds"));
        }
        return duration.asInt();
    }

    private MatchMutation.Response serializeQuarter(QuarterSettingsService.Response quarter, int status, String location) {
        var meta = MatchApiResponse.Meta.create();
        var body = new MatchDetailQuery.VersionedResponse<>(quarter,
                new MatchDetailQuery.DetailMeta(meta.requestId(), meta.serverTime(), quarter.matchVersion()));
        return serialize(body, quarter.matchVersion(), status, location);
    }

    private MatchMutation.Response serialize(Match match, int status) {
        return serialize(MatchDetailQuery.Detail.from(match), match.version(), status,
                status == 201 ? PATH + "/" + match.id() : null);
    }

    private MatchMutation.Response serialize(Object body, long version, int status, String location) {
        var headers = new LinkedHashMap<String, String>();
        headers.put(HttpHeaders.ETAG, MatchMutation.etag(version));
        if (location != null) {
            headers.put(HttpHeaders.LOCATION, location);
        }
        return new MatchMutation.Response(status, json.writeValueAsString(headers), json.writeValueAsString(body));
    }

    private ResponseEntity<String> replay(MatchMutation.Response response) {
        var result = ResponseEntity.status(response.status());
        if (response.bodyJson() != null) {
            result.contentType(MediaType.APPLICATION_JSON);
        }
        var headers = json.readTree(response.headersJson());
        headers.properties().forEach(entry -> result.header(entry.getKey(), entry.getValue().asText()));
        return result.body(response.bodyJson());
    }

    private LocalDateTime expiresAt() {
        return LocalDateTime.now(ZoneOffset.UTC).plus(retention);
    }

    // The Bearer adapter must expose the verified internal UUID as the servlet principal name.
    static UUID actor(Principal principal) {
        UUID id = principal == null ? null : canonicalUuid(principal.getName());
        if (id == null) {
            throw new Unauthenticated();
        }
        return id;
    }

    static UUID key(String value) {
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
