package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.friend.domain.FriendPair;
import com.teamehera.kiikick.friend.domain.UserBlock;
import com.teamehera.kiikick.friend.repository.FriendPairRepository;
import com.teamehera.kiikick.friend.repository.UserBlockRepository;
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

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static com.teamehera.kiikick.auth.security.TestJwtRequests.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class QuarterFriendControllerTest {
    @Autowired MockMvc mvc;
    @Autowired com.teamehera.kiikick.auth.security.AccessTokenService accessTokens;
    @Autowired UserRepository users;
    @Autowired MatchRepository matches;
    @Autowired QuarterRepository quarters;
    @Autowired FriendPairRepository pairs;
    @Autowired UserBlockRepository blocks;
    private final UUID actor = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final UUID matchId = UUID.randomUUID();
    private final UUID quarterId = UUID.randomUUID();
    private final UUID secondQuarterId = UUID.randomUUID();
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 10, 0, 0);

    @org.springframework.test.context.DynamicPropertySource
    static void database(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:quarter_friends_controller;MODE=MySQL");
    }

    @BeforeEach
    void seed() {
        for (UUID id : new UUID[]{actor, friend, stranger}) {
            users.save(id, null);
            users.completeOnboarding(id, id.toString().substring(0, 8), now);
        }
        matches.save(new Match(matchId, actor, null, null, null, null, null,
                "COMPLETED", now, now.plusHours(1), 1, now, now));
        quarters.save(new Quarter(quarterId, matchId, 1, 900, "COMPLETED", now, now.plusMinutes(15), now, now));
        quarters.save(new Quarter(secondQuarterId, matchId, 2, 900, "COMPLETED", now, now.plusMinutes(15), now, now));
        var ids = FriendPairRepository.normalize(actor, friend);
        pairs.save(new FriendPair(UUID.randomUUID(), ids[0], ids[1], actor, "ACCEPTED", now, now));
    }

    @Test
    void addsListsAndRemovesFriendsIndependentlyPerQuarter() throws Exception {
        mvc.perform(write(put(path(quarterId, friend)), actor, 1)).andExpect(status().isNoContent())
                .andExpect(header().string("ETag", "\"2\""));
        mvc.perform(get(collection(quarterId)).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].userId").value(friend.toString()))
                .andExpect(jsonPath("$.meta.matchVersion").value(2));
        mvc.perform(get(collection(secondQuarterId)).with(bearer(accessTokens, actor)))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(write(put(path(secondQuarterId, friend)), actor, 2)).andExpect(status().isNoContent());
        mvc.perform(write(delete(path(quarterId, friend)), actor, 3)).andExpect(status().isNoContent());
        mvc.perform(get(collection(quarterId)).with(bearer(accessTokens, actor)))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(collection(secondQuarterId)).with(bearer(accessTokens, actor)))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void retriesReplayHeadersAndDifferentKeysDoNotDuplicateMembership() throws Exception {
        UUID key = UUID.randomUUID();
        var first = mvc.perform(write(put(path(quarterId, friend)), actor, 1, key))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        mvc.perform(write(put(path(quarterId, friend)), actor, 1, key))
                .andExpect(status().isNoContent()).andExpect(header().string("ETag", first.getHeader("ETag")))
                .andExpect(header().string("X-Request-Id", first.getHeader("X-Request-Id")));
        mvc.perform(write(put(path(quarterId, friend)), actor, 2)).andExpect(status().isNoContent());
        mvc.perform(get(collection(quarterId)).with(bearer(accessTokens, actor)))
                .andExpect(jsonPath("$.data.length()").value(1));
        UUID deleteKey = UUID.randomUUID();
        mvc.perform(write(delete(path(quarterId, friend)), actor, 3, deleteKey))
                .andExpect(status().isNoContent());
        mvc.perform(write(delete(path(quarterId, friend)), actor, 3, deleteKey))
                .andExpect(status().isNoContent()).andExpect(header().string("ETag", "\"4\""));
        mvc.perform(write(delete(path(quarterId, friend)), actor, 4)).andExpect(status().isNoContent());
        assertEquals(5, matches.findByIdAndOwnerId(matchId, actor).orElseThrow().version());
    }

    @Test
    void onlyOwnerCanReadAndWriteAndQuarterMustBelongToMatch() throws Exception {
        mvc.perform(get(collection(quarterId))).andExpect(status().isUnauthorized());
        mvc.perform(get(collection(quarterId)).with(bearer(accessTokens, friend))).andExpect(status().isNotFound());
        mvc.perform(write(put(path(quarterId, friend)), stranger, 1)).andExpect(status().isNotFound());
        mvc.perform(write(delete(path(quarterId, friend)), friend, 1)).andExpect(status().isNotFound());
        String wrongParent = path(quarterId, friend).replace(matchId.toString(), UUID.randomUUID().toString());
        mvc.perform(write(put(wrongParent), actor, 1)).andExpect(status().isNotFound());
        mvc.perform(write(put(path(UUID.randomUUID(), friend)), actor, 1)).andExpect(status().isNotFound());
    }

    @Test
    void rejectsSelfPendingUnknownBlockedAndDeletedUsersWithoutChangingVersion() throws Exception {
        mvc.perform(write(put(path(quarterId, actor)), actor, 1)).andExpect(status().isNotFound());
        mvc.perform(write(put(path(quarterId, UUID.randomUUID())), actor, 1)).andExpect(status().isNotFound());
        var ids = FriendPairRepository.normalize(actor, stranger);
        pairs.save(new FriendPair(UUID.randomUUID(), ids[0], ids[1], actor, "PENDING", null, now));
        mvc.perform(write(put(path(quarterId, stranger)), actor, 1)).andExpect(status().isNotFound());
        blocks.save(new UserBlock(friend, actor, now));
        mvc.perform(write(put(path(quarterId, friend)), actor, 1)).andExpect(status().isNotFound());
        blocks.delete(friend, actor);
        users.softDelete(friend);
        mvc.perform(write(put(path(quarterId, friend)), actor, 1)).andExpect(status().isNotFound());
        assertEquals(1, matches.findByIdAndOwnerId(matchId, actor).orElseThrow().version());
    }

    @Test
    void validatesHeadersAndRejectsBodies() throws Exception {
        mvc.perform(put(path(quarterId, friend)).with(bearer(accessTokens, actor))
                .header("Idempotency-Key", UUID.randomUUID())).andExpect(status().isPreconditionRequired());
        mvc.perform(write(put(path(quarterId, friend)), actor, 9)).andExpect(status().isPreconditionFailed());
        mvc.perform(put(path(quarterId, friend)).with(bearer(accessTokens, actor)).header("If-Match", "\"1\""))
                .andExpect(status().isBadRequest());
        mvc.perform(write(put(path(quarterId, friend)), actor, 1).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(delete(path(quarterId, friend)), actor, 1).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void historicalEntrySurvivesUnfriendingAndCanBeRemovedAfterBlocking() throws Exception {
        mvc.perform(write(put(path(quarterId, friend)), actor, 1)).andExpect(status().isNoContent());
        pairs.deleteByUsers(actor, friend);
        mvc.perform(get(collection(quarterId)).with(bearer(accessTokens, actor)))
                .andExpect(jsonPath("$.data.length()").value(1));
        blocks.save(new UserBlock(actor, friend, now));
        mvc.perform(get(collection(quarterId)).with(bearer(accessTokens, actor)))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(write(delete(path(quarterId, friend)), actor, 2)).andExpect(status().isNoContent());
        blocks.delete(actor, friend);
        mvc.perform(get(collection(quarterId)).with(bearer(accessTokens, actor)))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void simultaneousWritesWithSameVersionAllowOnlyOneMutation() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return mvc.perform(write(put(path(quarterId, friend)), actor, 1))
                    .andReturn().getResponse().getStatus(); });
            var b = pool.submit(() -> { start.await(); return mvc.perform(write(put(path(quarterId, friend)), actor, 1))
                    .andReturn().getResponse().getStatus(); });
            start.countDown();
            assertEquals(java.util.Set.of(204, 412), java.util.Set.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)));
        }
        assertEquals(2, matches.findByIdAndOwnerId(matchId, actor).orElseThrow().version());
    }

    @Test
    void deletingQuarterCascadesItsFriendEntries() throws Exception {
        mvc.perform(write(put(path(secondQuarterId, friend)), actor, 1)).andExpect(status().isNoContent());
        // Direct deletion exercises the FK used by any quarter cleanup path.
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update("DELETE FROM quarters WHERE id = ?",
                com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes(secondQuarterId));
        assertTrue(new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForList(
                "SELECT * FROM quarter_friends WHERE quarter_id = ?",
                com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes(secondQuarterId)).isEmpty());
    }

    @Autowired javax.sql.DataSource dataSource;

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, UUID user, long version) {
        return write(request, user, version, UUID.randomUUID());
    }
    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, UUID user, long version, UUID key) {
        return request.with(bearer(accessTokens, user)).header("Idempotency-Key", key)
                .header("If-Match", "\"" + version + "\"");
    }
    private String collection(UUID quarter) {
        return "/api/v1/matches/" + matchId + "/quarters/" + quarter + "/friends";
    }
    private String path(UUID quarter, UUID user) {
        return collection(quarter) + "/" + user;
    }
}
