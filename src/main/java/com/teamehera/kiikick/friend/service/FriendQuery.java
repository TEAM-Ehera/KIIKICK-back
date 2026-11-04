package com.teamehera.kiikick.friend.service;

import com.teamehera.kiikick.friend.repository.FriendDirectoryRepository;
import com.teamehera.kiikick.friend.repository.FriendDirectoryRepository.Row;
import com.teamehera.kiikick.match.api.MatchApiResponse;
import com.teamehera.kiikick.match.service.MatchListQuery.InvalidListRequest;
import com.teamehera.kiikick.match.service.MatchListQuery.Pagination;
import com.teamehera.kiikick.match.service.MatchOwnership.ResourceNotFound;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static com.teamehera.kiikick.match.api.MatchApiResponse.fromUtc;

@Service
public class FriendQuery {
    private final FriendDirectoryRepository directory;

    public FriendQuery(FriendDirectoryRepository directory) {
        this.directory = directory;
    }

    public Profile profile(UUID actor, UUID target) {
        return profile(actor, directory.profile(actor, target).orElseThrow(ResourceNotFound::new));
    }

    public Page<Profile> search(UUID actor, String query, String cursor, Integer limit) {
        String search = query == null ? "" : query.strip();
        int length = search.codePointCount(0, search.length());
        if (length < 2 || length > 255) {
            throw new InvalidSearch();
        }
        int size = size(limit);
        String scope = "1|search|" + actor + "|" + encode(search) + "|";
        var key = decode(cursor, scope);
        var rows = directory.search(actor, search, key.value(), key.id(), size + 1);
        return page(rows, size, row -> profile(actor, row),
                row -> scope + row.userId() + "|" + row.nickname());
    }

    public Page<Friend> friends(UUID actor, String cursor, Integer limit) {
        int size = size(limit);
        String scope = "1|friends|" + actor + "|";
        var key = decode(cursor, scope);
        var rows = directory.friends(actor, time(key), key.id(), size + 1);
        return page(rows, size, row -> new Friend(profile(actor, row), fromUtc(row.acceptedAt())),
                row -> scope + row.pairId() + "|" + row.acceptedAt());
    }

    public Page<Request> requests(UUID actor, String direction, String cursor, Integer limit) {
        if (!"SENT".equals(direction) && !"RECEIVED".equals(direction)) {
            throw InvalidListRequest.of("INVALID_REQUEST");
        }
        int size = size(limit);
        String scope = "1|requests|" + actor + "|" + direction + "|";
        var key = decode(cursor, scope);
        var rows = directory.requests(actor, "SENT".equals(direction), time(key), key.id(), size + 1);
        return page(rows, size, row -> new Request(row.pairId(), row.requesterId(),
                        actor.equals(row.requesterId()) ? row.userId() : actor, row.status(), fromUtc(row.createdAt())),
                row -> scope + row.pairId() + "|" + row.createdAt());
    }

    private static Profile profile(UUID actor, Row row) {
        String relationship = switch (row.status() == null ? "" : row.status()) {
            case "ACCEPTED" -> "FRIEND";
            case "PENDING" -> actor.equals(row.requesterId()) ? "PENDING_SENT" : "PENDING_RECEIVED";
            default -> "NONE";
        };
        return new Profile(row.userId(), row.nickname(), row.avatarUrl(), relationship);
    }

    private static int size(Integer limit) {
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100) {
            throw InvalidListRequest.of("INVALID_REQUEST");
        }
        return size;
    }

    private static <T> Page<T> page(List<Row> rows, int size, Function<Row, T> mapper,
                                   Function<Row, String> nextKey) {
        boolean hasNext = rows.size() > size;
        var visible = rows.subList(0, Math.min(size, rows.size()));
        String next = hasNext ? encode(nextKey.apply(visible.getLast())) : null;
        var meta = MatchApiResponse.Meta.create();
        return new Page<>(visible.stream().map(mapper).toList(),
                new PageMeta(meta.requestId(), meta.serverTime(), new Pagination(next, hasNext)));
    }

    private static Key decode(String cursor, String scope) {
        if (cursor == null) {
            return new Key(null, null);
        }
        if (cursor.length() <= 4000) {
            try {
                String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                if (value.startsWith(scope)) {
                    String[] fields = value.substring(scope.length()).split("\\|", 2);
                    if (fields.length == 2 && !fields[1].isBlank()) {
                        var id = UUID.fromString(fields[0]);
                        if (id.toString().equals(fields[0])) {
                            return new Key(id, fields[1]);
                        }
                    }
                }
            } catch (IllegalArgumentException ignored) {
                // The same error covers malformed cursors and cursors for another viewer or filter.
            }
        }
        throw InvalidListRequest.of("INVALID_CURSOR");
    }

    private static LocalDateTime time(Key key) {
        if (key.value() == null) {
            return null;
        }
        try {
            var timestamp = LocalDateTime.parse(key.value());
            if (timestamp.getYear() >= 1000 && timestamp.getYear() <= 9999) {
                return timestamp;
            }
        } catch (DateTimeParseException ignored) {
            // Reject invalid database timestamps as malformed cursors.
        }
        throw InvalidListRequest.of("INVALID_CURSOR");
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Key(UUID id, String value) {}
    public record Profile(UUID id, String nickname, String avatarUrl, String relationship) {}
    public record Friend(Profile profile, OffsetDateTime friendsSince) {}
    public record Request(UUID id, UUID requesterId, UUID recipientId, String status, OffsetDateTime createdAt) {}
    public record Page<T>(List<T> data, PageMeta meta) {}
    public record PageMeta(UUID requestId, OffsetDateTime serverTime, Pagination pagination) {}
    public static final class InvalidSearch extends RuntimeException {}
}
