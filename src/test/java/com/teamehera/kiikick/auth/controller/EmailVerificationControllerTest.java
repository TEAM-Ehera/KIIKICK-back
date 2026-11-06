package com.teamehera.kiikick.auth.controller;

import com.teamehera.kiikick.auth.service.EmailVerificationRequestResult;
import com.teamehera.kiikick.auth.service.EmailVerificationService;
import com.teamehera.kiikick.auth.service.EmailVerificationSuccessResult;
import com.teamehera.kiikick.auth.service.VerificationApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.sql.init.mode=never")
@AutoConfigureMockMvc
class EmailVerificationControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean EmailVerificationService verificationService;

    @Test
    void 인증번호_발송은_202와_검증_ID를_반환한다() throws Exception {
        UUID verificationId = UUID.randomUUID();
        when(verificationService.requestCode(
                eq("user@example.com"), eq("127.0.0.1")
        )).thenReturn(new EmailVerificationRequestResult(
                verificationId,
                Instant.parse("2026-11-05T00:03:00Z"),
                Instant.parse("2026-11-05T00:01:00Z")
        ));

        mockMvc.perform(post("/api/v1/auth/email-verifications")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.verificationId")
                        .value(verificationId.toString()))
                .andExpect(jsonPath("$.data.expiresAt").value("2026-11-05T09:03:00+09:00"))
                .andExpect(jsonPath("$.data.resendAvailableAt")
                        .value("2026-11-05T09:01:00+09:00"))
                .andExpect(jsonPath("$.meta.requestId").exists())
                .andExpect(jsonPath("$.meta.serverTime").value(org.hamcrest.Matchers.endsWith("+09:00")));
    }

    @Test
    void 인증번호_검증은_registrationToken을_한_번_반환한다() throws Exception {
        UUID verificationId = UUID.randomUUID();
        when(verificationService.verifyCode(verificationId, "123456"))
                .thenReturn(new EmailVerificationSuccessResult(
                        "registration-token",
                        Instant.parse("2026-11-05T00:10:00Z")
                ));

        mockMvc.perform(post(
                        "/api/v1/auth/email-verifications/{verificationId}/verify",
                        verificationId
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registrationToken")
                        .value("registration-token"))
                .andExpect(jsonPath("$.data.expiresAt")
                        .value("2026-11-05T09:10:00+09:00"));
    }

    @Test
    void 발송제한은_429와_Retry_After를_반환한다() throws Exception {
        Instant retryAt = Instant.now().plusSeconds(60);
        when(verificationService.requestCode(
                eq("limited@example.com"), eq("127.0.0.1")
        )).thenThrow(VerificationApiException.rateLimited(retryAt));

        mockMvc.perform(post("/api/v1/auth/email-verifications")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"limited@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.error.persistence").value("NOT_SAVED"))
                .andExpect(jsonPath("$.meta.serverTime").value(org.hamcrest.Matchers.endsWith("+09:00")));
    }

    @Test
    void 잘못된_요청은_공통_오류_봉투로_400을_반환한다() throws Exception {
        mockMvc.perform(post("/api/v1/auth/email-verifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.meta.requestId").exists());
    }
}
