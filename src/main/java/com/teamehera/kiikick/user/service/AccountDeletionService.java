package com.teamehera.kiikick.user.service;

import com.teamehera.kiikick.auth.controller.AuthApiResponseFactory;
import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import com.teamehera.kiikick.auth.repository.EmailSendRateLimitRepository;
import com.teamehera.kiikick.auth.repository.EmailVerificationFlowRepository;
import com.teamehera.kiikick.auth.repository.LoginAttemptRepository;
import com.teamehera.kiikick.global.idempotency.repository.IdempotencyRecordRepository;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.auth.repository.EmailCredentialRepository;
import com.teamehera.kiikick.user.domain.AccountDeletionRequest;
import com.teamehera.kiikick.user.repository.AccountDeletionRequestRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountDeletionService {
    public static final String PATH = "/api/v1/users/me/deletion-requests";

    private final UserRepository users;
    private final EmailCredentialRepository credentials;
    private final AuthSessionRepository sessions;
    private final AccountDeletionRequestRepository requests;
    private final EmailSendRateLimitRepository emailLocks;
    private final EmailVerificationFlowRepository flows;
    private final LoginAttemptRepository attempts;
    private final MatchMutation mutation;
    private final IdempotencyRecordRepository records;
    private final AuthApiResponseFactory responses;
    private final ObjectMapper json;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final Duration retention;

    public AccountDeletionService(UserRepository users, EmailCredentialRepository credentials,
                                  AuthSessionRepository sessions, AccountDeletionRequestRepository requests,
                                  EmailSendRateLimitRepository emailLocks, EmailVerificationFlowRepository flows,
                                  LoginAttemptRepository attempts, MatchMutation mutation,
                                  IdempotencyRecordRepository records, AuthApiResponseFactory responses,
                                  ObjectMapper json, PasswordEncoder passwords, Clock clock,
                                  @Value("${kiikick.match.idempotency-retention:P1D}") Duration retention) {
        this.users = users;
        this.credentials = credentials;
        this.sessions = sessions;
        this.requests = requests;
        this.emailLocks = emailLocks;
        this.flows = flows;
        this.attempts = attempts;
        this.mutation = mutation;
        this.records = records;
        this.responses = responses;
        this.json = json;
        this.passwords = passwords;
        this.clock = clock;
        this.retention = retention;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED,
            noRollbackFor = {InvalidPassword.class, RateLimited.class})
    public MatchMutation.Response request(UUID actor, String currentPassword, UUID key) {
        // 로그인과 같은 사용자 행을 잠가 탈퇴와 새 세션 발급의 순서를 보장한다.
        var user = users.findByIdForUpdate(actor).orElseThrow(MyProfileService.MissingUser::new);
        if (user.deletionRequestedAt() != null || requests.findActiveByUserId(actor).isPresent()) {
            throw new AlreadyRequested();
        }
        checkLimit(actor, utcNow());
        var credential = credentials.findByUserId(actor);
        if (currentPassword == null || currentPassword.length() > 72
                || currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
                || credential.filter(value -> passwords.matches(currentPassword, value.passwordHash())).isEmpty()) {
            var failedAt = utcNow();
            attempts.recordDeletionFailure(actor, failedAt);
            checkLimit(actor, failedAt);
            throw new InvalidPassword();
        }

        var now = utcNow();
        // 비밀번호는 재인증에만 쓰고 멱등 요청 지문에는 포함하지 않는다.
        var input = new MatchMutation.Request(actor, null, "POST", PATH, key, null, null);
        return mutation.executeUnversioned(input, now.plus(retention),
                () -> records.deleteExpiredByScope(actor, "POST", PATH, key, now), () -> {
                    var email = credential.orElseThrow().normalizedEmail();
                    // 발송과 같은 이메일 키를 잠가 기존 코드를 무효화하는 동안 새 코드가 생성되지 않게 한다.
                    emailLocks.lockEmailForAccountChange(email, now);
                    // 이메일 잠금 대기 중 생긴 인증 이력보다 이른 시각으로 무효화를 기록하지 않는다.
                    var acceptedAt = utcNow();
                    flows.invalidateUnconsumedSignUpFlows(email, acceptedAt);
                    if (users.markDeletionRequested(actor, acceptedAt) != 1) {
                        throw new AlreadyRequested();
                    }
                    var request = new AccountDeletionRequest(UUID.randomUUID(), actor, "PENDING", acceptedAt, null, null);
                    requests.save(request);
                    sessions.revokeAllByUserId(actor, acceptedAt);
                    if (credentials.deleteByUserId(actor) != 1) {
                        throw new org.springframework.dao.ConcurrencyFailureException("Account credential changed");
                    }
                    var body = responses.success(toReceipt(request));
                    String headers = json.writeValueAsString(Map.of("X-Request-Id", body.meta().requestId(),
                            "Cache-Control", "no-store", "Location", PATH + "/" + request.id(), "Retry-After", "3"));
                    return new MatchMutation.Response(202, headers, json.writeValueAsString(body));
                });
    }

    // TODO(#69): 탈퇴 후 조회 자격과 파기 후 상태 보존 계약이 확정되면 HTTP에 연결한다.
    @Transactional(readOnly = true)
    public Optional<Receipt> findReceipt(UUID actor, UUID deletionId) {
        return requests.findByIdAndUserId(deletionId, actor).map(AccountDeletionService::toReceipt);
    }

    private static Receipt toReceipt(AccountDeletionRequest request) {
        // UTC DATETIME을 시각으로 해석한 뒤 HTTP 응답 경계에서 서울 오프셋으로 표현한다.
        var completed = request.completedAt();
        return new Receipt(request.id(), request.status(),
                AuthApiResponseFactory.toSeoul(request.requestedAt().toInstant(ZoneOffset.UTC)),
                completed == null ? null : AuthApiResponseFactory.toSeoul(completed.toInstant(ZoneOffset.UTC)));
    }

    private void checkLimit(UUID actor, LocalDateTime now) {
        var retryAt = attempts.deletionBlockedUntil(actor, now);
        if (retryAt != null) throw new RateLimited(retryAt.toInstant(ZoneOffset.UTC));
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    public record Receipt(UUID id, String status, OffsetDateTime requestedAt, OffsetDateTime completedAt) {}
    public static final class AlreadyRequested extends RuntimeException {}
    public static final class InvalidPassword extends RuntimeException {}
    public static final class RateLimited extends RuntimeException {
        private final Instant retryAt;

        public RateLimited(Instant retryAt) { this.retryAt = retryAt; }
        public Instant retryAt() { return retryAt; }
    }
}
