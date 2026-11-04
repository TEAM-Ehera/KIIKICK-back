package com.teamehera.kiikick.match.api;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record MatchSettings(Field<String> title, Field<UUID> venueId,
                            Field<Snapshot> venueSnapshot, Field<OffsetDateTime> scheduledAt) {
    public record Field<T>(boolean present, T value) {
        public T orElse(T previous) {
            return present ? value : previous;
        }
    }

    public record Snapshot(String name, String address) {}

    public static MatchSettings parse(ObjectMapper json, byte[] body) {
        JsonNode root = readBody(json, body);
        checkObject(root, "body", Set.of("title", "venueId", "venueSnapshot", "scheduledAt"));
        var venueId = new Field<>(root.has("venueId"), uuid(text(root, "venueId", 36)));
        var snapshot = new Field<>(root.has("venueSnapshot"), snapshot(root.get("venueSnapshot")));
        if (venueId.value() != null && snapshot.value() != null) {
            throw new InvalidSettings(List.of("venueId", "venueSnapshot"));
        }
        return new MatchSettings(new Field<>(root.has("title"), text(root, "title", 255)),
                venueId, snapshot,
                new Field<>(root.has("scheduledAt"), scheduledAt(text(root, "scheduledAt", 40))));
    }

    static JsonNode readBody(ObjectMapper json, byte[] body) {
        if (body == null) {
            throw new MatchController.InvalidRequest();
        }
        try {
            return json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .readTree(body);
        } catch (JacksonException exception) {
            throw new MatchController.InvalidRequest();
        }
    }

    static void checkObject(JsonNode node, String field, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            throw new InvalidSettings(List.of(field));
        }
        var unknown = node.propertyNames().stream().filter(name -> !allowed.contains(name)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new InvalidSettings(unknown);
        }
    }

    private static Snapshot snapshot(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        checkObject(node, "venueSnapshot", Set.of("name", "address"));
        return new Snapshot(text(node, "name", 255), text(node, "address", 1000));
    }

    private static String text(JsonNode node, String field, int maxLength) {
        var value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().codePointCount(0, value.asText().length()) > maxLength) {
            throw new InvalidSettings(List.of(field));
        }
        return value.asText();
    }

    private static UUID uuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) {
                return id;
            }
        } catch (IllegalArgumentException ignored) {
            // Report malformed and noncanonical UUIDs with the same error.
        }
        throw new InvalidSettings(List.of("venueId"));
    }

    private static OffsetDateTime scheduledAt(String value) {
        if (value == null) {
            return null;
        }
        try {
            var time = OffsetDateTime.parse(value);
            int utcYear = MatchApiResponse.toUtc(time).getYear();
            if (utcYear >= 1000 && utcYear <= 9999) {
                return time;
            }
        } catch (DateTimeParseException ignored) {
            // Report invalid timestamps and out-of-range dates with the same error.
        }
        throw new InvalidSettings(List.of("scheduledAt"));
    }

    public static final class InvalidSettings extends RuntimeException {
        private final List<String> fields;

        InvalidSettings(List<String> fields) {
            this.fields = fields;
        }

        public List<String> fields() {
            return fields;
        }
    }
}
