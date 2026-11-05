package com.teamehera.kiikick.auth.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

public class SessionJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final OAuth2Error INVALID_TOKEN = new OAuth2Error(
            "invalid_token", "유효하지 않은 인증 정보입니다.", null
    );

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId = parseUuid(jwt.getClaimAsString(AccessTokenService.USER_ID_CLAIM));
        UUID sessionId = parseUuid(jwt.getClaimAsString(AccessTokenService.SESSION_ID_CLAIM));

        // Access Token은 짧게 유지하고, 보호 API에서는 DB 세션 조회 없이 JWT 클레임만 사용한다.
        AuthenticatedUser principal = new AuthenticatedUser(userId, sessionId);
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
