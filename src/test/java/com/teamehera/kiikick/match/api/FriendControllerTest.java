package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.auth.security.AccessTokenService;
import static com.teamehera.kiikick.auth.security.TestJwtRequests.bearer;

import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FriendControllerTest {
    private static final String REQUESTS = "/api/v1/friend-requests";
    private static final String SEARCH = "/api/v1/users/search";
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();
    private final UUID third = UUID.randomUUID();
    private final String prefix = first.toString().substring(0, 8);

    @Autowired MockMvc mvc;
    @Autowired AccessTokenService accessTokens;
    @Autowired UserRepository users;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:friend_controller;MODE=MySQL");
    }

    @BeforeEach
    void seedUsers() {
        onboard(first, prefix + " Alpha");
        onboard(second, prefix + " Beta");
        onboard(third, prefix + " Gamma");
    }

    @Test
    void requestsAreDirectionalAndFriendshipDoesNotGrantMatchAccess() throws Exception {
        UUID sendKey = UUID.randomUUID();
        var created = mvc.perform(send(first, second, sendKey))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse();
        String id = json.readTree(created.getContentAsString()).at("/data/id").stringValue();
        mvc.perform(send(first, second, sendKey)).andExpect(status().isCreated())
                .andExpect(content().string(created.getContentAsString()));
        mvc.perform(send(second, first, UUID.randomUUID())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REQUEST_ALREADY_RECEIVED"));
        mvc.perform(get(profile(second)).with(bearer(accessTokens, first)))
                .andExpect(jsonPath("$.data.relationship").value("PENDING_SENT"));
        mvc.perform(get(profile(first)).with(bearer(accessTokens, second)))
                .andExpect(jsonPath("$.data.relationship").value("PENDING_RECEIVED"));
        mvc.perform(get(REQUESTS).with(bearer(accessTokens, second)).param("direction", "RECEIVED"))
                .andExpect(jsonPath("$.data[0].id").value(id));
        mvc.perform(get(REQUESTS).with(bearer(accessTokens, first)).param("direction", "SENT"))
                .andExpect(jsonPath("$.data[0].recipientId").value(second.toString()));

        String acceptPath = REQUESTS + "/" + id + "/accept";
        mvc.perform(write(post(acceptPath), first)).andExpect(status().isForbidden());
        mvc.perform(write(post(acceptPath), third)).andExpect(status().isForbidden());
        mvc.perform(write(post(acceptPath), second)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        mvc.perform(get("/api/v1/friends").with(bearer(accessTokens, first)))
                .andExpect(jsonPath("$.data[0].profile.relationship").value("FRIEND"));
        mvc.perform(get(REQUESTS).with(bearer(accessTokens, first)).param("direction", "SENT"))
                .andExpect(jsonPath("$.data.length()").value(0));

        String match = mvc.perform(write(post("/api/v1/matches"), second).contentType("application/json").content("{}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        mvc.perform(get(match).with(bearer(accessTokens, first))).andExpect(status().isNotFound());
        mvc.perform(write(delete("/api/v1/friends/" + second), first)).andExpect(status().isNoContent());
        mvc.perform(get(profile(second)).with(bearer(accessTokens, first)))
                .andExpect(jsonPath("$.data.relationship").value("NONE"));
    }

    @Test
    void cancellationAndIndependentBlocksDoNotRestoreRelationships() throws Exception {
        String id = sendAndReadId(first, second);
        mvc.perform(write(delete(REQUESTS + "/" + id), second)).andExpect(status().isNoContent());
        mvc.perform(write(post(REQUESTS + "/" + id + "/accept"), second)).andExpect(status().isNotFound());
        String nextId = sendAndReadId(first, second);
        assertTrue(!id.equals(nextId));

        String blockSecond = "/api/v1/users/" + second + "/block";
        String blockFirst = "/api/v1/users/" + first + "/block";
        mvc.perform(write(put(blockSecond), first)).andExpect(status().isNoContent());
        mvc.perform(get(profile(second)).with(bearer(accessTokens, first))).andExpect(status().isNotFound());
        mvc.perform(get(profile(first)).with(bearer(accessTokens, second))).andExpect(status().isNotFound());
        mvc.perform(get(SEARCH).with(bearer(accessTokens, second)).param("query", prefix + " Alpha"))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(send(second, first, UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get(REQUESTS).with(bearer(accessTokens, second)).param("direction", "RECEIVED"))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(write(put(blockFirst), second)).andExpect(status().isNoContent());
        mvc.perform(write(delete(blockSecond), first)).andExpect(status().isNoContent());
        mvc.perform(get(profile(second)).with(bearer(accessTokens, first))).andExpect(status().isNotFound());
        mvc.perform(write(delete(blockFirst), second)).andExpect(status().isNoContent());
        mvc.perform(get(profile(second)).with(bearer(accessTokens, first)))
                .andExpect(jsonPath("$.data.relationship").value("NONE"));
    }

    @Test
    void searchPaginatesOnlyPublicProfilesAndScopesTheCursor() throws Exception {
        UUID privateUser = UUID.randomUUID();
        users.save(privateUser, prefix + " Hidden");
        mvc.perform(get(SEARCH).with(bearer(accessTokens, first)).param("query", "C"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get(profile(privateUser)).with(bearer(accessTokens, first))).andExpect(status().isNotFound());
        var page = mvc.perform(get(SEARCH).with(bearer(accessTokens, first)).param("query", prefix).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(second.toString()))
                .andExpect(jsonPath("$.data[0].email").doesNotExist())
                .andExpect(jsonPath("$.meta.pagination.hasNext").value(true)).andReturn().getResponse();
        String cursor = json.readTree(page.getContentAsString()).at("/meta/pagination/nextCursor").stringValue();
        mvc.perform(get(SEARCH).with(bearer(accessTokens, first)).param("query", prefix).param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(third.toString()));
        mvc.perform(get(SEARCH).with(bearer(accessTokens, second)).param("query", prefix).param("cursor", cursor))
                .andExpect(status().isBadRequest());
    }

    @Test
    void simultaneousOppositeRequestsCreateOnePendingPair() throws Exception {
        var ready = new CountDownLatch(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var sent = pool.submit(concurrentSend(first, second, ready));
            var received = pool.submit(concurrentSend(second, first, ready));
            assertEquals(Set.of(201, 409), Set.of(sent.get(10, TimeUnit.SECONDS), received.get(10, TimeUnit.SECONDS)));
        }
        mvc.perform(get("/api/v1/friends").with(bearer(accessTokens, first))).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void blockRevokesAccessToPreviouslySuccessfulSendAndAcceptReplays() throws Exception {
        UUID sendKey = UUID.randomUUID();
        var sent = mvc.perform(send(first, second, sendKey)).andExpect(status().isCreated())
                .andReturn().getResponse();
        String id = json.readTree(sent.getContentAsString()).at("/data/id").stringValue();
        String acceptPath = REQUESTS + "/" + id + "/accept";
        UUID acceptKey = UUID.randomUUID();
        var accepted = mvc.perform(post(acceptPath).with(bearer(accessTokens, second)).header("Idempotency-Key", acceptKey))
                .andExpect(status().isOk()).andReturn().getResponse();
        mvc.perform(post(acceptPath).with(bearer(accessTokens, second)).header("Idempotency-Key", acceptKey))
                .andExpect(status().isOk()).andExpect(content().string(accepted.getContentAsString()));

        mvc.perform(write(put("/api/v1/users/" + first + "/block"), second)).andExpect(status().isNoContent());
        mvc.perform(send(first, second, sendKey)).andExpect(status().isNotFound());
        mvc.perform(post(acceptPath).with(bearer(accessTokens, second)).header("Idempotency-Key", acceptKey))
                .andExpect(status().isNotFound());
    }

    @Test
    void requestIdMatchesResponseMetadataAndIsReplayedForNoContent() throws Exception {
        var profile = mvc.perform(get(profile(second)).with(bearer(accessTokens, first)))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertEquals(json.readTree(profile.getContentAsString()).at("/meta/requestId").stringValue(),
                profile.getHeader("X-Request-Id"));
        var error = mvc.perform(get(SEARCH).with(bearer(accessTokens, first)).param("query", "x"))
                .andExpect(status().isUnprocessableContent()).andReturn().getResponse();
        assertEquals(json.readTree(error.getContentAsString()).at("/meta/requestId").stringValue(),
                error.getHeader("X-Request-Id"));
        UUID key = UUID.randomUUID();
        String path = "/api/v1/users/" + second + "/block";
        var block = mvc.perform(put(path).with(bearer(accessTokens, first)).header("Idempotency-Key", key))
                .andExpect(status().isNoContent()).andExpect(header().exists("X-Request-Id"))
                .andReturn().getResponse();
        mvc.perform(put(path).with(bearer(accessTokens, first)).header("Idempotency-Key", key))
                .andExpect(status().isNoContent()).andExpect(header().string("X-Request-Id",
                        block.getHeader("X-Request-Id")));
    }

    private Callable<Integer> concurrentSend(UUID actor, UUID target, CountDownLatch ready) {
        return () -> {
            ready.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            return mvc.perform(send(actor, target, UUID.randomUUID())).andReturn().getResponse().getStatus();
        };
    }

    private String sendAndReadId(UUID actor, UUID target) throws Exception {
        var result = mvc.perform(send(actor, target, UUID.randomUUID())).andExpect(status().isCreated())
                .andReturn().getResponse();
        return json.readTree(result.getContentAsString()).at("/data/id").stringValue();
    }

    private MockHttpServletRequestBuilder send(UUID actor, UUID target, UUID key) {
        return post(REQUESTS).with(bearer(accessTokens, actor)).header("Idempotency-Key", key)
                .contentType("application/json").content(json.writeValueAsString(Map.of("targetUserId", target)));
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, UUID actor) {
        return request.with(bearer(accessTokens, actor)).header("Idempotency-Key", UUID.randomUUID());
    }

    private static String profile(UUID userId) {
        return "/api/v1/users/" + userId + "/profile";
    }

    private void onboard(UUID id, String nickname) {
        users.save(id, null);
        users.completeOnboarding(id, nickname, LocalDateTime.of(2026, 11, 4, 0, 0));
    }
}
