package com.teamehera.kiikick.match.api;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

public final class MatchApiResponse {

    static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private MatchApiResponse() {}

    public interface Metadata {
        UUID requestId();
    }

    public interface Envelope {
        Metadata meta();
    }

    public record Meta(UUID requestId, OffsetDateTime serverTime) implements Metadata {
        public static Meta create() {
            return new Meta(UUID.randomUUID(), Instant.now().atZone(SEOUL).toOffsetDateTime());
        }
    }

    public record Success<T>(T data, Meta meta) implements Envelope {}

    public record Failure(Error error, Meta meta) implements Envelope {}

    public record Error(String code, String message, boolean retryable,
                        String persistence, Map<String, ?> details) {}

    public static OffsetDateTime fromUtc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC)
                .atZoneSameInstant(SEOUL).toOffsetDateTime();
    }

    public static LocalDateTime toUtc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
