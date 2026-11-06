package com.teamehera.kiikick.auth.security;

import java.util.UUID;

/** 인증된 요청에서만 만들어지는 내부 사용자·세션 식별자다. */
public record AuthenticatedUser(UUID userId, UUID sessionId) implements java.security.Principal {
    @Override
    public String getName() {
        // 기존 API도 서명 검증된 내부 UUID를 Principal 이름으로 읽을 수 있게 한다.
        return userId.toString();
    }
}
