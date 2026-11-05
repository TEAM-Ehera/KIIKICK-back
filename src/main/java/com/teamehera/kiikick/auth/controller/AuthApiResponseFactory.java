package com.teamehera.kiikick.auth.controller;

import com.teamehera.kiikick.auth.dto.AuthApiMeta;
import com.teamehera.kiikick.auth.dto.AuthApiResponse;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

@Component
public class AuthApiResponseFactory {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Clock clock;

    public AuthApiResponseFactory(Clock clock) {
        this.clock = clock;
    }

    public <T> AuthApiResponse<T> success(T data) {
        return new AuthApiResponse<>(data, meta());
    }

    public AuthApiMeta meta() {
        return new AuthApiMeta(
                UUID.randomUUID(),
                OffsetDateTime.now(clock.withZone(SEOUL))
        );
    }

    public OffsetDateTime toSeoul(LocalDateTime utcDateTime) {
        return utcDateTime.atOffset(ZoneOffset.UTC).atZoneSameInstant(SEOUL)
                .toOffsetDateTime();
    }
}
