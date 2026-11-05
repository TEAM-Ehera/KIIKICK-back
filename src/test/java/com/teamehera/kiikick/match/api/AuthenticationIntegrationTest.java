package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationIntegrationTest extends AuthenticatedApiTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ObjectMapper json;
    private final UUID actor = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:authentication_integration;MODE=MySQL");
    }

    @BeforeEach
    void createUsers() {
        users.save(actor, "owner");
        users.save(other, "other");
    }

    @Test
    void legacyPathsCannotBypassOwnershipOrPublicDirectoryRules() throws Exception {
        String token = bearer(actor);
        mvc.perform(get("/api/v1/user/" + actor).header("Authorization", token)).andExpect(status().isOk());
        for (var request : java.util.List.of(
                get("/api/v1/users"), get("/api/v1/user/" + other), delete("/api/v1/user/" + other),
                put("/api/v1/user/" + other).contentType("application/json").content("{\"nickname\":\"changed\"}"),
                post("/api/v1/user/" + other + "/workout-payloads").contentType("application/json").content("{}"),
                post("/api/v1/user").contentType("application/json").content("{\"nickname\":\"new\"}"))) {
            mvc.perform(request.header("Authorization", token)).andExpect(status().isForbidden());
        }
        assertEquals("other", users.findById(other).orElseThrow().nickname());
    }

    @Test
    void deletingUserInvalidatesTheirExistingAccessToken() throws Exception {
        String token = bearer(actor);
        mvc.perform(get("/api/v1/matches").header("Authorization", token)).andExpect(status().isOk());
        users.softDelete(actor);
        mvc.perform(get("/api/v1/matches").header("Authorization", token)).andExpect(status().isUnauthorized());
    }

    @Test
    void successHeadersMatchMetadataAndStoredNoContentReplays() throws Exception {
        String token = bearer(actor);
        for (String path : java.util.List.of("/api/v1/matches", "/api/v1/venues", "/api/v1/analytics/summary")) {
            var response = mvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk()).andReturn().getResponse();
            assertEquals(json.readTree(response.getContentAsString()).at("/meta/requestId").asText(), response.getHeader("X-Request-Id"));
        }
        var venue = mvc.perform(post("/api/v1/venues").header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content("{\"name\":\"venue\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        assertEquals(json.readTree(venue.getContentAsString()).at("/meta/requestId").asText(), venue.getHeader("X-Request-Id"));
        UUID key = UUID.randomUUID();
        var deleted = mvc.perform(delete(venue.getHeader("Location")).header("Authorization", token).header("Idempotency-Key", key))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        mvc.perform(delete(venue.getHeader("Location")).header("Authorization", token).header("Idempotency-Key", key))
                .andExpect(status().isNoContent()).andExpect(header().string("X-Request-Id", deleted.getHeader("X-Request-Id")));
    }

    @Test
    void authenticationFailuresCarryCommonHeadersAndUnsavedMutationState() throws Exception {
        var response = mvc.perform(post("/api/v1/matches").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error.persistence").value("NOT_SAVED")).andReturn().getResponse();
        assertEquals(json.readTree(response.getContentAsString()).at("/meta/requestId").asText(), response.getHeader("X-Request-Id"));
        UUID.fromString(response.getHeader("X-Request-Id"));
    }
}
