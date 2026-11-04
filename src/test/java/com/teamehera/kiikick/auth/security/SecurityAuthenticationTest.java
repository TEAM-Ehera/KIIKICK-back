package com.teamehera.kiikick.auth.security;

import com.teamehera.kiikick.auth.domain.AuthSession;
import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.sql.init.mode=never")
@AutoConfigureMockMvc
@Import(SecurityAuthenticationTest.SecurityTestController.class)
class SecurityAuthenticationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccessTokenService accessTokenService;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    @Test
    void 활성_세션의_토큰은_인증_Principal을_제공한다() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(authSessionRepository.findActiveById(eq(sessionId), any()))
                .thenReturn(Optional.of(activeSession(sessionId, userId)));

        String token = accessTokenService.issue(userId, sessionId).token();

        mockMvc.perform(get("/api/v1/security-test")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()));

        verify(authSessionRepository).findActiveById(eq(sessionId), any());
    }

    @Test
    void 토큰이_유효해도_세션이_없거나_폐기되었으면_401이다() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(authSessionRepository.findActiveById(eq(sessionId), any()))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/security-test")
                        .header("Authorization", "Bearer "
                                + accessTokenService.issue(userId, sessionId).token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.meta.requestId").exists())
                .andExpect(jsonPath("$.meta.serverTime").exists());
    }

    @Test
    void 토큰_사용자와_세션_사용자가_다르면_401이다() throws Exception {
        UUID tokenUserId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(authSessionRepository.findActiveById(eq(sessionId), any()))
                .thenReturn(Optional.of(activeSession(sessionId, UUID.randomUUID())));

        mockMvc.perform(get("/api/v1/security-test")
                        .header("Authorization", "Bearer "
                                + accessTokenService.issue(tokenUserId, sessionId).token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 세션_ID가_없는_서명된_토큰은_401이다() throws Exception {
        String token = customToken(
                Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(300),
                Map.of(AccessTokenService.USER_ID_CLAIM, UUID.randomUUID().toString())
        );

        mockMvc.perform(get("/api/v1/security-test")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        verify(authSessionRepository, never()).findActiveById(any(), any());
    }

    @Test
    void 만료된_토큰은_세션을_조회하지_않고_TOKEN_EXPIRED를_반환한다()
            throws Exception {
        String token = customToken(
                Instant.now().minusSeconds(600),
                Instant.now().minusSeconds(300),
                Map.of(
                        AccessTokenService.USER_ID_CLAIM, UUID.randomUUID().toString(),
                        AccessTokenService.SESSION_ID_CLAIM, UUID.randomUUID().toString()
                )
        );

        mockMvc.perform(get("/api/v1/security-test")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));

        verify(authSessionRepository, never()).findActiveById(any(), any());
    }

    @Test
    void 위조된_토큰과_토큰_없는_보호_API는_401이다() throws Exception {
        String validToken = accessTokenService.issue(
                UUID.randomUUID(), UUID.randomUUID()
        ).token();
        String tamperedToken = validToken.substring(0, validToken.length() - 1)
                + (validToken.endsWith("a") ? "b" : "a");

        mockMvc.perform(get("/api/v1/security-test")
                        .header("Authorization", "Bearer " + tamperedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        mockMvc.perform(get("/api/v1/security-test"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void 공개_Auth_API는_토큰과_세션_조회가_필요하지_않다() throws Exception {
        mockMvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isNotFound());

        verify(authSessionRepository, never()).findActiveById(any(), any());
    }

    @Test
    void 발급된_Access_Token의_수명은_4시간이다() {
        Instant before = Instant.now();
        IssuedAccessToken issued = accessTokenService.issue(
                UUID.randomUUID(), UUID.randomUUID()
        );

        assertThat(issued.expiresAt())
                .isBetween(before.plusSeconds(4 * 60 * 60),
                        Instant.now().plusSeconds(4 * 60 * 60 + 1));
    }

    @Test
    void 비밀번호는_BCrypt_cost_12로_단방향_해시한다() {
        String rawPassword = "correct horse battery staple";
        String encoded = passwordEncoder.encode(rawPassword);

        assertThat(encoded).startsWith("$2");
        assertThat(encoded.split("\\$")[2]).isEqualTo("12");
        assertThat(encoded).isNotEqualTo(rawPassword);
        assertThat(passwordEncoder.matches(rawPassword, encoded)).isTrue();
    }

    private String customToken(
            Instant issuedAt,
            Instant expiresAt,
            Map<String, Object> extraClaims
    ) {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("kiikick-api-test")
                .audience(List.of("kiikick-test"))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim(AccessTokenService.TOKEN_TYPE_CLAIM,
                        AccessTokenService.ACCESS_TOKEN_TYPE);
        extraClaims.forEach(claims::claim);

        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims.build()
        )).getTokenValue();
    }

    private AuthSession activeSession(UUID sessionId, UUID userId) {
        return new AuthSession(
                sessionId,
                userId,
                UUID.randomUUID(),
                "refresh-token-hash",
                0,
                LocalDateTime.now().plusDays(1),
                null,
                LocalDateTime.now()
        );
    }

    @RestController
    static class SecurityTestController {

        @GetMapping(value = "/api/v1/security-test",
                produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, UUID> protectedEndpoint(
                @AuthenticationPrincipal AuthenticatedUser principal
        ) {
            return Map.of(
                    "userId", principal.userId(),
                    "sessionId", principal.sessionId()
            );
        }
    }
}
