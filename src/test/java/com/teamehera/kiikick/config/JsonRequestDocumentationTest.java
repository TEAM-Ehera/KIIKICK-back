package com.teamehera.kiikick.config;

import com.teamehera.kiikick.auth.email.VerificationEmailSender;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.sql.init.mode=never")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JsonRequestDocumentationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean VerificationEmailSender email;
    JsonNode document;

    @BeforeAll
    void readPublishedDocument() throws Exception {
        document = json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void allJsonRequestsExposeObjectsInsteadOfFileInputs() {
        var methods = Set.of("get", "post", "put", "patch", "delete");
        document.path("paths").properties().forEach(path -> path.getValue().properties().forEach(operation -> {
            if (!methods.contains(operation.getKey())) return;
            var schema = operation.getValue().at("/requestBody/content/application~1json/schema");
            if (schema.isMissingNode()) return;
            if (schema.has("$ref")) schema = document.at(schema.path("$ref").asText().substring(1));
            assertThat(schema.path("format").asText()).as(path.getKey()).isNotIn("byte", "binary");
            assertThat(schema.path("type").asText()).as(path.getKey()).isEqualTo("object");
        }));
    }

    @Test
    void bodylessActionsHideTheEditorAndAvatarUploadKeepsItsFileInput() {
        assertThat(operation("/api/v1/matches/{matchId}/{action}", "post").has("requestBody")).isFalse();
        assertThat(operation("/api/v1/venues/{venueId}", "delete").has("requestBody")).isFalse();
        assertThat(operation("/api/v1/quarters/{quarterId}/summary/recalculate", "post").has("requestBody")).isFalse();
        assertThat(operation("/api/v1/notifications/{notificationId}/read", "put").has("requestBody")).isFalse();
        assertThat(operation("/api/v1/users/me/avatar", "post")
                .at("/requestBody/content/multipart~1form-data/schema/properties/file/format").asText()).isEqualTo("binary");
    }

    @Test
    void examplesIncludeActualVenueCornersAndWorkoutContext() {
        var venue = operation("/api/v1/venues", "post").at("/requestBody/content/application~1json");
        assertThat(venue.at("/example/cornerA/latitude").isNumber()).isTrue();
        assertThat(venue.at("/schema/properties/rawCornerD/$ref").asText()).contains("Corner");
        assertThat(venue.at("/schema/required/0").asText()).isEqualTo("name");
        assertThat(operation("/api/v1/venues/{venueId}", "patch")
                .at("/requestBody/content/application~1json/schema/required").isMissingNode()).isTrue();
        var workout = operation("/api/v1/user/{userId}/workout-payloads", "post")
                .at("/requestBody/content/application~1json/examples/iOS WorkoutPayload/value");
        assertThat(workout.at("/events/0/type").asText()).isEqualTo("goal");
        assertThat(workout.at("/field/cornerD/latitude").isNumber()).isTrue();
    }

    @Test
    void commentDocumentationDescribesReplyRequestsAndActualResponseFields() {
        String comments = "/api/v1/matches/{matchId}/comments";
        var post = operation(comments, "post");
        var request = post.at("/requestBody/content/application~1json");
        assertThat(request.at("/schema/required").values()).extracting(JsonNode::asText).containsExactly("content");
        assertThat(request.at("/schema/properties/parentCommentId/format").asText()).isEqualTo("uuid");
        assertThat(request.at("/schema/properties/parentCommentId/type").values()).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("string", "null");
        assertThat(request.at("/examples/comment/value").has("parentCommentId")).isFalse();
        assertThat(request.at("/examples/reply/value/parentCommentId").isString()).isTrue();

        var created = post.at("/responses/201/content/application~1json");
        var item = created.at("/schema/properties/data/properties");
        assertThat(item.at("/parentCommentId/format").asText()).isEqualTo("uuid");
        assertThat(item.has("deletedAt")).isFalse();
        assertThat(created.at("/example/data/parentCommentId").asText())
                .isEqualTo(request.at("/examples/reply/value/parentCommentId").asText());
        assertThat(post.at("/responses/404/description").asText()).contains("RESOURCE_NOT_FOUND");
        assertThat(post.at("/responses/422/description").asText()).contains("VALIDATION_FAILED");
    }

    @Test
    void commentDocumentationDescribesScopedPaginationAndSoftDeletion() {
        String comments = "/api/v1/matches/{matchId}/comments";
        var get = operation(comments, "get");
        var parent = parameter(get, "parentCommentId");
        assertThat(parent.path("required").asBoolean()).isFalse();
        assertThat(parent.at("/schema/format").asText()).isEqualTo("uuid");
        assertThat(parameter(get, "cursor").path("description").asText()).contains("부모 ID");
        assertThat(parameter(get, "limit").at("/schema/default").asInt()).isEqualTo(20);
        var page = get.at("/responses/200/content/application~1json");
        assertThat(page.at("/schema/properties/data/items/properties/parentCommentId/format").asText()).isEqualTo("uuid");
        assertThat(page.at("/schema/properties/meta/properties/pagination/properties/nextCursor/type").values())
                .extracting(JsonNode::asText).containsExactlyInAnyOrder("string", "null");
        var deleted = page.at("/examples/deletedParent/value/data/0");
        assertThat(deleted.path("content").asText()).isEqualTo("[삭제된 댓글입니다]");
        assertThat(deleted.has("parentCommentId")).isFalse();
        assertThat(deleted.has("deletedAt")).isFalse();

        var delete = operation(comments + "/{commentId}", "delete");
        assertThat(delete.path("description").asText()).contains("기존 답글은 유지");
        assertThat(delete.has("requestBody")).isFalse();
        assertThat(delete.at("/responses/204").has("content")).isFalse();
        for (var write : new JsonNode[]{operation(comments, "post"), delete}) {
            assertThat(parameter(write, "Idempotency-Key").path("required").asBoolean()).isTrue();
            assertThat(parameter(write, "Idempotency-Key").at("/schema/format").asText()).isEqualTo("uuid");
            assertThat(write.path("parameters").values()).extracting(node -> node.path("name").asText())
                    .doesNotContain("If-Match");
        }
        assertThat(document.at("/security/0").has("bearerAuth")).isTrue();
    }

    @Test
    void reportPostDocumentsActualFieldsAndReplyVisibility() {
        var post = operation("/api/v1/matches/{matchId}/comments/{commentId}/reports", "post");
        assertThat(post.path("description").asText()).contains("삭제된 부모의 기존 답글", "하위 답글은 신고할 수 없습니다");
        var request = post.at("/requestBody/content/application~1json");
        var input = resolve(request.path("schema"));
        assertThat(input.path("required").values()).extracting(JsonNode::asText).containsExactly("reason");
        assertThat(input.at("/properties/reason/enum").values()).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("SPAM", "ABUSE", "PERSONAL_INFORMATION", "OTHER");
        assertThat(request.at("/example/reason").asText()).isEqualTo("SPAM");
        assertThat(input.at("/properties/detail/anyOf/1/type").asText()).isEqualTo("null");
        var response = post.at("/responses/201/content/application~1json");
        assertThat(resolve(resolve(response.path("schema")).at("/properties/data")).path("properties").propertyNames())
                .containsExactlyInAnyOrder("id", "commentId", "reportedAt");
        assertThat(response.at("/example/data/reportedAt").asText()).endsWith("+09:00");
        assertReportError(post, "401", "UNAUTHENTICATED");
        assertReportError(post, "404", "RESOURCE_NOT_FOUND");
        assertReportError(post, "409", "COMMENT_ALREADY_REPORTED");
        assertReportError(post, "422", "SELF_REPORT_NOT_ALLOWED");
        assertReportError(post, "422", "VALIDATION_FAILED");
    }

    @Test
    void adminReportDocsIncludeSecurityPagingAndModerationOutcomes() {
        String admin = "/api/v1/admin/comment-reports";
        var list = operation(admin, "get");
        assertThat(parameter(list, "status").at("/schema/default").asText()).isEqualTo("PENDING");
        assertThat(parameter(list, "cursor").path("description").asText()).contains("status");
        assertThat(parameter(list, "limit").at("/schema/maximum").asInt()).isEqualTo(100);
        assertReportError(list, "400", "INVALID_CURSOR");
        assertThat(list.at("/responses/200/content/application~1json/example/data/0/status").asText()).isEqualTo("PENDING");

        var detail = operation(admin + "/{reportId}", "get");
        assertReportError(detail, "404", "RESOURCE_NOT_FOUND");
        assertThat(detail.at("/responses/200/content/application~1json/example/data/commentContent").asText())
                .isEqualTo("신고 당시 원문");
        assertThat(detail.at("/responses/200/content/application~1json/example/data/processedAt").isNull()).isTrue();
        var properties = resolve(resolve(detail.at("/responses/200/content/application~1json/schema"))
                .at("/properties/data")).path("properties");
        for (String field : Set.of("detail", "processedAt", "processedBy", "resolution")) {
            assertThat(properties.at("/" + field + "/anyOf/1/type").asText()).isEqualTo("null");
        }

        var patch = operation(admin + "/{reportId}", "patch");
        assertThat(patch.path("description").asText()).contains("일반 조회·신고에서 제외", "소프트 삭제", "기존 답글");
        var body = patch.at("/requestBody/content/application~1json");
        var input = resolve(body.path("schema"));
        assertThat(input.path("required").values()).extracting(JsonNode::asText).containsExactly("resolution");
        assertThat(body.at("/examples/hide/value/resolution").asText()).isEqualTo("HIDDEN");
        assertThat(body.at("/examples/delete/value/resolution").asText()).isEqualTo("DELETED");
        assertThat(patch.at("/responses/200/content/application~1json/example/data/processedAt").asText()).endsWith("+09:00");
        assertReportError(patch, "409", "COMMENT_ALREADY_DELETED");
        assertReportError(patch, "409", "REPORT_ALREADY_PROCESSED");
        for (var operation : new JsonNode[]{list, detail, patch}) {
            assertReportError(operation, "401", "TOKEN_EXPIRED");
            assertReportError(operation, "403", "FORBIDDEN");
            assertThat(operation.at("/responses/401/headers/WWW-Authenticate/example").asText()).isEqualTo("Bearer");
        }
        assertThat(document.at("/security/0").has("bearerAuth")).isTrue();
    }

    private void assertReportError(JsonNode operation, String status, String code) {
        var error = operation.at("/responses/" + status + "/content/application~1json/examples/" + code + "/value/error");
        assertThat(error.path("code").asText()).isEqualTo(code);
    }

    private JsonNode resolve(JsonNode schema) {
        return schema.has("$ref") ? document.at(schema.path("$ref").asText().substring(1)) : schema;
    }

    private JsonNode parameter(JsonNode operation, String name) {
        return operation.path("parameters").values().stream()
                .filter(parameter -> name.equals(parameter.path("name").asText())).findFirst().orElseThrow();
    }

    private JsonNode operation(String path, String method) {
        return document.path("paths").path(path).path(method);
    }
}
