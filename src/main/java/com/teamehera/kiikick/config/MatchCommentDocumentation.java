package com.teamehera.kiikick.config;

import com.teamehera.kiikick.match.api.MatchApiResponse;
import com.teamehera.kiikick.match.api.MatchCommentController.CommentResponse;
import com.teamehera.kiikick.match.api.MatchCommentController.Page;
import com.teamehera.kiikick.match.service.MatchListQuery.PageMeta;
import com.teamehera.kiikick.match.service.MatchListQuery.Pagination;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.UUIDSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Configuration
class MatchCommentDocumentation {
    private static final String COMMENTS = "/api/v1/matches/{matchId}/comments";
    private static final String JSON = "application/json";
    private static final TypeReference<Map<String, Object>> EXAMPLE_OBJECT = new TypeReference<>() {};
    private static final UUID MATCH = new UUID(0, 1);
    private static final UUID PARENT = new UUID(0, 2);
    private static final UUID REPLY = new UUID(0, 3);
    private static final UUID AUTHOR = new UUID(0, 4);
    private static final UUID REQUEST = new UUID(0, 5);
    private static final OffsetDateTime TIME = OffsetDateTime.parse("2026-11-16T19:00:00+09:00");
    private static final String REPLY_TEXT = "다음 경기에도 같이 뛰어요!";
    private static final String ACCESS = "Bearer JWT 인증이 필요하며 경기 소유자 또는 현재 수락된 친구만 접근할 수 있습니다. ";
    private static final String IDEMPOTENCY = """
            UUID Idempotency-Key가 필요합니다. If-Match는 허용되지 않으며 경기 버전은 변경되지 않습니다.
            재요청 시 현재 경기 접근 권한을 확인한 뒤 같은 키와 요청에 최초 응답을 재생합니다.
            """;

    @Bean
    OpenApiCustomizer matchCommentOperations(ObjectMapper json) {
        return api -> {
            if (api.getPaths() == null || !api.getPaths().containsKey(COMMENTS)) return;

            var collection = api.getPaths().get(COMMENTS);
            var reply = new CommentResponse(REPLY, MATCH, AUTHOR, REPLY_TEXT, TIME, PARENT);
            var deletedParent = new CommentResponse(PARENT, MATCH, AUTHOR, "[삭제된 댓글입니다]", TIME, null);
            var pageMeta = new PageMeta(REQUEST, TIME, new Pagination(null, false));
            var item = new ObjectSchema()
                    .addProperty("id", new UUIDSchema()).addProperty("matchId", new UUIDSchema())
                    .addProperty("authorId", new UUIDSchema()).addProperty("content", new StringSchema())
                    .addProperty("createdAt", new DateTimeSchema())
                    .addProperty("parentCommentId", new UUIDSchema().description("답글 응답에만 포함되는 부모 댓글 ID"))
                    .required(List.of("id", "matchId", "authorId", "content", "createdAt"));
            var pagination = new ObjectSchema()
                    .addProperty("nextCursor", new StringSchema().types(Set.of("string", "null")))
                    .addProperty("hasNext", new BooleanSchema());
            var failure = failureSchema(new UUIDSchema());
            var authFailure = failureSchema(new StringSchema());

            var get = collection.getGet();
            get.setDescription(ACCESS + "최상위 댓글 또는 한 부모의 답글을 조회합니다. 양방향 차단 관계인 작성자의 댓글은 숨깁니다. "
                    + "부모가 조회자에게 숨겨진 경우 그 답글도 숨깁니다. "
                    + "삭제된 최상위 댓글은 [삭제된 댓글입니다]로 표시합니다.");
            parameter(get, "parentCommentId").description("생략하면 최상위 댓글을 조회합니다. 같은 경기의 최상위 댓글 ID를 "
                    + "지정하면 답글을 조회하며, 삭제된 부모의 기존 답글도 조회할 수 있습니다. 조회 가능한 답글이 없으면 빈 목록입니다.")
                    .example(PARENT.toString());
            parameter(get, "cursor").description("이전 응답의 meta.pagination.nextCursor. 정렬은 createdAt, id 내림차순입니다. "
                    + "커서는 조회자·경기·목록 유형에 귀속되며 답글 커서는 부모 ID도 같아야 합니다.");
            parameter(get, "limit").schema(new IntegerSchema()._default(20)
                    .minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(100)));
            var page = new MediaType().schema(new ObjectSchema()
                    .addProperty("data", new ArraySchema().items(item))
                    .addProperty("meta", meta(new UUIDSchema()).addProperty("pagination", pagination)))
                    .addExamples("deletedParent", example("삭제된 최상위 댓글", json.convertValue(
                            new Page(List.of(deletedParent), pageMeta), EXAMPLE_OBJECT)))
                    .addExamples("replies", example("부모 댓글의 답글", json.convertValue(
                            new Page(List.of(reply), pageMeta), EXAMPLE_OBJECT)));
            get.setResponses(new ApiResponses()
                    .addApiResponse("200", jsonResponse("댓글 목록", page))
                    .addApiResponse("400", error("INVALID_REQUEST: UUID 또는 limit 오류. INVALID_CURSOR: 잘못된 범위 또는 형식.", failure))
                    .addApiResponse("401", authenticationError(authFailure))
                    .addApiResponse("404", error("RESOURCE_NOT_FOUND: 경기 없음 또는 접근 불가.", failure)));

            var post = collection.getPost();
            post.setDescription(ACCESS + "같은 경기의 삭제되지 않은 최상위 댓글에만 답글을 작성할 수 있습니다. "
                    + "답글에 답글을 작성할 수 없으며 양방향 차단 관계인 작성자의 댓글도 부모로 사용할 수 없습니다.\n\n"
                    + IDEMPOTENCY);
            writeHeaders(post);
            var body = new ObjectSchema().additionalProperties(false)
                    .addProperty("content", new StringSchema().minLength(1).maxLength(1000)
                            .description("공백만으로 구성된 값은 허용하지 않으며 Unicode 코드 포인트 기준 최대 1000자입니다. 앞뒤 공백은 제거합니다."))
                    .addProperty("parentCommentId", new UUIDSchema().types(Set.of("string", "null"))
                            .description("선택 값. 생략하거나 null이면 최상위 댓글을 작성합니다.")).required(List.of("content"));
            var request = new MediaType().schema(body)
                    .addExamples("comment", example("최상위 댓글 작성", Map.of("content", "좋은 경기였어!")))
                    .addExamples("reply", example("답글 작성", Map.of("content", REPLY_TEXT, "parentCommentId", PARENT.toString())));
            post.setRequestBody(new RequestBody().required(true).content(new Content().addMediaType(JSON, request)));
            var created = new MediaType().schema(new ObjectSchema()
                    .addProperty("data", item).addProperty("meta", meta(new UUIDSchema())))
                    .example(json.convertValue(new MatchApiResponse.Success<>(reply, new MatchApiResponse.Meta(REQUEST, TIME)), EXAMPLE_OBJECT));
            post.setResponses(new ApiResponses()
                    .addApiResponse("201", jsonResponse("댓글 또는 답글 작성 완료", created)
                            .addHeaderObject("Location", new Header().schema(new StringSchema())
                                    .example("/api/v1/matches/" + MATCH + "/comments/" + REPLY))
                            .addHeaderObject("X-Request-Id", new Header().schema(new UUIDSchema())))
                    .addApiResponse("400", error("INVALID_REQUEST: 잘못된 경로 UUID, 헤더 또는 JSON 형식.", failure))
                    .addApiResponse("401", authenticationError(authFailure))
                    .addApiResponse("404", error("RESOURCE_NOT_FOUND: 경기 접근 불가 또는 부모가 없거나 다른 경기·답글·삭제·차단 상태.", failure))
                    .addApiResponse("409", idempotencyError(failure))
                    .addApiResponse("415", new ApiResponse().description("application/json 요청이 필요합니다."))
                    .addApiResponse("422", error("VALIDATION_FAILED: content, parentCommentId 또는 알 수 없는 필드 오류.", failure)));

            var delete = api.getPaths().get(COMMENTS + "/{commentId}").getDelete();
            delete.setDescription(ACCESS + "본인 작성 댓글만 소프트 삭제합니다. 삭제된 최상위 댓글은 "
                    + "`[삭제된 댓글입니다]`로 표시하며 기존 답글은 유지합니다. 삭제된 답글은 답글 목록에서 제외합니다.\n\n"
                    + IDEMPOTENCY);
            writeHeaders(delete);
            delete.setRequestBody(null);
            delete.setResponses(new ApiResponses()
                    .addApiResponse("204", new ApiResponse().description("삭제 완료. 응답 본문 없음.")
                            .addHeaderObject("X-Request-Id", new Header().schema(new UUIDSchema())))
                    .addApiResponse("400", error("INVALID_REQUEST: 잘못된 경로 UUID·헤더 또는 요청 본문 포함.", failure))
                    .addApiResponse("401", authenticationError(authFailure))
                    .addApiResponse("404", error("RESOURCE_NOT_FOUND: 접근 불가, 댓글 없음, 이미 삭제됐거나 작성자가 아님.", failure))
                    .addApiResponse("409", idempotencyError(failure)));
        };
    }

    static Parameter parameter(Operation operation, String name) {
        return operation.getParameters().stream().filter(parameter -> name.equals(parameter.getName()))
                .findFirst().orElseThrow();
    }

    private static void writeHeaders(Operation operation) {
        parameter(operation, "Idempotency-Key").required(true).schema(new UUIDSchema())
                .description("동일한 요청을 재시도할 때 재사용하는 UUID");
        operation.getParameters().removeIf(parameter -> "If-Match".equals(parameter.getName()));
    }

    private static ObjectSchema meta(Schema<?> requestId) {
        var meta = new ObjectSchema();
        meta.addProperty("requestId", requestId).addProperty("serverTime", new DateTimeSchema());
        return meta;
    }

    static Schema<?> failureSchema(Schema<?> requestId) {
        return new ObjectSchema()
                .addProperty("error", new ObjectSchema()
                        .addProperty("code", new StringSchema())
                        .addProperty("message", new StringSchema())
                        .addProperty("retryable", new BooleanSchema())
                        .addProperty("persistence", new StringSchema().types(Set.of("string", "null")))
                        .addProperty("details", new ObjectSchema().additionalProperties(true)))
                .addProperty("meta", meta(requestId));
    }

    private static Example example(String summary, Object value) {
        return new Example().summary(summary).value(value);
    }

    private static ApiResponse jsonResponse(String description, MediaType media) {
        return new ApiResponse().description(description).content(new Content().addMediaType(JSON, media));
    }

    private static ApiResponse error(String description, Schema<?> schema) {
        return jsonResponse(description, new MediaType().schema(schema));
    }

    private static ApiResponse authenticationError(Schema<?> schema) {
        return error("UNAUTHENTICATED 또는 TOKEN_EXPIRED.", schema)
                .addHeaderObject("WWW-Authenticate", new Header().schema(new StringSchema()).example("Bearer"));
    }

    private static ApiResponse idempotencyError(Schema<?> schema) {
        return error("IDEMPOTENCY_KEY_REUSED: 같은 키에 다른 요청. REQUEST_IN_PROGRESS: 기존 요청 처리 중.", schema);
    }
}
