package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.match.repository.QuarterRepository;
import com.teamehera.kiikick.match.service.MatchDetailQuery.DetailMeta;
import com.teamehera.kiikick.match.service.MatchDetailQuery.VersionedResponse;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.match.service.MatchOwnership;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateRequest;
import com.teamehera.kiikick.workout.service.QuarterWorkoutUpload;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.teamehera.kiikick.match.api.MatchApiResponse.fromUtc;

@MatchApiController
public class QuarterWorkoutController {
    private static final String PATH = "/api/v1/quarters/";
    private static final Set<String> TOTAL_FIELDS = Set.of("startDate", "endDate", "totalCalories", "totalDistance");
    private static final Set<String> PAYLOAD_FIELDS =
            Set.of("startDate", "endDate", "totalCalories", "totalDistance", "points", "heartRateSamples");
    private static final Set<String> POINT_FIELDS =
            Set.of("latitude", "longitude", "altitude", "horizontalAccuracy", "speed", "course", "timestamp");
    private static final Set<String> HEART_RATE_FIELDS = Set.of("timestamp", "bpm");

    private final QuarterRepository quarters;
    private final MatchOwnership ownership;
    private final MatchMutation mutation;
    private final QuarterWorkoutUpload upload;
    private final ObjectMapper json;
    private final Duration retention;
    private final int maxBytes;

    public QuarterWorkoutController(QuarterRepository quarters, MatchOwnership ownership, MatchMutation mutation,
                                    QuarterWorkoutUpload upload, ObjectMapper json,
                                    @Value("${kiikick.match.idempotency-retention:P1D}") Duration retention,
                                    @Value("${kiikick.workout.max-upload-bytes:8388608}") int maxBytes) {
        this.quarters = quarters;
        this.ownership = ownership;
        this.mutation = mutation;
        this.upload = upload;
        this.json = json;
        this.retention = retention;
        if (maxBytes < 1 || maxBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("kiikick.workout.max-upload-bytes must be positive and leave room for a sentinel byte");
        }
        this.maxBytes = maxBytes;
    }

    @PostMapping(path = PATH + "{quarterId}/workout-payloads", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> upload(
            Principal principal, @PathVariable UUID quarterId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            HttpServletRequest httpRequest) throws IOException {
        UUID actor = MatchController.actor(principal);
        var quarter = quarters.findByIdAndOwnerId(quarterId, actor)
                .orElseThrow(MatchOwnership.ResourceNotFound::new);
        UUID requestKey = MatchController.key(key);
        if (httpRequest.getContentLengthLong() > maxBytes) {
            throw new PayloadTooLarge();
        }
        byte[] body = httpRequest.getInputStream().readNBytes(Math.addExact(maxBytes, 1));
        if (body.length > maxBytes) {
            throw new PayloadTooLarge();
        }
        String path = PATH + quarterId;
        var request = new MatchMutation.Request(actor, quarter.matchId(), "POST",
                path + "/workout-payloads", requestKey, ifMatch, body);
        var result = mutation.execute(request, LocalDateTime.now(ZoneOffset.UTC).plus(retention),
                match -> ownership.requireQuarter(actor, match.id(), quarterId),
                match -> serialize(upload.save(match, ownership.requireQuarter(actor, match.id(), quarterId),
                        parse(body)), path));
        var response = ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON);
        json.readTree(result.headersJson()).properties()
                .forEach(entry -> response.header(entry.getKey(), entry.getValue().asText()));
        return response.body(result.bodyJson());
    }

    private WorkoutPayloadCreateRequest parse(byte[] body) {
        var root = MatchSettings.readBody(json, body);
        MatchSettings.checkObject(root, "body", PAYLOAD_FIELDS);
        requireNumbers(root, TOTAL_FIELDS, "");
        requireSamples(root.path("points"), POINT_FIELDS, "points");
        requireSamples(root.path("heartRateSamples"), HEART_RATE_FIELDS, "heartRateSamples");
        return json.treeToValue(root, WorkoutPayloadCreateRequest.class);
    }

    private static void requireSamples(JsonNode samples, Set<String> fields, String name) {
        if (!samples.isArray()) {
            throw new MatchSettings.InvalidSettings(List.of(name));
        }
        int index = 0;
        for (var sample : samples) {
            String field = name + "[" + index++ + "]";
            MatchSettings.checkObject(sample, field, fields);
            requireNumbers(sample, fields, field + ".");
        }
    }

    private static void requireNumbers(JsonNode object, Set<String> fields, String prefix) {
        var invalid = fields.stream().filter(field -> {
            var value = object.path(field);
            return !value.isNumber() || !Double.isFinite(value.doubleValue());
        }).sorted().map(field -> prefix + field).toList();
        if (!invalid.isEmpty()) {
            throw new MatchSettings.InvalidSettings(invalid);
        }
    }

    private MatchMutation.Response serialize(QuarterWorkoutUpload.Result result, String path) {
        var workout = result.workout();
        var data = new Workout(workout.id(), workout.quarterId(), fromUtc(workout.startDate()),
                fromUtc(workout.endDate()), workout.totalCalories(), workout.totalDistance());
        var meta = MatchApiResponse.Meta.create();
        var body = new VersionedResponse<>(data, new DetailMeta(meta.requestId(), meta.serverTime(), result.matchVersion()));
        String tag = MatchMutation.etag(result.matchVersion());
        var headers = result.created()
                ? Map.of(HttpHeaders.ETAG, tag, HttpHeaders.LOCATION, path + "/workout")
                : Map.of(HttpHeaders.ETAG, tag);
        return new MatchMutation.Response(result.created() ? 201 : 200,
                json.writeValueAsString(headers), json.writeValueAsString(body));
    }

    public record Workout(UUID id, UUID quarterId, OffsetDateTime startDate, OffsetDateTime endDate,
                          double totalCalories, double totalDistance) {}

    static final class PayloadTooLarge extends RuntimeException {}
}
