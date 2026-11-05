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
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.teamehera.kiikick.auth.email.VerificationEmailSender emailSender;
    @Autowired UserRepository users;
    @Autowired ObjectMapper json;
    @Autowired com.teamehera.kiikick.auth.repository.AuthSessionRepository sessions;
    @Autowired org.springframework.security.oauth2.jwt.JwtDecoder decoder;
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
    void verifiedSignupTokenCanCreateMatchWithCorrectOwnerAndFifteenMinuteExpiry() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        var challenge = mvc.perform(post("/api/v1/auth/email-verifications")
                        .contentType("application/json").content(json.writeValueAsString(java.util.Map.of("email", email))))
                .andExpect(status().isAccepted()).andReturn().getResponse();
        var code = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(emailSender).sendSignUpCode(org.mockito.ArgumentMatchers.eq(email), code.capture(), org.mockito.ArgumentMatchers.eq(3L));
        String verificationId = json.readTree(challenge.getContentAsString()).at("/data/verificationId").asText();
        var verified = mvc.perform(post("/api/v1/auth/email-verifications/" + verificationId + "/verify")
                        .contentType("application/json").content(json.writeValueAsString(java.util.Map.of("code", code.getValue()))))
                .andExpect(status().isOk()).andReturn().getResponse();
        String registration = json.readTree(verified.getContentAsString()).at("/data/registrationToken").asText();
        var signup = mvc.perform(post("/api/v1/auth/signup").contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of("registrationToken", registration, "password", "Strong-local-pass!72"))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        var body = json.readTree(signup.getContentAsString());
        assertEquals(body.at("/meta/requestId").asText(), signup.getHeader("X-Request-Id"));
        var jwt = decoder.decode(body.at("/data/accessToken").asText());
        assertEquals(java.time.Duration.ofMinutes(15), java.time.Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()));
        mvc.perform(post("/api/v1/matches").header("Authorization", "Bearer " + jwt.getTokenValue())
                        .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json").content("{}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.ownerId").value(body.at("/data/user/id").asText()));
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
    void deletionStopsSessionRefreshWhileShortLivedAccessTokenRemainsValid() throws Exception {
        String token = bearer(actor);
        mvc.perform(get("/api/v1/matches").header("Authorization", token)).andExpect(status().isOk());
        UUID session = UUID.fromString(decoder.decode(token.substring(7)).getClaimAsString("sid"));
        users.softDelete(actor);
        org.junit.jupiter.api.Assertions.assertTrue(sessions.findActiveById(session,
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)).isEmpty());
        // P01의 15분 Stateless 정책: 삭제 전 Access Token은 만료까지 검증된다.
        mvc.perform(get("/api/v1/matches").header("Authorization", token)).andExpect(status().isOk());
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
