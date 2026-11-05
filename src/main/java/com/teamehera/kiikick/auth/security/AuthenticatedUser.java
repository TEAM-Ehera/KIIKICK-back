package com.teamehera.kiikick.auth.security;

import java.util.UUID;
import java.security.Principal;

/** 인증된 요청에서만 만들어지는 내부 사용자·세션 식별자다. */
public record AuthenticatedUser(UUID userId, UUID sessionId) implements Principal {
    // Servlet Principal과 @AuthenticationPrincipal이 같은 내부 사용자 ID를 가리킨다.
    @Override
    public String getName() {
        return userId.toString();
    }
}
