package com.teamehera.kiikick.auth.controller;

import com.teamehera.kiikick.auth.dto.AuthApiMeta;
import com.teamehera.kiikick.auth.dto.AuthApiResponse;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Component
public class AuthApiResponseFactory {

    private final Clock clock;

    public AuthApiResponseFactory(Clock clock) {
        this.clock = clock;
    }

    public <T> AuthApiResponse<T> success(T data) {
        return new AuthApiResponse<>(data, meta());
    }

    public static OffsetDateTime toSeoul(Instant instant) {
        // 저장·계산한 시각을 유지하고 응답 표현만 계약의 서울 오프셋으로 변환한다.
        return instant.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
    }

    public AuthApiMeta meta() {
        return new AuthApiMeta(
                "req-" + UUID.randomUUID(),
                toSeoul(clock.instant())
        );
    }

}
