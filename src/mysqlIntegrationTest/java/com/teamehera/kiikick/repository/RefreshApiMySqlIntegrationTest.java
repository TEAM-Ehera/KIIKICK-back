package com.teamehera.kiikick.repository;

import com.jayway.jsonpath.JsonPath;
import com.teamehera.kiikick.auth.domain.AuthSession;
import com.teamehera.kiikick.auth.email.VerificationEmailSender;
import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import com.teamehera.kiikick.auth.security.RefreshTokenIssuer;
import com.teamehera.kiikick.testsupport.LocalMySqlInitializer;
import com.teamehera.kiikick.testsupport.RefreshRotationContract;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.UUID;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.sql.init.mode=never")
@ContextConfiguration(initializers=LocalMySqlInitializer.class)
@Import(RefreshRotationContract.Beans.class)
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class RefreshApiMySqlIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired AuthSessionRepository sessions;
    @Autowired RefreshTokenIssuer issuer;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtDecoder decoder;
    @Autowired RefreshRotationContract.TestClock clock;
    @MockitoBean VerificationEmailSender sender;
    UUID user;
    @AfterEach void cleanup() {
        if(user==null)return;
        jdbc.update("DELETE FROM auth_refresh_token_history WHERE session_id IN (SELECT id FROM auth_sessions WHERE user_id=?)",uuidToBytes(user));
        jdbc.update("DELETE FROM auth_sessions WHERE user_id=?",uuidToBytes(user));
        jdbc.update("DELETE FROM users WHERE id=?",uuidToBytes(user));
    }
    @Test void 실제_HTTP_갱신과_JWT15분_및_재사용폐기후_Access잔존을_검증한다() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("kiikick_repository_test");
        clock.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        user=UUID.randomUUID();users.save(user,"refresh-api");
        UUID id=UUID.randomUUID(); var original=issuer.issue();
        sessions.save(new AuthSession(id,user,null,original.tokenHash(),0,
                LocalDateTime.ofInstant(original.expiresAt(),ZoneOffset.UTC),null,LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC)));
        String body=mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\""+original.token()+"\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.sessionId").value(id.toString()))
                .andExpect(jsonPath("$.data.user.id").value(user.toString()))
                .andExpect(jsonPath("$.data.expiresAt").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andReturn().getResponse().getContentAsString();
        String token=JsonPath.read(body,"$.data.accessToken");
        var jwt=decoder.decode(token);
        assertThat(Duration.between(jwt.getIssuedAt(),jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        String expires=JsonPath.read(body,"$.data.expiresAt");
        assertThat(OffsetDateTime.parse(expires).toInstant()).isEqualTo(clock.instant().plusSeconds(900));
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\""+original.token()+"\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("INVALID_REFRESH_TOKEN"));
        assertThat(sessions.findByIdForUpdate(id).orElseThrow().revokedAt()).isNotNull();
        assertThat(decoder.decode(token).getSubject()).isEqualTo(user.toString());
    }
}
