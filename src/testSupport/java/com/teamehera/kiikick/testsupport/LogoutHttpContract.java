package com.teamehera.kiikick.testsupport;

import com.teamehera.kiikick.auth.domain.AuthSession;
import com.teamehera.kiikick.auth.email.VerificationEmailSender;
import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import com.teamehera.kiikick.auth.security.*;
import com.teamehera.kiikick.auth.service.*;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public abstract class LogoutHttpContract {
    @Autowired MockMvc mvc;
    @Autowired AccessTokenService access;
    @Autowired AuthSecurityProperties properties;
    @Autowired JwtEncoder encoder;
    @Autowired AuthSessionRepository sessions;
    @Autowired RefreshTokenIssuer issuer;
    @Autowired RefreshService refresh;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired RefreshRotationContract.TestClock clock;
    @MockitoBean VerificationEmailSender email;
    final List<UUID> owned=new ArrayList<>();

    @BeforeEach void time(){clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));}
    @AfterEach void cleanup(){for(UUID user:owned){
        jdbc.update("DELETE FROM idempotency_records WHERE user_id=?",uuidToBytes(user));
        jdbc.update("DELETE FROM auth_refresh_token_history WHERE session_id IN (SELECT id FROM auth_sessions WHERE user_id=?)",uuidToBytes(user));
        jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",uuidToBytes(user));
        jdbc.update("DELETE FROM users WHERE id=?",uuidToBytes(user));
    }}
    record Fixture(UUID user,UUID session,String refresh,String access){}
    Fixture fixture(){UUID user=UUID.randomUUID();owned.add(user);users.save(user,"logout-http");return session(user);}
    Fixture session(UUID user){
        UUID id=UUID.randomUUID();var token=issuer.issue();
        sessions.save(new AuthSession(id,user,null,token.tokenHash(),0,utc(token.expiresAt()),null,utc(clock.instant())));
        return new Fixture(user,id,token.token(),access.issue(user,id).token());
    }
    static LocalDateTime utc(Instant i){return LocalDateTime.ofInstant(i,ZoneOffset.UTC);}
    ResultActions request(Fixture f,String body,String key)throws Exception{
        var request=post("/api/v1/auth/logout").contentType("application/json").content(body).header("Authorization","Bearer "+f.access);
        if(key!=null)request.header("Idempotency-Key",key);
        return mvc.perform(request);
    }
    void notRevoked(Fixture f){assertThat(sessions.findByIdForUpdate(f.session).orElseThrow().revokedAt()).isNull();}
    void deniedRefresh(Fixture f){assertThatThrownBy(()->refresh.refresh(f.refresh)).isInstanceOfSatisfying(VerificationApiException.class,e->assertThat(e.code()).isEqualTo("INVALID_REFRESH_TOKEN"));}

    @Test void 현재세션_로그아웃은204_재생_Refresh차단과_Access잔존을_보장한다()throws Exception{
        var f=fixture();String key=UUID.randomUUID().toString();
        String requestId=request(f,"{}",key).andExpect(status().isNoContent()).andExpect(content().string(""))
            .andExpect(header().exists("X-Request-Id")).andExpect(header().string("Cache-Control","no-store"))
            .andReturn().getResponse().getHeader("X-Request-Id");
        request(f,"{}",key).andExpect(status().isNoContent()).andExpect(header().string("X-Request-Id",requestId));
        request(f,"{}",UUID.randomUUID().toString()).andExpect(status().isNoContent());
        deniedRefresh(f);
        mvc.perform(get("/api/v1/logout-security-probe").header("Authorization","Bearer "+f.access))
            .andExpect(status().isOk()).andExpect(content().string(f.user.toString()));
    }
    @Test void 다른본인세션만_명시해서_폐기할수있다()throws Exception{
        var f=fixture();var other=session(f.user);
        request(f,"{\"sessionId\":\""+other.session+"\"}",UUID.randomUUID().toString()).andExpect(status().isNoContent());
        deniedRefresh(other);notRevoked(f);assertThat(refresh.refresh(f.refresh)).isNotNull();
    }
    @Test void 타인세션과_존재하지않는세션은_같은403오류다()throws Exception{
        var f=fixture();var foreign=fixture();String message=null;
        for(UUID id:List.of(foreign.session,UUID.randomUUID())){
            String json=request(f,"{\"sessionId\":\""+id+"\"}",UUID.randomUUID().toString())
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andReturn().getResponse().getContentAsString();
            var parsed=new tools.jackson.databind.ObjectMapper().readTree(json).path("error").path("message").asText();
            if(message==null)message=parsed;else assertThat(parsed).isEqualTo(message);
        }
        notRevoked(f);notRevoked(foreign);
    }
    @Test void 생략과달리_null과_잘못된본문은422이고_부작용이없다()throws Exception{
        var f=fixture();
        for(String body:List.of("{\"sessionId\":null}","{\"sessionId\":1}","{\"sessionId\":\"bad\"}",
                "{\"unknown\":1}","[]","null","{", "")){
            request(f,body,UUID.randomUUID().toString()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        }
        notRevoked(f);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_records WHERE user_id=?",Long.class,uuidToBytes(f.user))).isZero();
    }
    @Test void 멱등키_누락과_UUID오류는422이다()throws Exception{
        var f=fixture();for(String key:Arrays.asList(null,"bad"))request(f,"{}",key).andExpect(status().isUnprocessableEntity());notRevoked(f);
    }
    @Test void 같은키의_다른요청은409이며_추가폐기하지않는다()throws Exception{
        var f=fixture();var other=session(f.user);String key=UUID.randomUUID().toString();
        request(f,"{}",key).andExpect(status().isNoContent());
        request(f,"{\"sessionId\":\""+other.session+"\"}",key).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));notRevoked(other);
    }
    @Test void 인증누락과_만료JWT는401_Bearer이고_세션을보존한다()throws Exception{
        var f=fixture();
        mvc.perform(post("/api/v1/auth/logout").contentType("application/json").content("{}").header("Idempotency-Key",UUID.randomUUID()))
            .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate","Bearer"));
        Instant now=Instant.now();
        var claims=JwtClaimsSet.builder().issuer(properties.issuer()).audience(List.of(properties.audience()))
            .subject(f.user.toString()).issuedAt(now.minusSeconds(1200)).expiresAt(now.minusSeconds(300))
            .claim("uid",f.user.toString()).claim("sid",f.session.toString()).claim("token_type","access").build();
        String expired=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
        mvc.perform(post("/api/v1/auth/logout").contentType("application/json").content("{}").header("Idempotency-Key",UUID.randomUUID())
            .header("Authorization","Bearer "+expired)).andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate","Bearer")).andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
        notRevoked(f);
    }
    @RestController public static class Probe {
        @GetMapping("/api/v1/logout-security-probe") public String user(@AuthenticationPrincipal AuthenticatedUser user){return user.getName();}
    }
}
