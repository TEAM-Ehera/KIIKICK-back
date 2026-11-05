package com.teamehera.kiikick.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Map;
import java.util.UUID;

public record LogoutRequest(UUID sessionId) {
    @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
    public static LogoutRequest from(Map<String,Object> body) {
        if (body==null || body.keySet().stream().anyMatch(k -> !k.equals("sessionId")))
            throw new InvalidInput("body");
        // 누락은 현재 세션을 의미하지만 명시적인 null은 계약상 거부한다.
        if (!body.containsKey("sessionId")) return new LogoutRequest(null);
        if (!(body.get("sessionId") instanceof String value)) throw new InvalidInput("sessionId");
        return new LogoutRequest(uuid(value,"sessionId"));
    }

    public static UUID uuid(String value, String field) {
        if (value!=null && value.length()==36) {
            try {
                UUID id=UUID.fromString(value);
                if (id.toString().equalsIgnoreCase(value)) return id;
            } catch (IllegalArgumentException ignored) { }
        }
        throw new InvalidInput(field);
    }

    public static final class InvalidInput extends RuntimeException {
        private final String field;
        public InvalidInput(String field) { super("Invalid logout request"); this.field=field; }
        public String field() { return field; }
    }
}
