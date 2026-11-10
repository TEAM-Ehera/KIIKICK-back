package com.teamehera.kiikick.user;

import com.teamehera.kiikick.auth.security.AccessTokenService;
import com.teamehera.kiikick.global.exception.DuplicateNicknameException;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
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

import static com.teamehera.kiikick.auth.security.TestJwtRequests.bearer;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class NicknameUniquenessTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AccessTokenService access;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:nickname_unique;MODE=MySQL;DB_CLOSE_DELAY=-1");
    }

    @Test
    void profileConflictRollsBackNicknameAvatarAndIdempotencyAndCanRetryAfterDeletion() throws Exception {
        String nickname = name();
        UUID owner = user(nickname);
        UUID actor = user(name());
        String previous = users.findById(actor).orElseThrow().nickname();
        UUID key = UUID.randomUUID();
        String body = json.writeValueAsString(Map.of("nickname", nickname, "avatarUrl", "https://example.com/a.png"));
        mvc.perform(profile(actor, body, key)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NICKNAME_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.error.message").value("이미 사용 중인 닉네임입니다."))
                .andExpect(jsonPath("$.error.details.fields[0]").value("nickname"))
                .andExpect(jsonPath("$.error.persistence").value("NOT_SAVED"));
        assertThat(users.findById(actor).orElseThrow().nickname()).isEqualTo(previous);
        assertThat(users.findById(actor).orElseThrow().avatarUrl()).isNull();
        assertThat(count("idempotency_records", actor)).isZero();
        users.softDelete(owner);
        var success = mvc.perform(profile(actor, body, key)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value(nickname)).andReturn().getResponse().getContentAsString();
        mvc.perform(profile(actor, body, key)).andExpect(status().isOk()).andExpect(content().string(success));
    }

    @Test
    void keepingOwnNicknameAndAvatarOnlyPatchAreAllowed() throws Exception {
        String nickname = name(); UUID actor = user(nickname);
        mvc.perform(profile(actor, json.writeValueAsString(Map.of("nickname", nickname)), UUID.randomUUID()))
                .andExpect(status().isOk());
        mvc.perform(profile(actor, "{\"avatarUrl\":null}", UUID.randomUUID())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value(nickname));
    }

    @Test
    void onboardingConflictRollsBackConsentAndCompletionAndReusesDeletedNickname() throws Exception {
        String nickname = name(); UUID owner = user(nickname); UUID actor = user(null); UUID key = UUID.randomUUID();
        String body = json.writeValueAsString(Map.of("nickname", nickname, "consents", java.util.List.of(
                Map.of("purpose", "GPS_COLLECTION", "policyVersion", "1.0", "granted", true))));
        mvc.perform(onboard(actor, body, key)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NICKNAME_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.error.message").value("이미 사용 중인 닉네임입니다."));
        assertThat(users.findById(actor).orElseThrow().onboardingCompletedAt()).isNull();
        assertThat(users.findById(actor).orElseThrow().nickname()).isNull();
        assertThat(count("user_consents", actor)).isZero();
        assertThat(count("idempotency_records", actor)).isZero();
        users.softDelete(owner);
        mvc.perform(onboard(actor, body, key)).andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value(nickname));
    }

    @Test
    void nullableNamesAndMultipleDeletedOwnersAreAllowedButActiveDuplicatesAreNot() {
        user(null); user(null);
        String nickname = name();
        UUID first = user(nickname);
        assertThatThrownBy(() -> user(nickname)).isInstanceOf(DuplicateNicknameException.class);
        users.softDelete(first);
        UUID second = user(nickname); users.softDelete(second);
        UUID third = user(nickname);
        assertThat(users.findById(third)).isPresent();
        assertThatThrownBy(() -> user(nickname)).isInstanceOf(DuplicateNicknameException.class);
    }

    @Test
    void legacyNicknameUpdatesAreProtectedAndOtherUniqueKeysKeepTheirOriginalException() {
        String nickname = name(); UUID owner = user(nickname); UUID other = user(name());
        assertThatThrownBy(() -> users.updateNickname(other, nickname)).isInstanceOf(DuplicateNicknameException.class);
        assertThatThrownBy(() -> users.save(owner, null)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void nicknameComparisonKeepsCaseAndSpacesWithoutNormalization() {
        String nickname = name();
        user(nickname); user(nickname.toUpperCase(java.util.Locale.ROOT)); user(nickname + " ");
        assertThatThrownBy(() -> user(nickname)).isInstanceOf(DuplicateNicknameException.class);
    }

    @Test
    void simultaneousProfileAndOnboardingClaimsHaveExactlyOneWinner() throws Exception {
        String nickname = name(); UUID first = user(null); UUID second = user(null);
        var ready = new CountDownLatch(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                ready.countDown(); assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                return mvc.perform(profile(first, json.writeValueAsString(Map.of("nickname", nickname)), UUID.randomUUID()))
                        .andReturn().getResponse().getStatus();
            });
            var b = pool.submit(() -> {
                ready.countDown(); assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                return mvc.perform(onboard(second, json.writeValueAsString(Map.of("nickname", nickname, "consents", java.util.List.of())), UUID.randomUUID()))
                        .andReturn().getResponse().getStatus();
            });
            assertThat(java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE active_nickname = ?", Integer.class,
                nickname.getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(1);
    }

    private UUID user(String nickname) { UUID id = UUID.randomUUID(); users.save(id, nickname); return id; }
    private String name() { return "nick" + UUID.randomUUID().toString().substring(0, 8); }
    private long count(String table, UUID actor) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Long.class, uuidToBytes(actor));
    }
    private MockHttpServletRequestBuilder profile(UUID actor, String body, UUID key) {
        return patch("/api/v1/users/me").with(bearer(access, actor)).header("Idempotency-Key", key)
                .contentType("application/json").content(body);
    }
    private MockHttpServletRequestBuilder onboard(UUID actor, String body, UUID key) {
        return put("/api/v1/users/me/onboarding").with(bearer(access, actor)).header("Idempotency-Key", key)
                .contentType("application/json").content(body);
    }
}
