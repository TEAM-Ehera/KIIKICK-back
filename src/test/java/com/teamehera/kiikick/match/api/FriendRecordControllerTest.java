package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.auth.security.AccessTokenService;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.domain.Quarter;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterPlayerStatsRepository;
import com.teamehera.kiikick.match.repository.QuarterRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import com.teamehera.kiikick.venue.domain.Venue;
import com.teamehera.kiikick.venue.domain.FieldCorner;
import com.teamehera.kiikick.venue.repository.VenueRepository;
import com.teamehera.kiikick.workout.domain.TrackPoint;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.teamehera.kiikick.auth.security.TestJwtRequests.bearer;
import static org.hamcrest.Matchers.closeTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FriendRecordControllerTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 3, 0);
    private final UUID viewer = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private UUID venue;
    private UUID match;
    private UUID quarter;
    private UUID workout;

    @Autowired MockMvc mvc;
    @Autowired AccessTokenService accessTokens;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired MatchRepository matches;
    @Autowired QuarterRepository quarters;
    @Autowired QuarterPlayerStatsRepository stats;
    @Autowired WorkoutPayloadRepository workouts;
    @Autowired TrackPointRepository tracks;
    @Autowired QuarterSummaryRepository summaries;
    @Autowired VenueRepository venues;
    @Autowired QuarterSummaryCalculation calculations;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:friend_records;MODE=MySQL");
    }

    @BeforeEach
    void seed() {
        for (var user : List.of(viewer, friend, stranger)) {
            users.save(user, null);
            users.completeOnboarding(user, "record-" + user.toString().substring(0, 8), NOW);
        }
        venue = UUID.randomUUID();
        venues.save(new Venue(venue, friend, "현재 경기장 이름", "현재 주소",
                new FieldCorner(37, 127), new FieldCorner(37, 127.001),
                new FieldCorner(37.001, 127.001), new FieldCorner(37.001, 127),
                null, null, null, null, NOW, NOW));
        match = match(friend, "COMPLETED", NOW);
        quarter = quarter(match, 1, "COMPLETED");
        stats.upsert(quarter, 2, 1, NOW);
        workout = UUID.randomUUID();
        workouts.save(new WorkoutPayload(workout, quarter, friend, NOW, NOW.plusMinutes(10), 100, 1200));
        summaries.saveProcessing(quarter, workout, "v1", "{}", "{}", "a".repeat(64), NOW);
        tracks.saveAll(List.of(point(0, 37.000011, 127.000011, 5, 2),
                point(1, 37.000012, 127.000012, 20, 12),
                point(2, 37.000061, 127.000011, 5, 2),
                point(3, 37.000011, 127.000011, 21, 2),
                point(4, 37.000011, 127.000011, 5, 13)));
    }

    @Test
    void acceptanceGrantsBothDirectionsWithoutComparisonConsent() throws Exception {
        assertDenied(viewer);
        String request = send();
        assertDenied(viewer);
        accept(request);
        read(viewer, base()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(match.toString()));
        var result = read(viewer, base() + "/" + match + "/result").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.goals").value(2))
                .andExpect(jsonPath("$.data.assists").value(1))
                .andExpect(jsonPath("$.data.match.venueSnapshot.name").value("친구 풋살장"))
                .andReturn().getResponse();
        assertEquals(json.readTree(result.getContentAsString()).at("/meta/requestId").asText(),
                result.getHeader("X-Request-Id"));
        read(viewer, base() + "/" + match + "/venue").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.address").value("서울시 테스트 주소"))
                .andExpect(jsonPath("$.data.cornerA.latitude").value(37));
        read(viewer, base() + "/" + match + "/result/quarters").andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].stats.goals").value(2))
                .andExpect(jsonPath("$.data[0].summary.calculationStatus").value("PROCESSING"));
        read(viewer, quarterPath() + "/result").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workout.totalDistance").value(1200));
        read(viewer, quarterPath() + "/heatmap").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"));
        UUID mine = match(viewer, "COMPLETED", NOW);
        read(friend, "/api/v1/friends/" + viewer + "/matches/" + mine + "/result")
                .andExpect(status().isOk());
        // Friendship grants read access only through these routes, never ownership or write access.
        read(viewer, "/api/v1/matches/" + match).andExpect(status().isNotFound());
        mvc.perform(write(patch("/api/v1/matches/" + match), viewer).header("If-Match", "\"1\"")
                .contentType("application/json").content("{\"title\":\"changed\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void calculatedDetailedMetricsAreVisibleAfterAcceptance() throws Exception {
        accept(send());
        calculations.process(new QuarterSummaryRepository.PendingCalculation(
                summaries.findByQuarterId(quarter).orElseThrow(), match, friend));
        read(viewer, quarterPath() + "/result").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.calculationStatus").value("PARTIAL"))
                .andExpect(jsonPath("$.data.summary.totalDistanceM").value(1200))
                .andExpect(jsonPath("$.data.summary.averageSpeedMps").value(2))
                .andExpect(jsonPath("$.data.summary.maxSpeedMps").value(12))
                .andExpect(jsonPath("$.data.summary.averageHeartRateBpm").isEmpty());
    }

    @Test
    void heatmapAggregatesDensityAndOmitsInvalidSamplesAndRawFields() throws Exception {
        accept(send());
        read(viewer, quarterPath() + "/heatmap").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cellSizeDegrees").value(0.00005))
                .andExpect(jsonPath("$.data.sampleCount").value(3))
                .andExpect(jsonPath("$.data.cells.length()").value(2))
                .andExpect(jsonPath("$.data.cells[0].latitude", closeTo(37.000025, 0.000000001)))
                .andExpect(jsonPath("$.data.cells[0].sampleCount").value(2))
                .andExpect(jsonPath("$.data.cells[0].timestamp").doesNotExist())
                .andExpect(jsonPath("$.data.cells[0].speed").doesNotExist());
    }

    @Test
    void removalRevokesEveryRouteIncludingAnExistingCursor() throws Exception {
        accept(send());
        match(friend, "COMPLETED", NOW.plusSeconds(1));
        String cursor = cursor();
        mvc.perform(write(delete("/api/v1/friends/" + friend), viewer)).andExpect(status().isNoContent());
        assertDenied(viewer);
        mvc.perform(get(base()).with(bearer(accessTokens, viewer)).param("status", "COMPLETED").param("cursor", cursor))
                .andExpect(status().isNotFound());
    }

    @Test
    void eitherDirectionBlockRevokesEveryRoute() throws Exception {
        accept(send());
        mvc.perform(write(put("/api/v1/users/" + viewer + "/block"), friend)).andExpect(status().isNoContent());
        assertDenied(viewer);
        mvc.perform(write(delete("/api/v1/users/" + viewer + "/block"), friend)).andExpect(status().isNoContent());
        assertDenied(viewer);
        accept(send());
        mvc.perform(write(put("/api/v1/users/" + friend + "/block"), viewer)).andExpect(status().isNoContent());
        assertDenied(viewer);
    }

    @Test
    void rejectsStrangersWrongOwnersWrongParentsAndUnfinishedMatches() throws Exception {
        accept(send());
        assertDenied(stranger);
        UUID otherMatch = match(stranger, "COMPLETED", NOW);
        UUID otherQuarter = quarter(otherMatch, 1, "COMPLETED");
        for (String suffix : List.of("/result", "/venue", "/result/quarters")) {
            read(viewer, base() + "/" + otherMatch + suffix).andExpect(status().isNotFound());
        }
        UUID another = match(friend, "COMPLETED", NOW.plusSeconds(1));
        UUID sameOwnerOtherQuarter = quarter(another, 1, "COMPLETED");
        for (UUID wrong : List.of(otherQuarter, sameOwnerOtherQuarter, UUID.randomUUID())) {
            for (String suffix : List.of("/result", "/heatmap")) {
                read(viewer, base() + "/" + match + "/quarters/" + wrong + suffix)
                        .andExpect(status().isNotFound());
            }
        }
        for (String state : List.of("DRAFT", "READY", "IN_PROGRESS", "CANCELLED")) {
            UUID unfinished = match(friend, state, NOW);
            for (String suffix : List.of("/result", "/result/quarters")) {
                read(viewer, base() + "/" + unfinished + suffix).andExpect(status().isConflict());
            }
            read(viewer, base() + "/" + unfinished + "/venue").andExpect(status().isOk());
        }
    }

    @Test
    void paginatesOnlyCompletedMatchesAndValidatesCursorsAndLimits() throws Exception {
        accept(send());
        UUID newest = match(friend, "COMPLETED", NOW.plusSeconds(1));
        match(friend, "IN_PROGRESS", NOW.plusSeconds(2));
        String cursor = cursor();
        read(viewer, base() + "?status=COMPLETED").andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(newest.toString()));
        mvc.perform(get(base()).with(bearer(accessTokens, viewer)).param("status", "COMPLETED").param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(match.toString()))
                .andExpect(jsonPath("$.meta.pagination.hasNext").value(false));
        mvc.perform(get(base()).with(bearer(accessTokens, viewer)).param("status", "COMPLETED").param("cursor", "invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(base()).with(bearer(accessTokens, viewer)).param("status", "COMPLETED").param("limit", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingWorkoutAndNoValidPointsHaveExplicitEmptyStates() throws Exception {
        accept(send());
        UUID missing = quarter(match, 2, "COMPLETED");
        String path = base() + "/" + match + "/quarters/" + missing + "/heatmap";
        read(viewer, path).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MISSING_WORKOUT"))
                .andExpect(jsonPath("$.data.cells.length()").value(0));
        workouts.save(new WorkoutPayload(UUID.randomUUID(), missing, friend, NOW, NOW.plusMinutes(10), 0, 0));
        read(viewer, path).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NO_VALID_POINTS"))
                .andExpect(jsonPath("$.data.sampleCount").value(0));
    }

    @Test
    void deletedVenueKeepsMatchSnapshotAndDoesNotExposeAnotherSavedVenue() throws Exception {
        accept(send());
        venues.deleteByIdAndOwnerId(venue, friend);
        read(viewer, base() + "/" + match + "/venue").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("친구 풋살장"))
                .andExpect(jsonPath("$.data.address").value("서울시 테스트 주소"))
                .andExpect(jsonPath("$.data.id").isEmpty())
                .andExpect(jsonPath("$.data.cornerA").isEmpty());
    }

    @Test
    void everyRouteRequiresAuthentication() throws Exception {
        for (String path : paths()) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    private String base() { return "/api/v1/friends/" + friend + "/matches"; }
    private String quarterPath() { return base() + "/" + match + "/quarters/" + quarter; }

    private List<String> paths() {
        return List.of(base(), base() + "/" + match + "/result", base() + "/" + match + "/venue",
                base() + "/" + match + "/result/quarters", quarterPath() + "/result", quarterPath() + "/heatmap");
    }

    private void assertDenied(UUID user) throws Exception {
        for (String path : paths()) {
            read(user, path).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    private ResultActions read(UUID user, String path) throws Exception {
        return mvc.perform(get(path).with(bearer(accessTokens, user)));
    }

    private String send() throws Exception {
        var response = mvc.perform(write(post("/api/v1/friend-requests"), viewer).contentType("application/json")
                .content(json.writeValueAsString(Map.of("targetUserId", friend))))
                .andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).at("/data/id").asText();
    }

    private void accept(String request) throws Exception {
        mvc.perform(write(post("/api/v1/friend-requests/" + request + "/accept"), friend))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request, UUID user) {
        return request.with(bearer(accessTokens, user)).header("Idempotency-Key", UUID.randomUUID());
    }

    private String cursor() throws Exception {
        var response = mvc.perform(get(base()).with(bearer(accessTokens, viewer)).param("status", "COMPLETED").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.pagination.hasNext").value(true))
                .andReturn().getResponse();
        return json.readTree(response.getContentAsString()).at("/meta/pagination/nextCursor").asText();
    }

    private UUID match(UUID owner, String status, LocalDateTime created) {
        UUID id = UUID.randomUUID();
        matches.save(new Match(id, owner, owner.equals(friend) ? venue : null, "친구 경기", "친구 풋살장", "서울시 테스트 주소", null,
                status, "COMPLETED".equals(status) ? NOW : null,
                "COMPLETED".equals(status) ? NOW.plusMinutes(10) : null, 1, created, created));
        return id;
    }

    private UUID quarter(UUID matchId, int number, String status) {
        UUID id = UUID.randomUUID();
        quarters.save(new Quarter(id, matchId, number, 600, status, NOW, NOW.plusMinutes(10), NOW, NOW));
        return id;
    }

    private TrackPoint point(int sequence, double latitude, double longitude, double accuracy, double speed) {
        return new TrackPoint(workout, sequence, latitude, longitude, 0, accuracy, speed, 0, NOW.plusSeconds(sequence));
    }
}
