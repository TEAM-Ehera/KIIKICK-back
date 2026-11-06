package com.teamehera.kiikick.user.service;

import com.teamehera.kiikick.auth.controller.AuthApiResponseFactory;
import com.teamehera.kiikick.global.idempotency.repository.IdempotencyRecordRepository;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.user.dto.ProfilePatchRequest;
import com.teamehera.kiikick.user.dto.UserSelfResponse;
import com.teamehera.kiikick.user.repository.UserConsentRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
public class MyProfileService {
    private static final String PATH = "/api/v1/users/me";
    private final UserRepository users;
    private final UserConsentRepository consents;
    private final MatchMutation mutation;
    private final IdempotencyRecordRepository records;
    private final AuthApiResponseFactory responses;
    private final ObjectMapper json;
    private final Clock clock;
    private final Duration retention;

    public MyProfileService(UserRepository users, UserConsentRepository consents, MatchMutation mutation,
            IdempotencyRecordRepository records, AuthApiResponseFactory responses, ObjectMapper json, Clock clock,
            @Value("${kiikick.match.idempotency-retention:P1D}") Duration retention) {
        this.users = users; this.consents = consents; this.mutation = mutation; this.records = records;
        this.responses = responses; this.json = json; this.clock = clock; this.retention = retention;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserSelfResponse get(UUID actor) {
        var user = users.findById(actor).orElseThrow(MissingUser::new);
        boolean consent = consents.findLatest(actor, "FRIEND_COMPARISON").map(c -> c.granted()).orElse(false);
        return new UserSelfResponse(user.id(), user.nickname(), user.avatarUrl(),
                user.onboardingCompletedAt() == null ? "REQUIRED" : "COMPLETED", consent,
                AuthApiResponseFactory.toSeoul(user.createdAt().toInstant(ZoneOffset.UTC)));
    }

    public MatchMutation.Response patch(UUID actor, ProfilePatchRequest patch, UUID key, String ifMatch, byte[] body) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        var request = new MatchMutation.Request(actor, null, "PATCH", PATH, key, ifMatch, body);
        // 공통 사용자 행 잠금 안에서 기존 값 병합·DB 수정·멱등 응답 저장을 함께 처리한다.
        return mutation.executeUnversioned(request, now.plus(retention),
                () -> records.deleteExpiredByScope(actor, "PATCH", PATH, key, now), () -> {
                    var current = users.findById(actor).orElseThrow(MissingUser::new);
                    int updated = users.updateProfile(actor,
                            patch.nicknamePresent() ? patch.nickname() : current.nickname(),
                            patch.avatarPresent() ? patch.avatarUrl() : current.avatarUrl());
                    if (updated != 1) throw new MissingUser();
                    var response = responses.success(get(actor));
                    String headers = json.writeValueAsString(Map.of("X-Request-Id", response.meta().requestId(), "Cache-Control", "no-store"));
                    return new MatchMutation.Response(200, headers, json.writeValueAsString(response));
                });
    }

    public static final class MissingUser extends RuntimeException {
        public MissingUser() { super("Authenticated user unavailable"); }
    }
}
