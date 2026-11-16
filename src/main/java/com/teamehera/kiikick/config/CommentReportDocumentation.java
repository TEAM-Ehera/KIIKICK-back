package com.teamehera.kiikick.config;

import com.teamehera.kiikick.match.api.AdminCommentReportController.ReportDetail;
import com.teamehera.kiikick.match.api.AdminCommentReportController.ReportPage;
import com.teamehera.kiikick.match.api.AdminCommentReportController.ReportSummary;
import com.teamehera.kiikick.match.api.MatchApiResponse;
import com.teamehera.kiikick.match.api.MatchCommentController.ReportResponse;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository.Resolution;
import com.teamehera.kiikick.match.repository.MatchCommentReportRepository.Status;
import com.teamehera.kiikick.match.service.MatchListQuery.PageMeta;
import com.teamehera.kiikick.match.service.MatchListQuery.Pagination;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.UUIDSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.teamehera.kiikick.config.MatchCommentDocumentation.failureSchema;
import static com.teamehera.kiikick.config.MatchCommentDocumentation.parameter;

@Configuration
class CommentReportDocumentation {
    private static final String JSON = "application/json";
    private static final String REPORTS = "/api/v1/matches/{matchId}/comments/{commentId}/reports";
    private static final String ADMIN = "/api/v1/admin/comment-reports";
    private static final String ADMIN_ACCESS = "Bearer JWT와 활성 관리자 권한이 필요합니다. ";
    private static final String WRITE_HEADERS = "Idempotency-Key와 If-Match는 필요하지 않습니다.";
    private static final String REASON = "SPAM";
    private static final String DETAIL = "같은 광고를 반복 게시했습니다.";
    private static final UUID REPORT_ID = new UUID(0, 1);
    private static final UUID COMMENT_ID = new UUID(0, 2);
    private static final UUID REPORTER_ID = new UUID(0, 3);
    private static final UUID AUTHOR_ID = new UUID(0, 4);
    private static final UUID ADMIN_ID = new UUID(0, 5);
    private static final UUID REQUEST_ID = new UUID(0, 6);
    private static final OffsetDateTime REPORTED_AT = OffsetDateTime.parse("2026-11-16T19:00:00+09:00");
    private static final OffsetDateTime PROCESSED_AT = REPORTED_AT.plusMinutes(1);
    private static final TypeReference<Map<String, Object>> EXAMPLE = new TypeReference<>() {};
    private static final ErrorExample INVALID = new ErrorExample("INVALID_REQUEST", "요청 형식을 확인해주세요.");
    private static final ErrorExample MISSING = new ErrorExample("RESOURCE_NOT_FOUND", "기록을 찾을 수 없습니다.");

    @Bean
    OpenApiCustomizer commentReportOperations(ObjectMapper json) {
        return api -> {
            if (api.getPaths() == null || !api.getPaths().containsKey(REPORTS)) return;

            var report = api.getPaths().get(REPORTS).getPost();
            report.setDescription("Bearer JWT와 경기 접근 권한이 필요합니다. 일반 댓글과 답글을 같은 경로에서 신고합니다. "
                    + "삭제된 대상, 숨겨진 대상 또는 차단으로 볼 수 없는 대상은 신고할 수 없습니다. "
                    + "삭제된 부모의 기존 답글은 신고할 수 있으며, 숨겨졌거나 차단으로 볼 수 없는 부모의 하위 답글은 신고할 수 없습니다. "
                    + "본인 댓글은 신고할 수 없고, 같은 신고자가 같은 댓글을 한 번만 신고할 수 있습니다. " + WRITE_HEADERS);
            var request = report.getRequestBody().getContent().get(JSON);
            request.setExamples(null);
            request.setExample(Map.of("reason", REASON, "detail", DETAIL));
            allowNull(api, request.getSchema(), "detail");
            var created = success(report, "201", json, new MatchApiResponse.Success<>(
                    new ReportResponse(REPORT_ID, COMMENT_ID, REPORTED_AT), meta(REPORTED_AT)));
            created.setDescription("댓글 또는 답글 신고 접수 완료");
            created.addHeaderObject("X-Request-Id", new Header().schema(new UUIDSchema()));
            errors(report, "400", true, INVALID);
            authenticationErrors(report, false);
            errors(report, "404", true, MISSING);
            errors(report, "409", true, new ErrorExample("COMMENT_ALREADY_REPORTED", "이미 신고한 댓글입니다."));
            unsupportedMedia(report);
            errors(report, "422", true, validation("reason"), new ErrorExample("SELF_REPORT_NOT_ALLOWED", "내 댓글은 신고할 수 없습니다."));

            var summary = new ReportSummary(REPORT_ID, REPORTER_ID, COMMENT_ID, REASON, REPORTED_AT, Status.PENDING);
            var list = api.getPaths().get(ADMIN).getGet();
            list.setDescription(ADMIN_ACCESS + "접수 시각과 신고 ID 내림차순으로 조회합니다. 기본 상태 필터는 PENDING입니다.");
            parameter(list, "cursor").description("이전 응답의 meta.pagination.nextCursor. 같은 status 필터로만 사용할 수 있습니다.");
            parameter(list, "limit").schema(new IntegerSchema()._default(20)
                    .minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(100)));
            success(list, "200", json, new ReportPage(List.of(summary), new PageMeta(REQUEST_ID, REPORTED_AT, new Pagination(null, false))));
            authenticationErrors(list, true);
            errors(list, "400", false, INVALID,
                    new ErrorExample("INVALID_CURSOR", "목록 조회 조건을 확인해주세요."));

            var item = api.getPaths().get(ADMIN + "/{reportId}");
            var detail = item.getGet();
            detail.setDescription(ADMIN_ACCESS + "신고 당시 원문 스냅샷과 신고 처리 정보를 조회합니다.");
            var detailed = success(detail, "200", json, new MatchApiResponse.Success<>(new ReportDetail(
                    REPORT_ID, REPORTER_ID, COMMENT_ID, REASON, REPORTED_AT, Status.PENDING, AUTHOR_ID, "신고 당시 원문",
                    DETAIL, null, null, null), meta(REPORTED_AT)));
            var envelope = resolve(api, detailed.getContent().get(JSON).getSchema());
            allowNull(api, envelope.getProperties().get("data"), "detail", "processedAt", "processedBy", "resolution");
            authenticationErrors(detail, true);
            errors(detail, "400", false, INVALID);
            errors(detail, "404", false, MISSING);

            var process = item.getPatch();
            process.setDescription(ADMIN_ACCESS + "NO_ACTION은 신고만 처리합니다. HIDDEN은 댓글과 그 하위 답글을 일반 조회·신고에서 제외합니다. "
                    + "DELETED는 소프트 삭제로 처리하고 기존 답글과 신고 스냅샷을 보존합니다. 이미 숨겨진 스레드는 계속 숨깁니다. "
                    + "삭제된 댓글의 HIDDEN은 409이며 신고는 미처리 상태로 유지됩니다. " + WRITE_HEADERS);
            var resolution = process.getRequestBody().getContent().get(JSON);
            resolution.setExample(null);
            resolution.setExamples(Map.of(
                    "hide", new Example().summary("숨김").value(Map.of("resolution", "HIDDEN")),
                    "delete", new Example().summary("소프트 삭제").value(Map.of("resolution", "DELETED"))));
            success(process, "200", json, new MatchApiResponse.Success<>(new ReportDetail(REPORT_ID, REPORTER_ID,
                    COMMENT_ID, REASON, REPORTED_AT, Status.PROCESSED, AUTHOR_ID, "신고 당시 원문", DETAIL,
                    PROCESSED_AT, ADMIN_ID, Resolution.DELETED), meta(PROCESSED_AT)));
            authenticationErrors(process, true);
            errors(process, "400", true, INVALID);
            errors(process, "404", true, MISSING);
            errors(process, "409", true, new ErrorExample("REPORT_ALREADY_PROCESSED", "이미 처리된 신고입니다."),
                    new ErrorExample("COMMENT_ALREADY_DELETED", "이미 삭제된 댓글입니다."));
            unsupportedMedia(process);
            errors(process, "422", true, validation("resolution"));
        };
    }

    private static MatchApiResponse.Meta meta(OffsetDateTime time) {
        return new MatchApiResponse.Meta(REQUEST_ID, time);
    }

    private static ApiResponse success(Operation operation, String status, ObjectMapper json, Object body) {
        var response = operation.getResponses().get(status);
        var content = response.getContent();
        var inferred = content.remove("*/*");
        var media = content.computeIfAbsent(JSON, ignored -> new MediaType());
        if (media.getSchema() == null && inferred != null) {
            media.setSchema(inferred.getSchema());
        }
        media.example(json.convertValue(body, EXAMPLE));
        return response;
    }

    private static void allowNull(OpenAPI api, Schema<?> schema, String... fields) {
        var properties = resolve(api, schema).getProperties();
        for (String field : fields) {
            properties.put(field, new ComposedSchema().addAnyOfItem(properties.get(field))
                    .addAnyOfItem(new Schema<>().types(Set.of("null"))));
        }
    }

    private static Schema<?> resolve(OpenAPI api, Schema<?> schema) {
        String ref = schema.get$ref();
        return ref == null ? schema : api.getComponents().getSchemas().get(ref.substring(ref.lastIndexOf('/') + 1));
    }

    private static void authenticationErrors(Operation operation, boolean admin) {
        errors(operation, "401", false, new ErrorExample("UNAUTHENTICATED", "인증이 필요합니다."),
                new ErrorExample("TOKEN_EXPIRED", "Access Token이 만료되었습니다."));
        operation.getResponses().get("401").addHeaderObject("WWW-Authenticate",
                new Header().schema(new StringSchema()).example("Bearer"));
        if (admin) {
            errors(operation, "403", false, new ErrorExample("FORBIDDEN", "접근 권한이 없습니다."));
        }
    }

    private static void unsupportedMedia(Operation operation) {
        operation.getResponses().addApiResponse("415", new ApiResponse().description("application/json 요청이 필요합니다."));
    }

    private static void errors(Operation operation, String status, boolean write, ErrorExample... examples) {
        boolean security = status.equals("401") || status.equals("403");
        var media = new MediaType().schema(failureSchema(security ? new StringSchema() : new UUIDSchema()));
        for (var example : examples) {
            var error = new LinkedHashMap<String, Object>();
            error.put("code", example.code());
            error.put("message", example.message());
            error.put("retryable", false);
            error.put("persistence", write && !security ? "NOT_SAVED" : null);
            error.put("details", example.details());
            media.addExamples(example.code(), new Example().value(Map.of("error", error, "meta", Map.of(
                    "requestId", (security ? "req-" : "") + REQUEST_ID, "serverTime", REPORTED_AT.toString()))));
        }
        operation.getResponses().addApiResponse(status, new ApiResponse()
                .description(String.join(" / ", media.getExamples().keySet()))
                .content(new Content().addMediaType(JSON, media)));
    }

    private static ErrorExample validation(String field) {
        return new ErrorExample("VALIDATION_FAILED", "입력값을 확인해주세요.", Map.of("fields", List.of(field)));
    }

    private record ErrorExample(String code, String message, Map<String, ?> details) {
        ErrorExample(String code, String message) {
            this(code, message, Map.of());
        }
    }
}
