package com.teamehera.kiikick.auth.controller;

import com.teamehera.kiikick.auth.dto.LoginRequest;
import com.teamehera.kiikick.auth.dto.LoginResponse;
import com.teamehera.kiikick.auth.service.LoginResult;
import com.teamehera.kiikick.auth.service.LoginService;
import com.teamehera.kiikick.auth.service.VerificationApiException;
import com.teamehera.kiikick.user.domain.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.sql.init.mode=never")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LoginControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean LoginService loginService;

    @Test
    void 공개_로그인_API는_200과_세션_사용자_캐시금지를_반환한다() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User user = new User(userId, "닉네임", null, LocalDateTime.of(2026, 11, 5, 0, 0),
                null, LocalDateTime.of(2026, 11, 4, 0, 0), null, null);
        when(loginService.login("user@example.com", "Original-Case-73!"))
                .thenReturn(new LoginResult(sessionId, "access-test", "refresh-test",
                        Instant.parse("2026-11-05T00:15:00Z"), user, true));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"Original-Case-73!\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.data.accessToken").value("access-test"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-test"))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-11-05T09:15:00+09:00"))
                .andExpect(jsonPath("$.data.user.createdAt").value("2026-11-04T09:00:00+09:00"))
                .andExpect(jsonPath("$.meta.serverTime").value(org.hamcrest.Matchers.endsWith("+09:00")))
                .andExpect(jsonPath("$.data.user.id").value(userId.toString()))
                .andExpect(jsonPath("$.data.user.onboardingStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.user.comparisonConsent").value(true))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    void 자격증명_오류는_401_공통오류로_반환하고_민감정보를_노출하지_않는다(CapturedOutput output)
            throws Exception {
        when(loginService.login("unknown@example.com", "private-password-73"))
                .thenThrow(VerificationApiException.invalidCredentials());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"unknown@example.com\",\"password\":\"private-password-73\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(content().string(not(containsString("private-password-73"))));
        assertThat(output.getAll()).doesNotContain("private-password-73");
        assertThat(new LoginRequest("unknown@example.com", "private-password-73").toString())
                .doesNotContain("unknown@example.com", "private-password-73");
        assertThat(new LoginResponse(UUID.randomUUID(), "private-access", "private-refresh", null, null)
                .toString()).doesNotContain("private-access", "private-refresh");
    }

    @Test
    void 제한은_429와_Retry_After를_반환한다() throws Exception {
        when(loginService.login("user@example.com", "Original-Case-73!"))
                .thenThrow(VerificationApiException.rateLimited(Instant.now().plusSeconds(900)));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"Original-Case-73!\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.error.retryable").value(true));
    }

    @Test
    void 저장소나_토큰발급_장애는_503으로_응답한다(CapturedOutput output) throws Exception {
        when(loginService.login("user@example.com", "Original-Case-73!"))
                .thenThrow(VerificationApiException.authenticationUnavailable());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"Original-Case-73!\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_UNAVAILABLE"))
                .andExpect(content().string(not(containsString("Original-Case-73!"))));
        assertThat(output.getAll()).doesNotContain("Original-Case-73!");
    }

    @Test
    void 빈_요청과_깨진_JSON은_400으로_응답한다() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }
}
