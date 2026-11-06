package com.teamehera.kiikick.auth.service;

import com.teamehera.kiikick.auth.domain.EmailVerificationFlow;
import com.teamehera.kiikick.auth.domain.SendRateLimitDecision;
import com.teamehera.kiikick.auth.email.VerificationEmailSender;
import com.teamehera.kiikick.auth.repository.EmailSendRateLimitRepository;
import com.teamehera.kiikick.auth.repository.EmailVerificationFlowRepository;
import com.teamehera.kiikick.auth.security.AuthHashEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class EmailVerificationService {

    private static final String PURPOSE = "SIGN_UP";
    private static final Duration CODE_LIFETIME = Duration.ofMinutes(3);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final Duration ACTION_TOKEN_LIFETIME = Duration.ofMinutes(10);
    private static final int MAX_FAILED_ATTEMPTS = 5;

    private final EmailVerificationFlowRepository flowRepository;
    private final EmailSendRateLimitRepository rateLimitRepository;
    private final EmailNormalizer emailNormalizer;
    private final AuthHashEncoder hashEncoder;
    private final VerificationSecretGenerator secretGenerator;
    private final VerificationEmailSender emailSender;
    private final Clock clock;

    public EmailVerificationService(
            EmailVerificationFlowRepository flowRepository,
            EmailSendRateLimitRepository rateLimitRepository,
            EmailNormalizer emailNormalizer,
            AuthHashEncoder hashEncoder,
            VerificationSecretGenerator secretGenerator,
            VerificationEmailSender emailSender,
            Clock clock
    ) {
        this.flowRepository = flowRepository;
        this.rateLimitRepository = rateLimitRepository;
        this.emailNormalizer = emailNormalizer;
        this.hashEncoder = hashEncoder;
        this.secretGenerator = secretGenerator;
        this.emailSender = emailSender;
        this.clock = clock;
    }

    @Transactional
    public EmailVerificationRequestResult requestCode(
            String email,
            String remoteAddress
    ) {
        String normalizedEmail = emailNormalizer.normalize(email);
        String canonicalIp = canonicalIpAddress(remoteAddress);
        String ipHash = hashEncoder.hashIpAddress(canonicalIp);
        Instant now = now();

        SendRateLimitDecision decision = rateLimitRepository
                .tryAcquireInCurrentTransaction(normalizedEmail, ipHash, utc(now));
        if (!decision.allowed()) {
            throw VerificationApiException.rateLimited(instant(decision.retryAt()));
        }

        String code = secretGenerator.generateCode();
        EmailVerificationFlow flow = prepareFlow(normalizedEmail, code, now);

        // SMTP 실패 예외가 전파되면 Flow 변경과 Rate Limit 이벤트를 함께 롤백한다.
        emailSender.sendSignUpCode(normalizedEmail, code, CODE_LIFETIME.toMinutes());
        return new EmailVerificationRequestResult(
                flow.id(), instant(flow.codeExpiresAt()), now.plus(RESEND_COOLDOWN)
        );
    }

    @Transactional(noRollbackFor = VerificationApiException.class)
    public EmailVerificationSuccessResult verifyCode(
            UUID verificationId,
            String code
    ) {
        if (verificationId == null || code == null || !code.matches("\\d{6}")) {
            throw VerificationApiException.invalidVerification();
        }
        Instant now = now();
        EmailVerificationFlow flow = flowRepository.findByIdForUpdate(verificationId)
                .orElseThrow(VerificationApiException::invalidVerification);

        if (flow.invalidatedAt() != null || flow.verifiedAt() != null
                || flow.codeHash() == null) {
            throw VerificationApiException.invalidVerification();
        }
        if (!now.isBefore(instant(flow.codeExpiresAt()))) {
            flowRepository.invalidate(flow.id(), utc(now));
            throw VerificationApiException.expiredVerification();
        }
        if (flow.failedAttempts() >= MAX_FAILED_ATTEMPTS) {
            throw VerificationApiException.attemptsExceeded(instant(flow.codeExpiresAt()));
        }

        String expectedHash = hashEncoder.hashEmailCode(flow.id(), code);
        if (!constantTimeEquals(flow.codeHash(), expectedHash)) {
            flowRepository.incrementFailedAttempts(flow.id(), utc(now));
            int failedAttempts = flow.failedAttempts() + 1;
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                throw VerificationApiException.attemptsExceeded(instant(flow.codeExpiresAt()));
            }
            throw VerificationApiException.invalidVerification();
        }

        String registrationToken = secretGenerator.generateActionToken();
        String tokenHash = hashEncoder.hashActionToken(registrationToken);
        Instant tokenExpiresAt = now.plus(ACTION_TOKEN_LIFETIME);
        if (flowRepository.verifyAndIssueActionToken(
                flow.id(), expectedHash, tokenHash, utc(tokenExpiresAt), utc(now)
        ) != 1) {
            throw VerificationApiException.invalidVerification();
        }
        return new EmailVerificationSuccessResult(
                registrationToken, tokenExpiresAt
        );
    }

    private EmailVerificationFlow prepareFlow(
            String normalizedEmail,
            String code,
            Instant now
    ) {
        EmailVerificationFlow latest = flowRepository
                .findLatestForUpdate(normalizedEmail, PURPOSE)
                .orElse(null);
        if (latest != null) {
            // 인증 완료 여부와 무관하게 마지막 발송부터 60초가 지나야 다시 발송한다.
            Instant resendAt = instant(latest.lastSentAt()).plus(RESEND_COOLDOWN);
            if (now.isBefore(resendAt)) {
                throw VerificationApiException.rateLimited(resendAt);
            }
            if (latest.verifiedAt() != null || latest.invalidatedAt() != null) {
                // 기존 registrationToken 상태를 보존하고 별도의 인증 Flow를 생성한다.
                latest = null;
            }
        }
        if (latest != null && !now.isBefore(instant(latest.codeExpiresAt()))) {
            flowRepository.invalidate(latest.id(), utc(now));
            latest = null;
        }
        if (latest != null && latest.failedAttempts() >= MAX_FAILED_ATTEMPTS) {
            throw VerificationApiException.attemptsExceeded(instant(latest.codeExpiresAt()));
        }
        if (latest != null) {
            String codeHash = hashEncoder.hashEmailCode(latest.id(), code);
            Instant expiresAt = now.plus(CODE_LIFETIME);
            if (flowRepository.resend(latest.id(), codeHash, utc(expiresAt), utc(now)) != 1) {
                throw VerificationApiException.invalidVerification();
            }
            return new EmailVerificationFlow(
                    latest.id(), latest.normalizedEmail(), latest.purpose(), codeHash,
                    latest.failedAttempts(), utc(expiresAt), utc(now), null, null,
                    null, null, null, latest.createdAt(), utc(now)
            );
        }

        UUID id = UUID.randomUUID();
        Instant expiresAt = now.plus(CODE_LIFETIME);
        EmailVerificationFlow created = new EmailVerificationFlow(
                id, normalizedEmail, PURPOSE,
                hashEncoder.hashEmailCode(id, code), 0,
                utc(expiresAt), utc(now), null, null,
                null, null, null, utc(now), utc(now)
        );
        flowRepository.save(created);
        return created;
    }

    private Instant now() {
        return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime utcDateTime) {
        return utcDateTime.toInstant(ZoneOffset.UTC);
    }

    private String canonicalIpAddress(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            throw VerificationApiException.invalidRequest("요청 IP를 확인할 수 없습니다.");
        }
        try {
            return InetAddress.getByName(remoteAddress).getHostAddress();
        } catch (UnknownHostException exception) {
            throw VerificationApiException.invalidRequest("요청 IP를 확인할 수 없습니다.");
        }
    }

    private boolean constantTimeEquals(String first, String second) {
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII)
        );
    }
}
