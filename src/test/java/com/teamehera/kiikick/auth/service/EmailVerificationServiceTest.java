package com.teamehera.kiikick.auth.service;

import com.teamehera.kiikick.auth.config.EmailVerificationConfig;
import com.teamehera.kiikick.auth.domain.EmailVerificationFlow;
import com.teamehera.kiikick.auth.email.VerificationEmailSender;
import com.teamehera.kiikick.auth.repository.EmailSendRateLimitRepository;
import com.teamehera.kiikick.auth.repository.EmailVerificationFlowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@JdbcTest
@Import({
        EmailVerificationService.class,
        RegistrationTokenService.class,
        EmailNormalizer.class,
        EmailVerificationConfig.class,
        EmailVerificationFlowRepository.class,
        EmailSendRateLimitRepository.class,
        EmailVerificationServiceTest.ClockConfig.class
})
class EmailVerificationServiceTest {

    private static final Instant START = Instant.parse("2026-11-05T00:00:00Z");

    @Autowired EmailVerificationService verificationService;
    @Autowired RegistrationTokenService registrationTokenService;
    @Autowired EmailVerificationFlowRepository flowRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MutableClock clock;

    @MockitoBean VerificationEmailSender emailSender;
    @MockitoBean VerificationSecretGenerator secretGenerator;

    @BeforeEach
    void setUp() {
        clock.set(START);
        reset(emailSender, secretGenerator);
        when(secretGenerator.generateCode()).thenReturn("123456");
        when(secretGenerator.generateActionToken()).thenReturn(
                "test-registration-token-256-bits-long-value"
        );
    }

    @Test
    void 발송하고_검증하면_원문을_저장하지_않고_일회용_토큰을_발급한다() {
        EmailVerificationRequestResult requested = verificationService.requestCode(
                " User+tag@BÜCHER.DE ", "127.0.0.1"
        );

        EmailVerificationFlow stored = flowRepository.findById(
                requested.verificationId()
        ).orElseThrow();
        assertThat(stored.normalizedEmail())
                .isEqualTo("user+tag@xn--bcher-kva.de");
        assertThat(stored.codeHash()).isNotEqualTo("123456");
        verify(emailSender).sendSignUpCode(
                eq("user+tag@xn--bcher-kva.de"), eq("123456"), eq(3L)
        );

        EmailVerificationSuccessResult verified = verificationService.verifyCode(
                requested.verificationId(), "123456"
        );
        EmailVerificationFlow afterVerification = flowRepository.findById(
                requested.verificationId()
        ).orElseThrow();
        assertThat(afterVerification.codeHash()).isNull();
        assertThat(afterVerification.actionTokenHash())
                .isNotEqualTo(verified.registrationToken());

        RegistrationTokenContext context = registrationTokenService.consume(
                verified.registrationToken()
        );
        assertThat(context.normalizedEmail())
                .isEqualTo("user+tag@xn--bcher-kva.de");
        assertThatThrownBy(() -> registrationTokenService.consume(
                verified.registrationToken()
        )).isInstanceOf(VerificationApiException.class)
                .extracting(exception -> ((VerificationApiException) exception).code())
                .isEqualTo("INVALID_REGISTRATION_TOKEN");
    }

    @Test
    void 인증_성공_후_59초에는_추가_발송을_거부한다() {
        EmailVerificationRequestResult first = verificationService.requestCode(
                "verified-cooldown@example.com", "192.0.2.20"
        );
        verificationService.verifyCode(first.verificationId(), "123456");
        clock.advance(Duration.ofSeconds(59));

        assertThatThrownBy(() -> verificationService.requestCode(
                "VERIFIED-COOLDOWN@example.com", "192.0.2.21"
        )).isInstanceOfSatisfying(VerificationApiException.class, exception -> {
            assertThat(exception.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(exception.code()).isEqualTo("RATE_LIMITED");
            assertThat(exception.retryAt()).isEqualTo(first.resendAvailableAt());
        });
        verify(emailSender, times(1)).sendSignUpCode(
                eq("verified-cooldown@example.com"), eq("123456"), eq(3L)
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_verification_flows WHERE normalized_email = ?",
                Integer.class, "verified-cooldown@example.com"
        )).isEqualTo(1);
    }

    @Test
    void 인증_성공_후_정확히_60초에는_새_Flow를_발송하고_기존_토큰을_보존한다() {
        EmailVerificationRequestResult first = verificationService.requestCode(
                "verified-new-flow@example.com", "192.0.2.22"
        );
        EmailVerificationSuccessResult verified = verificationService.verifyCode(
                first.verificationId(), "123456"
        );
        EmailVerificationFlow original = flowRepository.findById(
                first.verificationId()
        ).orElseThrow();
        clock.advance(Duration.ofSeconds(60));

        EmailVerificationRequestResult second = verificationService.requestCode(
                "verified-new-flow@example.com", "192.0.2.22"
        );

        assertThat(second.verificationId()).isNotEqualTo(first.verificationId());
        EmailVerificationFlow newFlow = flowRepository.findById(
                second.verificationId()
        ).orElseThrow();
        assertThat(newFlow.verifiedAt()).isNull();
        assertThat(newFlow.failedAttempts()).isZero();
        assertThat(newFlow.lastSentAt()).isEqualTo(first.resendAvailableAt());
        assertThat(flowRepository.findById(first.verificationId()).orElseThrow())
                .isEqualTo(original);
        verify(emailSender, times(2)).sendSignUpCode(
                eq("verified-new-flow@example.com"), eq("123456"), eq(3L)
        );
        assertThat(registrationTokenService.consume(verified.registrationToken())
                .verificationId()).isEqualTo(first.verificationId());
    }

    @Test
    void 미인증_Flow도_59초에는_재발송을_거부한다() {
        EmailVerificationRequestResult first = verificationService.requestCode(
                "unverified-cooldown@example.com", "192.0.2.23"
        );
        EmailVerificationFlow original = flowRepository.findById(
                first.verificationId()
        ).orElseThrow();
        clock.advance(Duration.ofSeconds(59));

        assertThatThrownBy(() -> verificationService.requestCode(
                "unverified-cooldown@example.com", "192.0.2.23"
        )).isInstanceOfSatisfying(VerificationApiException.class, exception -> {
            assertThat(exception.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(exception.code()).isEqualTo("RATE_LIMITED");
        });
        assertThat(flowRepository.findById(first.verificationId()).orElseThrow())
                .isEqualTo(original);
        verify(emailSender, times(1)).sendSignUpCode(
                eq("unverified-cooldown@example.com"), eq("123456"), eq(3L)
        );
    }

    @Test
    void 재발송은_이전_코드를_무효화하고_실패횟수를_유지한다() {
        when(secretGenerator.generateCode()).thenReturn("111111", "222222");
        EmailVerificationRequestResult first = verificationService.requestCode(
                "resend@example.com", "127.0.0.2"
        );

        assertInvalidCode(first.verificationId(), "000000");
        assertInvalidCode(first.verificationId(), "000000");
        clock.advance(Duration.ofSeconds(60));

        EmailVerificationRequestResult resent = verificationService.requestCode(
                "RESEND@example.com", "127.0.0.2"
        );
        assertThat(resent.verificationId()).isEqualTo(first.verificationId());
        assertThat(flowRepository.findById(first.verificationId()).orElseThrow()
                .failedAttempts()).isEqualTo(2);
        assertInvalidCode(first.verificationId(), "111111");
        verificationService.verifyCode(first.verificationId(), "222222");
    }

    @Test
    void 인증번호는_3분_후_만료되고_실패는_5회까지만_허용한다() {
        EmailVerificationRequestResult expired = verificationService.requestCode(
                "expired@example.com", "127.0.0.3"
        );
        clock.advance(Duration.ofMinutes(3));
        assertThatThrownBy(() -> verificationService.verifyCode(
                expired.verificationId(), "123456"
        )).isInstanceOf(VerificationApiException.class)
                .extracting(exception -> ((VerificationApiException) exception).code())
                .isEqualTo("VERIFICATION_EXPIRED");

        clock.advance(Duration.ofSeconds(1));
        EmailVerificationRequestResult limited = verificationService.requestCode(
                "attempts@example.com", "127.0.0.4"
        );
        for (int attempt = 1; attempt < 5; attempt++) {
            assertInvalidCode(limited.verificationId(), "000000");
        }
        assertThatThrownBy(() -> verificationService.verifyCode(
                limited.verificationId(), "000000"
        )).isInstanceOf(VerificationApiException.class)
                .extracting(exception -> ((VerificationApiException) exception).code())
                .isEqualTo("VERIFICATION_ATTEMPTS_EXCEEDED");
        assertThat(flowRepository.findById(limited.verificationId()).orElseThrow()
                .failedAttempts()).isEqualTo(5);
        assertThatThrownBy(() -> verificationService.verifyCode(
                limited.verificationId(), "123456"
        )).isInstanceOf(VerificationApiException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void SMTP_실패는_Flow와_RateLimit_이벤트를_롤백하고_재시도를_허용한다() {
        doThrow(VerificationApiException.emailDeliveryUnavailable(
                new IllegalStateException("smtp down")
        )).doNothing().when(emailSender).sendSignUpCode(
                eq("failure@example.com"), eq("123456"), anyLong()
        );

        assertThatThrownBy(() -> verificationService.requestCode(
                "failure@example.com", "127.0.0.5"
        )).isInstanceOf(VerificationApiException.class)
                .extracting(exception -> ((VerificationApiException) exception).code())
                .isEqualTo("EMAIL_DELIVERY_UNAVAILABLE");

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_verification_flows "
                        + "WHERE normalized_email = 'failure@example.com'",
                Integer.class
        ));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_rate_limit_events",
                Integer.class
        ));

        verificationService.requestCode("failure@example.com", "127.0.0.5");
    }

    @Test
    void 이메일과_IP_RateLimit을_독립적으로_적용한다() {
        for (int attempt = 0; attempt < 5; attempt++) {
            verificationService.requestCode(
                    "daily@example.com", "192.0.2." + (attempt + 1)
            );
            clock.advance(Duration.ofSeconds(61));
        }
        assertThatThrownBy(() -> verificationService.requestCode(
                "daily@example.com", "192.0.2.100"
        )).isInstanceOf(VerificationApiException.class)
                .extracting(exception -> ((VerificationApiException) exception).code())
                .isEqualTo("RATE_LIMITED");

        for (int attempt = 0; attempt < 3; attempt++) {
            verificationService.requestCode(
                    "ip-" + attempt + "@example.com", "198.51.100.10"
            );
        }
        assertThatThrownBy(() -> verificationService.requestCode(
                "ip-blocked@example.com", "198.51.100.10"
        )).isInstanceOf(VerificationApiException.class)
                .extracting(exception -> ((VerificationApiException) exception).code())
                .isEqualTo("RATE_LIMITED");
    }

    @Test
    void 동일_이메일의_동시_발송은_한_건만_성공한다() throws Exception {
        String email = "concurrent-" + UUID.randomUUID() + "@example.com";
        ExecutorService pool = Executors.newFixedThreadPool(5);
        CountDownLatch ready = new CountDownLatch(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 5; index++) {
                String ip = "203.0.113." + (index + 1);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        verificationService.requestCode(email, ip);
                        return true;
                    } catch (VerificationApiException exception) {
                        return false;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(15, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            assertThat(successes).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertInvalidCode(UUID id, String code) {
        assertThatThrownBy(() -> verificationService.verifyCode(id, code))
                .isInstanceOf(VerificationApiException.class)
                .extracting(exception -> ((VerificationApiException) exception).code())
                .isEqualTo("INVALID_VERIFICATION");
    }

    @TestConfiguration
    static class ClockConfig {

        @Bean
        MutableClock mutableClock() {
            return new MutableClock(START, ZoneOffset.UTC);
        }

    }
}
