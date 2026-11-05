package com.teamehera.kiikick.match.api;

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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class VenueControllerTest extends AuthenticatedApiTest {
    private static final String VENUES = "/api/v1/venues";
    private final UUID owner = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:venue_controller;MODE=MySQL");
    }

    @BeforeEach
    void createUsers() {
        users.save(owner, null);
        users.save(other, null);
    }

    @Test
    void venueWritesReplayAndMatchSnapshotSurvivesRenameAndDelete() throws Exception {
        var body = Map.of("name", "Original", "address", "Seoul");
        UUID createKey = UUID.randomUUID();
        var created = mvc.perform(write(post(VENUES), owner, createKey, body))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String venuePath = created.getHeader("Location");
        String venueId = venuePath.substring(VENUES.length() + 1);
        mvc.perform(write(post(VENUES), owner, createKey, body))
                .andExpect(status().isCreated()).andExpect(header().string("Location", venuePath))
                .andExpect(content().string(created.getContentAsString()));
        String matchPath = mvc.perform(write(post("/api/v1/matches"), owner, UUID.randomUUID(),
                        Map.of("venueId", venueId))).andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");

        String secondMatchPath = mvc.perform(write(post("/api/v1/matches"), owner, UUID.randomUUID(),
                        Map.of("venueId", venueId))).andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        String unrelatedMatchPath = mvc.perform(write(post("/api/v1/matches"), owner, UUID.randomUUID(), Map.of()))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");

        mvc.perform(get(venuePath).header("Authorization", bearer(other))).andExpect(status().isNotFound());
        mvc.perform(write(patch(venuePath), other, UUID.randomUUID(), Map.of("name", "Other")))
                .andExpect(status().isNotFound());
        mvc.perform(delete(venuePath).header("Authorization", bearer(other)).header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isNotFound());
        mvc.perform(write(patch(venuePath), owner, UUID.randomUUID(), Map.of("name", "Renamed")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.address").value("Seoul"));
        mvc.perform(patch(venuePath).header("Authorization", bearer(owner)).header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content("""
                                {"address":null}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("Renamed"))
                .andExpect(jsonPath("$.data.address").value(nullValue()));
        var before = mvc.perform(get(matchPath).header("Authorization", bearer(owner)))
                .andExpect(header().string("ETag", tag(1)))
                .andExpect(jsonPath("$.data.venueSnapshot.name").value("Original")).andReturn().getResponse();
        String previousTag = before.getHeader("ETag");

        UUID deleteKey = UUID.randomUUID();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(delete(venuePath).header("Authorization", bearer(owner)).header("Idempotency-Key", deleteKey))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));
        }
        mvc.perform(get(venuePath).header("Authorization", bearer(owner))).andExpect(status().isNotFound());
        mvc.perform(get(matchPath).header("Authorization", bearer(owner)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", tag(2)))
                .andExpect(jsonPath("$.data.venueId").value(nullValue()))
                .andExpect(jsonPath("$.data.venueSnapshot.name").value("Original"))
                .andExpect(jsonPath("$.data.venueSnapshot.address").value("Seoul"));
        mvc.perform(get(secondMatchPath).header("Authorization", bearer(owner)))
                .andExpect(header().string("ETag", tag(2))).andExpect(jsonPath("$.data.venueId").value(nullValue()));
        mvc.perform(get(unrelatedMatchPath).header("Authorization", bearer(owner)))
                .andExpect(header().string("ETag", tag(1)));
        mvc.perform(write(patch(matchPath), owner, UUID.randomUUID(), Map.of("title", "Updated"))
                        .header("If-Match", previousTag))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.details.currentVersion").value(2));
    }

    @Test
    void searchPaginatesByOwnerAndTreatsWildcardsAsLiteralText() throws Exception {
        create(owner, "Court_One");
        create(owner, "Court_Two");
        create(owner, "CourtThree");
        create(other, "Court_Other");
        var page = mvc.perform(get(VENUES).header("Authorization", bearer(owner)).param("q", "_").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.pagination.hasNext").value(true)).andReturn().getResponse();
        var first = json.readTree(page.getContentAsString());
        String cursor = first.at("/meta/pagination/nextCursor").stringValue();
        var next = mvc.perform(get(VENUES).header("Authorization", bearer(owner)).param("q", "_").param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.pagination.hasNext").value(false)).andReturn().getResponse();
        var names = java.util.Set.of(first.at("/data/0/name").stringValue(),
                json.readTree(next.getContentAsString()).at("/data/0/name").stringValue());
        assertEquals(java.util.Set.of("Court_One", "Court_Two"), names);
        mvc.perform(get(VENUES).header("Authorization", bearer(owner)).param("cursor", cursor))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"));
        mvc.perform(get(VENUES).header("Authorization", bearer(other)).param("q", "_").param("cursor", cursor))
                .andExpect(status().isBadRequest());
    }

    @Test
    void venueRejectsInvalidInputAndIfMatch() throws Exception {
        mvc.perform(get(VENUES)).andExpect(status().isUnauthorized());
        mvc.perform(write(post(VENUES), owner, UUID.randomUUID(), Map.of("name", " ")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.error.details.fields[0]").value("name"));
        mvc.perform(write(post(VENUES), owner, UUID.randomUUID(), Map.of("name", "Court"))
                        .header("If-Match", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void venueDeletionSerializesWithMatchSettingsUpdate() throws Exception {
        String venue = mvc.perform(write(post(VENUES), owner, UUID.randomUUID(), Map.of("name", "Court")))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");
        var reference = Map.of("venueId", venue.substring(VENUES.length() + 1));
        String match = mvc.perform(write(post("/api/v1/matches"), owner, UUID.randomUUID(), reference))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location");

        var ready = new CountDownLatch(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var update = workers.submit(() -> {
                ready.countDown();
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                return mvc.perform(write(patch(match), owner, UUID.randomUUID(), reference).header("If-Match", tag(1)))
                        .andReturn().getResponse().getStatus();
            });
            var delete = workers.submit(() -> {
                ready.countDown();
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                return mvc.perform(delete(venue).header("Authorization", bearer(owner)).header("Idempotency-Key", UUID.randomUUID()))
                        .andReturn().getResponse().getStatus();
            });
            int updated = update.get(10, TimeUnit.SECONDS);
            assertTrue(updated == 200 || updated == 412);
            assertEquals(204, delete.get(10, TimeUnit.SECONDS));
            mvc.perform(get(match).header("Authorization", bearer(owner)))
                    .andExpect(header().string("ETag", tag(updated == 200 ? 3 : 2)))
                    .andExpect(jsonPath("$.data.venueId").value(nullValue()))
                    .andExpect(jsonPath("$.data.venueSnapshot.name").value("Court"));
        }
    }

    private static String tag(long version) {
        return Character.toString('"') + version + '"';
    }

    private void create(UUID actor, String name) throws Exception {
        mvc.perform(write(post(VENUES), actor, UUID.randomUUID(), Map.of("name", name))).andExpect(status().isCreated());
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, UUID actor, UUID key,
                                                Map<String, String> body) {
        return request.header("Authorization", bearer(actor)).header("Idempotency-Key", key)
                .contentType("application/json").content(json.writeValueAsString(body));
    }
}
