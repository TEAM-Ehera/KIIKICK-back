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
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

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
                .andExpect(jsonPath("$.data.parentCommentId").doesNotHaveJsonPath())
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
        mvc.perform(read(get(comments()), owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].id").value(id))
                .andExpect(jsonPath("$.data[1].content").value("[삭제된 댓글입니다]"));
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

    @Test
    void repliesAreIdempotentAndRemainVisibleAfterParentDeletion() throws Exception {
        accept(requestFriendship());
        String parent = createComment(owner, "원문 비공개");
        UUID key = UUID.randomUUID();
        var saved = mvc.perform(reply(friend, key, parent, "답글"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.parentCommentId").value(parent))
                .andReturn().getResponse();
        String id = json.readTree(saved.getContentAsString()).at("/data/id").stringValue();
        mvc.perform(reply(friend, key, parent, "답글")).andExpect(status().isCreated())
                .andExpect(header().string("Location", comments() + "/" + id))
                .andExpect(content().string(saved.getContentAsString()));

        mvc.perform(read(get(comments()), friend)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(parent));
        mvc.perform(replies(friend, parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(id));

        mvc.perform(write(delete(comments() + "/" + parent), owner)).andExpect(status().isNoContent());
        mvc.perform(read(get(comments()), friend)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(parent))
                .andExpect(jsonPath("$.data[0].content").value("[삭제된 댓글입니다]"))
                .andExpect(content().string(not(containsString("원문 비공개"))));
        mvc.perform(replies(friend, parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].content").value("답글"));
        mvc.perform(reply(friend, UUID.randomUUID(), parent, "새 답글")).andExpect(status().isNotFound());
        mvc.perform(write(delete(comments() + "/" + id), owner)).andExpect(status().isNotFound());
        mvc.perform(write(delete(comments() + "/" + id), friend)).andExpect(status().isNoContent());
        mvc.perform(replies(friend, parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(read(get(comments()), friend)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(parent));
    }

    @Test
    void rejectsInvalidReplyParents() throws Exception {
        String parent = createComment(owner, "부모");
        var saved = mvc.perform(reply(owner, UUID.randomUUID(), parent, "답글"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String id = json.readTree(saved.getContentAsString()).at("/data/id").stringValue();
        mvc.perform(reply(owner, UUID.randomUUID(), id, "중첩 답글")).andExpect(status().isNotFound());

        var otherMatch = UUID.randomUUID();
        var now = LocalDateTime.of(2026, 11, 9, 0, 0);
        matches.save(new Match(otherMatch, owner, null, "다른 경기", null, null, null, "DRAFT",
                null, null, 1, now, now));
        mvc.perform(write(post("/api/v1/matches/" + otherMatch + "/comments"), owner)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("content", "다른 경기 답글", "parentCommentId", parent))))
                .andExpect(status().isNotFound());
        mvc.perform(reply(owner, UUID.randomUUID(), UUID.randomUUID().toString(), "없는 부모")).andExpect(status().isNotFound());
        mvc.perform(reply(owner, UUID.randomUUID(), "1-1-1-1-1", "잘못된 UUID")).andExpect(status().isUnprocessableContent());
        mvc.perform(write(post(comments()), owner).contentType("application/json")
                        .content(json.writeValueAsString(Map.of("content", "답글", "parentCommentId", 123))))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void replyCursorsAreScopedToAParentAndSeparatedFromTopLevelCursors() throws Exception {
        String parent = createComment(owner, "부모");
        String otherParent = createComment(owner, "다른 부모");
        mvc.perform(reply(owner, UUID.randomUUID(), parent, "첫 답글")).andExpect(status().isCreated());
        mvc.perform(reply(owner, UUID.randomUUID(), parent, "둘째 답글")).andExpect(status().isCreated());
        var page = mvc.perform(replies(owner, parent).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].content").value("둘째 답글"))
                .andExpect(jsonPath("$.meta.pagination.hasNext").value(true)).andReturn().getResponse();
        String cursor = json.readTree(page.getContentAsString()).at("/meta/pagination/nextCursor").stringValue();
        mvc.perform(replies(owner, parent).param("cursor", cursor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].content").value("첫 답글"))
                .andExpect(jsonPath("$.meta.pagination.hasNext").value(false));
        mvc.perform(replies(owner, otherParent).param("cursor", cursor)).andExpect(status().isBadRequest());
        mvc.perform(read(get(comments()), owner).param("cursor", cursor)).andExpect(status().isBadRequest());

        var rootPage = mvc.perform(read(get(comments()), owner).param("limit", "1"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String rootCursor = json.readTree(rootPage.getContentAsString()).at("/meta/pagination/nextCursor").stringValue();
        mvc.perform(replies(owner, parent).param("cursor", rootCursor)).andExpect(status().isBadRequest());
    }

    @Test
    void blockedParentPreventsReplyReadsAndWrites() throws Exception {
        accept(requestFriendship());
        String parent = createComment(friend, "친구 댓글");
        mvc.perform(reply(owner, UUID.randomUUID(), parent, "기존 답글")).andExpect(status().isCreated());
        mvc.perform(replies(stranger, parent)).andExpect(status().isNotFound());

        mvc.perform(write(put("/api/v1/users/" + owner + "/block"), friend)).andExpect(status().isNoContent());
        mvc.perform(replies(owner, parent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(reply(owner, UUID.randomUUID(), parent, "차단 이후 답글")).andExpect(status().isNotFound());
    }

    private String createComment(UUID actor, String text) throws Exception {
        var response = mvc.perform(comment(actor, UUID.randomUUID(), text))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).at("/data/id").stringValue();
    }

    private MockHttpServletRequestBuilder reply(UUID actor, UUID key, String parent, String text) {
        return read(post(comments()), actor).header("Idempotency-Key", key).contentType("application/json")
                .content(json.writeValueAsString(Map.of("content", text, "parentCommentId", parent)));
    }

    private MockHttpServletRequestBuilder replies(UUID actor, String parent) {
        return read(get(comments()), actor).param("parentCommentId", parent);
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
                .content(json.writeValueAsString(Map.of("content", text)));
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
