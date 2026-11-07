package com.teamehera.kiikick.testsupport;

import com.teamehera.kiikick.auth.email.VerificationEmailSender;
import com.teamehera.kiikick.auth.security.*;
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
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public abstract class UserConsentReadContract {
    static final String PATH = "/api/v1/users/me/consents";
    static final LocalDateTime RECORDED = LocalDateTime.parse("2026-01-02T03:04:05.123456");
    @Autowired MockMvc mvc;
    @Autowired AccessTokenService access;
    @Autowired JwtEncoder encoder;
    @Autowired AuthSecurityProperties properties;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RefreshRotationContract.TestClock clock;
    @MockitoSpyBean UserRepository users;
    @MockitoSpyBean UserConsentRepository consents;
    @MockitoBean VerificationEmailSender email;
    final List<UUID> owned = new ArrayList<>();
    record Fixture(UUID id, String token) {}

    @BeforeEach void setup() {
        reset(users, consents);
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
    }
    @AfterEach void cleanup() {
        reset(users, consents);
        // 테스트가 만든 사용자 UUID에 한해서만 fixture를 정리한다.
        for (UUID id : owned) {
            jdbc.update("DELETE FROM user_consents WHERE user_id=?", uuidToBytes(id));
            jdbc.update("DELETE FROM users WHERE id=?", uuidToBytes(id));
        }
    }
    Fixture fixture() {
        UUID id = UUID.randomUUID(); owned.add(id); users.save(id, "consent-read");
        return new Fixture(id, access.issue(id, UUID.randomUUID()).token());
    }
    void consent(Fixture f, UUID id, String purpose, String version, boolean granted, LocalDateTime at) {
        consents.save(new UserConsent(id, f.id, purpose, version, granted, at));
    }
    void consent(Fixture f, String purpose, boolean granted, int seconds) {
        consent(f, UUID.randomUUID(), purpose, "1.0", granted, RECORDED.plusSeconds(seconds));
    }
    ResultActions read(Fixture f) throws Exception {
        var request = get(PATH); if (f != null) request.header("Authorization", "Bearer " + f.token);
        return mvc.perform(request);
    }
    JsonNode parsed(ResultActions result) throws Exception { return json.readTree(result.andReturn().getResponse().getContentAsString()); }
    JsonNode data(Fixture f) throws Exception { return parsed(read(f).andExpect(status().isOk())).path("data"); }

    @Test void 이력이없으면_빈배열과_공통메타_보안헤더를반환한다() throws Exception {
        var f = fixture();
        var response = read(f).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        var body = parsed(response); assertThat(body.path("data").isArray()).isTrue(); assertThat(body.path("data").isEmpty()).isTrue();
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("data", "meta");
        assertThat(body.at("/meta/requestId").asText()).isEqualTo(response.andReturn().getResponse().getHeader("X-Request-Id"));
        assertThat(body.at("/meta/serverTime").asText()).endsWith("+09:00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_consents WHERE user_id=?", Long.class, uuidToBytes(f.id))).isZero();
    }
    @Test void 목적별_가장최근기록의_거절도_그대로반환한다() throws Exception {
        var f = fixture();
        consent(f, "GPS_COLLECTION", true, 0); consent(f, "GPS_COLLECTION", false, 1);
        var rows = data(f); assertThat(rows.size()).isEqualTo(1); assertThat(rows.get(0).path("granted").asBoolean()).isFalse();
        assertThat(rows.get(0).path("recordedAt").asText()).isEqualTo("2026-01-02T12:04:06.123456+09:00");
    }
    @Test void 동일시각은_ID가큰이력을선택한다() throws Exception {
        var f = fixture(); long prefix = UUID.randomUUID().getMostSignificantBits();
        consent(f, new UUID(prefix, 2), "GPS_COLLECTION", "1.0", false, RECORDED);
        consent(f, new UUID(prefix, 1), "GPS_COLLECTION", "1.0", true, RECORDED);
        var rows = data(f); assertThat(rows.size()).isEqualTo(1); assertThat(rows.get(0).path("granted").asBoolean()).isFalse();
    }
    @Test void ID보다_기록시각이먼저최신값을결정한다() throws Exception {
        var f = fixture(); long prefix = UUID.randomUUID().getMostSignificantBits();
        consent(f, new UUID(prefix, 2), "GPS_COLLECTION", "1.0", true, RECORDED);
        consent(f, new UUID(prefix, 1), "GPS_COLLECTION", "1.0", false, RECORDED.plusSeconds(1));
        assertThat(data(f).get(0).path("granted").asBoolean()).isFalse();
    }
    @Test void 목적을오름차순으로정렬하고_응답은네필드뿐이다() throws Exception {
        var f = fixture(); consent(f, "HEALTH_DATA", false, 1); consent(f, "GPS_COLLECTION", true, 0);
        var rows = data(f); assertThat(rows.size()).isEqualTo(2);
        assertThat(rows.get(0).path("purpose").asText()).isEqualTo("GPS_COLLECTION");
        assertThat(rows.get(1).path("purpose").asText()).isEqualTo("HEALTH_DATA");
        for (var row : rows) assertThat(row.propertyNames()).containsExactlyInAnyOrder("purpose", "policyVersion", "granted", "recordedAt");
    }
    @Test void 미제출목적의_false행을만들지않는다() throws Exception {
        var f = fixture(); consent(f, "GPS_COLLECTION", true, 0);
        var rows = data(f); assertThat(rows.size()).isEqualTo(1); assertThat(rows.get(0).path("purpose").asText()).isEqualTo("GPS_COLLECTION");
        assertThat(consents.findLatest(f.id, "HEALTH_DATA")).isEmpty();
    }
    @Test void 비교동의와_미지원목적을보존하면서_응답에서만제외한다() throws Exception {
        var f = fixture(); consent(f, "FRIEND_COMPARISON", true, 0); consent(f, "FUTURE_PURPOSE", false, 1); consent(f, "HEALTH_DATA", true, 2);
        var rows = data(f); assertThat(rows.size()).isEqualTo(1); assertThat(rows.get(0).path("purpose").asText()).isEqualTo("HEALTH_DATA");
        assertThat(consents.findLatestByUserId(f.id)).hasSize(3); assertThat(consents.findLatest(f.id, "FRIEND_COMPARISON").orElseThrow().granted()).isTrue();
    }
    @Test void 저장된실제버전을_현재버전으로바꾸거나_필터링하지않는다() throws Exception {
        var f = fixture(); consent(f, UUID.randomUUID(), "GPS_COLLECTION", "0.9", true, RECORDED);
        assertThat(data(f).get(0).path("policyVersion").asText()).isEqualTo("0.9");
    }
    @Test void 타인ID쿼리와무관하게_Principal의본인이력만반환한다() throws Exception {
        var me = fixture(); var other = fixture(); consent(me, "GPS_COLLECTION", false, 0); consent(other, "HEALTH_DATA", true, 1);
        var body = parsed(mvc.perform(get(PATH).param("userId", other.id.toString()).header("Authorization", "Bearer " + me.token)).andExpect(status().isOk()));
        assertThat(body.path("data").size()).isEqualTo(1); assertThat(body.at("/data/0/purpose").asText()).isEqualTo("GPS_COLLECTION");
        verify(consents, never()).findLatestByUserId(other.id);
    }
    @Test void 미인증은401_Bearer다() throws Exception {
        read(null).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }
    @Test void 만료JWT는401_TOKEN_EXPIRED다() throws Exception {
        var f = fixture(); Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(properties.issuer()).audience(List.of(properties.audience())).subject(f.id.toString())
                .issuedAt(now.minusSeconds(1200)).expiresAt(now.minusSeconds(300)).claim("uid", f.id.toString())
                .claim("sid", UUID.randomUUID().toString()).claim("token_type", "access").build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        read(new Fixture(f.id, token)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
        verify(consents, never()).findLatestByUserId(any());
    }
    @Test void 탈퇴요청_삭제_미존재사용자는401다() throws Exception {
        var pending = fixture(); var deleted = fixture();
        users.markDeletionRequested(pending.id, RECORDED); users.softDelete(deleted.id);
        UUID missing = UUID.randomUUID();
        for (var f : List.of(pending, deleted, new Fixture(missing, access.issue(missing, UUID.randomUUID()).token()))) {
            read(f).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        }
        verify(consents, never()).findLatestByUserId(any());
    }
    @Test void 사용자조회DB오류는_내부정보없이503다() throws Exception {
        var f = fixture(); doThrow(new DataAccessResourceFailureException("PRIVATE_SQL_DETAIL")).when(users).findById(f.id);
        var response = read(f).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.retryable").value(true)).andExpect(jsonPath("$.error.persistence").value("NOT_SAVED"));
        assertThat(response.andReturn().getResponse().getContentAsString()).doesNotContain("PRIVATE_SQL_DETAIL", f.token);
    }
    @Test void 동의조회DB오류는_내부정보없이503다() throws Exception {
        var f = fixture(); doThrow(new DataAccessResourceFailureException("PRIVATE_SQL_DETAIL")).when(consents).findLatestByUserId(f.id);
        var response = read(f).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
        assertThat(response.andReturn().getResponse().getContentAsString()).doesNotContain("PRIVATE_SQL_DETAIL", f.token);
    }
    @Test void UTC기록시각을_같은Instant와_마이크로초를보존해_서울로반환한다() throws Exception {
        var f = fixture(); consent(f, "GPS_COLLECTION", true, 0);
        String actual = data(f).get(0).path("recordedAt").asText();
        assertThat(actual).isEqualTo("2026-01-02T12:04:05.123456+09:00");
        assertThat(OffsetDateTime.parse(actual).toInstant()).isEqualTo(RECORDED.toInstant(ZoneOffset.UTC));
        assertThat(consents.findLatest(f.id, "GPS_COLLECTION").orElseThrow().recordedAt()).isEqualTo(RECORDED);
    }
    @Test void 반복GET은_사용자와모든이력_멱등기록을변경하지않는다() throws Exception {
        var f = fixture(); consent(f, "GPS_COLLECTION", true, 0); consent(f, "GPS_COLLECTION", false, 1); consent(f, "FRIEND_COMPARISON", true, 2);
        var user = users.findById(f.id).orElseThrow();
        String query = "SELECT * FROM user_consents WHERE user_id=? ORDER BY id";
        org.springframework.jdbc.core.RowMapper<UserConsent> mapper = (rs, n) -> new UserConsent(
                bytesToUuid(rs.getBytes("id")), bytesToUuid(rs.getBytes("user_id")), rs.getString("purpose"),
                rs.getString("policy_version"), rs.getBoolean("granted"), rs.getObject("recorded_at", LocalDateTime.class));
        var before = jdbc.query(query, mapper, uuidToBytes(f.id));
        Long keys = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE user_id=?", Long.class, uuidToBytes(f.id));
        clearInvocations(users, consents);
        read(f).andExpect(status().isOk()); read(f).andExpect(status().isOk());
        assertThat(users.findById(f.id).orElseThrow()).isEqualTo(user);
        assertThat(jdbc.query(query, mapper, uuidToBytes(f.id))).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE user_id=?", Long.class, uuidToBytes(f.id))).isEqualTo(keys);
        verify(consents, never()).save(any()); verify(users, never()).save(any(), any());
        verify(users, never()).completeOnboarding(any(), anyString(), any());
    }
    @Test void Swagger_GET만있고_Bearer와_배열스키마를표시한다() throws Exception {
        var doc = parsed(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()));
        var path = doc.path("paths").path(PATH); var operation = path.path("get");
        assertThat(operation.path("operationId").asText()).isEqualTo("getMyConsents");
        assertThat(operation.path("security").toString()).contains("bearerAuth");
        assertThat(operation.has("requestBody")).isFalse(); assertThat(operation.path("parameters").toString()).doesNotContain("Idempotency-Key", "If-Match");
        assertThat(path.has("put")).isFalse(); assertThat(doc.path("paths").has(PATH + "/{purpose}")).isFalse();
        var schema = operation.at("/responses/200/content/application~1json/schema");
        if (schema.has("$ref")) schema = doc.at(schema.path("$ref").asText().substring(1));
        assertThat(schema.at("/properties/data/type").asText()).isEqualTo("array");
        var item = schema.at("/properties/data/items"); if (item.has("$ref")) item = doc.at(item.path("$ref").asText().substring(1));
        assertThat(item.path("properties").propertyNames()).containsExactlyInAnyOrder("purpose", "policyVersion", "granted", "recordedAt");
        for (String code : List.of("401", "503")) assertThat(operation.path("responses").path(code).at("/content/application~1json/schema").isMissingNode()).isFalse();
    }
}
