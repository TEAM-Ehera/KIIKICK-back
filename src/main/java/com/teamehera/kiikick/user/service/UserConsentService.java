package com.teamehera.kiikick.user.service;

import com.teamehera.kiikick.auth.controller.AuthApiResponseFactory;
import com.teamehera.kiikick.auth.domain.ConsentPolicy;
import com.teamehera.kiikick.global.idempotency.repository.IdempotencyRecordRepository;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.user.domain.UserConsent;
import com.teamehera.kiikick.user.dto.ConsentResponse;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;
import com.teamehera.kiikick.user.repository.UserConsentRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.List;
import java.util.UUID;

@Service
public class UserConsentService {
    private final UserRepository users;
    private final UserConsentRepository consents;
    private final MatchMutation mutation;
    private final IdempotencyRecordRepository records;
    private final AuthApiResponseFactory responses;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration retention;

    public UserConsentService(UserRepository users, UserConsentRepository consents,
                              MatchMutation mutation, IdempotencyRecordRepository records,
                              AuthApiResponseFactory responses, ObjectMapper json, Clock clock,
                              @Value("${kiikick.match.idempotency-retention:P1D}") Duration retention) {
        this.users = users;
        this.consents = consents;
        this.mutation = mutation;
        this.records = records;
        this.responses = responses;
        this.json = json;
        this.clock = clock;
        this.retention = retention;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ConsentResponse> get(UUID actor) {
        users.findById(actor).filter(user -> user.deletionRequestedAt() == null)
                .orElseThrow(MyProfileService.MissingUser::new);
        // 최신값·동률 ID·정렬은 기존 SQL을 재사용하고 타 목적 이력은 보존한 채 응답에서만 제외한다.
        return consents.findLatestByUserId(actor).stream()
                .filter(consent -> ConsentPolicy.supportsPurpose(consent.purpose()))
                .map(UserConsentService::toResponse)
                .toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public boolean hasEffectiveConsent(UUID userId, String purpose) {
        if (!ConsentPolicy.supportsPurpose(purpose)) {
            return false;
        }
        // 같은 조회 시점에 계정 상태와 최신 이력을 확인하고 이전 부여 이력으로 되돌아가지 않는다.
        return consents.findLatestForActiveUser(userId, purpose)
                .filter(consent -> consent.granted() && ConsentPolicy.supports(purpose, consent.policyVersion()))
                .isPresent();
    }

    public MatchMutation.Response change(UUID actor, String purpose, String policyVersion, boolean granted,
                                         UUID key, String ifMatch, byte[] body) {
        String path = "/api/v1/users/me/consents/" + purpose;
        LocalDateTime now = utcNow();
        var request = new MatchMutation.Request(actor, null, "PUT", path, key, ifMatch, body);
        // MatchMutation의 사용자 행 잠금과 트랜잭션을 동의 이력과 멱등 응답에 공유한다.
        return mutation.executeUnversioned(request, now.plus(retention), () -> {
            users.findById(actor).filter(user -> user.deletionRequestedAt() == null)
                    .orElseThrow(MyProfileService.MissingUser::new);
            records.deleteExpiredByScope(actor, "PUT", path, key, utcNow());
        }, () -> {
            LocalDateTime recordedAt = utcNow();
            var latest = consents.findLatest(actor, purpose);
            // 시각이 같거나 역행해도 방금 저장한 이력이 GET에서 최신으로 선택되어야 한다.
            if (latest.isPresent() && !recordedAt.isAfter(latest.get().recordedAt())) {
                recordedAt = latest.get().recordedAt().plus(1, ChronoUnit.MICROS);
            }
            var consent = new UserConsent(UUID.randomUUID(), actor, purpose, policyVersion, granted, recordedAt);
            consents.save(consent);
            var response = responses.success(toResponse(consent));
            String headers = json.writeValueAsString(Map.of(
                    "X-Request-Id", response.meta().requestId(), "Cache-Control", "no-store"));
            return new MatchMutation.Response(200, headers, json.writeValueAsString(response));
        });
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static ConsentResponse toResponse(UserConsent consent) {
        return new ConsentResponse(consent.purpose(), consent.policyVersion(), consent.granted(),
                AuthApiResponseFactory.toSeoul(consent.recordedAt().toInstant(ZoneOffset.UTC)));
    }
}
