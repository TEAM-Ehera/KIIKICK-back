package com.teamehera.kiikick.observability;

import java.util.UUID;

/** 분석에 필요한 식별자만 허용하며 원본 운동·계정 정보는 담지 않는다. */
public record ProductEvent(Type type, UUID userId, UUID entityId) {
    public enum Type {
        USER_SIGNED_UP("user_signed_up"),
        MATCH_CREATED("match_created"),
        WORKOUT_UPLOADED("workout_uploaded");

        private final String eventName;

        Type(String eventName) { this.eventName = eventName; }

        public String eventName() { return eventName; }
    }
}
