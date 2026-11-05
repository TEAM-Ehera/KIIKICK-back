package com.teamehera.kiikick.auth.controller;

import com.teamehera.kiikick.auth.service.SignupApiException;
import com.teamehera.kiikick.auth.service.SignupResult;
import com.teamehera.kiikick.auth.service.SignupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.sql.init.mode=never")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SignupControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean SignupService signupService;

    @Test
    void 회원가입_완료는_201과_REQUIRED_Session을_반환한다() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        when(signupService.signup("registration-token", "Valid-local-72!"))
                .thenReturn(new SignupResult(
                        sessionId,
                        "access-token",
                        "refresh-token",
                        Instant.parse("2026-11-05T00:15:00Z"),
                        userId,
                        LocalDateTime.of(2026, 11, 5, 0, 0)
                ));

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "registrationToken": "registration-token",
                                  "password": "Valid-local-72!"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sessionId").value(sessionId.toString()))
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"))
                .andExpect(jsonPath("$.data.expiresAt")
                        .value("2026-11-05T09:15:00+09:00"))
                .andExpect(jsonPath("$.data.user.id").value(userId.toString()))
                .andExpect(jsonPath("$.data.user.nickname").isEmpty())
                .andExpect(jsonPath("$.data.user.avatarUrl").isEmpty())
                .andExpect(jsonPath("$.data.user.onboardingStatus").value("REQUIRED"))
                .andExpect(jsonPath("$.data.user.comparisonConsent").value(false))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }

    @Test
    void 비밀번호_정책오류는_공통_오류_봉투로_422를_반환한다() throws Exception {
        when(signupService.signup("registration-token", "password"))
                .thenThrow(SignupApiException.invalidPassword());

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "registrationToken": "registration-token",
                                  "password": "password"
                                }
                                """))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.error.code").value("INVALID_PASSWORD"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.error.persistence").value("NOT_SAVED"));
    }

    @Test
    void 필수값이_누락되면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 실패응답과_일반로그에_토큰원문을_노출하지_않는다(CapturedOutput output)
            throws Exception {
        String registrationToken = "raw-registration-token-secret";
        String accessToken = "raw-access-token-secret";
        String refreshToken = "raw-refresh-token-secret";
        when(signupService.signup(registrationToken, "Valid-local-72!"))
                .thenThrow(SignupApiException.tokenIssuanceFailed(
                        new IllegalStateException(
                                accessToken + " " + refreshToken + " " + registrationToken
                        )
                ));

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "registrationToken": "raw-registration-token-secret",
                                  "password": "Valid-local-72!"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("TOKEN_ISSUANCE_FAILED"))
                .andExpect(content().string(not(containsString(registrationToken))))
                .andExpect(content().string(not(containsString(accessToken))))
                .andExpect(content().string(not(containsString(refreshToken))));

        org.assertj.core.api.Assertions.assertThat(output.getAll())
                .doesNotContain(registrationToken, accessToken, refreshToken);
    }
}
