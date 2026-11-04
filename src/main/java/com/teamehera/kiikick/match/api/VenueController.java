package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.venue.service.VenueService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@MatchApiController
@RequestMapping(VenueController.PATH)
public class VenueController {
    static final String PATH = "/api/v1/venues";
    private static final Set<String> FIELDS = Set.of("name", "address");

    private final VenueService venues;
    private final MatchMutation mutation;
    private final ObjectMapper json;
    private final Duration retention;

    public VenueController(VenueService venues, MatchMutation mutation, ObjectMapper json,
                           @Value("${kiikick.match.idempotency-retention:P1D}") Duration retention) {
        this.venues = venues;
        this.mutation = mutation;
        this.json = json;
        this.retention = retention;
    }

    @Operation(summary = "내 저장 경기장 목록·검색")
    @GetMapping
    public VenueService.Page list(Principal principal, @RequestParam(required = false) String q,
                                  @RequestParam(required = false) String cursor,
                                  @RequestParam(required = false) Integer limit) {
        return venues.list(MatchController.actor(principal), q, cursor, limit);
    }

    @Operation(summary = "내 저장 경기장 상세")
    @GetMapping("/{venueId}")
    public MatchApiResponse.Success<VenueService.View> detail(Principal principal, @PathVariable UUID venueId) {
        return success(venues.detail(MatchController.actor(principal), venueId));
    }

    @Operation(summary = "경기장 저장")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> create(Principal principal,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        var request = request(principal, "POST", PATH, key, ifMatch, body);
        return replay(mutation.executeUnversioned(request, expiresAt(), () -> {
            var input = input(body);
            var venue = venues.create(request.actorUserId(), name(input, true), address(input));
            return response(201, Map.of(HttpHeaders.LOCATION, PATH + "/" + venue.id()), venue);
        }));
    }

    @Operation(summary = "저장 경기장 수정")
    @PatchMapping(path = "/{venueId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> update(Principal principal, @PathVariable UUID venueId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        var request = request(principal, "PATCH", PATH + "/" + venueId, key, ifMatch, body);
        return replay(mutation.executeUnversioned(request, expiresAt(),
                () -> venues.detail(request.actorUserId(), venueId), () -> {
                    var input = input(body);
                    var venue = venues.update(request.actorUserId(), venueId, name(input, input.has("name")),
                            address(input), input.has("address"));
                    return response(200, Map.of(), venue);
                }));
    }

    @Operation(summary = "저장 경기장 삭제")
    @DeleteMapping("/{venueId}")
    public ResponseEntity<String> delete(Principal principal, @PathVariable UUID venueId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        var request = request(principal, "DELETE", PATH + "/" + venueId, key, ifMatch, body);
        if (body != null) {
            throw new MatchController.InvalidRequest();
        }
        return replay(mutation.executeUnversioned(request, expiresAt(), () -> {
            venues.delete(request.actorUserId(), venueId);
            return response(204, Map.of(), null);
        }));
    }

    private static MatchMutation.Request request(Principal principal, String method, String path, String key,
                                                  String ifMatch, byte[] body) {
        return new MatchMutation.Request(MatchController.actor(principal), null, method, path,
                MatchController.key(key), ifMatch, body);
    }

    private LocalDateTime expiresAt() {
        return LocalDateTime.now(ZoneOffset.UTC).plus(retention);
    }

    private JsonNode input(byte[] body) {
        var input = MatchSettings.readBody(json, body);
        MatchSettings.checkObject(input, "body", FIELDS);
        return input;
    }

    private static String name(JsonNode input, boolean required) {
        if (!required) {
            return null;
        }
        var name = input.path("name");
        if (!name.isTextual() || name.asText().isBlank() || length(name.asText()) > 255) {
            throw new MatchSettings.InvalidSettings(List.of("name"));
        }
        return name.asText();
    }

    private static String address(JsonNode input) {
        var address = input.get("address");
        if (address == null || address.isNull()) {
            return null;
        }
        if (!address.isTextual() || length(address.asText()) > 1000) {
            throw new MatchSettings.InvalidSettings(List.of("address"));
        }
        return address.asText();
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    private MatchMutation.Response response(int status, Map<String, String> headers, VenueService.View venue) {
        return new MatchMutation.Response(status, json.writeValueAsString(headers),
                venue == null ? null : json.writeValueAsString(success(venue)));
    }

    private static MatchApiResponse.Success<VenueService.View> success(VenueService.View venue) {
        return new MatchApiResponse.Success<>(venue, MatchApiResponse.Meta.create());
    }

    private ResponseEntity<String> replay(MatchMutation.Response result) {
        var response = ResponseEntity.status(result.status());
        if (result.bodyJson() != null) {
            response.contentType(MediaType.APPLICATION_JSON);
        }
        json.readTree(result.headersJson()).properties()
                .forEach(entry -> response.header(entry.getKey(), entry.getValue().asText()));
        return response.body(result.bodyJson());
    }
}
