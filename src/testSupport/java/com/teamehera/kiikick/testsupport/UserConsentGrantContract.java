package com.teamehera.kiikick.testsupport;

import com.teamehera.kiikick.auth.email.VerificationEmailSender;
import com.teamehera.kiikick.auth.security.AccessTokenService;
import com.teamehera.kiikick.global.idempotency.repository.IdempotencyRecordRepository;
import com.teamehera.kiikick.user.domain.UserConsent;
import com.teamehera.kiikick.user.repository.UserConsentRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import com.teamehera.kiikick.user.service.UserConsentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public abstract class UserConsentGrantContract {
    private static final String PATH = "/api/v1/users/me/consents";
    private static final String GPS = "GPS_COLLECTION";
    private static final String HEALTH = "HEALTH_DATA";
    private static final String GRANT = """
            {"policyVersion":"1.0","granted":true}
            """;
    private static final String WITHDRAW = """
            {"policyVersion":"1.0","granted":false}
            """;

    @Autowired MockMvc mvc;
    @Autowired AccessTokenService access;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired UserConsentRepository consents;
    @Autowired UserConsentService consentService;
    @Autowired ObjectMapper json;
    @Autowired RefreshRotationContract.TestClock clock;
    @MockitoSpyBean IdempotencyRecordRepository records;
    @MockitoBean VerificationEmailSender email;

    private final List<UUID> owned = new ArrayList<>();

    @AfterEach void cleanup() {
        reset(records);
        for (UUID id : owned) {
            jdbc.update("DELETE FROM idempotency_records WHERE user_id=?", uuidToBytes(id));
            jdbc.update("DELETE FROM user_consents WHERE user_id=?", uuidToBytes(id));
            jdbc.update("DELETE FROM users WHERE id=?", uuidToBytes(id));
        }
    }

    @Test void appendsGrantAndReturnsTheExistingConsentShape() throws Exception {
        var actor = fixture();
        var other = fixture();
        seedDenial(actor, LocalDateTime.ofInstant(clock.instant().minusSeconds(1), ZoneOffset.UTC));
        request(actor, GPS, UUID.randomUUID(), GRANT).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.meta.requestId").isString())
                .andExpect(jsonPath("$.data.purpose").value(GPS))
                .andExpect(jsonPath("$.data.policyVersion").value("1.0"))
                .andExpect(jsonPath("$.data.granted").value(true))
                .andExpect(jsonPath("$.data.recordedAt").isString());
        assertCounts(actor, 2, 1);
        assertCounts(other, 0, 0);
        assertThat(consents.findLatest(actor, GPS).orElseThrow().granted()).isTrue();
    }

    @Test void replaysSameBodyAndRejectsChangedBodyWithinPurpose() throws Exception {
        var actor = fixture();
        var key = UUID.randomUUID();
        var first = request(actor, GPS, key, GRANT).andExpect(status().isOk()).andReturn().getResponse();
        var replay = request(actor, GPS, key, GRANT).andExpect(status().isOk()).andReturn().getResponse();
        // MySQL JSON 컬럼은 키 순서와 공백을 정규화하므로 응답의 구조와 값을 비교한다.
        assertThat(json.readTree(replay.getContentAsString())).isEqualTo(json.readTree(first.getContentAsString()));
        assertThat(replay.getHeader("X-Request-Id")).isEqualTo(first.getHeader("X-Request-Id"));
        request(actor, GPS, key, GRANT.trim()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        request(actor, HEALTH, key, GRANT).andExpect(status().isOk());
        assertCounts(actor, 2, 2);
    }

    @Test void withdrawalUpdatesOnlyItsPurposeAndReplayAfterRegrantDoesNotWithdrawAgain() throws Exception {
        var actor = fixture();
        request(actor, GPS, UUID.randomUUID(), GRANT).andExpect(status().isOk());
        request(actor, HEALTH, UUID.randomUUID(), GRANT).andExpect(status().isOk());

        var key = UUID.randomUUID();
        var first = request(actor, GPS, key, WITHDRAW).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.granted").value(false)).andReturn().getResponse();
        mvc.perform(get(PATH).header("Authorization", bearer(actor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].purpose").value(GPS))
                .andExpect(jsonPath("$.data[0].granted").value(false))
                .andExpect(jsonPath("$.data[1].purpose").value(HEALTH))
                .andExpect(jsonPath("$.data[1].granted").value(true));

        request(actor, GPS, key, GRANT).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        request(actor, GPS, UUID.randomUUID(), GRANT).andExpect(status().isOk());
        var replay = request(actor, GPS, key, WITHDRAW).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(json.readTree(replay.getContentAsString())).isEqualTo(json.readTree(first.getContentAsString()));
        assertThat(replay.getHeader("X-Request-Id")).isEqualTo(first.getHeader("X-Request-Id"));
        assertThat(consents.findLatest(actor, GPS).orElseThrow().granted()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_consents WHERE user_id=? AND granted=false",
                Integer.class, uuidToBytes(actor))).isEqualTo(1);
        assertCounts(actor, 4, 4);
    }

    @Test void rollsBackWithdrawalAndPreservesEffectiveGrantWhenSavingResponseFails() throws Exception {
        var actor = fixture();
        request(actor, GPS, UUID.randomUUID(), GRANT).andExpect(status().isOk());
        var key = UUID.randomUUID();
        doThrow(new DataAccessResourceFailureException("fixture completion failure"))
                .when(records).complete(any(), anyString(), eq(200), anyString(), anyString());
        request(actor, GPS, key, WITHDRAW).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.persistence").value("NOT_SAVED"));
        assertThat(consents.findLatest(actor, GPS).orElseThrow().granted()).isTrue();
        assertCounts(actor, 1, 1);
        reset(records);
        request(actor, GPS, key, WITHDRAW).andExpect(status().isOk());
        assertThat(consents.findLatest(actor, GPS).orElseThrow().granted()).isFalse();
        assertCounts(actor, 2, 2);
    }

    @Test void concurrentSameKeyWithdrawsOnlyOnce() throws Exception {
        var actor = fixture();
        request(actor, GPS, UUID.randomUUID(), GRANT).andExpect(status().isOk());
        var key = UUID.randomUUID();
        var replies = concurrent(() -> successTime(actor, key, WITHDRAW), () -> successTime(actor, key, WITHDRAW));
        assertThat(replies.get(0)).isEqualTo(replies.get(1));
        assertThat(consents.findLatest(actor, GPS).orElseThrow().granted()).isFalse();
        assertCounts(actor, 2, 2);
    }

    @Test void concurrentWithdrawalAndRegrantUseCommitOrderEvenAtSameMicrosecond() throws Exception {
        var actor = fixture();
        request(actor, GPS, UUID.randomUUID(), GRANT).andExpect(status().isOk());
        var times = concurrent(() -> successTime(actor, UUID.randomUUID(), WITHDRAW),
                () -> successTime(actor, UUID.randomUUID(), GRANT));
        assertThat(times.get(0)).isNotEqualTo(times.get(1));
        var latest = consents.findLatest(actor, GPS).orElseThrow();
        assertThat(latest.granted()).isEqualTo(times.get(1).isAfter(times.get(0)));
        assertThat(latest.recordedAt().toInstant(ZoneOffset.UTC)).isEqualTo(
                times.get(1).isAfter(times.get(0)) ? times.get(1) : times.get(0));
        assertCounts(actor, 3, 3);
    }

    @Test void effectiveConsentIsFalseWithoutHistoryAndFollowsWithdrawalAndRegrant() throws Exception {
        var actor = fixture();
        assertThat(consentService.hasEffectiveConsent(actor, GPS)).isFalse();
        assertThat(consentService.hasEffectiveConsent(actor, HEALTH)).isFalse();
        request(actor, GPS, UUID.randomUUID(), GRANT).andExpect(status().isOk());
        assertThat(consentService.hasEffectiveConsent(actor, GPS)).isTrue();
        request(actor, GPS, UUID.randomUUID(), WITHDRAW).andExpect(status().isOk());
        assertThat(consentService.hasEffectiveConsent(actor, GPS)).isFalse();
        request(actor, GPS, UUID.randomUUID(), GRANT).andExpect(status().isOk());
        assertThat(consentService.hasEffectiveConsent(actor, GPS)).isTrue();
        assertThat(consentService.hasEffectiveConsent(actor, HEALTH)).isFalse();
        assertCounts(actor, 3, 3);
    }

    @Test void effectiveConsentRejectsOldVersionUnsupportedPurposeAndInactiveUsers() throws Exception {
        var actor = fixture();
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        request(actor, HEALTH, UUID.randomUUID(), GRANT).andExpect(status().isOk());
        assertThat(consentService.hasEffectiveConsent(actor, HEALTH)).isTrue();
        consents.save(new UserConsent(UUID.randomUUID(), actor, HEALTH, "0.9", true, now.plusSeconds(1)));
        assertThat(consentService.hasEffectiveConsent(actor, HEALTH)).isFalse();

        consents.save(new UserConsent(UUID.randomUUID(), actor, "FRIEND_COMPARISON", "1.0", true, now));
        assertThat(consentService.hasEffectiveConsent(actor, "FRIEND_COMPARISON")).isFalse();
        assertThat(consentService.hasEffectiveConsent(UUID.randomUUID(), GPS)).isFalse();
        request(actor, GPS, UUID.randomUUID(), GRANT).andExpect(status().isOk());
        users.markDeletionRequested(actor, now);
        assertThat(consentService.hasEffectiveConsent(actor, GPS)).isFalse();
        users.softDelete(actor);
        assertThat(consentService.hasEffectiveConsent(actor, GPS)).isFalse();
    }

    @Test void rejectsInvalidPurposeVersionBodyAndKeyWithoutSaving() throws Exception {
        var actor = fixture();
        request(actor, "FRIEND_COMPARISON", UUID.randomUUID(), GRANT).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0]").value("purpose"));
        request(actor, GPS, UUID.randomUUID(), GRANT.replace("1.0", "2.0"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.error.details.fields[0]").value("policyVersion"));
        for (var body : List.of("{}", GRANT.replace("true", "\"true\""), GRANT.replace("true", "null"))) {
            request(actor, GPS, UUID.randomUUID(), body).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        for (var body : List.of("{", GRANT + "{}", GRANT.replace("true", "false,\"granted\":true"))) {
            request(actor, GPS, UUID.randomUUID(), body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        request(actor, GPS, null, GRANT).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.fields[0]").value("Idempotency-Key"));
        assertCounts(actor, 0, 0);
    }

    @Test void rejectsIfMatchEvenForAnExistingIdempotencyKey() throws Exception {
        var actor = fixture();
        var key = UUID.randomUUID();
        request(actor, GPS, key, GRANT).andExpect(status().isOk());
        mvc.perform(put(PATH + "/" + GPS).contentType("application/json")
                        .header("Authorization", bearer(actor)).header("Idempotency-Key", key)
                        .header("If-Match", "\"1\"").content(GRANT))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertCounts(actor, 1, 1);
    }

    @Test void requiresJwtAndRejectsReplayAfterDeletionRequest() throws Exception {
        var actor = fixture();
        var key = UUID.randomUUID();
        mvc.perform(put(PATH + "/" + GPS).contentType("application/json").header("Idempotency-Key", key)
                        .content(GRANT))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        request(actor, GPS, key, GRANT).andExpect(status().isOk());
        users.markDeletionRequested(actor, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        request(actor, GPS, key, GRANT).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        assertCounts(actor, 1, 1);
    }

    @Test void rollsBackGrantAndIdempotencyRecordWhenSavingResponseFails() throws Exception {
        var actor = fixture();
        var key = UUID.randomUUID();
        doThrow(new DataAccessResourceFailureException("fixture completion failure"))
                .when(records).complete(any(), anyString(), eq(200), anyString(), anyString());
        request(actor, GPS, key, GRANT).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.persistence").value("NOT_SAVED"));
        assertCounts(actor, 0, 0);
        reset(records);
        request(actor, GPS, key, GRANT).andExpect(status().isOk());
        assertCounts(actor, 1, 1);
    }

    @Test void concurrentDifferentKeysAtSameMicrosecondProduceOrderedLatestHistory() throws Exception {
        var actor = fixture();
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        seedDenial(actor, now);
        var key1 = UUID.randomUUID();
        var key2 = UUID.randomUUID();
        var results = concurrent(() -> successTime(actor, key1), () -> successTime(actor, key2));
        var latest = consents.findLatest(actor, GPS).orElseThrow();
        assertThat(latest.recordedAt()).isEqualTo(now.plus(2, ChronoUnit.MICROS));
        assertThat(results).containsExactlyInAnyOrder(
                now.plus(1, ChronoUnit.MICROS).toInstant(ZoneOffset.UTC),
                now.plus(2, ChronoUnit.MICROS).toInstant(ZoneOffset.UTC));
        mvc.perform(get(PATH).header("Authorization", bearer(actor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].granted").value(true));
        assertCounts(actor, 3, 2);
    }

    @Test void concurrentSameKeyAppendsOnlyOnce() throws Exception {
        var actor = fixture();
        var key = UUID.randomUUID();
        var results = concurrent(() -> successTime(actor, key), () -> successTime(actor, key));
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertCounts(actor, 1, 1);
    }

    private UUID fixture() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        UUID id = UUID.randomUUID();
        owned.add(id);
        users.save(id, "consent-grant");
        return id;
    }

    private void seedDenial(UUID actor, LocalDateTime recordedAt) {
        consents.save(new UserConsent(UUID.randomUUID(), actor, GPS, "1.0", false, recordedAt));
    }

    private String bearer(UUID actor) {
        return "Bearer " + access.issue(actor, UUID.randomUUID()).token();
    }

    private ResultActions request(UUID actor, String purpose, UUID key, String body) throws Exception {
        var request = put(PATH + "/" + purpose).contentType("application/json")
                .header("Authorization", bearer(actor)).content(body);
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }

    private Instant successTime(UUID actor, UUID key) throws Exception {
        return successTime(actor, key, GRANT);
    }

    private Instant successTime(UUID actor, UUID key, String input) throws Exception {
        var body = request(actor, GPS, key, input).andExpect(status().isOk()).andReturn().getResponse();
        return java.time.OffsetDateTime.parse(json.readTree(body.getContentAsString())
                .at("/data/recordedAt").asText()).toInstant();
    }

    private <T> List<T> concurrent(Callable<T> first, Callable<T> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var left = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return first.call(); });
            var right = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return second.call(); });
            return List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertCounts(UUID actor, int history, int idempotency) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_consents WHERE user_id=?", Integer.class,
                uuidToBytes(actor))).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE user_id=?", Integer.class,
                uuidToBytes(actor))).isEqualTo(idempotency);
    }
}
