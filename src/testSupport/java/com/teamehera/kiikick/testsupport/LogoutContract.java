package com.teamehera.kiikick.testsupport;

import com.teamehera.kiikick.auth.domain.AuthSession;
import com.teamehera.kiikick.auth.repository.*;
import com.teamehera.kiikick.auth.security.*;
import com.teamehera.kiikick.auth.service.*;
import com.teamehera.kiikick.global.idempotency.repository.IdempotencyRecordRepository;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public abstract class LogoutContract {
    @Autowired LogoutService logout;
    @Autowired RefreshService refresh;
    @Autowired AuthSessionRepository sessions;
    @Autowired RefreshTokenIssuer issuer;
    @Autowired UserRepository users;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired RefreshRotationContract.TestClock clock;
    @MockitoBean AccessTokenService access;
    @MockitoSpyBean IdempotencyRecordRepository records;
    @MockitoSpyBean AuthSessionRepository sessionSpy;
    final List<UUID> owned=new ArrayList<>();
    static final byte[] BODY="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @BeforeEach void setup() {
        reset(access,records,sessionSpy);
        clock.set(Instant.parse("2026-11-06T00:00:00.123456Z"));
        when(access.issue(any(),any())).thenAnswer(i -> new IssuedAccessToken("test-access",clock.instant().plusSeconds(900)));
    }
    @AfterEach void cleanup() {
        for(UUID user:owned) {
            jdbc.update("DELETE FROM idempotency_records WHERE user_id=?",uuidToBytes(user));
            jdbc.update("DELETE FROM auth_refresh_token_history WHERE session_id IN (SELECT id FROM auth_sessions WHERE user_id=?)",uuidToBytes(user));
            jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",uuidToBytes(user));
            jdbc.update("DELETE FROM users WHERE id=?",uuidToBytes(user));
        }
    }
    record Fixture(UUID user,UUID session,String token) {
        AuthenticatedUser actor(){return new AuthenticatedUser(user,session);}
    }
    Fixture fixture() {UUID user=UUID.randomUUID();owned.add(user);users.save(user,"logout"+owned.size());return session(user);}
    Fixture session(UUID user) {
        UUID session=UUID.randomUUID();var token=issuer.issue();
        sessions.save(new AuthSession(session,user,null,token.tokenHash(),0,utc(token.expiresAt()),null,utc(clock.instant())));
        return new Fixture(user,session,token.token());
    }
    static LocalDateTime utc(Instant value){return LocalDateTime.ofInstant(value,ZoneOffset.UTC);}
    AuthSession stored(Fixture f){return sessions.findByIdForUpdate(f.session).orElseThrow();}
    MatchMutation.Response call(Fixture f,UUID target,UUID key,String header){
        return logout.logout(f.actor(),target,key,null,BODY,"{\"X-Request-Id\":\""+header+"\"}");
    }
    long recordCount(Fixture f){return jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE user_id=?",Long.class,uuidToBytes(f.user));}
    void sameResponse(MatchMutation.Response actual,MatchMutation.Response expected) {
        assertThat(actual.status()).isEqualTo(expected.status());
        assertThat(actual.bodyJson()).isEqualTo(expected.bodyJson());
        // MySQL JSON은 공백을 정규화하므로 HTTP 헤더 값의 의미를 비교한다.
        var json=new tools.jackson.databind.ObjectMapper();
        assertThat(json.readTree(actual.headersJson())).isEqualTo(json.readTree(expected.headersJson()));
    }
    void refreshDenied(String token){assertThatThrownBy(() -> refresh.refresh(token)).isInstanceOfSatisfying(VerificationApiException.class,e -> assertThat(e.code()).isEqualTo("INVALID_REFRESH_TOKEN"));}

    @Test void 현재세션_폐기와_Refresh차단은_원자적이다() {
        var f=fixture();var now=clock.instant();assertThat(call(f,null,UUID.randomUUID(),"first").status()).isEqualTo(204);
        assertThat(stored(f).revokedAt()).isEqualTo(utc(now));assertThat(recordCount(f)).isEqualTo(1);refreshDenied(f.token);
    }
    @Test void 명시한_다른본인세션만_폐기한다() {
        var f=fixture();var other=session(f.user);
        call(f,other.session,UUID.randomUUID(),"request");refreshDenied(other.token);
        assertThat(refresh.refresh(f.token)).isNotNull();assertThat(stored(f).revokedAt()).isNull();
    }
    @Test void 타인과_존재하지않는_세션은_동일한403이다() {
        var f=fixture();var other=fixture();
        for(UUID target:List.of(other.session,UUID.randomUUID()))
            assertThatThrownBy(() -> call(f,target,UUID.randomUUID(),"request")).isInstanceOfSatisfying(VerificationApiException.class,e -> {
                assertThat(e.status().value()).isEqualTo(403);assertThat(e.code()).isEqualTo("FORBIDDEN");
            });
        assertThat(stored(other).revokedAt()).isNull();assertThat(recordCount(f)).isZero();
    }
    @Test void 같은키는_최초헤더를_재생하고_새키반복도_최초폐기시각을_보존한다() {
        var f=fixture();UUID key=UUID.randomUUID();var first=call(f,null,key,"first");var time=stored(f).revokedAt();
        clock.set(clock.instant().plusSeconds(60));
        sameResponse(call(f,null,key,"second"),first);
        assertThat(call(f,null,UUID.randomUUID(),"third").status()).isEqualTo(204);
        assertThat(stored(f).revokedAt()).isEqualTo(time);assertThat(recordCount(f)).isEqualTo(2);
    }
    @Test void 만료된_본인세션도_폐기할수있다() {
        var f=fixture();clock.set(clock.instant().plus(Duration.ofDays(91)));
        assertThat(call(f,null,UUID.randomUUID(),"request").status()).isEqualTo(204);assertThat(stored(f).revokedAt()).isNotNull();
    }
    @Test void 같은키의_다른본문은409이고_다른세션을_폐기하지않는다() {
        var f=fixture();var other=session(f.user);UUID key=UUID.randomUUID();call(f,null,key,"first");
        assertThatThrownBy(() -> logout.logout(f.actor(),other.session,key,null,"different-body".getBytes(java.nio.charset.StandardCharsets.UTF_8),"{}"))
                .isInstanceOfSatisfying(MatchMutation.Rejected.class,e -> assertThat(e.getMessage()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(stored(other).revokedAt()).isNull();
    }
    @Test void 같은키와_빈본문도_다른현재세션에서는_충돌한다() {
        var f=fixture();var other=session(f.user);UUID key=UUID.randomUUID();call(f,null,key,"first");
        assertThatThrownBy(() -> call(other,null,key,"second")).isInstanceOfSatisfying(MatchMutation.Rejected.class,e -> assertThat(e.status()).isEqualTo(409));
        assertThat(stored(other).revokedAt()).isNull();
    }
    @Test void 만료키는_로그아웃범위에서_재사용할수있다() {
        var f=fixture();UUID key=UUID.randomUUID();call(f,null,key,"first");
        LocalDateTime deadline=jdbc.queryForObject("SELECT expires_at FROM idempotency_records WHERE user_id=?",(rs,n)->rs.getObject(1,LocalDateTime.class),uuidToBytes(f.user));
        assertThat(deadline).isEqualTo(utc(clock.instant().plus(Duration.ofDays(1))));
        clock.set(clock.instant().plus(Duration.ofDays(1)));
        assertThat(call(f,null,key,"second").headersJson()).contains("second");assertThat(recordCount(f)).isEqualTo(1);
    }
    @Test void 세션폐기_저장실패는_멱등기록과_함께_롤백한다() {
        var f=fixture();
        doAnswer(i -> {i.callRealMethod();throw new DataAccessResourceFailureException("test failure");}).when(sessionSpy).revoke(any(),any(),any());
        assertThatThrownBy(() -> call(f,null,UUID.randomUUID(),"request")).isInstanceOfSatisfying(VerificationApiException.class,e -> assertThat(e.status().value()).isEqualTo(503));
        assertThat(stored(f).revokedAt()).isNull();assertThat(recordCount(f)).isZero();
    }
    @Test void 멱등완료_저장실패도_세션폐기를_롤백한다() {
        var f=fixture();doThrow(new DataAccessResourceFailureException("test failure")).when(records).complete(any(),anyString(),anyInt(),anyString(),isNull());
        assertThatThrownBy(() -> call(f,null,UUID.randomUUID(),"request")).isInstanceOf(VerificationApiException.class);
        assertThat(stored(f).revokedAt()).isNull();assertThat(recordCount(f)).isZero();
    }
    @Test void 재생전에_세션소유권을_다시_확인한다() {
        var f=fixture();UUID key=UUID.randomUUID();call(f,null,key,"request");
        jdbc.update("DELETE FROM auth_sessions WHERE id=?",uuidToBytes(f.session));
        assertThatThrownBy(() -> call(f,null,key,"again")).isInstanceOfSatisfying(VerificationApiException.class,e -> assertThat(e.status().value()).isEqualTo(403));
    }
    @Test void 동시_같은키_로그아웃은_하나의성공결과를_재생한다() throws Exception {
        var f=fixture();UUID key=UUID.randomUUID();CountDownLatch go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> {go.await();return call(f,null,key,"first");});
            var b=pool.submit(() -> {go.await();return call(f,null,key,"second");});go.countDown();
            sameResponse(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        assertThat(recordCount(f)).isEqualTo(1);assertThat(stored(f).revokedAt()).isNotNull();
    }
    @Test void 로그아웃이_먼저잠그면_동시Refresh는_거부된다() throws Exception { concurrent(false); }
    @Test void Refresh가_먼저잠가도_이후로그아웃이_새토큰을_차단한다() throws Exception { concurrent(true); }
    void concurrent(boolean refreshFirst) throws Exception {
        var f=fixture();var other=session(f.user);CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1),started=new CountDownLatch(1);
        String holder=refreshFirst ? "refresh-holder" : "logout-holder";
        doAnswer(i -> {
            if(Thread.currentThread().getName().equals("logout-contender"))started.countDown();
            var result=i.callRealMethod();if(Thread.currentThread().getName().equals(holder)) {
            locked.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("lock timeout");
        }return result;}).when(sessionSpy).findByIdForUpdate(f.session);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Future<Object> first=pool.submit(() -> {Thread.currentThread().setName(holder);return refreshFirst ? refresh.refresh(f.token) : call(f,null,UUID.randomUUID(),"request");});
            try {
                assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                Future<Object> second=pool.submit(() -> {Thread.currentThread().setName("logout-contender");
                    if(refreshFirst)return call(f,null,UUID.randomUUID(),"request");
                    try {refresh.refresh(f.token);return "unexpected";}catch(VerificationApiException e){return e.code();}
                });
                assertThat(started.await(10,TimeUnit.SECONDS)).isTrue();
                // 첫 트랜잭션이 세션 잠금을 보유하는 동안 후속 DB 요청이 완료되지 않아야 한다.
                assertThatThrownBy(() -> second.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                Object a=first.get(20,TimeUnit.SECONDS),b=second.get(20,TimeUnit.SECONDS);
                if(refreshFirst)refreshDenied(((LoginResult)a).refreshToken());else assertThat(b).isEqualTo("INVALID_REFRESH_TOKEN");
            } finally {release.countDown();}
        }
        assertThat(stored(f).revokedAt()).isNotNull();assertThat(stored(other).revokedAt()).isNull();
        assertThat(refresh.refresh(other.token)).isNotNull();
    }
}
