package com.teamehera.kiikick.testsupport;

import com.teamehera.kiikick.auth.domain.AuthSession;
import com.teamehera.kiikick.auth.domain.EmailCredential;
import com.teamehera.kiikick.auth.domain.EmailVerificationFlow;
import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import com.teamehera.kiikick.auth.repository.EmailCredentialRepository;
import com.teamehera.kiikick.auth.repository.EmailSendRateLimitRepository;
import com.teamehera.kiikick.auth.repository.EmailVerificationFlowRepository;
import com.teamehera.kiikick.auth.security.AccessTokenService;
import com.teamehera.kiikick.auth.security.AuthHashEncoder;
import com.teamehera.kiikick.auth.security.RefreshTokenIssuer;
import com.teamehera.kiikick.auth.service.LoginService;
import com.teamehera.kiikick.auth.service.RefreshService;
import com.teamehera.kiikick.auth.service.EmailVerificationService;
import com.teamehera.kiikick.auth.service.SignupApiException;
import com.teamehera.kiikick.auth.service.SignupService;
import com.teamehera.kiikick.auth.service.VerificationApiException;
import com.teamehera.kiikick.user.domain.AccountDeletionRequest;
import com.teamehera.kiikick.user.repository.AccountDeletionRequestRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import com.teamehera.kiikick.user.service.AccountDeletionService;
import com.teamehera.kiikick.user.service.MyProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public abstract class AccountDeletionContract {
    private static final String PASSWORD = "deletion-fixture-A42";
    @Autowired AccountDeletionService deletion;
    @Autowired AccountDeletionRequestRepository requests;
    @Autowired UserRepository users;
    @MockitoSpyBean EmailCredentialRepository credentials;
    @MockitoSpyBean EmailSendRateLimitRepository emailLocks;
    @Autowired EmailVerificationFlowRepository flows;
    @Autowired EmailVerificationService verification;
    @Autowired AuthHashEncoder hashes;
    @Autowired SignupService signup;
    @Autowired RefreshRotationContract.TestClock clock;
    @Autowired PasswordEncoder passwords;
    @Autowired RefreshTokenIssuer issuer;
    @Autowired AccessTokenService access;
    @Autowired MockMvc mvc;
    @Autowired RefreshService refresh;
    @Autowired LoginService login;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AuthSessionRepository sessions;

    private final List<Fixture> fixtures = new ArrayList<>();
    private final List<UUID> flowIds = new ArrayList<>();
    private final List<UUID> rejoined = new ArrayList<>();

    @BeforeEach void setClock() {
        clock.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
    record Fixture(UUID userId, String email) {}
    record Session(UUID id, String token) {}

    @AfterEach void cleanFixtures() {
        for (UUID flow : flowIds) jdbc.update("DELETE FROM email_verification_flows WHERE id=?", uuidToBytes(flow));
        for (UUID user : rejoined) cleanAccount(user);
        for (var f : fixtures) {
            jdbc.update("DELETE FROM auth_rate_limit_events WHERE rate_action='EMAIL_PASSWORD_LOGIN' AND key_value=?", f.email());
            jdbc.update("DELETE FROM auth_rate_limit_keys WHERE rate_action='EMAIL_PASSWORD_LOGIN' AND key_value=?", f.email());
            jdbc.update("DELETE FROM auth_rate_limit_events WHERE rate_action='ACCOUNT_DELETION_PASSWORD' AND key_value=?", f.userId().toString());
            jdbc.update("DELETE FROM auth_rate_limit_keys WHERE rate_action='ACCOUNT_DELETION_PASSWORD' AND key_value=?", f.userId().toString());
            jdbc.update("DELETE FROM auth_rate_limit_keys WHERE rate_action='EMAIL_VERIFICATION_SEND' AND limit_type='EMAIL' AND key_value=?", f.email());
            cleanAccount(f.userId());
        }
    }

    private void cleanAccount(UUID user) {
        var id = uuidToBytes(user);
        jdbc.update("DELETE FROM idempotency_records WHERE user_id=?", id);
        jdbc.update("DELETE FROM auth_refresh_token_history WHERE session_id IN (SELECT id FROM auth_sessions WHERE user_id=?)", id);
        jdbc.update("DELETE FROM auth_sessions WHERE user_id=?", id);
        jdbc.update("DELETE FROM account_deletion_requests WHERE user_id=?", id);
        jdbc.update("DELETE FROM email_credentials WHERE user_id=?", id);
        jdbc.update("DELETE FROM users WHERE id=?", id);
    }

    @Test void acceptsOnceAndBlocksAllOwnRefreshSessionsAndLogin() {
        var actor = fixture();
        var first = session(actor);
        var second = session(actor);
        var other = session(fixture());
        assertThat(deletion.request(actor.userId(), PASSWORD, UUID.randomUUID()).status()).isEqualTo(202);
        var result = requests.findActiveByUserId(actor.userId()).orElseThrow();
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.completedAt()).isNull();
        assertThat(requests.findByIdAndUserId(result.id(), actor.userId())).contains(result);
        assertThat(users.findById(actor.userId()).orElseThrow().deletionRequestedAt()).isEqualTo(result.requestedAt());
        for (var session : List.of(first, second)) {
            assertThat(sessions.findByIdForUpdate(session.id()).orElseThrow().revokedAt()).isEqualTo(result.requestedAt());
            assertThatThrownBy(() -> refresh.refresh(session.token()))
                    .isInstanceOfSatisfying(VerificationApiException.class, error ->
                            assertThat(error.code()).isEqualTo("INVALID_REFRESH_TOKEN"));
        }
        assertThat(sessions.findByIdForUpdate(other.id()).orElseThrow().revokedAt()).isNull();
        assertThatThrownBy(() -> login.login(actor.email(), PASSWORD))
                .isInstanceOfSatisfying(VerificationApiException.class, error ->
                        assertThat(error.code()).isEqualTo("INVALID_CREDENTIALS"));
        assertThatThrownBy(() -> deletion.request(actor.userId(), PASSWORD, UUID.randomUUID()))
                .isInstanceOf(AccountDeletionService.AlreadyRequested.class);
        assertThat(count(actor)).isEqualTo(1);
    }

    @Test void refusesWrongPasswordAndUnknownUser() {
        var actor = fixture();
        var session = session(actor);
        assertThatThrownBy(() -> deletion.request(actor.userId(), "incorrect-password", UUID.randomUUID()))
                .isInstanceOf(AccountDeletionService.InvalidPassword.class);
        assertThatThrownBy(() -> deletion.request(UUID.randomUUID(), PASSWORD, UUID.randomUUID()))
                .isInstanceOf(MyProfileService.MissingUser.class);
        assertUnchanged(actor, session);
    }

    @Test void rollsBackUserRequestAndSessionChangesWhenRevocationFails() {
        var actor = fixture();
        var session = session(actor);
        doAnswer(call -> {
            call.callRealMethod();
            throw new DataAccessResourceFailureException("fixture rollback");
        }).when(sessions).revokeAllByUserId(eq(actor.userId()), any());
        assertThatThrownBy(() -> deletion.request(actor.userId(), PASSWORD, UUID.randomUUID()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertUnchanged(actor, session);
    }

    @Test void concurrentRequestsCreateOnlyOnePendingRecord() throws Exception {
        var actor = fixture();
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> attempt(actor, start));
            var second = pool.submit(() -> attempt(actor, start));
            start.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("202", "duplicate");
        }
        assertThat(count(actor)).isEqualTo(1);
    }

    @Test void authenticationRejectsPreexistingTokenAfterDeletionCommits() throws Exception {
        var actor = fixture();
        var session = session(actor);
        String token = access.issue(actor.userId(), session.id()).token();
        profile(token).andExpect(status().isOk());

        var revocationWritten = new CountDownLatch(1);
        var commitAllowed = new CountDownLatch(1);
        doAnswer(call -> {
            var result = call.callRealMethod();
            revocationWritten.countDown();
            if (!commitAllowed.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("fixture commit timeout");
            }
            return result;
        }).when(sessions).revokeAllByUserId(eq(actor.userId()), any());

        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = pool.submit(() -> deletion.request(actor.userId(), PASSWORD, UUID.randomUUID()));
            try {
                assertThat(revocationWritten.await(10, TimeUnit.SECONDS)).isTrue();
                profile(token).andExpect(status().isOk());
            } finally {
                commitAllowed.countDown();
            }
            pending.get(10, TimeUnit.SECONDS);
        }

        profile(token).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test void authenticationRejectsExistingTokenForDeletedAccount() throws Exception {
        var actor = fixture();
        String token = access.issue(actor.userId(), UUID.randomUUID()).token();
        assertThat(users.softDelete(actor.userId())).isEqualTo(1);
        profile(token).andExpect(status().isUnauthorized());
    }

    @Test void httpRequiresBearerAndReturnsAcceptedOnlyAfterRevocation() throws Exception {
        var actor = fixture();
        var session = session(actor);
        String token = access.issue(actor.userId(), session.id()).token();
        postDeletion(null, PASSWORD).andExpect(status().isUnauthorized());
        postDeletion(token, "wrong-password").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
        postDeletion(token, PASSWORD).andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.completedAt").isEmpty())
                .andExpect(jsonPath("$.data.statusToken").doesNotExist());
        postDeletion(token, PASSWORD).andExpect(status().isUnauthorized());
        profile(token).andExpect(status().isUnauthorized());
        assertThat(count(actor)).isEqualTo(1);
    }

    @Test void malformedPasswordBodyCreatesNeitherRequestNorFailureEvent() throws Exception {
        var actor = fixture();
        var session = session(actor);
        String token = access.issue(actor.userId(), session.id()).token();
        for (String body : List.of(
                "{\"currentPassword\":\"wrong\",\"currentPassword\":\"" + PASSWORD + "\"}",
                "{\"currentPassword\":\"" + PASSWORD + "\"} {}",
                "{")) {
            mvc.perform(post(AccountDeletionService.PATH).contentType("application/json")
                            .header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", UUID.randomUUID()).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        }
        assertUnchanged(actor, session);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM auth_rate_limit_events
                WHERE rate_action='ACCOUNT_DELETION_PASSWORD' AND key_value=?
                """, Integer.class, actor.userId().toString())).isZero();
    }

    @Test void tenthPasswordFailureStartsIndependentFifteenMinuteLimit() throws Exception {
        var actor = fixture();
        String token = access.issue(actor.userId(), session(actor).id()).token();
        for (int i = 0; i < 9; i++) {
            postDeletion(token, "wrong-password").andExpect(status().isUnauthorized());
        }
        postDeletion(token, "wrong-password").andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"));
        assertThat(login.login(actor.email(), PASSWORD)).isNotNull();
        clock.set(clock.instant().plusSeconds(899));
        postDeletion(token, PASSWORD).andExpect(status().isTooManyRequests());
        clock.set(clock.instant().plusSeconds(1));
        postDeletion(access.issue(actor.userId(), UUID.randomUUID()).token(), PASSWORD)
                .andExpect(status().isAccepted());
    }

    @Test void recordsAcceptanceAndInvalidationAfterWaitingForEmailLock() {
        var actor = fixture();
        var session = session(actor);
        var beforeLock = utcNow();
        doAnswer(call -> {
            clock.set(clock.instant().plusSeconds(1));
            UUID earlierFlow = UUID.fromString(registration(actor, false));
            call.callRealMethod();
            assertThat(flows.findById(earlierFlow).orElseThrow().createdAt()).isAfter(beforeLock);
            clock.set(clock.instant().plusSeconds(1));
            return null;
        }).when(emailLocks).lockEmailForAccountChange(eq(actor.email()), any());

        assertThat(deletion.request(actor.userId(), PASSWORD, UUID.randomUUID()).status()).isEqualTo(202);
        var expected = beforeLock.plusSeconds(2);
        assertThat(requests.findActiveByUserId(actor.userId()).orElseThrow().requestedAt()).isEqualTo(expected);
        assertThat(flows.findById(flowIds.getLast()).orElseThrow().invalidatedAt()).isEqualTo(expected);
        assertThat(sessions.findByIdForUpdate(session.id()).orElseThrow().revokedAt()).isEqualTo(expected);
    }

    @Test void rollsBackCredentialDeletionAndTokenInvalidationTogether() {
        var actor = fixture();
        var session = session(actor);
        String oldToken = registration(actor, true);
        doAnswer(call -> {
            call.callRealMethod();
            throw new DataAccessResourceFailureException("fixture credential rollback");
        }).when(credentials).deleteByUserId(actor.userId());
        assertThatThrownBy(() -> deletion.request(actor.userId(), PASSWORD, UUID.randomUUID()))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertUnchanged(actor, session);
        assertThat(credentials.findByUserId(actor.userId())).isPresent();
        assertThat(flows.findActiveByActionToken(hashes.hashActionToken(oldToken), "SIGN_UP", utcNow())).isPresent();
    }

    @Test void rejoiningRequiresFreshProofAndConcurrentSignupsCreateOneNewAccount() throws Exception {
        var actor = fixture();
        String staleToken = registration(actor, true);
        String codeFlow = registration(actor, false);
        deletion.request(actor.userId(), PASSWORD, UUID.randomUUID());
        assertThatThrownBy(() -> signup.signup(staleToken, PASSWORD))
                .isInstanceOfSatisfying(VerificationApiException.class, error ->
                        assertThat(error.code()).isEqualTo("INVALID_REGISTRATION_TOKEN"));
        assertThatThrownBy(() -> verification.verifyCode(UUID.fromString(codeFlow), "123456"))
                .isInstanceOfSatisfying(VerificationApiException.class, error ->
                        assertThat(error.code()).isEqualTo("INVALID_VERIFICATION"));
        var start = new CountDownLatch(1);
        String firstToken = registration(actor, true);
        String secondToken = registration(actor, true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> rejoin(firstToken, start));
            var second = pool.submit(() -> rejoin(secondToken, start));
            start.countDown();
            UUID a = first.get(20, TimeUnit.SECONDS);
            UUID b = second.get(20, TimeUnit.SECONDS);
            if (a != null) rejoined.add(a);
            if (b != null) rejoined.add(b);
            assertThat(rejoined).hasSize(1);
        }
        UUID newId = rejoined.getFirst();
        assertThat(newId).isNotEqualTo(actor.userId());
        assertThat(credentials.findByNormalizedEmail(actor.email()).orElseThrow().userId()).isEqualTo(newId);
        assertThat(credentials.findByUserId(actor.userId())).isEmpty();
        assertThat(requests.findActiveByUserId(newId)).isEmpty();
        assertThat(requests.findActiveByUserId(actor.userId())).isPresent();
        assertThat(users.findById(actor.userId()).orElseThrow().deletionRequestedAt()).isNotNull();
        assertThat(users.findById(newId).orElseThrow().deletionRequestedAt()).isNull();
    }

    @Test void statusReadDistinguishesNeitherMissingNorOtherOwners() {
        var owner = fixture();
        var other = fixture();
        UUID requestId = UUID.randomUUID();
        requests.save(new AccountDeletionRequest(requestId, owner.userId(), "PENDING", utcNow(), null, null));

        assertThat(deletion.findReceipt(owner.userId(), requestId)).isPresent();
        assertThat(deletion.findReceipt(other.userId(), requestId)).isEmpty();
        assertThat(deletion.findReceipt(owner.userId(), UUID.randomUUID())).isEmpty();
    }

    @Test void statusReadReturnsContractStatesAndPreservesUtcMicroseconds() {
        var actor = fixture();
        var requestedAt = LocalDateTime.parse("2026-01-02T03:04:05.123456");
        var completedAt = requestedAt.plusSeconds(3);
        for (String state : List.of("PENDING", "PROCESSING", "COMPLETED", "FAILED")) {
            UUID id = UUID.randomUUID();
            requests.save(new AccountDeletionRequest(id, actor.userId(), state, requestedAt,
                    state.equals("COMPLETED") ? completedAt : null, null));
            var receipt = deletion.findReceipt(actor.userId(), id).orElseThrow();
            assertThat(receipt.id()).isEqualTo(id);
            assertThat(receipt.status()).isEqualTo(state);
            assertThat(receipt.requestedAt().toString()).isEqualTo("2026-01-02T12:04:05.123456+09:00");
            if (state.equals("COMPLETED")) {
                assertThat(receipt.completedAt().toString()).isEqualTo("2026-01-02T12:04:08.123456+09:00");
            } else {
                assertThat(receipt.completedAt()).isNull();
            }
        }
    }

    private UUID rejoin(String token, CountDownLatch start) throws InterruptedException {
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture start timeout");
        try {
            return signup.signup(token, PASSWORD).userId();
        } catch (SignupApiException exception) {
            assertThat(exception.code()).isEqualTo("SIGNUP_NOT_AVAILABLE");
            return null;
        }
    }

    private String registration(Fixture actor, boolean verified) {
        UUID id = UUID.randomUUID();
        flowIds.add(id);
        String token = UUID.randomUUID().toString();
        var now = utcNow();
        flows.save(new EmailVerificationFlow(id, actor.email(), "SIGN_UP",
                verified ? null : hashes.hashEmailCode(id, "123456"), 0, now.plusMinutes(3), now,
                verified ? now : null, null, verified ? hashes.hashActionToken(token) : null,
                verified ? now.plusMinutes(10) : null, null, now, now));
        return verified ? token : id.toString();
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private ResultActions postDeletion(String token, String password) throws Exception {
        var request = post(AccountDeletionService.PATH).contentType("application/json")
                .header("Idempotency-Key", UUID.randomUUID()).content("{\"currentPassword\":\"" + password + "\"}");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request);
    }

    private ResultActions profile(String token) throws Exception {
        return mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token));
    }

    private String attempt(Fixture actor, CountDownLatch start) throws InterruptedException {
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture start timeout");
        try {
            return Integer.toString(deletion.request(actor.userId(), PASSWORD, UUID.randomUUID()).status());
        } catch (AccountDeletionService.AlreadyRequested exception) {
            return "duplicate";
        }
    }

    private Fixture fixture() {
        UUID id = UUID.randomUUID();
        String email = id + "@kiikick.test";
        var fixture = new Fixture(id, email);
        fixtures.add(fixture);
        var now = LocalDateTime.now(ZoneOffset.UTC);
        users.save(id, "deletion");
        credentials.save(new EmailCredential(id, email, email, passwords.encode(PASSWORD), now, now, now, now));
        return fixture;
    }

    private Session session(Fixture fixture) {
        UUID id = UUID.randomUUID();
        var token = issuer.issue();
        sessions.save(new AuthSession(id, fixture.userId(), null, token.tokenHash(), 0,
                LocalDateTime.ofInstant(token.expiresAt(), ZoneOffset.UTC), null, LocalDateTime.now(ZoneOffset.UTC)));
        return new Session(id, token.token());
    }

    private int count(Fixture f) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM account_deletion_requests WHERE user_id=?",
                Integer.class, uuidToBytes(f.userId()));
    }

    private void assertUnchanged(Fixture actor, Session session) {
        assertThat(count(actor)).isZero();
        assertThat(users.findById(actor.userId()).orElseThrow().deletionRequestedAt()).isNull();
        assertThat(sessions.findByIdForUpdate(session.id()).orElseThrow().revokedAt()).isNull();
    }
}
