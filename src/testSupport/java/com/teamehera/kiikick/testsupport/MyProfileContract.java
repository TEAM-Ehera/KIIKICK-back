package com.teamehera.kiikick.testsupport;

import com.teamehera.kiikick.auth.email.VerificationEmailSender;
import com.teamehera.kiikick.auth.security.*;
import com.teamehera.kiikick.global.idempotency.repository.IdempotencyRecordRepository;
import com.teamehera.kiikick.user.domain.UserConsent;
import com.teamehera.kiikick.user.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public abstract class MyProfileContract {
    @Autowired MockMvc mvc;
    @Autowired AccessTokenService access;
    @Autowired JwtEncoder encoder;
    @Autowired AuthSecurityProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserConsentRepository consents;
    @Autowired RefreshRotationContract.TestClock clock;
    @Autowired ObjectMapper json;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean IdempotencyRecordRepository records;
    @MockitoBean VerificationEmailSender email;
    final List<UUID> owned = new ArrayList<>();
    static final LocalDateTime CREATED = LocalDateTime.parse("2026-01-02T03:04:05.123456");
    record Fixture(UUID id, String token) {}

    @BeforeEach void setup() {
        reset(users, records);
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
    @AfterEach void cleanup() {
        for (UUID id : owned) {
            jdbc.update("DELETE FROM idempotency_records WHERE user_id=?", uuidToBytes(id));
            jdbc.update("DELETE FROM user_consents WHERE user_id=?", uuidToBytes(id));
            jdbc.update("DELETE FROM users WHERE id=?", uuidToBytes(id));
        }
    }
    Fixture fixture(String nickname) {
        UUID id = UUID.randomUUID(); owned.add(id); users.save(id, nickname);
        jdbc.update("UPDATE users SET created_at=? WHERE id=?", CREATED, uuidToBytes(id));
        return new Fixture(id, access.issue(id, UUID.randomUUID()).token());
    }
    ResultActions read(Fixture f) throws Exception {
        return mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + f.token));
    }
    ResultActions patch(Fixture f, String body, String key) throws Exception {
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/users/me")
                .header("Authorization", "Bearer " + f.token).contentType("application/json").content(body);
        if (key != null) request.header("Idempotency-Key", key);
        return mvc.perform(request);
    }
    ResultActions patch(Fixture f, String body) throws Exception { return patch(f, body, UUID.randomUUID().toString()); }
    JsonNode parsed(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    long count(Fixture f) { return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE user_id=?", Long.class, uuidToBytes(f.id)); }
    void consent(Fixture f, String purpose, boolean granted, int second) {
        consents.save(new UserConsent(UUID.randomUUID(), f.id, purpose, "test-v1", granted, CREATED.plusSeconds(second)));
    }

    @Test void 본인만조회하고_null필드와_서울시각_마이크로초를_보존한다() throws Exception {
        var f = fixture(null); var other = fixture("other");
        var body = parsed(read(f).andExpect(status().isOk()).andExpect(header().exists("X-Request-Id"))
                .andExpect(header().string("Cache-Control", "no-store")));
        assertThat(body.path("data").propertyNames()).containsExactlyInAnyOrder("id", "nickname", "avatarUrl", "onboardingStatus", "comparisonConsent", "createdAt");
        assertThat(body.at("/data/id").asText()).isEqualTo(f.id.toString());
        assertThat(body.at("/data/nickname").isNull()).isTrue();
        assertThat(body.at("/data/avatarUrl").isNull()).isTrue();
        assertThat(body.at("/data/onboardingStatus").asText()).isEqualTo("REQUIRED");
        assertThat(body.at("/data/comparisonConsent").asBoolean()).isFalse();
        assertThat(OffsetDateTime.parse(body.at("/data/createdAt").asText()).toInstant()).isEqualTo(CREATED.toInstant(ZoneOffset.UTC));
        assertThat(body.at("/data/createdAt").asText()).endsWith(".123456+09:00");
        assertThat(body.at("/meta/serverTime").asText()).endsWith("+09:00");
        mvc.perform(get("/api/v1/users/me").param("userId", other.id.toString()).header("Authorization", "Bearer " + f.token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(f.id.toString()));
    }
    @Test void 온보딩과_비교동의는_실제DB상태와_최신목적별이력에서_파생한다() throws Exception {
        var f = fixture("ready");
        jdbc.update("UPDATE users SET onboarding_completed_at=? WHERE id=?", CREATED, uuidToBytes(f.id));
        consent(f, "FRIEND_COMPARISON", true, 1); consent(f, "OTHER", false, 9);
        read(f).andExpect(jsonPath("$.data.onboardingStatus").value("COMPLETED")).andExpect(jsonPath("$.data.comparisonConsent").value(true));
        consent(f, "FRIEND_COMPARISON", false, 2);
        read(f).andExpect(jsonPath("$.data.comparisonConsent").value(false));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_consents WHERE user_id=?", Long.class, uuidToBytes(f.id))).isEqualTo(3);
    }
    @Test void 닉네임만수정하면_아바타와_온보딩상태를_유지한다() throws Exception {
        var f = fixture("before"); users.updateProfile(f.id, "before", "https://example.com/a.png");
        patch(f, "{\"nickname\":\"after\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value("after"))
                .andExpect(jsonPath("$.data.avatarUrl").value("https://example.com/a.png")).andExpect(jsonPath("$.data.onboardingStatus").value("REQUIRED"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-01-02T12:04:05.123456+09:00"))
                .andExpect(jsonPath("$.meta.serverTime").value(org.hamcrest.Matchers.endsWith("+09:00")));
        assertThat(users.findById(f.id).orElseThrow().onboardingCompletedAt()).isNull();
    }
    @Test void 아바타만수정하면_닉네임을유지한다() throws Exception {
        var f = fixture("before");
        patch(f, "{\"avatarUrl\":\"https://example.com/b.png\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value("before"))
                .andExpect(jsonPath("$.data.avatarUrl").value("https://example.com/b.png"));
    }
    @Test void 명시적null은_아바타만_제거한다() throws Exception {
        var f = fixture("before"); users.updateProfile(f.id, "before", "https://example.com/a.png");
        var body = parsed(patch(f, "{\"avatarUrl\":null}").andExpect(status().isOk()));
        assertThat(body.at("/data/avatarUrl").isNull()).isTrue();
        assertThat(users.findById(f.id).orElseThrow().avatarUrl()).isNull();
        assertThat(body.at("/data/nickname").asText()).isEqualTo("before");
    }
    @Test void 닉네임16자를_허용하고_활성계정중복은_거절한다() throws Exception {
        String nickname = "가".repeat(16); var f = fixture("before"); var other = fixture(nickname);
        patch(f, json.writeValueAsString(Map.of("nickname", nickname))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NICKNAME_ALREADY_EXISTS"));
        assertThat(users.findById(other.id).orElseThrow().nickname()).isEqualTo(nickname);
        users.softDelete(other.id);
        patch(f, json.writeValueAsString(Map.of("nickname", nickname))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value(nickname));
        patch(f, json.writeValueAsString(Map.of("nickname", "😀".repeat(8)))).andExpect(status().isOk());
        patch(f, json.writeValueAsString(Map.of("nickname", "😀".repeat(9)))).andExpect(status().isUnprocessableEntity());
    }
    @Test void 빈PATCH_null닉네임_공백_17자_타입_읽기전용필드와_잘못된JSON은422다() throws Exception {
        var f = fixture("before");
        for (String body : List.of("{}", "null", "[]", "{", "", "{\"nickname\":null}", "{\"nickname\":\"  \"}",
                "{\"nickname\":\"12345678901234567\"}", "{\"nickname\":1}", "{\"avatarUrl\":false}",
                "{\"id\":\"" + UUID.randomUUID() + "\"}", "{\"comparisonConsent\":true}", "{\"onboardingStatus\":\"COMPLETED\"}",
                "{\"nickname\":\"after\",\"userId\":\"" + UUID.randomUUID() + "\"}")) {
            String field = body.startsWith("{\"nickname\":") && !body.contains("userId") ? "nickname" : body.startsWith("{\"avatarUrl\":") ? "avatarUrl" : "body";
            patch(f, body).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.fields[0]").value(field));
        }
        assertThat(users.findById(f.id).orElseThrow().nickname()).isEqualTo("before"); assertThat(count(f)).isZero();
    }
    @Test void 미인증_GET과_PATCH는401_Bearer다() throws Exception {
        mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/users/me").contentType("application/json").content("{\"nickname\":\"x\"}")
                .header("Idempotency-Key", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }
    @Test void 만료JWT의_GET과_PATCH는401이며_수정되지않는다() throws Exception {
        var f = fixture("before"); Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(properties.issuer()).audience(List.of(properties.audience())).subject(f.id.toString())
                .issuedAt(now.minusSeconds(1200)).expiresAt(now.minusSeconds(300)).claim("uid", f.id.toString())
                .claim("sid", UUID.randomUUID().toString()).claim("token_type", "access").build();
        String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        read(new Fixture(f.id, expired)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
        patch(new Fixture(f.id, expired), "{\"nickname\":\"after\"}").andExpect(status().isUnauthorized());
        assertThat(users.findById(f.id).orElseThrow().nickname()).isEqualTo("before"); assertThat(count(f)).isZero();
    }
    @Test void 존재하지않거나_삭제된사용자의_유효JWT는401이다() throws Exception {
        var f = fixture("before"); jdbc.update("UPDATE users SET deleted_at=? WHERE id=?", CREATED, uuidToBytes(f.id));
        var absent = new Fixture(UUID.randomUUID(), access.issue(UUID.randomUUID(), UUID.randomUUID()).token());
        for (var actor : List.of(f, absent)) {
            read(actor).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
            patch(actor, "{\"nickname\":\"after\"}").andExpect(status().isUnauthorized());
        }
        assertThat(count(f)).isZero();
    }
    @Test void 같은키재시도는_최초응답을재생하며_나중변경을_덮어쓰지않는다() throws Exception {
        var f = fixture("before"); String key = UUID.randomUUID().toString();
        var first = parsed(patch(f, "{\"nickname\":\"first\"}", key).andExpect(status().isOk()));
        patch(f, "{\"nickname\":\"later\"}").andExpect(status().isOk());
        var replay = patch(f, "{\"nickname\":\"first\"}", key).andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", first.at("/meta/requestId").asText()));
        assertThat(parsed(replay)).isEqualTo(first);
        assertThat(users.findById(f.id).orElseThrow().nickname()).isEqualTo("later"); assertThat(count(f)).isEqualTo(2);
    }
    @Test void 같은키_다른본문과_IfMatch는409다() throws Exception {
        var f = fixture("before"); String key = UUID.randomUUID().toString();
        patch(f, "{\"nickname\":\"first\"}", key).andExpect(status().isOk());
        patch(f, "{\"nickname\":\"second\"}", key).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/users/me").header("Authorization", "Bearer " + f.token)
                .header("Idempotency-Key", key).header("If-Match", "ignored").contentType("application/json").content("{\"nickname\":\"first\"}"))
                .andExpect(status().isConflict());
        assertThat(users.findById(f.id).orElseThrow().nickname()).isEqualTo("first");
    }
    @Test void 멱등키_누락과_잘못된UUID는422다() throws Exception {
        var f = fixture("before");
        for (String key : Arrays.asList(null, "bad", "1-1-1-1-1")) patch(f, "{\"nickname\":\"after\"}", key).andExpect(status().isUnprocessableEntity());
        assertThat(count(f)).isZero();
    }
    @Test void 신규요청의_지원하지않는_IfMatch는400이다() throws Exception {
        var f = fixture("before");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/users/me").header("Authorization", "Bearer " + f.token)
                .header("Idempotency-Key", UUID.randomUUID()).header("If-Match", "ignored").contentType("application/json").content("{\"nickname\":\"after\"}"))
                .andExpect(status().isBadRequest()); assertThat(count(f)).isZero();
    }
    @Test void 같은키도_사용자별로분리된다() throws Exception {
        var f = fixture("before"); var other = fixture("other"); String key = UUID.randomUUID().toString();
        patch(f, "{\"nickname\":\"first\"}", key).andExpect(status().isOk());
        patch(other, "{\"nickname\":\"second\"}", key).andExpect(status().isOk());
        assertThat(users.findById(f.id).orElseThrow().nickname()).isEqualTo("first");
        assertThat(users.findById(other.id).orElseThrow().nickname()).isEqualTo("second");
    }
    @Test void 수정SQL실패시_프로필과_멱등기록을_롤백한다() throws Exception {
        var f = fixture("before");
        doAnswer(i -> { i.callRealMethod(); throw new DataAccessResourceFailureException("PRIVATE_DB_DETAIL"); }).when(users).updateProfile(any(), any(), any());
        var response = patch(f, "{\"nickname\":\"after\"}").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.persistence").value("NOT_SAVED"));
        assertThat(response.andReturn().getResponse().getContentAsString()).doesNotContain("PRIVATE_DB_DETAIL");
        assertThat(users.findById(f.id).orElseThrow().nickname()).isEqualTo("before"); assertThat(count(f)).isZero();
    }
    @Test void 멱등완료실패시_프로필과_멱등기록을_롤백한다() throws Exception {
        var f = fixture("before");
        doAnswer(i -> { i.callRealMethod(); throw new DataAccessResourceFailureException("PRIVATE_DB_DETAIL"); }).when(records).complete(any(), anyString(), anyInt(), anyString(), anyString());
        patch(f, "{\"nickname\":\"after\"}").andExpect(status().isServiceUnavailable());
        assertThat(users.findById(f.id).orElseThrow().nickname()).isEqualTo("before"); assertThat(count(f)).isZero();
    }
    @Test void 조회DB오류도_내부정보없이503이다() throws Exception {
        var f = fixture("before"); doThrow(new DataAccessResourceFailureException("PRIVATE_DB_DETAIL")).when(users).findById(f.id);
        String body = read(f).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString(); assertThat(body).doesNotContain("PRIVATE_DB_DETAIL");
    }
    @Test void 동시_같은키는_수정한번과_동일응답을_보장한다() throws Exception {
        var f = fixture("before"); String key = UUID.randomUUID().toString(); var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { go.await(); return parsed(patch(f, "{\"nickname\":\"after\"}", key).andExpect(status().isOk())); });
            var b = pool.submit(() -> { go.await(); return parsed(patch(f, "{\"nickname\":\"after\"}", key).andExpect(status().isOk())); });
            go.countDown(); assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
        }
        assertThat(count(f)).isEqualTo(1); verify(users, times(1)).updateProfile(eq(f.id), eq("after"), isNull());
    }
    @Test void 동시_서로다른필드수정은_누락필드를_덮어쓰지않는다() throws Exception {
        var f = fixture("before"); var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var waiting = new CountDownLatch(1);
        doAnswer(i -> {
            if (Thread.currentThread().getName().equals("profile-contender")) waiting.countDown();
            var value = i.callRealMethod();
            if (Thread.currentThread().getName().equals("profile-holder")) { locked.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("lock timeout"); }
            return value;
        }).when(users).findByIdForUpdate(f.id);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { Thread.currentThread().setName("profile-holder"); return patch(f, "{\"nickname\":\"after\"}").andExpect(status().isOk()); });
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var b = pool.submit(() -> { Thread.currentThread().setName("profile-contender"); return patch(f, "{\"avatarUrl\":\"https://example.com/b.png\"}").andExpect(status().isOk()); });
                assertThat(waiting.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> b.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        var user = users.findById(f.id).orElseThrow(); assertThat(user.nickname()).isEqualTo("after"); assertThat(user.avatarUrl()).isEqualTo("https://example.com/b.png");
    }
    @Test void 만료된_프로필멱등키는_기존1일설정에따라_재사용한다() throws Exception {
        var f = fixture("before"); String key = UUID.randomUUID().toString();
        patch(f, "{\"nickname\":\"first\"}", key).andExpect(status().isOk());
        clock.set(clock.instant().plus(Duration.ofDays(1)));
        patch(f, "{\"nickname\":\"second\"}", key).andExpect(status().isOk());
        assertThat(count(f)).isEqualTo(1); assertThat(users.findById(f.id).orElseThrow().nickname()).isEqualTo("second");
    }
    @Test void 기존_UserCRUD조회경로는_유지된다() throws Exception {
        var f = fixture("before");
        mvc.perform(get("/api/v1/user/" + f.id).header("Authorization", "Bearer " + f.token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(f.id.toString())).andExpect(jsonPath("$.nickname").value("before"));
    }
}
