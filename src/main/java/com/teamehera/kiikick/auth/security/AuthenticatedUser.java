package com.teamehera.kiikick.auth.security;

import java.util.UUID;

/** 인증된 요청에서만 만들어지는 내부 사용자·세션 식별자다. */
public record AuthenticatedUser(UUID userId, UUID sessionId) {
}
