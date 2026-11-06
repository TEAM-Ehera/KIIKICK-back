package com.teamehera.kiikick.auth.security;

import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.util.UUID;

public final class TestJwtRequests {
    private TestJwtRequests() { }
    public static RequestPostProcessor bearer(AccessTokenService issuer, UUID userId) {
        // 실제 서명 토큰으로 필터·클레임 검증·Principal 변환을 모두 통과시킨다.
        String token=issuer.issue(userId, UUID.randomUUID()).token();
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }
}
