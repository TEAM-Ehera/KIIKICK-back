package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.domain.Quarter;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FriendMatchControllerTest {
    @Autowired com.teamehera.kiikick.auth.security.AccessTokenService accessTokens;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired MatchRepository matches;
    @Autowired QuarterRepository quarters;
    @Autowired ObjectMapper json;
    private final UUID owner = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final UUID match = UUID.randomUUID();
    private final UUID quarter = UUID.randomUUID();

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:friend_match_controller;MODE=MySQL");
    }

    @BeforeEach
    void seed() {
        var now = LocalDateTime.of(2026, 11, 9, 0, 0);
        for (var user : new UUID[]{owner, friend, stranger}) {
            users.save(user, null);
            users.completeOnboarding(user, user.toString().substring(0, 8), now);
        }
        matches.save(new Match(match, owner, null, "친구 경기", null, null, null, "COMPLETED",
                now, now.plusHours(1), 1, now, now));
        quarters.save(new Quarter(quarter, match, 1, 900, "COMPLETED", now, now.plusMinutes(15), now, now));
    }

    @Test
    void acceptedFriendsReadMatchesAndResultsWithoutWriteAccess() throws Exception {
        mvc.perform(read(get(friendMatches()), friend)).andExpect(status().isNotFound());
        String request = requestFriendship();
        mvc.perform(read(get(friendMatches()), friend)).andExpect(status().isNotFound());
        accept(request);
        mvc.perform(read(get(friendMatches()), friend)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(match.toString()));
        mvc.perform(read(get(friendMatches() + "/" + match), friend)).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""));
        mvc.perform(read(get(friendMatches() + "/" + match + "/quarters"), friend)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(quarter.toString()));
        mvc.perform(read(get(friendMatches() + "/" + match + "/result"), friend)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quarterCount").value(1));
        mvc.perform(read(get(friendMatches() + "/" + match + "/result/quarters"), friend)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].quarter.id").value(quarter.toString()));
        mvc.perform(read(get(friendMatches()), stranger)).andExpect(status().isNotFound());
        mvc.perform(write(patch("/api/v1/matches/" + match), friend).header("If-Match", "\"1\"")
                .contentType("application/json").content("{\"title\":\"변경\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(read(get(friendMatches() + "/" + UUID.randomUUID()), friend)).andExpect(status().isNotFound());
    }

    @Test
    void commentsAreIdempotentAndOnlyAuthorsCanDelete() throws Exception {
        accept(requestFriendship());
        UUID key = UUID.randomUUID();
        var saved = mvc.perform(comment(friend, key, "좋은 경기!"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.authorId").value(friend.toString()))
                .andReturn().getResponse();
        String id = json.readTree(saved.getContentAsString()).at("/data/id").stringValue();
        mvc.perform(comment(friend, key, "좋은 경기!")).andExpect(status().isCreated())
                .andExpect(content().string(saved.getContentAsString()));
        mvc.perform(comment(friend, key, "다른 댓글")).andExpect(status().isConflict());
        mvc.perform(comment(owner, UUID.randomUUID(), "고마워!" )).andExpect(status().isCreated());
        mvc.perform(read(get(comments()), owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(comment(stranger, UUID.randomUUID(), "접근 불가")).andExpect(status().isNotFound());
        mvc.perform(read(get(comments()), stranger)).andExpect(status().isNotFound());
        mvc.perform(write(delete(comments() + "/" + id), owner)).andExpect(status().isNotFound());
        UUID deleteKey = UUID.randomUUID();
        mvc.perform(read(delete(comments() + "/" + id), friend).header("Idempotency-Key", deleteKey)).andExpect(status().isNoContent());
        mvc.perform(read(delete(comments() + "/" + id), friend).header("Idempotency-Key", deleteKey)).andExpect(status().isNoContent());
        mvc.perform(read(get(comments()), owner)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(read(get("/api/v1/matches/" + match), owner)).andExpect(header().string("ETag", "\"1\""));
    }

    @Test
    void removalAndBlocksRevokeReadsAndSuccessfulWriteReplays() throws Exception {
        accept(requestFriendship());
        UUID key = UUID.randomUUID();
        mvc.perform(comment(friend, key, "댓글")).andExpect(status().isCreated());
        mvc.perform(write(delete("/api/v1/friends/" + owner), friend)).andExpect(status().isNoContent());
        mvc.perform(read(get(friendMatches()), friend)).andExpect(status().isNotFound());
        mvc.perform(read(get(comments()), friend)).andExpect(status().isNotFound());
        mvc.perform(comment(friend, key, "댓글")).andExpect(status().isNotFound());
        accept(requestFriendship());
        mvc.perform(write(put("/api/v1/users/" + owner + "/block"), friend)).andExpect(status().isNoContent());
        mvc.perform(read(get(friendMatches() + "/" + match), friend)).andExpect(status().isNotFound());
        mvc.perform(comment(friend, key, "댓글")).andExpect(status().isNotFound());
        mvc.perform(read(get(comments()), owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void commentValidationAndPaginationAreScopedToViewerAndMatch() throws Exception {
        accept(requestFriendship());
        for (String content : new String[]{"", "   ", "가".repeat(1001)}) {
            mvc.perform(comment(friend, UUID.randomUUID(), content)).andExpect(status().isUnprocessableContent());
        }
        mvc.perform(read(post(comments()), friend).contentType("application/json").content("{\"content\":\"댓글\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(comment(friend, UUID.randomUUID(), "첫 댓글")).andExpect(status().isCreated());
        mvc.perform(comment(owner, UUID.randomUUID(), "둘째 댓글")).andExpect(status().isCreated());
        var page = mvc.perform(read(get(comments()), friend).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.pagination.hasNext").value(true))
                .andReturn().getResponse();
        String cursor = json.readTree(page.getContentAsString()).at("/meta/pagination/nextCursor").stringValue();
        mvc.perform(read(get(comments()), friend).param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].content").value("첫 댓글"))
                .andExpect(jsonPath("$.meta.pagination.hasNext").value(false));
        mvc.perform(read(get(comments()), owner).param("cursor", cursor)).andExpect(status().isBadRequest());
        mvc.perform(read(get(comments()), owner).param("limit", "101")).andExpect(status().isBadRequest());
        mvc.perform(get(comments())).andExpect(status().isUnauthorized());
    }

    private String requestFriendship() throws Exception {
        var response = mvc.perform(write(post("/api/v1/friend-requests"), friend).contentType("application/json")
                        .content("{\"targetUserId\":\"" + owner + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).at("/data/id").stringValue();
    }
    private void accept(String id) throws Exception {
        mvc.perform(write(post("/api/v1/friend-requests/" + id + "/accept"), owner)).andExpect(status().isOk());
    }
    private MockHttpServletRequestBuilder comment(UUID actor, UUID key, String text) {
        return read(post(comments()), actor).header("Idempotency-Key", key).contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("content", text)));
    }
    private MockHttpServletRequestBuilder read(MockHttpServletRequestBuilder request, UUID actor) {
        return request.with(com.teamehera.kiikick.auth.security.TestJwtRequests.bearer(accessTokens, actor));
    }
    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, UUID actor) {
        return read(request, actor).header("Idempotency-Key", UUID.randomUUID());
    }
    private String friendMatches() { return "/api/v1/friends/" + owner + "/matches"; }
    private String comments() { return "/api/v1/matches/" + match + "/comments"; }
}
