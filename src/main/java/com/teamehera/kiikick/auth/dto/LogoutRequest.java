package com.teamehera.kiikick.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Map;
import java.util.UUID;

@Schema(description = "{}는 현재 세션. sessionId 명시 시 다른 본인 세션도 허용. 명시적 null은 422", additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record LogoutRequest(@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = false, type = "string", format = "uuid", description = "생략하면 현재 JWT 세션, 명시적 null 거부") UUID sessionId) {
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
