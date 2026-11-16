package com.teamehera.kiikick.config;

import com.teamehera.kiikick.workout.dto.WorkoutFieldRequest.Corner;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Configuration
public class JsonRequestDocumentation {
    private static final String JSON = "application/json";
    private static final String COMMENT_REPORT = "POST /api/v1/matches/{matchId}/comments/{commentId}/reports";
    private static final String ADMIN_REPORTS = "/api/v1/admin/comment-reports";
    private static final String QUARTER = "/api/v1/matches/{matchId}/quarters/{quarterId}";
    private static final String VENUE_EXAMPLE = """
            {"name":"풋살장","address":"서울",
             "cornerA":{"latitude":37.5,"longitude":127.0},
             "cornerB":{"latitude":37.5,"longitude":127.001},
             "cornerC":{"latitude":37.501,"longitude":127.001},
             "cornerD":{"latitude":37.501,"longitude":127.0},
             "rawCornerA":{"latitude":37.50001,"longitude":127.00001}}
            """;
    private static final RequestSpec MATCH = new RequestSpec(MatchBody.class, """
            {"title":"풋살","venueSnapshot":{"name":"풋살장","address":"서울"},
             "scheduledAt":"2026-11-09T20:00:00+09:00"}
            """, List.of());
    private static final RequestSpec DURATION = new RequestSpec(DurationBody.class,
            "{\"durationSeconds\":1200}", List.of("durationSeconds"));
    private static final Map<String, RequestSpec> REQUESTS = Map.ofEntries(
            Map.entry("POST /api/v1/venues", new RequestSpec(VenueBody.class, VENUE_EXAMPLE, List.of("name"))),
            Map.entry("PATCH /api/v1/venues/{venueId}", new RequestSpec(VenueBody.class, VENUE_EXAMPLE, List.of())),
            Map.entry("POST /api/v1/matches", MATCH),
            Map.entry("PATCH /api/v1/matches/{matchId}", MATCH),
            Map.entry("POST /api/v1/matches/{matchId}/quarters", DURATION),
            Map.entry("PATCH " + QUARTER, DURATION),
            Map.entry("PUT " + QUARTER + "/stats", new RequestSpec(StatsBody.class,
                    "{\"goals\":1,\"assists\":0}", List.of("goals", "assists"))),
            Map.entry("PUT " + QUARTER + "/position", new RequestSpec(PositionBody.class,
                    "{\"positionCode\":\"GK\"}", List.of("positionCode"))),
            Map.entry("PUT /api/v1/quarters/{quarterId}/summary/settings", new RequestSpec(SummarySettingsBody.class,
                    "{\"hrMaxReferenceBpm\":190}", List.of("hrMaxReferenceBpm"))),
            Map.entry("POST /api/v1/matches/{matchId}/comments", new RequestSpec(CommentBody.class,
                    "{\"content\":\"좋은 경기였어!\"}", List.of("content"))),
            Map.entry(COMMENT_REPORT, new RequestSpec(ReportBody.class,
                    """
                    {"reason":"SPAM","detail":"같은 광고를 반복 게시했습니다."}
                    """, List.of("reason"))),
            Map.entry("POST /api/v1/friend-requests", new RequestSpec(FriendRequestBody.class,
                    "{\"targetUserId\":\"00000000-0000-4000-8000-000000000001\"}", List.of("targetUserId"))),
            Map.entry("PUT /api/v1/devices/{deviceId}", new RequestSpec(DeviceBody.class,
                    "{\"platform\":\"IOS\",\"pushEnabled\":false,\"pushToken\":null}", List.of("platform", "pushEnabled")))
    );
    private static final Set<String> NO_BODY = Set.of(
            "POST /api/v1/matches/{matchId}/{action}", "POST " + QUARTER + "/{action}",
            "POST /api/v1/quarters/{quarterId}/summary/recalculate",
            "POST /api/v1/friend-requests/{requestId}/accept",
            "PUT /api/v1/users/{userId}/block", "PUT /api/v1/notifications/{notificationId}/read"
    );

    @Bean
    public OpenApiCustomizer editableJsonRequests(ObjectMapper json) {
        return api -> {
            if (api.getPaths() == null) return;
            api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                String key = method.name() + " " + path;
                // 본문을 받지 않는 요청에는 byte[]에서 추론된 불필요한 입력창을 표시하지 않는다.
                if (NO_BODY.contains(key) || (method.name().equals("DELETE") && inferredByteBody(operation.getRequestBody()))) {
                    operation.setRequestBody(null);
                    return;
                }
                if (path.equals(ADMIN_REPORTS) || path.equals(ADMIN_REPORTS + "/{reportId}")) {
                    documentAdminReportErrors(operation.getResponses(), method.name(), path.equals(ADMIN_REPORTS));
                }
                var spec = REQUESTS.get(key);
                if (spec == null) return;
                // 원본 바이트 기반 멱등성은 유지하고 문서에서만 실제 JSON 객체 계약을 노출한다.
                var model = ModelConverters.getInstance(true).resolveAsResolvedSchema(new AnnotatedType(spec.type()));
                model.referencedSchemas.forEach(api.getComponents()::addSchemas);
                spec.required().forEach(model.schema::addRequiredItem);
                var content = new MediaType().schema(model.schema).example(json.readValue(spec.example(), Map.class));
                operation.setRequestBody(new RequestBody().required(true).content(new Content().addMediaType(JSON, content)));
                if (COMMENT_REPORT.equals(key)) {
                    model.schema.setAdditionalProperties(false);
                    documentCommentReportResponses(operation.getResponses(), json);
                }
            }));
        };
    }

    private static void documentCommentReportResponses(ApiResponses responses, ObjectMapper json) {
        ApiResponse created = responses.computeIfAbsent("201", ignored -> new ApiResponse());
        created.setDescription("신고 접수 완료");
        if (created.getContent() == null) {
            created.setContent(new Content());
        }
        var content = created.getContent();
        var success = content.computeIfAbsent(JSON, ignored -> new MediaType());
        var inferred = content.remove("*/*");
        if (success.getSchema() == null && inferred != null) {
            success.setSchema(inferred.getSchema());
        }
        success.example(json.readValue("""
                {"data":{"id":"00000000-0000-4000-8000-000000000001",
                         "commentId":"00000000-0000-4000-8000-000000000002",
                         "reportedAt":"2026-11-12T15:00:00+09:00"},
                 "meta":{"requestId":"00000000-0000-4000-8000-000000000003",
                         "serverTime":"2026-11-12T15:00:00+09:00"}}
                """, Map.class));
        reportError(responses, "400", invalidRequest());
        securityErrors(responses);
        reportError(responses, "404", notFound());
        reportError(responses, "409", error("COMMENT_ALREADY_REPORTED", "이미 신고한 댓글입니다."));
        reportError(responses, "422", validation("reason"),
                error("SELF_REPORT_NOT_ALLOWED", "내 댓글은 신고할 수 없습니다."));
    }

    private static void documentAdminReportErrors(ApiResponses responses, String method, boolean list) {
        securityErrors(responses);
        reportError(responses, "403", new ReportError("FORBIDDEN", "접근 권한이 없습니다.", null, Map.of()));
        if (list) {
            reportError(responses, "400",
                    new ReportError("INVALID_REQUEST", "목록 조회 조건을 확인해주세요.", null, Map.of()),
                    new ReportError("INVALID_CURSOR", "목록 조회 조건을 확인해주세요.", null, Map.of()));
            return;
        }
        boolean write = "PATCH".equals(method);
        var invalid = invalidRequest();
        var missing = notFound();
        reportError(responses, "400", write ? invalid : invalid.readOnly());
        reportError(responses, "404", write ? missing : missing.readOnly());
        if (write) {
            reportError(responses, "409", error("REPORT_ALREADY_PROCESSED", "이미 처리된 신고입니다."),
                    error("COMMENT_ALREADY_DELETED", "이미 삭제된 댓글입니다."));
            reportError(responses, "422", validation("resolution"));
        }
    }

    private static void securityErrors(ApiResponses responses) {
        reportError(responses, "401",
                new ReportError("UNAUTHENTICATED", "인증이 필요합니다.", null, Map.of()),
                new ReportError("TOKEN_EXPIRED", "Access Token이 만료되었습니다.", null, Map.of()));
    }

    private static ReportError invalidRequest() {
        return error("INVALID_REQUEST", "요청 형식을 확인해주세요.");
    }

    private static ReportError notFound() {
        return error("RESOURCE_NOT_FOUND", "기록을 찾을 수 없습니다.");
    }

    private static ReportError validation(String field) {
        return new ReportError("VALIDATION_FAILED", "입력값을 확인해주세요.", "NOT_SAVED",
                Map.of("fields", List.of(field)));
    }

    private static ReportError error(String code, String message) {
        return new ReportError(code, message, "NOT_SAVED", Map.of());
    }

    private static void reportError(ApiResponses responses, String status, ReportError... examples) {
        var content = new MediaType();
        boolean security = "401".equals(status) || "403".equals(status);
        String requestId = (security ? "req-" : "") + "00000000-0000-4000-8000-000000000003";
        for (var example : examples) {
            var error = new LinkedHashMap<String, Object>();
            error.put("code", example.code());
            error.put("message", example.message());
            error.put("retryable", false);
            error.put("persistence", example.persistence());
            error.put("details", example.details());
            content.addExamples(example.code(), new Example().value(Map.of(
                    "error", error,
                    "meta", Map.of("requestId", requestId, "serverTime", "2026-11-12T15:00:00+09:00"))));
        }
        responses.addApiResponse(status, new ApiResponse().description(
                String.join(" / ", java.util.Arrays.stream(examples).map(ReportError::code).toList()))
                .content(new Content().addMediaType(JSON, content)));
    }

    private record ReportError(String code, String message, String persistence, Map<String, ?> details) {
        ReportError readOnly() {
            return new ReportError(code, message, null, details);
        }
    }

    private static boolean inferredByteBody(RequestBody body) {
        if (body == null || body.getContent() == null) return false;
        var content = body.getContent().get(JSON);
        return content != null && content.getSchema() != null
                && "byte".equals(content.getSchema().getFormat());
    }

    private record RequestSpec(Class<?> type, String example, List<String> required) {}

    public record VenueBody(String name, String address,
                            Corner cornerA, Corner cornerB, Corner cornerC, Corner cornerD,
                            Corner rawCornerA, Corner rawCornerB, Corner rawCornerC, Corner rawCornerD) {}
    public record VenueSnapshotBody(String name, String address) {}
    public record MatchBody(String title,
                            @Schema(description = "본인 저장 경기장 ID. venueSnapshot과 동시에 지정 불가") UUID venueId,
                            VenueSnapshotBody venueSnapshot, OffsetDateTime scheduledAt) {}
    public record DurationBody(Integer durationSeconds) {}
    public record StatsBody(Integer goals, Integer assists) {}
    public record PositionBody(@Schema(description = "서버에 설정된 대표 포지션 코드") String positionCode) {}
    public record SummarySettingsBody(Double hrMaxReferenceBpm) {}
    public record CommentBody(@Schema(description = "공백만 불가, Unicode 코드 포인트 기준 최대 1000자",
                                         minLength = 1, maxLength = 1000) String content) {}
    public record ReportBody(
            @Schema(allowableValues = {"SPAM", "ABUSE", "PERSONAL_INFORMATION", "OTHER"}) String reason,
            @Schema(description = "Unicode 코드 포인트 기준 최대 1000자", maxLength = 1000) String detail) {}
    public record FriendRequestBody(UUID targetUserId) {}
    public record DeviceBody(@Schema(allowableValues = {"IOS", "WATCHOS"}) String platform,
                             String pushToken, Boolean pushEnabled) {}
}
