package com.teamehera.kiikick.auth.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import com.teamehera.kiikick.auth.controller.AuthApiResponseFactory;
import java.util.Map;
import java.util.UUID;

public class AuthSecurityErrorWriter {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuthSecurityErrorWriter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void write(
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ErrorBody body = new ErrorBody(
                new ErrorDetail(code, message, false, null, Map.of()),
                new ErrorMeta(
                        "req-" + UUID.randomUUID(),
                        AuthApiResponseFactory.toSeoul(clock.instant())
                )
        );
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    record ErrorBody(ErrorDetail error, ErrorMeta meta) {
    }

    record ErrorDetail(
            String code,
            String message,
            boolean retryable,
            String persistence,
            Map<String, Object> details
    ) {
    }

    record ErrorMeta(String requestId, OffsetDateTime serverTime) {
    }
}
