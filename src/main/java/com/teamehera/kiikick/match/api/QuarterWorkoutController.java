package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.match.repository.QuarterRepository;
import com.teamehera.kiikick.match.service.MatchDetailQuery.DetailMeta;
import com.teamehera.kiikick.match.service.MatchDetailQuery.VersionedResponse;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.match.service.MatchOwnership;
import com.teamehera.kiikick.match.service.MatchWorkoutSubmission;
import com.teamehera.kiikick.match.service.QuarterWorkoutQuery;
import com.teamehera.kiikick.match.service.QuarterWorkoutQuery.Workout;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateRequest;
import com.teamehera.kiikick.workout.service.QuarterSummaryCommands;
import com.teamehera.kiikick.workout.service.QuarterWorkoutUpload;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.security.Principal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@MatchApiController
public class QuarterWorkoutController {
    private static final String PATH = "/api/v1/quarters/";
    private static final String SUMMARY = PATH + "{quarterId}/summary";
    private static final String SUMMARY_RETRY_SECONDS = "5";
    private static final Set<String> TOTAL_FIELDS = Set.of("startDate", "endDate", "totalCalories", "totalDistance");
    private static final Set<String> PAYLOAD_FIELDS =
            Set.of("startDate", "endDate", "totalCalories", "totalDistance", "points", "heartRateSamples", "events", "field");
    private static final Set<String> POINT_FIELDS =
            Set.of("latitude", "longitude", "altitude", "horizontalAccuracy", "speed", "course", "timestamp");
    private static final Set<String> HEART_RATE_FIELDS = Set.of("timestamp", "bpm");

    private final QuarterRepository quarters;
    private final MatchOwnership ownership;
    private final MatchMutation mutation;
    private final QuarterWorkoutUpload upload;
    private final QuarterWorkoutQuery query;
    private final MatchWorkoutSubmission submission;
    private final QuarterSummaryCommands summaryCommands;
    private final ObjectMapper json;
    private final Duration retention;
    private final int maxBytes;

    public QuarterWorkoutController(QuarterRepository quarters, MatchOwnership ownership, MatchMutation mutation,
                                    QuarterWorkoutUpload upload, QuarterWorkoutQuery query,
                                    QuarterSummaryCommands summaryCommands, MatchWorkoutSubmission submission, ObjectMapper json,
                                    @Value("${kiikick.match.idempotency-retention:P1D}") Duration retention,
                                    @Value("${kiikick.workout.max-upload-bytes:8388608}") int maxBytes) {
        this.quarters = quarters;
        this.ownership = ownership;
        this.mutation = mutation;
        this.upload = upload;
        this.query = query;
        this.submission = submission;
        this.summaryCommands = summaryCommands;
        this.json = json;
        this.retention = retention;
        if (maxBytes < 1 || maxBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("kiikick.workout.max-upload-bytes must be positive and leave room for a sentinel byte");
        }
        this.maxBytes = maxBytes;
    }

    @GetMapping(PATH + "{quarterId}/workout")
    public ResponseEntity<VersionedResponse<Workout>> workout(Principal principal, @PathVariable UUID quarterId) {
        var result = query.workout(MatchController.actor(principal), quarterId);
        return ResponseEntity.ok().eTag(MatchMutation.etag(result.meta().matchVersion())).body(result);
    }

    @GetMapping(SUMMARY)
    public ResponseEntity<VersionedResponse<QuarterWorkoutQuery.Summary>> summary(
            Principal principal, @PathVariable UUID quarterId) {
        var result = query.summary(MatchController.actor(principal), quarterId);
        var response = ResponseEntity.ok().eTag(MatchMutation.etag(result.meta().matchVersion()));
        if (result.data() != null && "PROCESSING".equals(result.data().calculationStatus())) {
            response.header(HttpHeaders.RETRY_AFTER, SUMMARY_RETRY_SECONDS);
        }
        return response.body(result);
    }

    @PutMapping(path = SUMMARY + "/settings", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> changeSummarySettings(
            Principal principal, @PathVariable UUID quarterId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        return queueSummary(MatchController.actor(principal), quarterId, key, ifMatch, "PUT", "/settings", body);
    }

    @PostMapping(SUMMARY + "/recalculate")
    public ResponseEntity<String> recalculateSummary(
            Principal principal, @PathVariable UUID quarterId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody(required = false) byte[] body) {
        UUID actor = MatchController.actor(principal);
        if (body != null) {
            throw new MatchController.InvalidRequest();
        }
        return queueSummary(actor, quarterId, key, ifMatch, "POST", "/recalculate", null);
    }

    private ResponseEntity<String> queueSummary(UUID actor, UUID quarterId, String key, String ifMatch,
                                                String method, String action, byte[] body) {
        var quarter = quarters.findByIdAndOwnerId(quarterId, actor).orElseThrow(MatchOwnership.ResourceNotFound::new);
        String location = PATH + quarterId + "/summary";
        var request = new MatchMutation.Request(actor, quarter.matchId(), method, location + action,
                MatchController.key(key), ifMatch, body);
        return replay(mutation.execute(request, LocalDateTime.now(ZoneOffset.UTC).plus(retention),
                match -> ownership.requireQuarter(actor, match.id(), quarterId), match -> {
                    summaryCommands.queue(match, quarterId, "PUT".equals(method) ? reference(body) : null);
                    var result = query.summary(actor, quarterId);
                    var headers = Map.of(HttpHeaders.LOCATION, location, HttpHeaders.RETRY_AFTER, SUMMARY_RETRY_SECONDS,
                            HttpHeaders.ETAG, MatchMutation.etag(result.meta().matchVersion()));
                    return new MatchMutation.Response(202, json.writeValueAsString(headers), json.writeValueAsString(result));
                }));
    }

    private double reference(byte[] body) {
        var root = MatchSettings.readBody(json, body);
        var fields = Set.of("hrMaxReferenceBpm");
        MatchSettings.checkObject(root, "body", fields);
        requireNumbers(root, fields, "");
        double reference = root.path("hrMaxReferenceBpm").doubleValue();
        if (reference <= 0) {
            throw new MatchSettings.InvalidSettings(List.of("hrMaxReferenceBpm"));
        }
        return reference;
    }

    @GetMapping(PATH + "{quarterId}/track-points")
    public ResponseEntity<QuarterWorkoutQuery.Page<QuarterWorkoutQuery.TrackSample>> trackPoints(
            Principal principal, @PathVariable UUID quarterId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        var result = query.trackPoints(MatchController.actor(principal), quarterId, cursor, limit);
        return ResponseEntity.ok().eTag(MatchMutation.etag(result.meta().matchVersion())).body(result);
    }

    @GetMapping(PATH + "{quarterId}/heart-rate-samples")
    public ResponseEntity<QuarterWorkoutQuery.Page<QuarterWorkoutQuery.HeartRate>> heartRateSamples(
            Principal principal, @PathVariable UUID quarterId,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        var result = query.heartRateSamples(MatchController.actor(principal), quarterId, cursor, limit);
        return ResponseEntity.ok().eTag(MatchMutation.etag(result.meta().matchVersion())).body(result);
    }

    @Operation(summary = "쿼터 운동 원본 업로드",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = WorkoutPayloadCreateRequest.class))))
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
        byte[] body = readUpload(httpRequest);
        String path = PATH + quarterId;
        var request = new MatchMutation.Request(actor, quarter.matchId(), "POST",
                path + "/workout-payloads", requestKey, ifMatch, body);
        var result = mutation.execute(request, LocalDateTime.now(ZoneOffset.UTC).plus(retention),
                match -> ownership.requireQuarter(actor, match.id(), quarterId),
                match -> serialize(upload.save(match, ownership.requireQuarter(actor, match.id(), quarterId),
                        parse(body)), path));
        return replay(result);
    }

    @Operation(
            summary = "완료 경기의 운동 기록과 포지션 일괄 제출",
            description = "본인 경기의 모든 쿼터를 정확히 한 번씩 보내야 합니다. 하나라도 실패하면 전체가 롤백됩니다. "
                    + "요청 크기 기본 상한은 8 MiB이며, 응답을 받지 못했다면 같은 헤더와 본문으로 재시도합니다.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SubmissionBody.class))),
            responses = {
                    @ApiResponse(responseCode = "202", description = "저장 완료. 결과 경로에서 분석 진행 상태를 조회합니다.",
                            headers = {
                                    @Header(name = HttpHeaders.LOCATION, description = "경기 결과 조회 경로",
                                            schema = @Schema(type = "string")),
                                    @Header(name = HttpHeaders.ETAG, description = "새 경기 버전",
                                            schema = @Schema(type = "string")),
                                    @Header(name = HttpHeaders.RETRY_AFTER, description = "조회 대기 시간(초)",
                                            schema = @Schema(type = "integer", example = SUMMARY_RETRY_SECONDS))
                            },
                            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                                    schema = @Schema(implementation = SubmissionResponse.class))),
                    @ApiResponse(responseCode = "409", description = "경기 상태, 멱등 키 또는 기존 원본과 충돌"),
                    @ApiResponse(responseCode = "412", description = "경기 버전 불일치"),
                    @ApiResponse(responseCode = "413", description = "요청 크기 초과"),
                    @ApiResponse(responseCode = "422", description = "쿼터 목록, 포지션 또는 운동 데이터 검증 실패"),
                    @ApiResponse(responseCode = "428", description = "If-Match 누락")
            })
    @PostMapping(path = MatchController.PATH + "/{matchId}/workout-submissions",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> submit(
            Principal principal, @PathVariable UUID matchId,
            @Parameter(required = true, description = "재시도에 사용할 UUID 멱등 키", schema = @Schema(format = "uuid"))
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Parameter(required = true, description = "최신 경기 ETag")
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            HttpServletRequest httpRequest) throws IOException {
        UUID actor = MatchController.actor(principal);
        ownership.requireMatch(actor, matchId);
        UUID requestKey = MatchController.key(key);
        byte[] body = readUpload(httpRequest);
        String path = MatchController.PATH + "/" + matchId;
        var request = new MatchMutation.Request(actor, matchId, "POST", path + "/workout-submissions",
                requestKey, ifMatch, body);
        return replay(mutation.execute(request, LocalDateTime.now(ZoneOffset.UTC).plus(retention),
                match -> {}, match -> {
                    var entries = parseSubmission(body);
                    long version = submission.save(match, entries);
                    var meta = MatchApiResponse.Meta.create();
                    var response = new VersionedResponse<>(new SubmissionReceipt(matchId, entries.size()),
                            new DetailMeta(meta.requestId(), meta.serverTime(), version));
                    var headers = Map.of(HttpHeaders.LOCATION, path + "/result",
                            HttpHeaders.RETRY_AFTER, SUMMARY_RETRY_SECONDS, HttpHeaders.ETAG, MatchMutation.etag(version));
                    return new MatchMutation.Response(202, json.writeValueAsString(headers),
                            json.writeValueAsString(response));
                }));
    }

    private Map<UUID, MatchWorkoutSubmission.Entry> parseSubmission(byte[] body) {
        var root = MatchSettings.readBody(json, body);
        MatchSettings.checkObject(root, "body", Set.of("quarters"));
        var quarters = root.path("quarters");
        if (!quarters.isArray() || quarters.isEmpty()) {
            throw new MatchSettings.InvalidSettings(List.of("quarters"));
        }

        Map<UUID, MatchWorkoutSubmission.Entry> entries = new HashMap<>();
        int index = 0;
        for (var item : quarters) {
            String field = "quarters[" + index++ + "]";
            MatchSettings.checkObject(item, field, Set.of("quarterId", "position", "workout"));
            var id = item.path("quarterId");
            if (!id.isTextual() || id.asText().length() != 36) {
                throw new MatchSettings.InvalidSettings(List.of(field + ".quarterId"));
            }
            var quarterId = MatchSettings.uuid(id.asText(), field + ".quarterId");
            if (entries.containsKey(quarterId)) {
                throw new MatchSettings.InvalidSettings(List.of(field + ".quarterId"));
            }
            var position = item.path("position");
            if (!position.isTextual()) {
                throw new MatchSettings.InvalidSettings(List.of(field + ".position"));
            }
            entries.put(quarterId, new MatchWorkoutSubmission.Entry(
                    position.asText(), parseWorkout(item.path("workout"), field + ".workout")));
        }
        return entries;
    }

    // The controller streams the request to enforce the size limit before parsing.
    @Schema(requiredProperties = "quarters")
    private record SubmissionBody(List<SubmissionQuarter> quarters) {}

    @Schema(requiredProperties = {"quarterId", "position", "workout"})
    private record SubmissionQuarter(UUID quarterId,
                                     @Schema(description = "서버에 설정된 대표 포지션 코드") String position,
                                     WorkoutPayloadCreateRequest workout) {}

    private record SubmissionResponse(SubmissionReceipt data, DetailMeta meta) {}

    private record SubmissionReceipt(UUID matchId, int quarterCount) {}

    private byte[] readUpload(HttpServletRequest httpRequest) throws IOException {
        if (httpRequest.getContentLengthLong() > maxBytes) {
            throw new PayloadTooLarge();
        }
        byte[] body = httpRequest.getInputStream().readNBytes(Math.addExact(maxBytes, 1));
        if (body.length > maxBytes) {
            throw new PayloadTooLarge();
        }
        return body;
    }

    private ResponseEntity<String> replay(MatchMutation.Response result) {
        var response = ResponseEntity.status(result.status()).contentType(MediaType.APPLICATION_JSON);
        json.readTree(result.headersJson()).properties()
                .forEach(entry -> response.header(entry.getKey(), entry.getValue().asText()));
        return response.body(result.bodyJson());
    }

    private WorkoutPayloadCreateRequest parse(byte[] body) {
        return parseWorkout(MatchSettings.readBody(json, body), "body");
    }

    private WorkoutPayloadCreateRequest parseWorkout(JsonNode root, String field) {
        MatchSettings.checkObject(root, field, PAYLOAD_FIELDS);
        String prefix = "body".equals(field) ? "" : field + ".";
        requireNumbers(root, TOTAL_FIELDS, prefix);
        requireSamples(root.path("points"), POINT_FIELDS, prefix + "points");
        requireSamples(root.path("heartRateSamples"), HEART_RATE_FIELDS, prefix + "heartRateSamples");
        requireContext(root, prefix);
        return json.treeToValue(root, WorkoutPayloadCreateRequest.class);
    }

    private static void requireContext(JsonNode root, String prefix) {
        var events = root.get("events");
        if (events != null && !events.isNull()) {
            if (!events.isArray()) {
                throw new MatchSettings.InvalidSettings(List.of(prefix + "events"));
            }
            int index = 0;
            for (var event : events) {
                String path = prefix + "events[" + index++ + "]";
                MatchSettings.checkObject(event, path, Set.of("type", "location"));
                if (!event.path("type").isTextual()
                        || !Set.of("goal", "assist").contains(event.path("type").asText())) {
                    throw new MatchSettings.InvalidSettings(List.of(path + ".type"));
                }
                MatchSettings.checkObject(event.path("location"), path + ".location", POINT_FIELDS);
                requireNumbers(event.path("location"), POINT_FIELDS, path + ".location.");
            }
        }
        var field = root.get("field");
        if (field == null || field.isNull()) {
            return;
        }
        MatchSettings.checkObject(field, prefix + "field", Set.of("name", "cornerA", "cornerB", "cornerC", "cornerD",
                "rawCornerA", "rawCornerB", "rawCornerC", "rawCornerD"));
        if (!field.path("name").isTextual()) {
            throw new MatchSettings.InvalidSettings(List.of(prefix + "field.name"));
        }
        for (String name : List.of("cornerA", "cornerB", "cornerC", "cornerD", "rawCornerA", "rawCornerB", "rawCornerC", "rawCornerD")) {
            var corner = field.get(name);
            if (name.startsWith("raw") && (corner == null || corner.isNull())) {
                continue;
            }
            MatchSettings.checkObject(corner, prefix + "field." + name, Set.of("latitude", "longitude"));
            requireNumbers(corner, Set.of("latitude", "longitude"), prefix + "field." + name + ".");
        }
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
        var data = Workout.from(result.workout(), json);
        var meta = MatchApiResponse.Meta.create();
        var body = new VersionedResponse<>(data, new DetailMeta(meta.requestId(), meta.serverTime(), result.matchVersion()));
        String tag = MatchMutation.etag(result.matchVersion());
        var headers = result.created()
                ? Map.of(HttpHeaders.ETAG, tag, HttpHeaders.LOCATION, path + "/workout")
                : Map.of(HttpHeaders.ETAG, tag);
        return new MatchMutation.Response(result.created() ? 201 : 200,
                json.writeValueAsString(headers), json.writeValueAsString(body));
    }

    static final class PayloadTooLarge extends RuntimeException {}
}
