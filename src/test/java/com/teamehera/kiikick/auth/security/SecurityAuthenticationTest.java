package com.teamehera.kiikick.auth.security;

import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @MockitoBean
    private UserRepository users;

    @Test
    void 유효한_JWT는_세션_조회_없이_인증_Principal을_제공한다() throws Exception {
        UUID userId = UUID.randomUUID();
        when(users.isActiveForAuthentication(userId)).thenReturn(true);
        UUID sessionId = UUID.randomUUID();

        String token = accessTokenService.issue(userId, sessionId).token();

        mockMvc.perform(get("/api/v1/security-test")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.sessionId").value(sessionId.toString()));

        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void servlet_Principal_이름은_검증된_사용자_UUID이다() throws Exception {
        UUID userId = UUID.randomUUID();
        when(users.isActiveForAuthentication(userId)).thenReturn(true);
        String token = accessTokenService.issue(userId, UUID.randomUUID()).token();
        mockMvc.perform(get("/api/v1/security-test/name")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(userId.toString()));
        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void 세션이_폐기되어도_발급된_JWT는_만료_전까지_유효할_수_있다() throws Exception {
        UUID userId = UUID.randomUUID();
        when(users.isActiveForAuthentication(userId)).thenReturn(true);
        UUID sessionId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/security-test")
                        .header("Authorization", "Bearer "
                                + accessTokenService.issue(userId, sessionId).token()))
                .andExpect(status().isOk());

        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void signedTokenWithUnknownUserIsRejected() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = accessTokenService.issue(userId, UUID.randomUUID()).token();
        mockMvc.perform(get("/api/v1/security-test").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        verify(users).isActiveForAuthentication(userId);
        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void accountStateLookupFailureDoesNotAuthenticate() throws Exception {
        UUID userId = UUID.randomUUID();
        when(users.isActiveForAuthentication(userId))
                .thenThrow(new DataAccessResourceFailureException("private DB details"));
        String token = accessTokenService.issue(userId, UUID.randomUUID()).token();
        var response = mockMvc.perform(get("/api/v1/security-test").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED")).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("private DB details");
        verifyNoInteractions(authSessionRepository);
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
    void 사용자_ID가_없거나_필수_UUID_클레임이_잘못되면_401이다() throws Exception {
        String missingUserId = customToken(
                Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(300),
                Map.of(AccessTokenService.SESSION_ID_CLAIM, UUID.randomUUID().toString())
        );
        String invalidSessionId = customToken(
                Instant.now().minusSeconds(1),
                Instant.now().plusSeconds(300),
                Map.of(
                        AccessTokenService.USER_ID_CLAIM, UUID.randomUUID().toString(),
                        AccessTokenService.SESSION_ID_CLAIM, "invalid-uuid"
                )
        );

        mockMvc.perform(get("/api/v1/security-test")
                        .header("Authorization", "Bearer " + missingUserId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/v1/security-test")
                        .header("Authorization", "Bearer " + invalidSessionId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void exp가_없는_정상서명_토큰도_401이다() throws Exception {
        String token = customToken(
                Instant.now().minusSeconds(1),
                null,
                Map.of(
                        AccessTokenService.USER_ID_CLAIM, UUID.randomUUID().toString(),
                        AccessTokenService.SESSION_ID_CLAIM, UUID.randomUUID().toString()
                )
        );

        mockMvc.perform(get("/api/v1/security-test")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));

        verifyNoInteractions(authSessionRepository);
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
        verifyNoInteractions(users);
    }

    @Test
    void 위조된_토큰과_토큰_없는_보호_API는_401이다() throws Exception {
        String validToken = accessTokenService.issue(
                UUID.randomUUID(), UUID.randomUUID()
        ).token();
        String[] tokenParts = validToken.split("\\.");
        String signature = tokenParts[2];
        char tamperedFirstCharacter = signature.charAt(0) == 'a' ? 'b' : 'a';
        String tamperedToken = tokenParts[0] + "." + tokenParts[1] + "."
                + tamperedFirstCharacter + signature.substring(1);

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
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        verify(authSessionRepository, never()).findActiveById(any(), any());
    }

    @Test
    void 발급된_Access_Token의_수명은_15분이다() {
        Instant before = Instant.now();
        IssuedAccessToken issued = accessTokenService.issue(
                UUID.randomUUID(), UUID.randomUUID()
        );

        assertThat(issued.expiresAt())
                .isBetween(before.plusSeconds(15 * 60),
                        Instant.now().plusSeconds(15 * 60 + 1));
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
                .claim(AccessTokenService.TOKEN_TYPE_CLAIM,
                        AccessTokenService.ACCESS_TOKEN_TYPE);
        if (expiresAt != null) {
            claims.expiresAt(expiresAt);
        }
        extraClaims.forEach(claims::claim);

        return jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(),
                claims.build()
        )).getTokenValue();
    }

    @RestController
    static class SecurityTestController {
        @GetMapping("/api/v1/security-test/name")
        Map<String, String> principalName(java.security.Principal principal) {
            return Map.of("name", principal.getName());
        }

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
