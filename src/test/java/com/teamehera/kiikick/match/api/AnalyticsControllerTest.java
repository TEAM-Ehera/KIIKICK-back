package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.auth.security.AccessTokenService;
import static com.teamehera.kiikick.auth.security.TestJwtRequests.bearer;

import com.teamehera.kiikick.friend.domain.FriendPair;
import com.teamehera.kiikick.friend.domain.UserBlock;
import com.teamehera.kiikick.friend.repository.FriendPairRepository;
import com.teamehera.kiikick.friend.repository.UserBlockRepository;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.domain.Quarter;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterPlayerStatsRepository;
import com.teamehera.kiikick.match.repository.QuarterPositionRepository;
import com.teamehera.kiikick.match.repository.QuarterRepository;
import com.teamehera.kiikick.user.domain.UserConsent;
import com.teamehera.kiikick.user.repository.UserConsentRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import com.teamehera.kiikick.workout.domain.QuarterSummary;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import com.teamehera.kiikick.workout.repository.QuarterSummaryRepository;
import com.teamehera.kiikick.workout.repository.WorkoutPayloadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AnalyticsControllerTest {
    private static final String API = "/api/v1/analytics";
    private static final LocalDateTime SEOUL_MIDNIGHT_UTC = LocalDateTime.of(2026, 11, 4, 15, 0);
    private final UUID actor = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired AccessTokenService accessTokens;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired MatchRepository matches;
    @Autowired QuarterRepository quarters;
    @Autowired QuarterPlayerStatsRepository stats;
    @Autowired QuarterPositionRepository positions;
    @Autowired WorkoutPayloadRepository workouts;
    @Autowired QuarterSummaryRepository summaries;
    @Autowired FriendPairRepository pairs;
    @Autowired UserBlockRepository blocks;
    @Autowired UserConsentRepository consents;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:analytics_controller;MODE=MySQL");
    }

    @BeforeEach
    void createUsers() {
        users.save(actor, null);
        users.save(friend, null);
    }

    @Test
    void summaryUsesQuarterPeriodAndWeightedMetricsAndTracksMissingData() throws Exception {
        UUID firstMatch = match(actor, SEOUL_MIDNIGHT_UTC.plusHours(1));
        UUID first = quarter(firstMatch, 1, SEOUL_MIDNIGHT_UTC.minusSeconds(1));
        UUID second = quarter(firstMatch, 2, SEOUL_MIDNIGHT_UTC);
        stats.upsert(first, 0, 0, SEOUL_MIDNIGHT_UTC);
        positions.upsert(first, "TEST", SEOUL_MIDNIGHT_UTC);
        summary(actor, first, "v2", 100, 10, 100, 10, 180);
        summary(actor, second, "v3", 300, 20, 150, 20, 190);

        mvc.perform(get(API + "/summary").with(bearer(accessTokens, actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.matchCount").value(1))
                .andExpect(jsonPath("$.data.goals").value(nullValue()))
                .andExpect(jsonPath("$.data.meta.missingStatsQuarterCount").value(1))
                .andExpect(jsonPath("$.data.metrics.goals.includedQuarterCount").value(1))
                .andExpect(jsonPath("$.data.metrics.averageSpeedMps.value").value(closeTo(400.0 / 30, 1e-9)))
                .andExpect(jsonPath("$.data.metrics.averageHeartRateBpm.value").value(closeTo(4000.0 / 30, 1e-9)))
                .andExpect(jsonPath("$.data.metrics.accelerationCount.status").value("POLICY_REQUIRED"))
                .andExpect(jsonPath("$.data.metrics.trimpEdwards.status").value("POLICY_REQUIRED"));
        mvc.perform(get(API + "/summary").with(bearer(accessTokens, actor))
                        .param("to", "2026-11-05T00:00:00+09:00"))
                .andExpect(jsonPath("$.data.goals").value(0))
                .andExpect(jsonPath("$.data.quarterCount").value(1))
                .andExpect(jsonPath("$.data.metrics.totalDistanceM.value").value(100.0));
        mvc.perform(get(API + "/summary").with(bearer(accessTokens, actor))
                        .param("from", "2026-11-05T00:00:00+09:00"))
                .andExpect(jsonPath("$.data.quarterCount").value(1))
                .andExpect(jsonPath("$.data.metrics.totalDistanceM.value").value(300.0));
        mvc.perform(get(API + "/positions").with(bearer(accessTokens, actor)))
                .andExpect(jsonPath("$.data[0].positionCode").value("TEST"))
                .andExpect(jsonPath("$.data[0].ratio").value(0.5))
                .andExpect(jsonPath("$.data[1].positionCode").value(nullValue()));
    }

    @Test
    void trendPagesKeepEmptySeoulBucketsAndRejectCursorFromAnotherFilter() throws Exception {
        UUID match = match(actor, SEOUL_MIDNIGHT_UTC.plusHours(1));
        UUID quarter = quarter(match, 1, SEOUL_MIDNIGHT_UTC);
        summary(actor, quarter, "v3", 200, 10, 100, 10, 180);
        String from = "2026-11-04T00:00:00+09:00";
        String to = "2026-11-06T00:00:00+09:00";
        var first = mvc.perform(get(API + "/trends").with(bearer(accessTokens, actor))
                        .param("metric", "totalDistanceM").param("bucket", "DAY").param("limit", "1")
                        .param("from", from).param("to", to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].bucketStart").value("2026-11-04T00:00:00+09:00"))
                .andExpect(jsonPath("$.data[0].result.status").value("NO_DATA"))
                .andExpect(jsonPath("$.data[0].result.value").value(nullValue()))
                .andReturn().getResponse();
        String cursor = json.readTree(first.getContentAsString()).at("/meta/pagination/nextCursor").stringValue();
        mvc.perform(get(API + "/trends").with(bearer(accessTokens, actor))
                        .param("metric", "totalDistanceM").param("bucket", "DAY").param("cursor", cursor)
                        .param("from", from).param("to", to))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].bucketStart").value("2026-11-05T00:00:00+09:00"))
                .andExpect(jsonPath("$.data[0].result.value").value(200.0));
        mvc.perform(get(API + "/trends").with(bearer(accessTokens, actor))
                        .param("metric", "totalCaloriesKcal").param("bucket", "DAY").param("cursor", cursor)
                        .param("from", from).param("to", to))
                .andExpect(status().isBadRequest());
        mvc.perform(get(API + "/trends").with(bearer(accessTokens, actor))
                        .param("metric", "totalDistanceM").param("bucket", "MONTH").param("timeZone", "UTC"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void comparisonsEnforceOwnershipConsentAndCalculationCompatibility() throws Exception {
        UUID prior = match(actor, SEOUL_MIDNIGHT_UTC.minusDays(1));
        UUID current = match(actor, SEOUL_MIDNIGHT_UTC);
        UUID foreign = match(friend, SEOUL_MIDNIGHT_UTC);
        summary(actor, quarter(prior, 1, SEOUL_MIDNIGHT_UTC.minusDays(1)), "v2", 100, 10, 100, 10, 180);
        summary(actor, quarter(current, 1, SEOUL_MIDNIGHT_UTC), "v3", 150, 10, 100, 10, 180);
        summary(friend, quarter(foreign, 1, SEOUL_MIDNIGHT_UTC), "v3", 80, 10, 100, 10, 200);
        mvc.perform(get(API + "/match-comparison").with(bearer(accessTokens, actor))
                        .param("matchId", current.toString()).param("metric", "totalDistanceM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.delta").value(50.0));
        mvc.perform(get(API + "/match-comparison").with(bearer(accessTokens, actor))
                        .param("matchId", current.toString()).param("metric", "accelerationCount"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.delta").value(nullValue()));
        mvc.perform(get(API + "/match-comparison").with(bearer(accessTokens, actor))
                        .param("matchId", current.toString()).param("previousMatchId", foreign.toString())
                        .param("metric", "totalDistanceM"))
                .andExpect(status().isNotFound());

        String path = API + "/friend-comparison";
        mvc.perform(get(path).with(bearer(accessTokens, actor)).param("friendUserId", friend.toString())
                        .param("metric", "totalDistanceM")).andExpect(status().isNotFound());
        var ordered = FriendPairRepository.normalize(actor, friend);
        pairs.save(new FriendPair(UUID.randomUUID(), ordered[0], ordered[1], actor, "ACCEPTED",
                SEOUL_MIDNIGHT_UTC, SEOUL_MIDNIGHT_UTC));
        consent(actor, true, SEOUL_MIDNIGHT_UTC);
        mvc.perform(get(path).with(bearer(accessTokens, actor)).param("friendUserId", friend.toString())
                        .param("metric", "totalDistanceM"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("CONSENT_REQUIRED"));
        consent(friend, true, SEOUL_MIDNIGHT_UTC);
        mvc.perform(get(path).with(bearer(accessTokens, actor)).param("friendUserId", friend.toString())
                        .param("metric", "trimpEdwards").param("from", "2026-11-05T00:00:00+09:00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.delta").value(nullValue()))
                .andExpect(jsonPath("$.data.friend.metrics.trimpEdwards.value").value(5.0))
                .andExpect(jsonPath("$.data.friend.matchId").doesNotExist())
                .andExpect(jsonPath("$.data.friend.hrMaxReferenceBpm").doesNotExist());
        consent(friend, false, SEOUL_MIDNIGHT_UTC.plusSeconds(1));
        mvc.perform(get(API + "/summary").with(bearer(accessTokens, actor)).param("subjectUserId", friend.toString()))
                .andExpect(status().isForbidden());
        blocks.save(new UserBlock(friend, actor, SEOUL_MIDNIGHT_UTC));
        mvc.perform(get(API + "/positions").with(bearer(accessTokens, actor)).param("subjectUserId", friend.toString()))
                .andExpect(status().isNotFound());
    }

    private UUID match(UUID owner, LocalDateTime endedAt) {
        UUID id = UUID.randomUUID();
        matches.save(new Match(id, owner, null, null, null, null, null, "COMPLETED",
                endedAt.minusMinutes(20), endedAt, 1, endedAt, endedAt));
        return id;
    }

    private UUID quarter(UUID match, int number, LocalDateTime endedAt) {
        UUID id = UUID.randomUUID();
        quarters.save(new Quarter(id, match, number, 60, "COMPLETED",
                endedAt.minusMinutes(1), endedAt, endedAt, endedAt));
        return id;
    }

    private void summary(UUID owner, UUID quarter, String version, double distance, double duration, double averageHeart,
                         double heartDuration, double reference) {
        UUID workout = UUID.randomUUID();
        var now = SEOUL_MIDNIGHT_UTC;
        workouts.save(new WorkoutPayload(workout, quarter, owner, now.minusMinutes(1), now, 0, distance));
        UUID job = summaries.saveProcessing(quarter, workout, version, "{}", "{}", "fixture-hash", now);
        String statuses = """
                {"totalDistanceM":{"status":"READY"},"averageSpeedMps":{"status":"READY"},
                 "averageHeartRateBpm":{"status":"READY"},"accelerationCount":{"status":"READY"},
                 "trimpEdwards":{"status":"READY"}}
                """;
        summaries.updateCalculated(new QuarterSummary(quarter, workout, job, "PARTIAL", version, "{}", statuses,
                "fixture-hash", duration, null, distance, null, distance / duration, null, 1, null, null,
                averageHeart, null, 5.0, reference, heartDuration, null, now, now), "fixture-hash");
    }

    private void consent(UUID user, boolean granted, LocalDateTime at) {
        consents.save(new UserConsent(UUID.randomUUID(), user, "FRIEND_COMPARISON", "test", granted, at));
    }
}
