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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    void commentReportOperationsPublishRequestsSuccessAndApplicableErrors() {
        String admin = "/api/v1/admin/comment-reports";
        var submit = operation("/api/v1/matches/{matchId}/comments/{commentId}/reports", "post");
        var list = operation(admin, "get");
        var detail = operation(admin + "/{reportId}", "get");
        var process = operation(admin + "/{reportId}", "patch");

        assertThat(submit.at("/requestBody/content/application~1json/example/reason").asText()).isEqualTo("SPAM");
        assertThat(submit.at("/responses/201/content/application~1json/example/data/commentId").isString()).isTrue();
        assertThat(submit.at("/responses/201/content/application~1json/schema").isObject()).isTrue();
        assertThat(list.has("requestBody")).isFalse();
        assertThat(detail.has("requestBody")).isFalse();
        assertThat(process.at("/requestBody/content/application~1json/example/resolution").asText()).isEqualTo("HIDDEN");
        for (var adminOperation : List.of(list, detail, process)) {
            assertThat(adminOperation.at("/responses/200/content/application~1json/schema").isObject()).isTrue();
        }

        assertErrors(submit, "201", true, Map.of(
                "400", Set.of("INVALID_REQUEST"), "401", Set.of("UNAUTHENTICATED", "TOKEN_EXPIRED"),
                "404", Set.of("RESOURCE_NOT_FOUND"), "409", Set.of("COMMENT_ALREADY_REPORTED"),
                "422", Set.of("SELF_REPORT_NOT_ALLOWED", "VALIDATION_FAILED")));
        assertErrors(list, "200", false, Map.of(
                "400", Set.of("INVALID_REQUEST", "INVALID_CURSOR"),
                "401", Set.of("UNAUTHENTICATED", "TOKEN_EXPIRED"), "403", Set.of("FORBIDDEN")));
        assertErrors(detail, "200", false, Map.of(
                "400", Set.of("INVALID_REQUEST"), "401", Set.of("UNAUTHENTICATED", "TOKEN_EXPIRED"),
                "403", Set.of("FORBIDDEN"), "404", Set.of("RESOURCE_NOT_FOUND")));
        assertErrors(process, "200", true, Map.of(
                "400", Set.of("INVALID_REQUEST"), "401", Set.of("UNAUTHENTICATED", "TOKEN_EXPIRED"),
                "403", Set.of("FORBIDDEN"), "404", Set.of("RESOURCE_NOT_FOUND"),
                "409", Set.of("REPORT_ALREADY_PROCESSED", "COMMENT_ALREADY_DELETED"),
                "422", Set.of("VALIDATION_FAILED")));
        assertThat(errorExample(submit, "422", "VALIDATION_FAILED").at("/error/details/fields/0").asText())
                .isEqualTo("reason");
        assertThat(errorExample(process, "422", "VALIDATION_FAILED").at("/error/details/fields/0").asText())
                .isEqualTo("resolution");
    }

    private void assertErrors(JsonNode operation, String success, boolean write,
                              Map<String, Set<String>> expected) {
        var statuses = new HashSet<>(expected.keySet());
        statuses.add(success);
        assertThat(operation.path("responses").propertyNames()).containsExactlyInAnyOrderElementsOf(statuses);
        expected.forEach((status, codes) -> {
            boolean security = status.equals("401") || status.equals("403");
            var examples = operation.at("/responses/" + status + "/content/application~1json/examples");
            assertThat(examples.propertyNames()).containsExactlyInAnyOrderElementsOf(codes);
            for (String code : codes) {
                var example = errorExample(operation, status, code);
                assertThat(example.propertyNames()).containsExactlyInAnyOrder("error", "meta");
                var error = example.path("error");
                assertThat(error.propertyNames())
                        .containsExactlyInAnyOrder("code", "message", "retryable", "persistence", "details");
                assertThat(error.path("code").asText()).isEqualTo(code);
                assertThat(error.path("message").asText()).isNotBlank();
                assertThat(error.path("retryable").isBoolean()).isTrue();
                assertThat(error.path("retryable").asBoolean()).isFalse();
                if (write && !security) {
                    assertThat(error.path("persistence").asText()).isEqualTo("NOT_SAVED");
                } else {
                    assertThat(error.path("persistence").isNull()).isTrue();
                }
                assertThat(error.path("details").isObject()).isTrue();
                assertThat(example.path("meta").propertyNames()).containsExactlyInAnyOrder("requestId", "serverTime");
                assertThat(example.at("/meta/requestId").asText()).matches(security ? "req-.+" : "[0-9a-f-]{36}");
                assertThat(example.at("/meta/serverTime").asText()).endsWith("+09:00");
            }
        });
    }

    private JsonNode errorExample(JsonNode operation, String status, String code) {
        return operation.at("/responses/" + status + "/content/application~1json/examples/" + code + "/value");
    }

    private JsonNode operation(String path, String method) {
        return document.path("paths").path(path).path(method);
    }
}
