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

    private JsonNode operation(String path, String method) {
        return document.path("paths").path(path).path(method);
    }
}
