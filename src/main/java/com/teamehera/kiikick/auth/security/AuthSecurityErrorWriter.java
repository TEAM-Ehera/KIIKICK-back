package com.teamehera.kiikick.auth.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

public class AuthSecurityErrorWriter {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

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
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        ErrorBody body = new ErrorBody(
                new ErrorDetail(code, message, false, null, Map.of()),
                new ErrorMeta(
                        "req-" + UUID.randomUUID(),
                        OffsetDateTime.now(clock.withZone(SEOUL))
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
