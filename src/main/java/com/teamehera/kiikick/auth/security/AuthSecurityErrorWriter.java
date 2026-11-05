package com.teamehera.kiikick.auth.security;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import com.teamehera.kiikick.match.api.MatchApiResponse;
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
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        UUID requestId = UUID.randomUUID();
        response.setHeader("X-Request-Id", requestId.toString());
        if (status == 401) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        String persistence = switch (request.getMethod()) {
            case "GET", "HEAD" -> null;
            default -> "NOT_SAVED";
        };
        var body = new MatchApiResponse.Failure(
                new MatchApiResponse.Error(code, message, status >= 500, persistence, Map.of()),
                new MatchApiResponse.Meta(requestId, OffsetDateTime.now(clock.withZone(SEOUL))));
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
