package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.notification.service.NotificationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@MatchApiController
@RequestMapping("/api/v1")
public class NotificationController {
    private static final Set<String> PLATFORMS = Set.of("IOS", "WATCHOS");

    private final NotificationService notifications;
    private final MatchMutation mutation;
    private final ObjectMapper json;
    private final Duration retention;

    public NotificationController(NotificationService notifications, MatchMutation mutation, ObjectMapper json,
                                  @Value("${kiikick.match.idempotency-retention:P1D}") Duration retention) {
        this.notifications = notifications;
        this.mutation = mutation;
        this.json = json;
        this.retention = retention;
    }

    @GetMapping("/notifications")
    public ResponseEntity<NotificationService.Page> list(Principal principal,
            @RequestParam(defaultValue = "false") boolean unreadOnly, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        var page = notifications.list(MatchController.actor(principal), unreadOnly, cursor, limit);
        return ResponseEntity.ok().header(MatchApiResponse.REQUEST_ID_HEADER, page.meta().requestId().toString())
                .body(page);
    }

    @GetMapping("/notifications/unread-count")
    public ResponseEntity<MatchApiResponse.Success<NotificationService.Count>> unread(Principal principal) {
        var meta = MatchApiResponse.Meta.create();
        return ResponseEntity.ok().header(MatchApiResponse.REQUEST_ID_HEADER, meta.requestId().toString())
                .body(new MatchApiResponse.Success<>(notifications.unread(MatchController.actor(principal)), meta));
    }

    @PutMapping("/notifications/{notificationId}/read")
    public ResponseEntity<String> read(Principal principal, @PathVariable UUID notificationId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        var request = request(principal, "PUT", "/notifications/" + notificationId + "/read", key, ifMatch, body);
        requireNoBody(body);
        return execute(request, () -> notifications.requireVisible(request.actorUserId(), notificationId),
                () -> response(200, notifications.read(request.actorUserId(), notificationId)));
    }

    @PutMapping(path = "/devices/{deviceId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> register(Principal principal, @PathVariable UUID deviceId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        var request = request(principal, "PUT", "/devices/" + deviceId, key, ifMatch, body);
        return execute(request, () -> {}, () -> response(200, register(request.actorUserId(), deviceId, body)));
    }

    @DeleteMapping("/devices/{deviceId}")
    public ResponseEntity<String> revoke(Principal principal, @PathVariable UUID deviceId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        var request = request(principal, "DELETE", "/devices/" + deviceId, key, ifMatch, body);
        requireNoBody(body);
        return execute(request, () -> {}, () -> {
            notifications.revoke(request.actorUserId(), deviceId);
            return response(204, null);
        });
    }

    private NotificationService.DeviceView register(UUID actor, UUID deviceId, byte[] body) {
        var input = MatchSettings.readBody(json, body);
        MatchSettings.checkObject(input, "body", Set.of("platform", "pushToken", "pushEnabled"));
        var platform = input.path("platform");
        if (!platform.isTextual() || !PLATFORMS.contains(platform.stringValue())) {
            throw invalid("platform");
        }
        var enabled = input.path("pushEnabled");
        if (!enabled.isBoolean()) {
            throw invalid("pushEnabled");
        }
        var token = input.get("pushToken");
        String value = null;
        if (token != null && !token.isNull()) {
            if (!token.isTextual()) {
                throw invalid("pushToken");
            }
            value = token.stringValue();
            if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 1024) {
                throw invalid("pushToken");
            }
        }
        return notifications.register(actor, deviceId, platform.stringValue(), enabled.booleanValue(), token != null, value);
    }

    private static MatchSettings.InvalidSettings invalid(String field) {
        return new MatchSettings.InvalidSettings(List.of(field));
    }

    private static MatchMutation.Request request(Principal principal, String method, String path, String key,
                                                 String ifMatch, byte[] body) {
        return new MatchMutation.Request(MatchController.actor(principal), null, method, "/api/v1" + path,
                MatchController.key(key), ifMatch, body);
    }

    private static void requireNoBody(byte[] body) {
        if (body != null) {
            throw new MatchController.InvalidRequest();
        }
    }

    private MatchMutation.Response response(int status, Object data) {
        var meta = MatchApiResponse.Meta.create();
        var headers = Map.of(MatchApiResponse.REQUEST_ID_HEADER, meta.requestId().toString());
        return new MatchMutation.Response(status, json.writeValueAsString(headers),
                data == null ? null : json.writeValueAsString(new MatchApiResponse.Success<>(data, meta)));
    }

    private ResponseEntity<String> execute(MatchMutation.Request request, Runnable access,
                                           Supplier<MatchMutation.Response> change) {
        var result = mutation.executeUnversioned(request, LocalDateTime.now(ZoneOffset.UTC).plus(retention), access, change);
        var response = ResponseEntity.status(result.status());
        if (result.bodyJson() != null) {
            response.contentType(MediaType.APPLICATION_JSON);
        }
        json.readTree(result.headersJson()).properties()
                .forEach(entry -> response.header(entry.getKey(), entry.getValue().asText()));
        return response.body(result.bodyJson());
    }
}
