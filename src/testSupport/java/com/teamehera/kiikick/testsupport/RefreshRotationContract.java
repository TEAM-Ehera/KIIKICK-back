package com.teamehera.kiikick.testsupport;

import com.teamehera.kiikick.auth.domain.AuthSession;
import com.teamehera.kiikick.auth.repository.*;
import com.teamehera.kiikick.auth.security.*;
import com.teamehera.kiikick.auth.service.*;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public abstract class RefreshRotationContract {
    protected static final Instant NOW=Instant.parse("2026-11-06T00:00:00.123456Z");
    @Autowired protected RefreshService service;
    @Autowired protected AuthSessionRepository sessions;
    @Autowired protected RefreshTokenIssuer issuer;
    @Autowired protected RefreshTokenHistoryRepository history;
    @Autowired protected UserRepository users;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected TestClock clock;
    @MockitoBean protected AccessTokenService access;
    @MockitoSpyBean protected RefreshTokenHistoryRepository historySpy;
    @MockitoSpyBean protected AuthSessionRepository sessionSpy;
    private final List<UUID> owned=new ArrayList<>();

    @BeforeEach void setup() {
        clock.set(NOW); reset(access); reset(historySpy); reset(sessionSpy);
        when(access.issue(any(),any())).thenAnswer(i -> new IssuedAccessToken("test-access",clock.instant().plusSeconds(900)));
    }
    @AfterEach void cleanup() {
        for(UUID id:owned) {
            jdbc.update("DELETE FROM auth_refresh_token_history WHERE session_id IN (SELECT id FROM auth_sessions WHERE user_id=?)",uuidToBytes(id));
            jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",uuidToBytes(id));
            jdbc.update("DELETE FROM users WHERE id=?",uuidToBytes(id));
        }
    }
    protected record Fixture(UUID userId,UUID sessionId,String token,Instant expiry) { }
    protected Fixture fixture() {
        UUID user=UUID.randomUUID(); owned.add(user); users.save(user,"refresh"+owned.size());
        return session(user);
    }
    protected Fixture session(UUID user) {
        var token=issuer.issue(); UUID id=UUID.randomUUID();
        sessions.save(new AuthSession(id,user,null,token.tokenHash(),0,utc(token.expiresAt()),null,utc(clock.instant())));
        return new Fixture(user,id,token.token(),token.expiresAt());
    }
    protected AuthSession stored(Fixture f) {
        return sessions.findByIdForUpdate(f.sessionId()).orElseThrow();
    }
    protected long count(Fixture f) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM auth_refresh_token_history WHERE session_id=?",Long.class,uuidToBytes(f.sessionId()));
    }
    protected void invalid(String token) {
        assertThatThrownBy(() -> service.refresh(token)).isInstanceOfSatisfying(VerificationApiException.class,e -> {
            assertThat(e.code()).isEqualTo("INVALID_REFRESH_TOKEN"); assertThat(e.status().value()).isEqualTo(401);
            if(token!=null) assertThat(e.getMessage()).doesNotContain(token);
        });
    }
    @Test void 회전은_해시만_저장하고_절대만료와_마이크로초를_보존한다() {
        var f=fixture(); clock.set(NOW.plus(Duration.ofDays(30)));
        var first=service.refresh(f.token());
        assertThat(first.refreshToken()).isNotEqualTo(f.token());
        assertThat(first.expiresAt()).isEqualTo(clock.instant().plusSeconds(900));
        assertThat(stored(f).refreshTokenHash()).isEqualTo(issuer.sha256(first.refreshToken()));
        assertThat(stored(f).refreshTokenHash()).doesNotContain(first.refreshToken());
        assertThat(stored(f).expiresAt()).isEqualTo(utc(f.expiry()));
        assertThat(stored(f).createdAt()).isEqualTo(utc(NOW));
        assertThat(stored(f).refreshGeneration()).isEqualTo(1);
        assertThat(history.contains(issuer.sha256(f.token()),f.sessionId())).isTrue();
        var second=service.refresh(first.refreshToken());
        assertThat(stored(f).refreshGeneration()).isEqualTo(2);
        assertThat(stored(f).expiresAt()).isEqualTo(utc(f.expiry()));
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken()); assertThat(count(f)).isEqualTo(2);
    }
    @Test void 응답유실_재시도는_폐기를_커밋하고_같은_사용자의_다른기기는_유지한다() {
        var f=fixture(); var other=session(f.userId());
        var issued=service.refresh(f.token());
        invalid(f.token());
        assertThat(stored(f).revokedAt()).isEqualTo(utc(clock.instant()));
        invalid(issued.refreshToken());
        assertThat(service.refresh(other.token()).user().id()).isEqualTo(f.userId());
        assertThat(stored(other).revokedAt()).isNull();
    }
    @Test void 여러세대_이전토큰_재사용도_현재세션을_폐기한다() {
        var f=fixture(); var a=service.refresh(f.token()); var b=service.refresh(a.refreshToken());
        invalid(f.token()); invalid(b.refreshToken()); assertThat(stored(f).revokedAt()).isNotNull();
    }
    @Test void 만료_폐기_위조_잘못된형식은_회전하지_않는다() {
        var f=fixture(); clock.set(f.expiry()); invalid(f.token()); assertThat(count(f)).isZero();
        clock.set(NOW); sessions.revoke(f.sessionId(),f.userId(),utc(NOW)); invalid(f.token());
        invalid(issuer.issue().token()); invalid("forged-secret"); invalid(null);
        assertThat(stored(f).refreshGeneration()).isZero(); verifyNoInteractions(access);
    }
    @Test void JWT발급실패는_회전과_소비이력을_모두_롤백한다() {
        var f=fixture(); doThrow(new JwtEncodingException("test failure")).when(access).issue(any(),any());
        assertThatThrownBy(() -> service.refresh(f.token())).isInstanceOfSatisfying(VerificationApiException.class,e -> assertThat(e.status().value()).isEqualTo(503));
        assertThat(count(f)).isZero(); assertThat(stored(f).refreshGeneration()).isZero();
        assertThat(stored(f).refreshTokenHash()).isEqualTo(issuer.sha256(f.token()));
        reset(access); when(access.issue(any(),any())).thenReturn(new IssuedAccessToken("ok",NOW.plusSeconds(900)));
        assertThat(service.refresh(f.token())).isNotNull();
    }
    @Test void 이력저장후_DB실패도_전체롤백한다() {
        var f=fixture();
        doAnswer(i -> {i.callRealMethod(); throw new org.springframework.dao.DataIntegrityViolationException("test failure");})
                .when(historySpy).save(anyString(),any(),anyLong(),any(),any());
        assertThatThrownBy(() -> service.refresh(f.token())).isInstanceOf(VerificationApiException.class);
        assertThat(count(f)).isZero(); assertThat(stored(f).refreshGeneration()).isZero();
    }
    @Test void 잘못된세대_조건부갱신은_실패한다() {
        var f=fixture();
        assertThat(sessions.rotateCurrentToken(f.sessionId(),1,issuer.sha256(f.token()),issuer.issue().tokenHash(),utc(NOW))).isZero();
        assertThat(stored(f).refreshGeneration()).isZero();
        doReturn(0).when(sessionSpy).rotateCurrentToken(any(),anyLong(),anyString(),anyString(),any());
        assertThatThrownBy(() -> service.refresh(f.token())).isInstanceOfSatisfying(VerificationApiException.class,
                e -> assertThat(e.status().value()).isEqualTo(503));
        assertThat(count(f)).isZero();
        assertThat(stored(f).refreshTokenHash()).isEqualTo(issuer.sha256(f.token()));
    }
    @Test void 동시_갱신은_한번만_성공하고_재사용_폐기가_남는다() throws Exception {
        var f=fixture(); CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<2;i++) results.add(pool.submit(() -> {ready.countDown(); if(!go.await(10,TimeUnit.SECONDS)) throw new AssertionError("latch");
                try {service.refresh(f.token()); return true;}
                catch(VerificationApiException e) {assertThat(e.code()).isEqualTo("INVALID_REFRESH_TOKEN"); return false;}
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();
            int successes=0;for(var result:results) if(result.get(20,TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
        }
        assertThat(stored(f).refreshGeneration()).isEqualTo(1);assertThat(count(f)).isEqualTo(1);
        assertThat(stored(f).revokedAt()).isNotNull();
    }
    @Test void 이력은_절대만료까지_보존하고_만료후에만_정리한다() {
        assertThat(history.deleteExpired(utc(NOW))).isZero();
        var f=fixture();service.refresh(f.token());
        history.deleteExpired(utc(f.expiry().minusNanos(1000)));assertThat(count(f)).isEqualTo(1);
        history.deleteExpired(utc(f.expiry()));assertThat(count(f)).isZero();
        assertThat(history.deleteExpired(utc(f.expiry()))).isZero();
        clock.set(f.expiry());invalid(f.token());
    }
    @Test void 정리실패_이후에도_토큰갱신은_정상이다() {
        var f=fixture();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test failure"))
                .when(historySpy).deleteExpired(any());
        new com.teamehera.kiikick.auth.config.RefreshHistoryCleanup(history,clock).deleteExpired();
        assertThat(service.refresh(f.token())).isNotNull();
        assertThat(stored(f).revokedAt()).isNull();
    }
    @Test void 동시_정리와_활성세션_갱신은_만료전이력을_보존한다() throws Exception {
        var expired=fixture(); service.refresh(expired.token());
        clock.set(expired.expiry()); var active=fixture();
        CountDownLatch go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var cleanup1=pool.submit(() -> {go.await(); return history.deleteExpired(utc(clock.instant()));});
            var cleanup2=pool.submit(() -> {go.await(); return history.deleteExpired(utc(clock.instant()));});
            var refresh=pool.submit(() -> {go.await(); return service.refresh(active.token());});
            go.countDown();
            assertThat(cleanup1.get(20,TimeUnit.SECONDS)+cleanup2.get(20,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(refresh.get(20,TimeUnit.SECONDS)).isNotNull();
        }
        assertThat(count(expired)).isZero(); assertThat(count(active)).isEqualTo(1);
        assertThat(stored(active).revokedAt()).isNull();
    }
    protected static LocalDateTime utc(Instant i) {return LocalDateTime.ofInstant(i,ZoneOffset.UTC);}
    public static class TestClock extends Clock {
        private volatile Instant now=NOW;
        public void set(Instant value) {now=value;}
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return this;}
        public Instant instant(){return now;}
    }
    @TestConfiguration public static class Beans {
        @Bean @Primary public TestClock refreshTestClock(){return new TestClock();}
    }
}
