package com.teamehera.kiikick.auth.security;

import com.teamehera.kiikick.auth.domain.AuthSession;
import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

public class SessionJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final OAuth2Error INVALID_TOKEN = new OAuth2Error(
            "invalid_token", "유효하지 않은 인증 정보입니다.", null
    );

    private final AuthSessionRepository authSessionRepository;
    private final Clock clock;

    public SessionJwtAuthenticationConverter(
            AuthSessionRepository authSessionRepository,
            Clock clock
    ) {
        this.authSessionRepository = authSessionRepository;
        this.clock = clock;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId = parseUuid(jwt.getClaimAsString(AccessTokenService.USER_ID_CLAIM));
        UUID sessionId = parseUuid(jwt.getClaimAsString(AccessTokenService.SESSION_ID_CLAIM));
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        // 서명이 유효해도 DB 세션이 폐기·만료되면 Access Token을 즉시 거부한다.
        AuthSession session = authSessionRepository.findActiveById(sessionId, now)
                .filter(activeSession -> activeSession.userId().equals(userId))
                .orElseThrow(() -> new OAuth2AuthenticationException(INVALID_TOKEN));

        AuthenticatedUser principal = new AuthenticatedUser(
                session.userId(), session.id()
        );
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, jwt, List.of()
        );
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new OAuth2AuthenticationException(INVALID_TOKEN, exception);
        }
    }
}
