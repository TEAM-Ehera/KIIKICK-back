package com.teamehera.kiikick.user.service;

import com.teamehera.kiikick.auth.controller.AuthApiResponseFactory;
import com.teamehera.kiikick.global.idempotency.repository.IdempotencyRecordRepository;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.user.domain.UserConsent;
import com.teamehera.kiikick.user.dto.OnboardingRequest;
import com.teamehera.kiikick.user.repository.UserConsentRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
public class OnboardingService {
    private static final String PATH = "/api/v1/users/me/onboarding";
    private final UserRepository users;
    private final UserConsentRepository consents;
    private final MyProfileService profiles;
    private final MatchMutation mutation;
    private final IdempotencyRecordRepository records;
    private final AuthApiResponseFactory responses;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration retention;

    public OnboardingService(UserRepository users, UserConsentRepository consents, MyProfileService profiles,
            MatchMutation mutation, IdempotencyRecordRepository records, AuthApiResponseFactory responses,
            ObjectMapper json, Clock clock, @Value("${kiikick.match.idempotency-retention:P1D}") Duration retention) {
        this.users = users; this.consents = consents; this.profiles = profiles; this.mutation = mutation;
        this.records = records; this.responses = responses; this.json = json; this.clock = clock; this.retention = retention;
    }

    public MatchMutation.Response complete(UUID actor, OnboardingRequest input, UUID key, String ifMatch, byte[] body) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        var request = new MatchMutation.Request(actor, null, "PUT", PATH, key, ifMatch, body);
        // 기존 멱등 서비스의 사용자 행 잠금·트랜잭션 안에서 이력과 완료 응답까지 함께 저장한다.
        return mutation.executeUnversioned(request, now.plus(retention),
                () -> records.deleteExpiredByScope(actor, "PUT", PATH, key, now), () -> {
                    var user = users.findById(actor).orElseThrow(MyProfileService.MissingUser::new);
                    if (user.onboardingCompletedAt() != null) throw new AlreadyCompleted();
                    if (users.completeOnboarding(actor, input.nickname(), now) != 1) throw new AlreadyCompleted();
                    // 빈 배열·거부는 허용하고 미제출 목적의 동의 이력은 만들지 않는다.
                    for (var consent : input.consents()) {
                        consents.save(new UserConsent(UUID.randomUUID(), actor, consent.purpose(),
                                consent.policyVersion(), consent.granted(), now));
                    }
                    var response = responses.success(profiles.get(actor));
                    String headers = json.writeValueAsString(Map.of("X-Request-Id", response.meta().requestId(), "Cache-Control", "no-store"));
                    return new MatchMutation.Response(200, headers, json.writeValueAsString(response));
                });
    }

    public static final class AlreadyCompleted extends RuntimeException {}
}
