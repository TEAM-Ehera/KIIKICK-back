package com.teamehera.kiikick.auth.controller;

import com.teamehera.kiikick.auth.dto.AuthApiMeta;
import com.teamehera.kiikick.auth.dto.AuthApiResponse;
import org.springframework.stereotype.Component;

import java.time.Clock;
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

    public AuthApiMeta meta() {
        return new AuthApiMeta(
                "req-" + UUID.randomUUID(),
                clock.instant()
        );
    }

}
