package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.auth.security.AccessTokenService;
import static com.teamehera.kiikick.auth.security.TestJwtRequests.bearer;

import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterPlayerStatsRepository;
import com.teamehera.kiikick.match.repository.QuarterRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import com.teamehera.kiikick.venue.domain.Venue;
import com.teamehera.kiikick.venue.repository.VenueRepository;
import com.teamehera.kiikick.workout.domain.HeartRateSample;
import com.teamehera.kiikick.workout.domain.TrackPoint;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import com.teamehera.kiikick.workout.repository.HeartRateSampleRepository;
import com.teamehera.kiikick.workout.repository.QuarterSummaryRepository;
import com.teamehera.kiikick.workout.repository.TrackPointRepository;
import com.teamehera.kiikick.workout.repository.WorkoutPayloadRepository;
import com.teamehera.kiikick.workout.service.QuarterSummaryCalculation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class MatchControllerTest {
    private static final String MATCHES = "/api/v1/matches";
    private final UUID actor = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired AccessTokenService accessTokens;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired VenueRepository venues;
    @Autowired MatchRepository matches;
    @Autowired QuarterRepository quarters;
    @Autowired QuarterPlayerStatsRepository stats;
    @Autowired WorkoutPayloadRepository workouts;
    @Autowired QuarterSummaryRepository summaries;
    @Autowired QuarterSummaryCalculation summaryCalculation;
    @Autowired HeartRateSampleRepository heartRates;
    @MockitoSpyBean TrackPointRepository trackPoints;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("kiikick.match.position-codes", () -> "TEST_FIRST,TEST_SECOND");
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:match_controller;MODE=MySQL");
    }

    @BeforeEach
    void setUp() {
        users.save(actor, null);
        users.save(other, null);
    }

    @Test
    void createReturnsLocationAndReplaysWithoutCreatingAnotherMatch() throws Exception {
        UUID venueId = venue(actor);
        UUID key = UUID.randomUUID();
        String body = """
                {"title":"풋살","venueId":"%s","scheduledAt":"2026-11-04T10:00:00+09:00"}
                """.formatted(venueId);
        var original = mvc.perform(write(post(MATCHES), actor, key, body))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.data.ownerId").value(actor.toString()))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.venueSnapshot.name").value("풋살장"))
                .andExpect(jsonPath("$.data.scheduledAt").value("2026-11-04T10:00:00+09:00"))
                .andExpect(jsonPath("$.meta.matchVersion").value(1))
                .andReturn().getResponse();
        String location = original.getHeader("Location");
        assertEquals(MATCHES + "/" + json.readTree(original.getContentAsString()).at("/data/id").asText(), location);
        mvc.perform(write(post(MATCHES), actor, key, body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", location))
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(content().json(original.getContentAsString()));
        mvc.perform(write(post(MATCHES), actor, key, "{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(1, matches.findPageByOwnerId(actor, null, 10).size());

        mvc.perform(get(location).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.data.venueId").value(venueId.toString()));
        mvc.perform(get(location).with(bearer(accessTokens, other)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void concurrentCreatesWithOneKeyReturnTheSameMatch() throws Exception {
        var ready = new CountDownLatch(2);
        UUID key = UUID.randomUUID();
        Callable<String> create = () -> {
            ready.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            return mvc.perform(write(post(MATCHES), actor, key, "{}"))
                    .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(create);
            var second = pool.submit(create);
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, matches.findPageByOwnerId(actor, null, 10).size());
    }

    @Test
    void listReturnsOwnerPagesAndAnEmptyArray() throws Exception {
        String older = create(actor, "{}");
        String newer = create(actor, "{}");
        create(other, "{}");
        var page = mvc.perform(get(MATCHES).with(bearer(accessTokens, actor))
                        .param("status", "DRAFT").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(id(newer).toString()))
                .andExpect(jsonPath("$.meta.pagination.hasNext").value(true))
                .andReturn().getResponse();
        String cursor = json.readTree(page.getContentAsString()).at("/meta/pagination/nextCursor").asText();
        mvc.perform(get(MATCHES).with(bearer(accessTokens, actor))
                        .param("status", "DRAFT").param("cursor", cursor).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id(older).toString()))
                .andExpect(jsonPath("$.meta.pagination.hasNext").value(false))
                .andExpect(jsonPath("$.meta.pagination.nextCursor").value(nullValue()));
        mvc.perform(get(MATCHES).with(bearer(accessTokens, other)).param("status", "DRAFT").param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
        mvc.perform(get(MATCHES).with(bearer(accessTokens, actor)).param("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(MATCHES).with(bearer(accessTokens, actor)).param("status", "READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void updateDistinguishesOmittedAndNullFieldsAndReplaysBeforeVersionCheck() throws Exception {
        String location = create(actor, """
                {"title":"유지","venueId":"%s","scheduledAt":"2026-11-04T10:00:00+09:00"}
                """.formatted(venue(actor)));
        String body = """
                {"venueSnapshot":{"name":"직접 입력"},"scheduledAt":null}
                """;
        UUID key = UUID.randomUUID();
        var original = mvc.perform(write(patch(location), actor, key, body).header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.data.title").value("유지"))
                .andExpect(jsonPath("$.data.venueId").value(nullValue()))
                .andExpect(jsonPath("$.data.venueSnapshot.name").value("직접 입력"))
                .andExpect(jsonPath("$.data.scheduledAt").value(nullValue()))
                .andReturn().getResponse();
        mvc.perform(write(patch(location), actor, key, body).header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(content().json(original.getContentAsString()));
        mvc.perform(write(patch(location), actor, UUID.randomUUID(), body).header("If-Match", "\"1\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.error.details.currentVersion").value(2));
        mvc.perform(write(patch(location), actor, UUID.randomUUID(), body))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(write(patch(location), other, UUID.randomUUID(), body).header("If-Match", "\"2\""))
                .andExpect(status().isNotFound());

        matches.updateStatus(id(location), actor, 2, "DRAFT", "READY", null, null);
        mvc.perform(write(patch(location), actor, UUID.randomUUID(), """
                        {"title":null,"venueSnapshot":null}
                        """).header("If-Match", "\"3\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value(nullValue()))
                .andExpect(jsonPath("$.data.venueSnapshot").value(nullValue()));
        matches.updateStatus(id(location), actor, 4, "READY", "IN_PROGRESS", null, null);
        mvc.perform(write(patch(location), actor, UUID.randomUUID(), "{}").header("If-Match", "\"5\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void rejectsUnverifiedRequestsAndInvalidSettings() throws Exception {
        mvc.perform(get(MATCHES).header("Authorization", "Bearer " + actor))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(post(MATCHES).with(bearer(accessTokens, actor)).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mvc.perform(write(post(MATCHES), actor, UUID.randomUUID(), "{"))
                .andExpect(status().isBadRequest());
        mvc.perform(write(post(MATCHES), actor, UUID.randomUUID(), """
                        {"venueId":"%s"}
                        """.formatted(venue(other))))
                .andExpect(status().isNotFound());
        for (String body : new String[]{"{\"ownerId\":null}", "{\"scheduledAt\":\"2026-11-04T10:00:00\"}",
                """
                {"venueId":"%s","venueSnapshot":{"name":"직접 입력"}}
                """.formatted(venue(actor))}) {
            mvc.perform(write(post(MATCHES), actor, UUID.randomUUID(), body))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        assertEquals(0, matches.findPageByOwnerId(actor, null, 10).size());
    }

    @Test
    void quarterPaginationCarriesMatchVersionAndRejectsStaleCursors() throws Exception {
        String match = create(actor, "{}");
        String path = match + "/quarters";
        String first = addQuarter(path, 1);
        addQuarter(path, 2);
        var page = mvc.perform(get(path).with(bearer(accessTokens, actor)).param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", tag(3)))
                .andExpect(jsonPath("$.data[0].number").value(1))
                .andExpect(jsonPath("$.data[0].matchVersion").value(3))
                .andExpect(jsonPath("$.meta.matchVersion").value(3))
                .andExpect(jsonPath("$.meta.pagination.hasNext").value(true))
                .andReturn().getResponse();
        String cursor = json.readTree(page.getContentAsString()).at("/meta/pagination/nextCursor").asText();
        mvc.perform(get(path).with(bearer(accessTokens, actor)).param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].number").value(2))
                .andExpect(jsonPath("$.meta.pagination.hasNext").value(false));
        mvc.perform(get(path).with(bearer(accessTokens, other))).andExpect(status().isNotFound());
        mvc.perform(write(patch(first), actor, UUID.randomUUID(), "{\"durationSeconds\":75}")
                        .header("If-Match", tag(3)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", tag(4)))
                .andExpect(jsonPath("$.data.durationSeconds").value(75));
        mvc.perform(get(path).with(bearer(accessTokens, actor)).param("cursor", cursor))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CURSOR_SNAPSHOT_CHANGED"));
    }

    @Test
    void deletesOnlyUnusedLastQuarterAndReplaysNoContent() throws Exception {
        String path = create(actor, "{}") + "/quarters";
        String first = addQuarter(path, 1);
        String last = addQuarter(path, 2);
        UUID lastId = UUID.fromString(last.substring(last.lastIndexOf('/') + 1));
        assertTrue(stats.findByQuarterId(lastId).isEmpty());
        mvc.perform(remove(first, UUID.randomUUID(), 3)).andExpect(status().isConflict());

        String wrongParent = create(actor, "{}") + "/quarters/" + lastId;
        mvc.perform(remove(wrongParent, UUID.randomUUID(), 3)).andExpect(status().isNotFound());
        UUID key = UUID.randomUUID();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(remove(last, key, 3)).andExpect(status().isNoContent())
                    .andExpect(header().string("ETag", tag(4))).andExpect(content().string(""));
        }
        mvc.perform(remove(last, UUID.randomUUID(), 4)).andExpect(status().isNotFound());
        UUID firstId = UUID.fromString(first.substring(first.lastIndexOf('/') + 1));
        stats.upsert(firstId, 0, 0, LocalDateTime.of(2026, 11, 4, 0, 0));
        mvc.perform(remove(first, UUID.randomUUID(), 4)).andExpect(status().isConflict());
    }

    @Test
    void validatesDurationAndQuarterState() throws Exception {
        String match = create(actor, "{}");
        String path = match + "/quarters";
        for (String body : new String[]{"{}", "{\"durationSeconds\":0}", "{\"durationSeconds\":1.5}"}) {
            mvc.perform(write(post(path), actor, UUID.randomUUID(), body).header("If-Match", tag(1)))
                    .andExpect(status().isUnprocessableContent());
        }
        String quarter = addQuarter(path, 1);
        UUID quarterId = UUID.fromString(quarter.substring(quarter.lastIndexOf('/') + 1));
        quarters.updateStatus(quarterId, id(match), "READY", "IN_PROGRESS", LocalDateTime.now(), null);
        mvc.perform(write(patch(quarter), actor, UUID.randomUUID(), "{\"durationSeconds\":75}")
                        .header("If-Match", tag(2)))
                .andExpect(status().isConflict());
        matches.updateStatus(id(match), actor, 2, "DRAFT", "COMPLETED", null, null);
        mvc.perform(write(post(path), actor, UUID.randomUUID(), "{\"durationSeconds\":60}")
                        .header("If-Match", tag(3)))
                .andExpect(status().isConflict());
    }

    @Test
    void concurrentQuarterAddsWithTheSameVersionHaveOneWinner() throws Exception {
        String path = create(actor, "{}") + "/quarters";
        var ready = new CountDownLatch(2);
        Callable<Integer> add = () -> {
            ready.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            return mvc.perform(write(post(path), actor, UUID.randomUUID(), "{\"durationSeconds\":60}")
                            .header("If-Match", tag(1))).andReturn().getResponse().getStatus();
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(add);
            var second = pool.submit(add);
            assertEquals(java.util.Set.of(201, 412),
                    java.util.Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
        }
        addQuarter(path, 2);
        mvc.perform(get(path).with(bearer(accessTokens, actor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].number").value(1))
                .andExpect(jsonPath("$.data[1].number").value(2));
    }

    @Test
    void transitionsMatchAndReplaysEndWithoutWaitingForWorkout() throws Exception {
        String match = create(actor, "{}");
        mvc.perform(transition(match, "ready", actor, UUID.randomUUID(), 1))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        mvc.perform(transition(match, "start", actor, UUID.randomUUID(), 1))
                .andExpect(status().isConflict());
        String quarter = addQuarter(match + "/quarters", 1);
        UUID quarterId = UUID.fromString(quarter.substring(quarter.lastIndexOf('/') + 1));
        mvc.perform(transition(match, "ready", other, UUID.randomUUID(), 2))
                .andExpect(status().isNotFound());
        mvc.perform(transition(match, "ready", actor, UUID.randomUUID(), 1))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(transition(match, "ready", actor, UUID.randomUUID(), 2))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(3)))
                .andExpect(jsonPath("$.data.status").value("READY"));
        mvc.perform(transition(match, "start", actor, UUID.randomUUID(), 3))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(4)))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty());

        mvc.perform(transition(match, "cancel", actor, UUID.randomUUID(), 4))
                .andExpect(status().isConflict());
        mvc.perform(transition(match, "end", actor, UUID.randomUUID(), 4))
                .andExpect(status().isConflict());
        var startedAt = matches.findByIdAndOwnerId(id(match), actor).orElseThrow().startedAt();
        assertEquals(1, quarters.updateStatus(quarterId, id(match), "READY", "IN_PROGRESS", startedAt, null));
        mvc.perform(transition(match, "end", actor, UUID.randomUUID(), 4))
                .andExpect(status().isConflict());
        assertEquals(1, quarters.updateStatus(quarterId, id(match), "IN_PROGRESS", "COMPLETED", null, startedAt));

        UUID key = UUID.randomUUID();
        var completed = mvc.perform(transition(match, "end", actor, key, 4))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(5)))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.endedAt").isNotEmpty())
                .andExpect(jsonPath("$.meta.matchVersion").value(5))
                .andReturn().getResponse();
        mvc.perform(transition(match, "end", actor, key, 4))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(5)))
                .andExpect(content().string(completed.getContentAsString()));
    }

    @Test
    void cancelsBeforeStartAndRejectsBody() throws Exception {
        String draft = create(actor, "{}");
        mvc.perform(transition(draft, "cancel", actor, UUID.randomUUID(), 1)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(transition(draft, "cancel", actor, UUID.randomUUID(), 1))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(2)))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        String ready = create(actor, "{}");
        addQuarter(ready + "/quarters", 1);
        mvc.perform(transition(ready, "ready", actor, UUID.randomUUID(), 2))
                .andExpect(status().isOk());
        mvc.perform(transition(ready, "cancel", actor, UUID.randomUUID(), 3))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mvc.perform(transition(ready, "start", actor, UUID.randomUUID(), 4))
                .andExpect(status().isConflict());
    }

    @Test
    void cannotEndInProgressMatchWithNoQuarters() throws Exception {
        String match = create(actor, "{}");
        String quarter = addQuarter(match + "/quarters", 1);
        mvc.perform(transition(match, "ready", actor, UUID.randomUUID(), 2)).andExpect(status().isOk());
        mvc.perform(transition(match, "start", actor, UUID.randomUUID(), 3)).andExpect(status().isOk());
        mvc.perform(remove(quarter, UUID.randomUUID(), 4)).andExpect(status().isNoContent());
        mvc.perform(transition(match, "end", actor, UUID.randomUUID(), 5))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void measuredQuarterRemainsActiveUntilCompletedAndMatchCanThenEnd() throws Exception {
        String match = create(actor, "{}");
        String first = addQuarter(match + "/quarters", 1);
        String second = addQuarter(match + "/quarters", 2);
        mvc.perform(transition(first, "start", actor, UUID.randomUUID(), 3)).andExpect(status().isConflict());
        mvc.perform(transition(match, "ready", actor, UUID.randomUUID(), 3)).andExpect(status().isOk());
        mvc.perform(transition(match, "start", actor, UUID.randomUUID(), 4)).andExpect(status().isOk());

        mvc.perform(transition(first, "start", other, UUID.randomUUID(), 5)).andExpect(status().isNotFound());
        mvc.perform(transition(first, "start", actor, UUID.randomUUID(), 5))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(6)))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.startedAt").isNotEmpty());
        mvc.perform(transition(first, "complete", actor, UUID.randomUUID(), 6))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("QUARTER_NOT_ENDED"));
        mvc.perform(transition(first, "end", actor, UUID.randomUUID(), 6))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(7)))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.endedAt").isNotEmpty());
        mvc.perform(transition(second, "start", actor, UUID.randomUUID(), 7))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ACTIVE_QUARTER_EXISTS"));
        mvc.perform(transition(first, "end", actor, UUID.randomUUID(), 7))
                .andExpect(status().isConflict());

        UUID key = UUID.randomUUID();
        var completed = mvc.perform(transition(first, "complete", actor, key, 7))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(8)))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.meta.matchVersion").value(8))
                .andReturn().getResponse();
        mvc.perform(transition(first, "complete", actor, key, 7))
                .andExpect(status().isOk()).andExpect(content().string(completed.getContentAsString()));
        mvc.perform(transition(match, "end", actor, UUID.randomUUID(), 8))
                .andExpect(status().isConflict());
        mvc.perform(transition(second, "start", actor, UUID.randomUUID(), 8)).andExpect(status().isOk());
        mvc.perform(transition(second, "end", actor, UUID.randomUUID(), 9)).andExpect(status().isOk());
        mvc.perform(transition(second, "complete", actor, UUID.randomUUID(), 10)).andExpect(status().isOk());
        mvc.perform(transition(match, "end", actor, UUID.randomUUID(), 11))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void simultaneousQuarterStartsHaveOnlyOneWinner() throws Exception {
        String match = create(actor, "{}");
        String first = addQuarter(match + "/quarters", 1);
        String second = addQuarter(match + "/quarters", 2);
        mvc.perform(transition(match, "ready", actor, UUID.randomUUID(), 3)).andExpect(status().isOk());
        mvc.perform(transition(match, "start", actor, UUID.randomUUID(), 4)).andExpect(status().isOk());

        var ready = new CountDownLatch(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var firstResult = pool.submit(() -> startConcurrently(first, ready));
            var secondResult = pool.submit(() -> startConcurrently(second, ready));
            int firstStatus = firstResult.get(10, TimeUnit.SECONDS);
            int secondStatus = secondResult.get(10, TimeUnit.SECONDS);
            assertEquals(java.util.Set.of(200, 412), java.util.Set.of(firstStatus, secondStatus));
            String remaining = firstStatus == 200 ? second : first;
            mvc.perform(transition(remaining, "start", actor, UUID.randomUUID(), 6))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("ACTIVE_QUARTER_EXISTS"));
        }
    }

    @Test
    void quarterStatsDistinguishMissingFromZeroAndReplaceWithoutAccumulating() throws Exception {
        String match = create(actor, "{}");
        String quarter = addQuarter(match + "/quarters", 1);
        String path = quarter + "/stats";
        mvc.perform(get(path).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(2)))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.meta.matchVersion").value(2));
        mvc.perform(get(path).with(bearer(accessTokens, other))).andExpect(status().isNotFound());

        String anotherMatch = create(actor, "{}");
        String wrongParent = anotherMatch + quarter.substring(match.length()) + "/stats";
        mvc.perform(get(wrongParent).with(bearer(accessTokens, actor))).andExpect(status().isNotFound());
        mvc.perform(putStats(wrongParent, UUID.randomUUID(), 1, "{}")).andExpect(status().isNotFound());

        mvc.perform(transition(match, "ready", actor, UUID.randomUUID(), 2)).andExpect(status().isOk());
        mvc.perform(transition(match, "start", actor, UUID.randomUUID(), 3)).andExpect(status().isOk());
        mvc.perform(putStats(path, UUID.randomUUID(), 4, "{\"goals\":0,\"assists\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(transition(quarter, "start", actor, UUID.randomUUID(), 4)).andExpect(status().isOk());

        for (String invalid : new String[]{"{}", "{\"goals\":-1,\"assists\":0}",
                "{\"goals\":0,\"assists\":1.5}", "{\"goals\":2147483648,\"assists\":0}",
                "{\"goals\":\"0\",\"assists\":0}", "{\"goals\":0,\"assists\":0,\"userId\":null}"}) {
            mvc.perform(putStats(path, UUID.randomUUID(), 5, invalid))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(putStats(path, UUID.randomUUID(), 5, "{\"goals\":0,\"assists\":0}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(6)))
                .andExpect(jsonPath("$.data.goals").value(0)).andExpect(jsonPath("$.data.assists").value(0));
        mvc.perform(get(path).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.goals").value(0));

        UUID key = UUID.randomUUID();
        String totals = "{\"goals\":2,\"assists\":1}";
        var saved = mvc.perform(putStats(path, key, 6, totals))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(7)))
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty())
                .andReturn().getResponse();
        mvc.perform(putStats(path, key, 6, totals))
                .andExpect(status().isOk()).andExpect(content().string(saved.getContentAsString()));
        mvc.perform(transition(quarter, "end", actor, UUID.randomUUID(), 7)).andExpect(status().isOk());
        mvc.perform(transition(quarter, "complete", actor, UUID.randomUUID(), 8)).andExpect(status().isOk());
        mvc.perform(putStats(path, UUID.randomUUID(), 9, totals))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(10)))
                .andExpect(jsonPath("$.data.goals").value(2)).andExpect(jsonPath("$.data.assists").value(1));
        mvc.perform(transition(match, "end", actor, UUID.randomUUID(), 10)).andExpect(status().isOk());
        mvc.perform(putStats(path, UUID.randomUUID(), 11, "{\"goals\":0,\"assists\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(get(path).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(11)))
                .andExpect(jsonPath("$.data.goals").value(2)).andExpect(jsonPath("$.data.assists").value(1));
    }

    private MockHttpServletRequestBuilder putStats(String path, UUID key, long version, String body) {
        return write(put(path), actor, key, body).header("If-Match", tag(version));
    }

    @Test
    void missingQuarterPositionReturnsNullWithMatchVersionAndRequiresOwnership() throws Exception {
        String match = create(actor, "{}");
        String quarter = addQuarter(match + "/quarters", 1);
        String path = quarter + "/position";
        mvc.perform(get(path).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(2)))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.meta.matchVersion").value(2));
        mvc.perform(get(path).with(bearer(accessTokens, other))).andExpect(status().isNotFound());

        String anotherMatch = create(actor, "{}");
        String wrongParent = anotherMatch + quarter.substring(match.length()) + "/position";
        mvc.perform(get(wrongParent).with(bearer(accessTokens, actor))).andExpect(status().isNotFound());
    }

    @Test
    void positionCanBeReplacedAfterMeasurementAndIsOptionalForCompletion() throws Exception {
        String match = create(actor, "{}");
        String quarter = addQuarter(match + "/quarters", 1);
        String path = quarter + "/position";
        mvc.perform(transition(match, "ready", actor, UUID.randomUUID(), 2)).andExpect(status().isOk());
        mvc.perform(transition(match, "start", actor, UUID.randomUUID(), 3)).andExpect(status().isOk());
        mvc.perform(transition(quarter, "start", actor, UUID.randomUUID(), 4)).andExpect(status().isOk());
        mvc.perform(putPosition(path, UUID.randomUUID(), 5, "TEST_FIRST"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("QUARTER_NOT_ENDED"));
        mvc.perform(transition(quarter, "end", actor, UUID.randomUUID(), 5)).andExpect(status().isOk());
        for (String invalid : java.util.List.of("{}", "{\"positionCode\":null}", "{\"positionCode\":1}")) {
            mvc.perform(write(put(path), actor, UUID.randomUUID(), invalid).header("If-Match", tag(6)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.details.fields[0]").value("positionCode"));
        }
        mvc.perform(putPosition(path, UUID.randomUUID(), 6, "UNKNOWN"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0]").value("positionCode"));

        mvc.perform(transition(quarter, "complete", actor, UUID.randomUUID(), 6)).andExpect(status().isOk());
        UUID key = UUID.randomUUID();
        var saved = mvc.perform(putPosition(path, key, 7, "TEST_FIRST"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(8)))
                .andExpect(jsonPath("$.data.positionCode").value("TEST_FIRST"))
                .andReturn().getResponse();
        mvc.perform(putPosition(path, key, 7, "TEST_FIRST"))
                .andExpect(status().isOk()).andExpect(content().string(saved.getContentAsString()));
        mvc.perform(putPosition(path, UUID.randomUUID(), 8, "TEST_SECOND"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(9)));
        mvc.perform(transition(match, "end", actor, UUID.randomUUID(), 9)).andExpect(status().isOk());
        mvc.perform(putPosition(path, UUID.randomUUID(), 10, "TEST_FIRST")).andExpect(status().isConflict());
        mvc.perform(get(path).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(10)))
                .andExpect(jsonPath("$.data.positionCode").value("TEST_SECOND"));
    }

    private MockHttpServletRequestBuilder putPosition(String path, UUID key, long version, String code) {
        return write(put(path), actor, key, json.writeValueAsString(java.util.Map.of("positionCode", code)))
                .header("If-Match", tag(version));
    }

    private static final String WORKOUT = """
            {
              "startDate": 815274000, "endDate": 815274600,
              "totalCalories": 100, "totalDistance": 1500,
              "points": [{"latitude":37.5,"longitude":127,"altitude":20,"horizontalAccuracy":5,
                          "speed":-1,"course":-1,"timestamp":815274001}],
              "heartRateSamples": [{"timestamp":815274001,"bpm":142}]
            }
            """;

    private String workoutWithContext() {
        var root = (tools.jackson.databind.node.ObjectNode) json.readTree(WORKOUT);
        root.set("events", json.readTree("""
                [{"type":"goal","location":{"latitude":37.500000123,"longitude":127.000000456,
                  "altitude":20,"horizontalAccuracy":5,"speed":-1,"course":-1,"timestamp":815274010}},
                 {"type":"assist","location":{"latitude":37.500000124,"longitude":127.000000457,
                  "altitude":21,"horizontalAccuracy":6,"speed":2,"course":100,"timestamp":815274020}}]
                """));
        root.set("field", json.readTree("""
                {"name":"운동 당시 경기장",
                 "cornerA":{"latitude":37.5,"longitude":127},
                 "cornerB":{"latitude":37.5,"longitude":127.001},
                 "cornerC":{"latitude":37.501,"longitude":127.001},
                 "cornerD":{"latitude":37.501,"longitude":127},
                 "rawCornerA":{"latitude":37.50001,"longitude":127.00001}}
                """));
        return json.writeValueAsString(root);
    }

    @Test
    void workoutEventsAndFieldRoundTripInWorkoutAndResultWithoutChangingStats() throws Exception {
        String path = completedQuarterUploadPath();
        UUID quarterId = UUID.fromString(path.split("/")[4]);
        var body = workoutWithContext();
        var created = mvc.perform(workoutUpload(path, actor, UUID.randomUUID(), 8, body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.events[0].type").value("goal"))
                .andExpect(jsonPath("$.data.events[1].location.timestamp").value(815274020))
                .andExpect(jsonPath("$.data.field.name").value("운동 당시 경기장"))
                .andReturn().getResponse();
        mvc.perform(get(path.replace("/workout-payloads", "/workout")).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.events[0].location.latitude").value(37.500000123))
                .andExpect(jsonPath("$.data.field.cornerD.latitude").value(37.501))
                .andExpect(jsonPath("$.data.field.rawCornerA.latitude").value(37.50001))
                .andExpect(jsonPath("$.data.field.rawCornerD").value(nullValue()));
        String result = path.replace("/workout-payloads", "/result");
        mvc.perform(get(result).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.workout.events.length()").value(2))
                .andExpect(jsonPath("$.data.workout.field.name").value("운동 당시 경기장"));
        var match = quarters.findByIdAndOwnerId(quarterId, actor).orElseThrow().matchId();
        mvc.perform(get(MATCHES + "/" + match + "/result/quarters").with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].workout.events[1].type").value("assist"));
        mvc.perform(workoutUpload(path, actor, UUID.randomUUID(), 9, body))
                .andExpect(status().isOk());
        mvc.perform(workoutUpload(path, actor, UUID.randomUUID(), 10, body.replace("goal", "assist")))
                .andExpect(status().isConflict());
        mvc.perform(workoutUpload(path, actor, UUID.randomUUID(), 10, body.replace("운동 당시 경기장", "다른 경기장")))
                .andExpect(status().isConflict());
        assertTrue(stats.findByQuarterId(quarterId).isEmpty());
        mvc.perform(get(path.replace("/workout-payloads", "/workout")).with(bearer(accessTokens, other)))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidEventAndFieldInputRollsBackAndLegacyEmptyFieldsKeepOriginalHash() throws Exception {
        String path = completedQuarterUploadPath();
        UUID quarterId = UUID.fromString(path.split("/")[4]);
        String valid = workoutWithContext();
        for (String body : java.util.List.of(valid.replace("goal", "Goal"),
                valid.replace("815274010", "815274601"), valid.replace("37.500000123", "91"),
                valid.replace("\"cornerA\":", "\"unknownCorner\":"),
                valid.replace("\"type\":\"goal\"", "\"type\":null"),
                valid.replace("\"speed\":-1", "\"speed\":-2"))) {
            mvc.perform(workoutUpload(path, actor, UUID.randomUUID(), 8, body))
                    .andExpect(status().isUnprocessableContent());
            assertTrue(workouts.findByQuarterId(quarterId).isEmpty());
        }
        mvc.perform(workoutUpload(path, actor, UUID.randomUUID(), 8, WORKOUT))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.events.length()").value(0))
                .andExpect(jsonPath("$.data.field").value(nullValue()));
        var root = (tools.jackson.databind.node.ObjectNode) json.readTree(WORKOUT);
        root.set("events", json.readTree("[]"));
        root.set("field", json.readTree("null"));
        mvc.perform(workoutUpload(path, actor, UUID.randomUUID(), 9, json.writeValueAsString(root)))
                .andExpect(status().isOk());
        var typed = json.treeToValue(root, com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateRequest.class);
        assertFalse(json.valueToTree(typed).has("events"));
        assertFalse(json.valueToTree(typed).has("field"));
    }

    @Test
    void delayedWorkoutUploadReplaysAndRejectsDifferentSource() throws Exception {
        String path = completedQuarterUploadPath();
        UUID quarterId = UUID.fromString(path.split("/")[4]);
        UUID key = UUID.randomUUID();
        var created = mvc.perform(workoutUpload(path, actor, key, 8, WORKOUT))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", tag(9)))
                .andExpect(jsonPath("$.data.quarterId").value(quarterId.toString()))
                .andReturn().getResponse();
        mvc.perform(workoutUpload(path, actor, key, 8, WORKOUT))
                .andExpect(status().isCreated()).andExpect(content().string(created.getContentAsString()));
        var stored = workouts.findByQuarterId(quarterId).orElseThrow();
        mvc.perform(workoutUpload(path, actor, UUID.randomUUID(), 9, WORKOUT.replace("1500", "1500.0")))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(10)))
                .andExpect(jsonPath("$.data.id").value(stored.id().toString()));
        mvc.perform(workoutUpload(path, actor, UUID.randomUUID(), 10, WORKOUT.replace("1500", "1600")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("WORKOUT_ALREADY_EXISTS"));
        mvc.perform(workoutUpload(path, other, UUID.randomUUID(), 10, WORKOUT))
                .andExpect(status().isNotFound());
        assertEquals(1, trackPoints.findAllForCalculation(stored.id()).size());
        assertEquals("PROCESSING", summaries.findByQuarterId(quarterId).orElseThrow().calculationStatus());
    }

    @Test
    void matchWorkoutSubmissionRollsBackAllQuartersAndReplaysSuccessfulRetry() throws Exception {
        String match = create(actor, "{}");
        String first = addQuarter(match + "/quarters", 1);
        String second = addQuarter(match + "/quarters", 2);
        UUID firstId = UUID.fromString(first.substring(first.lastIndexOf('/') + 1));
        UUID secondId = UUID.fromString(second.substring(second.lastIndexOf('/') + 1));

        long version = 3;
        mvc.perform(transition(match, "ready", actor, UUID.randomUUID(), version++)).andExpect(status().isOk());
        mvc.perform(transition(match, "start", actor, UUID.randomUUID(), version++)).andExpect(status().isOk());
        for (String quarter : java.util.List.of(first, second)) {
            for (String action : java.util.List.of("start", "end", "complete")) {
                mvc.perform(transition(quarter, action, actor, UUID.randomUUID(), version++)).andExpect(status().isOk());
            }
        }
        mvc.perform(transition(match, "end", actor, UUID.randomUUID(), version++)).andExpect(status().isOk());

        String path = match + "/workout-submissions";
        String body = """
                {"quarters":[
                  {"quarterId":"%s","position":"TEST_FIRST","workout":%s},
                  {"quarterId":"%s","position":"TEST_SECOND","workout":%s}
                ]}
                """.formatted(firstId, workoutWithContext(), secondId, WORKOUT);
        UUID key = UUID.randomUUID();
        mvc.perform(workoutUpload(path, actor, key, version, body.replace("TEST_SECOND", "UNKNOWN")))
                .andExpect(status().isUnprocessableContent());
        assertTrue(workouts.findByQuarterId(firstId).isEmpty());
        assertTrue(summaries.findByQuarterId(firstId).isEmpty());
        mvc.perform(get(first + "/position").with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(nullValue()));

        var accepted = mvc.perform(workoutUpload(path, actor, key, version, body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("ETag", tag(version + 1)))
                .andExpect(header().string("Location", match + "/result"))
                .andExpect(jsonPath("$.data.quarterCount").value(2))
                .andReturn().getResponse();
        mvc.perform(workoutUpload(path, actor, key, version, body))
                .andExpect(status().isAccepted()).andExpect(content().string(accepted.getContentAsString()));
        mvc.perform(get(match + "/result/quarters").with(bearer(accessTokens, actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].position.positionCode").value("TEST_FIRST"))
                .andExpect(jsonPath("$.data[1].position.positionCode").value("TEST_SECOND"))
                .andExpect(jsonPath("$.data[0].summary.calculationStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.data[1].summary.calculationStatus").value("PROCESSING"));
    }

    @Test
    void localMatchSubmissionCreatesCompletedMatchAndQuartersAndReplaysWithoutAnEtag() throws Exception {
        UUID matchId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String path = MATCHES + "/" + matchId + "/workout-submissions";
        String body = """
                {"quarters":[
                  {"quarterId":"%s","position":"TEST_FIRST","workout":%s},
                  {"quarterId":"%s","position":"TEST_SECOND","workout":%s}
                ]}
                """.formatted(firstId, WORKOUT, secondId, WORKOUT);
        var accepted = mvc.perform(write(post(path), actor, key, body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("ETag", tag(2)))
                .andExpect(header().string("Location", MATCHES + "/" + matchId + "/result"))
                .andExpect(jsonPath("$.data.matchId").value(matchId.toString()))
                .andExpect(jsonPath("$.data.quarterCount").value(2))
                .andReturn().getResponse();
        var match = matches.findByIdAndOwnerId(matchId, actor).orElseThrow();
        assertEquals("COMPLETED", match.status());
        assertEquals(2, match.version());
        var imported = quarters.findByMatchId(matchId, 10);
        assertEquals(List.of(firstId, secondId), imported.stream().map(q -> q.id()).toList());
        assertEquals(600, imported.getFirst().durationSeconds());
        assertEquals(workouts.findByQuarterId(firstId).orElseThrow().startDate(), match.startedAt());
        assertEquals(workouts.findByQuarterId(secondId).orElseThrow().endDate(), match.endedAt());

        // Analysis can finish and advance the version before a lost response is retried.
        summaryCalculation.process(pendingSummary(firstId));
        summaryCalculation.process(pendingSummary(secondId));
        mvc.perform(write(post(path), actor, key, body))
                .andExpect(status().isAccepted())
                .andExpect(header().string("ETag", tag(2)))
                .andExpect(content().string(accepted.getContentAsString()));
        mvc.perform(get(MATCHES + "/" + matchId + "/result/quarters").with(bearer(accessTokens, actor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].position.positionCode").value("TEST_FIRST"))
                .andExpect(jsonPath("$.data[1].position.positionCode").value("TEST_SECOND"));
        mvc.perform(write(post(path), other, UUID.randomUUID(), body)).andExpect(status().isNotFound());
        mvc.perform(write(post(path), actor, UUID.randomUUID(), body))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(write(post(path), actor, key, body.replace("1500", "1600")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void invalidLocalSubmissionRollsBackImportedMatchAndAllQuarters() throws Exception {
        UUID matchId = UUID.randomUUID();
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String path = MATCHES + "/" + matchId + "/workout-submissions";
        String body = """
                {"quarters":[
                  {"quarterId":"%s","position":"TEST_FIRST","workout":%s},
                  {"quarterId":"%s","position":"UNKNOWN","workout":%s}
                ]}
                """.formatted(firstId, WORKOUT, secondId, WORKOUT);
        mvc.perform(write(post(path), actor, key, body)).andExpect(status().isUnprocessableContent());
        assertTrue(matches.findByIdAndOwnerId(matchId, actor).isEmpty());
        assertTrue(quarters.findByMatchId(matchId, 10).isEmpty());
        assertTrue(workouts.findByQuarterId(firstId).isEmpty());
        assertTrue(summaries.findByQuarterId(firstId).isEmpty());
        mvc.perform(write(post(path), actor, key, body.replace("UNKNOWN", "TEST_SECOND")))
                .andExpect(status().isAccepted());
    }

    @Test
    void localSubmissionAcceptsInitialVersionButRejectsOtherVersionsAndInvalidDates() throws Exception {
        UUID matchId = UUID.randomUUID();
        String path = MATCHES + "/" + matchId + "/workout-submissions";
        String body = localSubmission(UUID.randomUUID());
        mvc.perform(workoutUpload(path, actor, UUID.randomUUID(), 9, body))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(write(post(path), actor, UUID.randomUUID(), body.replace("815274600", "815273999")))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(write(post(path), actor, UUID.randomUUID(), body.replace("815274000", "1e100")))
                .andExpect(status().isUnprocessableContent());
        assertTrue(matches.findByIdAndOwnerId(matchId, actor).isEmpty());
        mvc.perform(workoutUpload(path, actor, UUID.randomUUID(), 1, body))
                .andExpect(status().isAccepted());
        mvc.perform(workoutUpload(path, actor, UUID.randomUUID(), 1, body))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(workoutUpload(path, actor, UUID.randomUUID(), 2, body))
                .andExpect(status().isAccepted());
    }

    @Test
    void localSubmissionCannotReuseAQuarterFromAnotherMatch() throws Exception {
        UUID originalId = UUID.randomUUID();
        UUID quarterId = UUID.randomUUID();
        String body = localSubmission(quarterId);
        mvc.perform(write(post(MATCHES + "/" + originalId + "/workout-submissions"), other,
                UUID.randomUUID(), body)).andExpect(status().isAccepted());
        UUID newId = UUID.randomUUID();
        mvc.perform(write(post(MATCHES + "/" + newId + "/workout-submissions"), actor,
                UUID.randomUUID(), body)).andExpect(status().isNotFound());
        assertTrue(matches.findByIdAndOwnerId(newId, actor).isEmpty());
        assertEquals(originalId, quarters.findByIdAndOwnerId(quarterId, other).orElseThrow().matchId());
    }

    @Test
    void concurrentLocalSubmissionsWithOneKeyCreateOneMatch() throws Exception {
        UUID matchId = UUID.randomUUID();
        UUID quarterId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String path = MATCHES + "/" + matchId + "/workout-submissions";
        String body = localSubmission(quarterId);
        var ready = new CountDownLatch(2);
        Callable<String> send = () -> {
            ready.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            return mvc.perform(write(post(path), actor, key, body))
                    .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(send);
            var second = pool.submit(send);
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(2, matches.findByIdAndOwnerId(matchId, actor).orElseThrow().version());
        assertEquals(1, quarters.findByMatchId(matchId, 10).size());
    }

    private String localSubmission(UUID quarterId) {
        return "{\"quarters\":[{\"quarterId\":\"%s\",\"position\":\"TEST_FIRST\",\"workout\":%s}]}"
                .formatted(quarterId, WORKOUT);
    }

    @Test
    void workoutRejectsInvalidSamplesAndRollsBackWhenSampleWriteFails() throws Exception {
        String path = completedQuarterUploadPath();
        UUID quarterId = UUID.fromString(path.split("/")[4]);
        mvc.perform(workoutUpload(path, actor, UUID.randomUUID(), 8, WORKOUT.replace("815274001", "815274601")))
                .andExpect(status().isUnprocessableContent());

        UUID key = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new IllegalStateException("sample write failed"))
                .doCallRealMethod().when(trackPoints).saveAll(org.mockito.ArgumentMatchers.anyList());
        try {
            mvc.perform(workoutUpload(path, actor, key, 8, WORKOUT)).andExpect(status().isInternalServerError());
        } catch (jakarta.servlet.ServletException exception) {
            assertEquals("sample write failed", exception.getCause().getMessage());
        }
        assertTrue(workouts.findByQuarterId(quarterId).isEmpty());
        assertTrue(summaries.findByQuarterId(quarterId).isEmpty());
        mvc.perform(workoutUpload(path, actor, key, 8, WORKOUT)).andExpect(status().isCreated());
    }

    @Test
    void concurrentWorkoutUploadsWithOneKeyReplayOneStoredSource() throws Exception {
        String path = completedQuarterUploadPath();
        UUID quarterId = UUID.fromString(path.split("/")[4]);
        UUID key = UUID.randomUUID();
        var ready = new CountDownLatch(2);
        Callable<String> send = () -> {
            ready.countDown();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            return mvc.perform(workoutUpload(path, actor, key, 8, WORKOUT))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(send);
            var second = pool.submit(send);
            assertEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
        var workout = workouts.findByQuarterId(quarterId).orElseThrow();
        assertEquals(1, trackPoints.findAllForCalculation(workout.id()).size());
        assertEquals(workout.id(), summaries.findByQuarterId(quarterId).orElseThrow().workoutPayloadId());
    }

    @Test
    void workoutQueriesDistinguishMissingAndEmptyAndBindCursorsToTheSource() throws Exception {
        String upload = completedQuarterUploadPath();
        String quarter = upload.substring(0, upload.lastIndexOf('/'));
        UUID quarterId = UUID.fromString(quarter.substring(quarter.lastIndexOf('/') + 1));
        String trackPath = quarter + "/track-points";
        String heartPath = quarter + "/heart-rate-samples";
        for (String path : List.of(quarter + "/workout", trackPath, heartPath)) {
            mvc.perform(get(path).with(bearer(accessTokens, actor))).andExpect(status().isNotFound());
        }
        String empty = """
                {"startDate":815274000,"endDate":815274600,"totalCalories":100,"totalDistance":1500,
                 "points":[],"heartRateSamples":[]}
                """;
        String location = mvc.perform(workoutUpload(upload, actor, UUID.randomUUID(), 8, empty))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        mvc.perform(get(location).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(9)))
                .andExpect(jsonPath("$.data.startDate").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$.data.points").doesNotExist())
                .andExpect(jsonPath("$.data.heartRateSamples").doesNotExist());
        for (String path : List.of(trackPath, heartPath)) {
            mvc.perform(get(path).with(bearer(accessTokens, actor)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty())
                    .andExpect(jsonPath("$.meta.pagination.nextCursor").value(nullValue()));
            mvc.perform(get(path).with(bearer(accessTokens, other))).andExpect(status().isNotFound());
        }

        var workout = workouts.findByQuarterId(quarterId).orElseThrow();
        var timestamp = workout.startDate();
        trackPoints.saveAll(List.of(
                new TrackPoint(workout.id(), 2, 37, 127, 20, 5, -1, -1, timestamp),
                new TrackPoint(workout.id(), 0, 37, 127, 20, 5, -1, -1, timestamp),
                new TrackPoint(workout.id(), 1, 37, 127, 20, 5, -1, -1, timestamp)));
        heartRates.saveAll(List.of(new HeartRateSample(workout.id(), 1, timestamp, 141),
                new HeartRateSample(workout.id(), 0, timestamp, 140)));

        String cursor = firstSampleCursor(trackPath);
        mvc.perform(get(trackPath).with(bearer(accessTokens, actor)).param("cursor", cursor).param("limit", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].sampleSeq").value(1))
                .andExpect(jsonPath("$.data[1].sampleSeq").value(2))
                .andExpect(jsonPath("$.meta.pagination.hasNext").value(false));
        mvc.perform(get(heartPath).with(bearer(accessTokens, actor)).param("cursor", firstSampleCursor(heartPath)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].bpm").value(141));
        mvc.perform(get(heartPath).with(bearer(accessTokens, actor)).param("cursor", cursor))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
        mvc.perform(get(trackPath).with(bearer(accessTokens, actor)).param("cursor", "*"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(trackPath).with(bearer(accessTokens, actor)).param("limit", "101"))
                .andExpect(status().isBadRequest());

        summaries.saveProcessing(quarterId, workout.id(), "v1", "{}", "{}", "f".repeat(64), timestamp);
        mvc.perform(get(trackPath).with(bearer(accessTokens, actor)).param("cursor", cursor))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CURSOR_SNAPSHOT_CHANGED"));
    }

    private String firstSampleCursor(String path) throws Exception {
        var first = mvc.perform(get(path).with(bearer(accessTokens, actor)).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(9)))
                .andExpect(jsonPath("$.data[0].sampleSeq").value(0))
                .andExpect(jsonPath("$.data[0].timestamp").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$.meta.pagination.hasNext").value(true))
                .andReturn().getResponse();
        return json.readTree(first.getContentAsString()).at("/meta/pagination/nextCursor").asText();
    }

    @Test
    void summaryRecoversProcessingRowsAndRejectsStalePublication() throws Exception {
        String upload = completedQuarterUploadPath();
        UUID quarterId = UUID.fromString(upload.split("/")[4]);
        String summary = upload.replace("/workout-payloads", "/summary");
        mvc.perform(get(summary).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(8)))
                .andExpect(jsonPath("$.data").value(nullValue()));
        mvc.perform(get(summary).with(bearer(accessTokens, other))).andExpect(status().isNotFound());

        mvc.perform(workoutUpload(upload, actor, UUID.randomUUID(), 8, WORKOUT)).andExpect(status().isCreated());
        mvc.perform(get(summary).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.data.calculationStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.data.totalDistanceM").value(nullValue()));
        var stale = pendingSummary(quarterId);
        var previous = stale.summary();
        summaries.saveProcessing(quarterId, previous.workoutPayloadId(), previous.calculationVersion(),
                "{}", "{}", previous.sourcePayloadHash(), previous.updatedAt());

        summaryCalculation.process(stale);
        mvc.perform(get(summary).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(9)))
                .andExpect(jsonPath("$.data.calculationStatus").value("PROCESSING"));

        var current = pendingSummary(quarterId);
        summaryCalculation.process(current);
        summaryCalculation.process(current);
        mvc.perform(get(summary).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(10)))
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.data.calculationStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.data.totalDistanceM").value(1500.0))
                .andExpect(jsonPath("$.data.totalCaloriesKcal").value(100.0))
                .andExpect(jsonPath("$.data.durationSeconds").value(600.0))
                .andExpect(jsonPath("$.data.averageSpeedMps").value(2.5))
                .andExpect(jsonPath("$.data.metricStatus.totalDistanceM.status").value("READY"))
                .andExpect(jsonPath("$.data.metricStatus.averageSpeedMps.status").value("READY"))
                .andExpect(jsonPath("$.data.calculationJobId").doesNotExist())
                .andExpect(jsonPath("$.data.sourcePayloadHash").doesNotExist());
    }

    @Test
    void invalidStoredSourcePublishesFailureWithoutRemovingWorkout() throws Exception {
        String upload = completedQuarterUploadPath();
        UUID quarterId = UUID.fromString(upload.split("/")[4]);
        UUID workoutId = UUID.randomUUID();
        var time = LocalDateTime.of(2026, 11, 4, 0, 0);
        workouts.save(new WorkoutPayload(workoutId, quarterId, actor, time, time.plusMinutes(1), 0, -1));
        summaries.saveProcessing(quarterId, workoutId, "v1", "{}", "{}", "a".repeat(64), time);
        var job = pendingSummary(quarterId);
        summaryCalculation.process(job);

        mvc.perform(get(upload.replace("/workout-payloads", "/summary")).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(9)))
                .andExpect(jsonPath("$.data.calculationStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.metricStatus.totalDistanceM.status").value("FAILED"))
                .andExpect(jsonPath("$.data.totalDistanceM").value(nullValue()));
        assertTrue(workouts.findById(workoutId).isPresent());
        mvc.perform(get(MATCHES + "/" + job.matchId() + "/result").with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summaryAvailability.failed").value(1));
    }

    @Test
    void summaryPersistsHeartRateMetricsAndReferenceSnapshot() throws Exception {
        String upload = completedQuarterUploadPath();
        UUID quarterId = UUID.fromString(upload.split("/")[4]);
        mvc.perform(workoutUpload(upload, actor, UUID.randomUUID(), 8, WORKOUT)).andExpect(status().isCreated());
        var pending = pendingSummary(quarterId).summary();
        heartRates.saveAll(List.of(new HeartRateSample(pending.workoutPayloadId(), 1,
                LocalDateTime.of(2001, 1, 1, 0, 0).plusSeconds(815274007), 142)));
        String path = upload.replace("/workout-payloads", "/summary");
        summaryCalculation.process(pendingSummary(quarterId));
        mvc.perform(get(path).with(bearer(accessTokens, actor)))
                .andExpect(jsonPath("$.data.trimpEdwards").value(nullValue()))
                .andExpect(jsonPath("$.data.metricStatus.trimpEdwards.status").value("MISSING_INPUT"))
                .andExpect(jsonPath("$.data.averageHeartRateBpm").value(142.0));

        var firstGpsTime = workouts.findById(pending.workoutPayloadId()).orElseThrow().startDate().plusSeconds(2);
        trackPoints.saveAll(List.of(
                new TrackPoint(pending.workoutPayloadId(), 1, 37, 127, 20, 5, 2, 0, firstGpsTime),
                new TrackPoint(pending.workoutPayloadId(), 2, 37, 127, 20, 5, 2, 0, firstGpsTime.plusSeconds(4))));
        summaries.saveProcessing(quarterId, pending.workoutPayloadId(), "v2",
                json.writeValueAsString(java.util.Map.of("hrMaxReferenceBpm", 200)),
                "{}", pending.sourcePayloadHash(), pending.updatedAt());
        summaryCalculation.process(pendingSummary(quarterId));
        mvc.perform(get(path).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(11)))
                .andExpect(jsonPath("$.data.calculationVersion").value("v3"))
                .andExpect(jsonPath("$.data.calculationStatus").value("READY"))
                .andExpect(jsonPath("$.data.validGpsDurationSeconds").value(4.0))
                .andExpect(jsonPath("$.data.highIntensityDistanceM").value(0.0))
                .andExpect(jsonPath("$.data.averageHeartRateBpm").value(142.0))
                .andExpect(jsonPath("$.data.validHrDurationSeconds").value(6.0))
                .andExpect(jsonPath("$.data.trimpEdwards").value(0.3))
                .andExpect(jsonPath("$.data.hrMaxReferenceBpm").value(200.0));
        var parameters = json.readTree(summaries.findByQuarterId(quarterId).orElseThrow().calculationParameters());
        assertEquals(10, parameters.at("/heartRatePolicy/maxGapSeconds").intValue());
    }

    @Test
    void summarySettingsAndRecalculationPreserveReferenceAndReplayAfterCompletion() throws Exception {
        String upload = completedQuarterUploadPath();
        UUID quarterId = UUID.fromString(upload.split("/")[4]);
        String summary = upload.replace("/workout-payloads", "/summary");
        String settings = summary + "/settings";
        mvc.perform(transition(summary, "recalculate", actor, UUID.randomUUID(), 8))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("WORKOUT_REQUIRED"));
        mvc.perform(workoutUpload(upload, actor, UUID.randomUUID(), 8, WORKOUT)).andExpect(status().isCreated());
        mvc.perform(transition(summary, "recalculate", actor, UUID.randomUUID(), 9))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CALCULATION_IN_PROGRESS"));
        var oldJob = pendingSummary(quarterId);
        summaryCalculation.process(oldJob);

        String valid = json.writeValueAsString(java.util.Map.of("hrMaxReferenceBpm", 190));
        String invalid = json.writeValueAsString(java.util.Map.of("hrMaxReferenceBpm", 0));
        mvc.perform(write(put(settings), actor, UUID.randomUUID(), invalid).header("If-Match", tag(10)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(write(put(settings), other, UUID.randomUUID(), valid).header("If-Match", tag(10)))
                .andExpect(status().isNotFound());
        mvc.perform(write(put(settings), actor, UUID.randomUUID(), valid).header("If-Match", tag(9)))
                .andExpect(status().isPreconditionFailed());

        UUID settingsKey = UUID.randomUUID();
        var accepted = mvc.perform(write(put(settings), actor, settingsKey, valid).header("If-Match", tag(10)))
                .andExpect(status().isAccepted()).andExpect(header().string("ETag", tag(11)))
                .andExpect(header().string("Location", summary)).andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.data.calculationStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.data.totalDistanceM").value(nullValue()))
                .andReturn().getResponse();
        var newJob = pendingSummary(quarterId);
        summaryCalculation.process(oldJob);
        assertEquals(newJob.summary().calculationJobId(), pendingSummary(quarterId).summary().calculationJobId());
        mvc.perform(write(put(settings), actor, UUID.randomUUID(), valid).header("If-Match", tag(11)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CALCULATION_IN_PROGRESS"));
        summaryCalculation.process(newJob);
        mvc.perform(write(put(settings), actor, settingsKey, valid).header("If-Match", tag(10)))
                .andExpect(status().isAccepted()).andExpect(header().string("ETag", tag(11)))
                .andExpect(content().string(accepted.getContentAsString()));
        mvc.perform(get(summary).with(bearer(accessTokens, actor)))
                .andExpect(header().string("ETag", tag(12)))
                .andExpect(jsonPath("$.data.hrMaxReferenceBpm").value(190.0));

        UUID recalculateKey = UUID.randomUUID();
        var requested = mvc.perform(transition(summary, "recalculate", actor, recalculateKey, 12))
                .andExpect(status().isAccepted()).andExpect(header().string("ETag", tag(13)))
                .andReturn().getResponse();
        var recalculated = pendingSummary(quarterId);
        var parameters = json.readTree(recalculated.summary().calculationParameters());
        assertEquals(190.0, parameters.path("hrMaxReferenceBpm").doubleValue());
        assertEquals("USER_PROVIDED", parameters.path("hrMaxReferenceSource").stringValue());
        assertEquals(oldJob.summary().sourcePayloadHash(), recalculated.summary().sourcePayloadHash());
        summaryCalculation.process(recalculated);
        mvc.perform(transition(summary, "recalculate", actor, recalculateKey, 12))
                .andExpect(status().isAccepted()).andExpect(content().string(requested.getContentAsString()));
        mvc.perform(get(summary).with(bearer(accessTokens, actor)))
                .andExpect(header().string("ETag", tag(14)))
                .andExpect(jsonPath("$.data.hrMaxReferenceBpm").value(190.0));
    }

    @Test
    void resultsFollowMatchLifecycleAndShowLateWorkoutWithoutLosingDirectRecords() throws Exception {
        String match = create(actor, "{}");
        String matchResult = match + "/result";
        String first = addQuarter(match + "/quarters", 1);
        String second = addQuarter(match + "/quarters", 2);
        String firstQuarterId = first.substring(first.lastIndexOf('/') + 1);
        String secondQuarterId = second.substring(second.lastIndexOf('/') + 1);
        String firstResult = "/api/v1/quarters/" + firstQuarterId + "/result";
        String secondResult = "/api/v1/quarters/" + secondQuarterId + "/result";
        for (String path : List.of(matchResult, matchResult + "/quarters", firstResult)) {
            mvc.perform(get(path).with(bearer(accessTokens, actor)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("RESULT_NOT_READY"));
            mvc.perform(get(path).with(bearer(accessTokens, other))).andExpect(status().isNotFound());
        }

        mvc.perform(transition(match, "ready", actor, UUID.randomUUID(), 3)).andExpect(status().isOk());
        mvc.perform(transition(match, "start", actor, UUID.randomUUID(), 4)).andExpect(status().isOk());
        mvc.perform(transition(first, "start", actor, UUID.randomUUID(), 5)).andExpect(status().isOk());
        String zeros = json.writeValueAsString(java.util.Map.of("goals", 0, "assists", 0));
        mvc.perform(putStats(first + "/stats", UUID.randomUUID(), 6, zeros)).andExpect(status().isOk());
        mvc.perform(transition(first, "end", actor, UUID.randomUUID(), 7)).andExpect(status().isOk());
        mvc.perform(putPosition(first + "/position", UUID.randomUUID(), 8, "TEST_FIRST")).andExpect(status().isOk());
        mvc.perform(transition(first, "complete", actor, UUID.randomUUID(), 9)).andExpect(status().isOk());
        mvc.perform(get(firstResult).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(10)))
                .andExpect(jsonPath("$.data.stats.goals").value(0))
                .andExpect(jsonPath("$.data.position.positionCode").value("TEST_FIRST"))
                .andExpect(jsonPath("$.data.workout").value(nullValue()))
                .andExpect(jsonPath("$.data.summary").value(nullValue()));
        mvc.perform(get(matchResult).with(bearer(accessTokens, actor))).andExpect(status().isConflict());

        mvc.perform(transition(second, "start", actor, UUID.randomUUID(), 10)).andExpect(status().isOk());
        mvc.perform(transition(second, "end", actor, UUID.randomUUID(), 11)).andExpect(status().isOk());
        mvc.perform(transition(second, "complete", actor, UUID.randomUUID(), 12)).andExpect(status().isOk());
        mvc.perform(transition(match, "end", actor, UUID.randomUUID(), 13)).andExpect(status().isOk());

        mvc.perform(get(matchResult).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(14)))
                .andExpect(jsonPath("$.data.quarterCount").value(2))
                .andExpect(jsonPath("$.data.goals").value(nullValue()))
                .andExpect(jsonPath("$.data.assists").value(nullValue()))
                .andExpect(jsonPath("$.data.missingStatsQuarterIds[0]").value(secondQuarterId))
                .andExpect(jsonPath("$.data.summaryAvailability.missing").value(2));
        mvc.perform(get(secondResult).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.stats").value(nullValue()));
        var page = mvc.perform(get(matchResult + "/quarters").with(bearer(accessTokens, actor)).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].quarter.number").value(1))
                .andExpect(jsonPath("$.data[0].stats.goals").value(0))
                .andReturn().getResponse();
        String cursor = json.readTree(page.getContentAsString()).at("/meta/pagination/nextCursor").stringValue();
        mvc.perform(get(matchResult + "/quarters").with(bearer(accessTokens, actor)).param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].quarter.number").value(2))
                .andExpect(jsonPath("$.meta.pagination.hasNext").value(false));

        String upload = "/api/v1/quarters/" + firstQuarterId + "/workout-payloads";
        mvc.perform(workoutUpload(upload, actor, UUID.randomUUID(), 14, WORKOUT)).andExpect(status().isCreated());
        mvc.perform(get(matchResult + "/quarters").with(bearer(accessTokens, actor)).param("cursor", cursor))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CURSOR_SNAPSHOT_CHANGED"));
        mvc.perform(get(matchResult).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summaryAvailability.processing").value(1))
                .andExpect(jsonPath("$.data.summaryAvailability.missing").value(1));
        summaryCalculation.process(pendingSummary(UUID.fromString(firstQuarterId)));
        mvc.perform(get(firstResult).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(16)))
                .andExpect(jsonPath("$.data.stats.goals").value(0))
                .andExpect(jsonPath("$.data.workout.points").doesNotExist())
                .andExpect(jsonPath("$.data.summary.calculationStatus").value("PARTIAL"));
        mvc.perform(get(matchResult).with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summaryAvailability.partial").value(1));
    }

    @Test
    void swaggerListsTheThreeResultEndpoints() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/matches/{matchId}/result'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/matches/{matchId}/result/quarters'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/quarters/{quarterId}/result'].get").exists());
    }

    private QuarterSummaryRepository.PendingCalculation pendingSummary(UUID quarterId) {
        return summaries.findProcessing(100).stream().filter(job -> job.summary().quarterId().equals(quarterId))
                .findFirst().orElseThrow();
    }

    private String completedQuarterUploadPath() throws Exception {
        String match = create(actor, "{}");
        String quarter = addQuarter(match + "/quarters", 1);
        mvc.perform(transition(match, "ready", actor, UUID.randomUUID(), 2)).andExpect(status().isOk());
        mvc.perform(transition(match, "start", actor, UUID.randomUUID(), 3)).andExpect(status().isOk());
        String path = "/api/v1/quarters/" + quarter.substring(quarter.lastIndexOf('/') + 1) + "/workout-payloads";
        mvc.perform(workoutUpload(path, actor, UUID.randomUUID(), 4, WORKOUT))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("QUARTER_NOT_ENDED"));
        mvc.perform(transition(quarter, "start", actor, UUID.randomUUID(), 4)).andExpect(status().isOk());
        mvc.perform(transition(quarter, "end", actor, UUID.randomUUID(), 5)).andExpect(status().isOk());
        mvc.perform(transition(quarter, "complete", actor, UUID.randomUUID(), 6)).andExpect(status().isOk());
        mvc.perform(transition(match, "end", actor, UUID.randomUUID(), 7)).andExpect(status().isOk());
        return path;
    }

    private MockHttpServletRequestBuilder workoutUpload(String path, UUID owner, UUID key,
                                                              long version, String body) {
        return write(post(path), owner, key, body).header("If-Match", tag(version));
    }

    private int startConcurrently(String quarter, CountDownLatch ready) throws Exception {
        ready.countDown();
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        return mvc.perform(transition(quarter, "start", actor, UUID.randomUUID(), 5))
                .andReturn().getResponse().getStatus();
    }

    private MockHttpServletRequestBuilder transition(String match, String action, UUID owner, UUID key,
                                                           long version) {
        return post(match + "/" + action).with(bearer(accessTokens, owner))
                .header("Idempotency-Key", key.toString()).header("If-Match", tag(version));
    }

    private String addQuarter(String path, long version) throws Exception {
        return mvc.perform(write(post(path), actor, UUID.randomUUID(), "{\"durationSeconds\":60}")
                        .header("If-Match", tag(version)))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", tag(version + 1)))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andReturn().getResponse().getHeader("Location");
    }

    private MockHttpServletRequestBuilder remove(String path, UUID key, long version) {
        return delete(path).with(bearer(accessTokens, actor)).header("Idempotency-Key", key.toString())
                .header("If-Match", tag(version));
    }

    private static String tag(long version) {
        return "\"" + version + "\"";
    }

    private String create(UUID owner, String body) throws Exception {
        return mvc.perform(write(post(MATCHES), owner, UUID.randomUUID(), body))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
    }

    private UUID venue(UUID owner) {
        UUID id = UUID.randomUUID();
        var time = LocalDateTime.of(2026, 11, 4, 0, 0);
        venues.save(new Venue(id, owner, "풋살장", "서울", time, time));
        return id;
    }

    private static UUID id(String location) {
        return UUID.fromString(location.substring(MATCHES.length() + 1));
    }

    private MockHttpServletRequestBuilder write(
            MockHttpServletRequestBuilder request, UUID owner, UUID key, String body) {
        return request.with(bearer(accessTokens, owner)).header("Idempotency-Key", key.toString())
                .contentType("application/json").content(body);
    }
}
