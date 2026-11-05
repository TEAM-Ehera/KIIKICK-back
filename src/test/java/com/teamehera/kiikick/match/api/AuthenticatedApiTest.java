package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.auth.domain.AuthSession;
import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import com.teamehera.kiikick.auth.security.AccessTokenService;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

abstract class AuthenticatedApiTest {
    @Autowired private AuthSessionRepository authSessions;
    @Autowired private AccessTokenService accessTokens;
    private final ConcurrentHashMap<UUID, String> tokens = new ConcurrentHashMap<>();

    protected String bearer(UUID user) {
        return tokens.computeIfAbsent(user, id -> {
            var now = LocalDateTime.now(ZoneOffset.UTC);
            var session = new AuthSession(UUID.randomUUID(), id, null, UUID.randomUUID().toString(),
                    0, now.plusDays(1), null, now);
            authSessions.save(session);
            return "Bearer " + accessTokens.issue(id, session.id()).token();
        });
    }
}
