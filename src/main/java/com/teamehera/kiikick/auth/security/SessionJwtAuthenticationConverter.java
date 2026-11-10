package com.teamehera.kiikick.auth.security;

import com.teamehera.kiikick.user.repository.UserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
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

    private final UserRepository users;

    public SessionJwtAuthenticationConverter(UserRepository users) {
        this.users = users;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId = parseUuid(jwt.getClaimAsString(AccessTokenService.USER_ID_CLAIM));
        UUID sessionId = parseUuid(jwt.getClaimAsString(AccessTokenService.SESSION_ID_CLAIM));

        // 세션 대신 사용자 상태를 확인해 탈퇴 전에 발급된 Access Token도 차단한다.
        boolean active;
        try {
            active = users.isActiveForAuthentication(userId);
        } catch (DataAccessException exception) {
            throw new OAuth2AuthenticationException(INVALID_TOKEN, exception);
        }
        if (!active) {
            throw new OAuth2AuthenticationException(INVALID_TOKEN);
        }
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
