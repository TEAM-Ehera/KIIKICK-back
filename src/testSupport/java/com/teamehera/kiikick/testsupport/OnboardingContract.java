package com.teamehera.kiikick.testsupport;

import com.teamehera.kiikick.auth.email.VerificationEmailSender;
import com.teamehera.kiikick.auth.security.*;
import com.teamehera.kiikick.global.idempotency.repository.IdempotencyRecordRepository;
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

public abstract class OnboardingContract {
    static final String PATH = "/api/v1/users/me/onboarding";
    static final LocalDateTime CREATED = LocalDateTime.parse("2026-01-02T03:04:05.123456");
    static final String EMPTY = "{\"nickname\":\"준킥\",\"consents\":[]}";
    @Autowired MockMvc mvc;
    @Autowired AccessTokenService access;
    @Autowired JwtEncoder encoder;
    @Autowired AuthSecurityProperties properties;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired RefreshRotationContract.TestClock clock;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean UserConsentRepository consents;
    @MockitoSpyBean IdempotencyRecordRepository records;
    @MockitoBean VerificationEmailSender email;
    final List<UUID> owned = new ArrayList<>();
    record Fixture(UUID id, String token) {}

    @BeforeEach void setup() {
        reset(users, consents, records);
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
    @AfterEach void cleanup() {
        // 이 테스트가 생성한 UUID만 정리하고 기존 사용자·테이블은 변경하지 않는다.
        reset(users, consents, records);
        for (UUID id : owned) {
            jdbc.update("DELETE FROM idempotency_records WHERE user_id=?", uuidToBytes(id));
            jdbc.update("DELETE FROM user_consents WHERE user_id=?", uuidToBytes(id));
            jdbc.update("DELETE FROM users WHERE id=?", uuidToBytes(id));
        }
    }
    Fixture fixture() {
        UUID id = UUID.randomUUID(); owned.add(id); users.save(id, owned.size() == 1 ? "before" : "before" + owned.size());
        jdbc.update("UPDATE users SET created_at=? WHERE id=?", CREATED, uuidToBytes(id));
        return new Fixture(id, access.issue(id, UUID.randomUUID()).token());
    }
    String body(String nickname, String... choices) {
        return "{\"nickname\":\"" + nickname + "\",\"consents\":[" + String.join(",", choices) + "]}";
    }
    String choice(String purpose, String version, boolean granted) {
        return "{\"purpose\":\"" + purpose + "\",\"policyVersion\":\"" + version + "\",\"granted\":" + granted + "}";
    }
    ResultActions request(Fixture f, String body, String key) throws Exception {
        var req = put(PATH).contentType("application/json").content(body);
        if (f != null) req.header("Authorization", "Bearer " + f.token);
        if (key != null) req.header("Idempotency-Key", key);
        return mvc.perform(req);
    }
    ResultActions request(Fixture f, String body) throws Exception { return request(f, body, UUID.randomUUID().toString()); }
    JsonNode parsed(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    long count(String table, Fixture f) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Long.class, uuidToBytes(f.id)); }
    void unchanged(Fixture f) {
        var user = users.findById(f.id).orElseThrow();
        assertThat(user.nickname()).isEqualTo(owned.indexOf(f.id) == 0 ? "before" : "before" + (owned.indexOf(f.id) + 1)); assertThat(user.onboardingCompletedAt()).isNull();
        assertThat(count("user_consents", f)).isZero(); assertThat(count("idempotency_records", f)).isZero();
    }
    @Test void 두목적동의와_본인응답_완료시각_마이크로초와_서울오프셋을_보존한다() throws Exception {
        var f = fixture(); var other = fixture(); users.updateProfile(f.id, "before", "https://example.com/avatar.png");
        var response = request(f, body("준킥", choice("GPS_COLLECTION", "1.0", true), choice("HEALTH_DATA", "1.0", true)))
                .andExpect(status().isOk()).andExpect(header().exists("X-Request-Id")).andExpect(header().string("Cache-Control", "no-store"));
        var doc = parsed(response); var data = doc.path("data");
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("id", "nickname", "avatarUrl", "onboardingStatus", "comparisonConsent", "createdAt");
        assertThat(data.path("id").asText()).isEqualTo(f.id.toString());
        assertThat(data.path("nickname").asText()).isEqualTo("준킥");
        assertThat(data.path("avatarUrl").asText()).isEqualTo("https://example.com/avatar.png");
        assertThat(data.path("onboardingStatus").asText()).isEqualTo("COMPLETED");
        assertThat(data.path("comparisonConsent").asBoolean()).isFalse();
        assertThat(data.path("createdAt").asText()).isEqualTo("2026-01-02T12:04:05.123456+09:00");
        assertThat(doc.at("/meta/serverTime").asText()).endsWith("+09:00");
        assertThat(users.findById(f.id).orElseThrow().onboardingCompletedAt()).isEqualTo(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        for (String purpose : List.of("GPS_COLLECTION", "HEALTH_DATA")) {
            var consent = consents.findLatest(f.id, purpose).orElseThrow();
            assertThat(consent.policyVersion()).isEqualTo("1.0"); assertThat(consent.granted()).isTrue();
        }
        LocalDateTime recorded = jdbc.queryForObject("SELECT recorded_at FROM user_consents WHERE user_id=? LIMIT 1",
                (rs, n) -> rs.getObject(1, LocalDateTime.class), uuidToBytes(f.id));
        assertThat(recorded).isEqualTo(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        unchanged(other);
    }
    @Test void 선택동의를_모두거부해도_완료하고_false이력을_저장한다() throws Exception {
        var f = fixture(); request(f, body("준킥", choice("GPS_COLLECTION", "1.0", false), choice("HEALTH_DATA", "1.0", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.onboardingStatus").value("COMPLETED"));
        assertThat(consents.findLatestByUserId(f.id)).hasSize(2).allMatch(c -> !c.granted());
    }
    @Test void 빈배열은_완료하되_이력을_생성하지않는다() throws Exception {
        var f = fixture(); request(f, EMPTY).andExpect(status().isOk());
        assertThat(count("user_consents", f)).isZero(); assertThat(count("idempotency_records", f)).isEqualTo(1);
    }
    @Test void 일부제출은_제출한목적만저장한다() throws Exception {
        var f = fixture(); request(f, body("준킥", choice("GPS_COLLECTION", "1.0", true))).andExpect(status().isOk());
        assertThat(count("user_consents", f)).isEqualTo(1); assertThat(consents.findLatest(f.id, "HEALTH_DATA")).isEmpty();
    }
    @Test void 닉네임16자를허용하고_중복을거절하며_원문을유지한다() throws Exception {
        var a = fixture(); var b = fixture(); String nickname = "가".repeat(16);
        request(a, body(nickname)).andExpect(status().isOk()); request(b, body(nickname)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NICKNAME_ALREADY_EXISTS"));
        var c = fixture(); request(c, body(" 준 ")).andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value(" 준 "));
    }
    @Test void 잘못된JSON_누락_null_타입_읽기전용필드를422로거부한다() throws Exception {
        var f = fixture();
        for (String body : List.of("", "{", "null", "[]", "{}", "{\"nickname\":\"준\"}", "{\"consents\":[]}",
                "{\"nickname\":null,\"consents\":[]}", "{\"nickname\":7,\"consents\":[]}",
                "{\"nickname\":\"  \",\"consents\":[]}", body("가".repeat(17)),
                "{\"nickname\":\"준\",\"consents\":null}", "{\"nickname\":\"준\",\"consents\":{}}",
                body("준", "null"), body("준", "{}"), body("준", "{\"purpose\":\"GPS_COLLECTION\",\"policyVersion\":\"1.0\",\"granted\":null}"),
                body("준", "{\"purpose\":\"GPS_COLLECTION\",\"policyVersion\":1.0,\"granted\":true}"),
                body("준", "{\"purpose\":\"GPS_COLLECTION\",\"policyVersion\":\"1.0\",\"granted\":\"true\"}"),
                "{\"nickname\":\"준\",\"consents\":[],\"userId\":\"" + UUID.randomUUID() + "\"}")) {
            request(f, body).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
            unchanged(f);
        }
    }
    @Test void 미지원목적과_버전은_유효항목과함께보내도_부분저장하지않는다() throws Exception {
        var f = fixture();
        for (String invalid : List.of(choice("FRIEND_COMPARISON", "1.0", true), choice("FRIEND_DISCOVERY", "1.0", true),
                choice("GPS_COLLECTION", "2.0", true), choice("HEALTH_DATA", "2026-11-01", true))) {
            String other = invalid.contains("HEALTH_DATA") ? "GPS_COLLECTION" : "HEALTH_DATA";
            request(f, body("준", choice(other, "1.0", true), invalid)).andExpect(status().isUnprocessableEntity()); unchanged(f);
        }
    }
    @Test void 같은목적의_중복입력은422다() throws Exception {
        var f = fixture(); request(f, body("준", choice("GPS_COLLECTION", "1.0", true), choice("GPS_COLLECTION", "1.0", false)))
                .andExpect(status().isUnprocessableEntity()); unchanged(f);
    }
    @Test void 멱등키_누락과_잘못된UUID는422다() throws Exception {
        var f = fixture();
        for (String key : Arrays.asList(null, "bad", "1-1-1-1-1")) request(f, EMPTY, key).andExpect(status().isUnprocessableEntity());
        unchanged(f);
    }
    @Test void 같은키와본문은_이력추가없이_최초응답을재생한다() throws Exception {
        var f = fixture(); String key = UUID.randomUUID().toString(); String body = body("준", choice("GPS_COLLECTION", "1.0", true));
        var first = request(f, body, key).andExpect(status().isOk()).andReturn().getResponse();
        clock.set(clock.instant().plusSeconds(3));
        var replay = request(f, body, key).andExpect(status().isOk()).andReturn().getResponse();
        // MySQL JSON 컬럼은 공백·키 순서를 정규화하므로 값과 최초 메타의 동일성을 검증한다.
        assertThat(json.readTree(replay.getContentAsString())).isEqualTo(json.readTree(first.getContentAsString()));
        assertThat(replay.getHeader("X-Request-Id")).isEqualTo(first.getHeader("X-Request-Id"));
        assertThat(count("user_consents", f)).isEqualTo(1); assertThat(count("idempotency_records", f)).isEqualTo(1);
    }
    @Test void 완료후_새키요청은409이고_기존데이터를유지한다() throws Exception {
        var f = fixture(); request(f, EMPTY).andExpect(status().isOk());
        request(f, body("변경", choice("HEALTH_DATA", "1.0", true))).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
        assertThat(users.findById(f.id).orElseThrow().nickname()).isEqualTo("준킥"); assertThat(count("user_consents", f)).isZero();
        assertThat(count("idempotency_records", f)).isEqualTo(1);
    }
    @Test void 같은키의_다른본문은409다() throws Exception {
        var f = fixture(); String key = UUID.randomUUID().toString(); request(f, EMPTY, key).andExpect(status().isOk());
        request(f, body("다름"), key).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    }
    @Test void 다른사용자는_같은멱등키를_독립적으로사용한다() throws Exception {
        var a = fixture(); var b = fixture(); String key = UUID.randomUUID().toString();
        request(a, EMPTY, key).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(a.id.toString()));
        request(b, body("다른준킥"), key).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(b.id.toString()));
    }
    @Test void 미인증과_만료JWT는401이며_저장하지않는다() throws Exception {
        var f = fixture(); request(null, EMPTY).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(properties.issuer()).audience(List.of(properties.audience())).subject(f.id.toString())
                .issuedAt(now.minusSeconds(1200)).expiresAt(now.minusSeconds(300)).claim("uid", f.id.toString())
                .claim("sid", UUID.randomUUID().toString()).claim("token_type", "access").build();
        String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        request(new Fixture(f.id, expired), EMPTY).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED")); unchanged(f);
    }
    @Test void 미존재와_삭제된사용자는401다() throws Exception {
        var f = fixture(); jdbc.update("UPDATE users SET deleted_at=? WHERE id=?", CREATED, uuidToBytes(f.id));
        request(f, EMPTY).andExpect(status().isUnauthorized());
        UUID absent = UUID.randomUUID(); request(new Fixture(absent, access.issue(absent, UUID.randomUUID()).token()), EMPTY).andExpect(status().isUnauthorized());
        assertThat(count("user_consents", f)).isZero(); assertThat(count("idempotency_records", f)).isZero();
    }
    @Test void 신규요청의_IfMatch는400다() throws Exception {
        var f = fixture(); mvc.perform(put(PATH).header("Authorization", "Bearer " + f.token).header("Idempotency-Key", UUID.randomUUID())
                .header("If-Match", "ignored").contentType("application/json").content(EMPTY)).andExpect(status().isBadRequest()); unchanged(f);
    }
    @Test void 사용자저장후_실패하면_모든변경을롤백한다() throws Exception {
        var f = fixture(); doAnswer(i -> { i.callRealMethod(); throw new DataAccessResourceFailureException("PRIVATE_DB_DETAIL"); })
                .when(users).completeOnboarding(eq(f.id), anyString(), any());
        request(f, EMPTY).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.persistence").value("NOT_SAVED")); unchanged(f);
    }
    @Test void 두번째동의저장후_실패하면_첫동의와_사용자와_멱등도롤백한다() throws Exception {
        var f = fixture(); doAnswer(i -> { i.callRealMethod(); if (i.<com.teamehera.kiikick.user.domain.UserConsent>getArgument(0).purpose().equals("HEALTH_DATA"))
            throw new DataAccessResourceFailureException("PRIVATE_DB_DETAIL"); return null; }).when(consents).save(any());
        var response = request(f, body("준", choice("GPS_COLLECTION", "1.0", true), choice("HEALTH_DATA", "1.0", true)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.retryable").value(true));
        assertThat(response.andReturn().getResponse().getContentAsString()).doesNotContain("PRIVATE_DB_DETAIL"); unchanged(f);
    }
    @Test void 멱등응답저장후_실패하면_사용자와_동의도롤백한다() throws Exception {
        var f = fixture(); doAnswer(i -> { i.callRealMethod(); throw new DataAccessResourceFailureException("PRIVATE_DB_DETAIL"); })
                .when(records).complete(any(), anyString(), anyInt(), anyString(), anyString());
        request(f, body("준", choice("GPS_COLLECTION", "1.0", true))).andExpect(status().isServiceUnavailable()); unchanged(f);
    }
    @Test void 동시같은키는_DB잠금대기후_동일응답을받는다() throws Exception {
        var f = fixture(); String key = UUID.randomUUID().toString();
        var locked = new CountDownLatch(1); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(i -> {
            boolean holder = Thread.currentThread().getName().equals("onboarding-holder");
            if (!holder) entered.countDown(); var user = i.callRealMethod();
            if (holder) { locked.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("lock timeout"); }
            return user;
        }).when(users).findByIdForUpdate(f.id);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { Thread.currentThread().setName("onboarding-holder"); return parsed(request(f, EMPTY, key).andExpect(status().isOk())); });
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var b = pool.submit(() -> { Thread.currentThread().setName("onboarding-contender"); return parsed(request(f, EMPTY, key).andExpect(status().isOk())); });
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> b.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
        assertThat(count("idempotency_records", f)).isEqualTo(1); verify(users, times(1)).completeOnboarding(eq(f.id), anyString(), any());
    }
    @Test void 동시다른키는_한번만완료하고_동의가중복되지않는다() throws Exception {
        var f = fixture(); var start = new CountDownLatch(1); String body = body("준", choice("GPS_COLLECTION", "1.0", true));
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> call = () -> { start.await(); return request(f, body).andReturn().getResponse().getStatus(); };
            var a = pool.submit(call); var b = pool.submit(call); start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(count("user_consents", f)).isEqualTo(1); assertThat(count("idempotency_records", f)).isEqualTo(1);
    }
    @Test void Swagger에_보호API_요청객체_성공과오류스키마가_표시된다() throws Exception {
        var doc = parsed(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()));
        var operation = doc.path("paths").path(PATH).path("put");
        assertThat(operation.path("operationId").asText()).isEqualTo("completeOnboarding");
        assertThat(operation.path("summary").asText()).isEqualTo("온보딩 완료");
        assertThat(operation.path("security").toString()).contains("bearerAuth");
        assertThat(operation.path("parameters").toString()).contains("Idempotency-Key");
        var input = operation.at("/requestBody/content/application~1json/schema");
        if (input.has("$ref")) input = doc.at(input.path("$ref").asText().substring(1));
        assertThat(input.path("properties").has("nickname")).isTrue(); assertThat(input.path("properties").has("consents")).isTrue();
        for (String code : List.of("200", "400", "401", "409", "422", "503"))
            assertThat(operation.path("responses").path(code).at("/content/application~1json/schema").isMissingNode()).isFalse();
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
