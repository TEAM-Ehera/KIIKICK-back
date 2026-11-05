package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.match.api.MatchApiResponse;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.service.MatchDetailQuery.MatchResponse;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class MatchListQuery {

    private static final Set<String> STATUSES =
            Set.of("DRAFT", "READY", "IN_PROGRESS", "COMPLETED", "CANCELLED");

    private final MatchRepository matches;

    public MatchListQuery(MatchRepository matches) {
        this.matches = matches;
    }

    public Page find(UUID actorUserId, String status, String cursor, Integer limit) {
        Objects.requireNonNull(actorUserId);
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100 || (status != null && !STATUSES.contains(status))) {
            throw new InvalidListRequest("INVALID_REQUEST");
        }

        var boundary = cursor == null ? null : decode(cursor, actorUserId, status);
        var rows = matches.findPageByOwnerId(actorUserId, status,
                boundary == null ? null : boundary.createdAt(),
                boundary == null ? null : boundary.id(), size + 1);
        boolean hasNext = rows.size() > size;
        var page = rows.subList(0, Math.min(size, rows.size()));
        String nextCursor = null;
        if (hasNext) {
            var last = page.getLast();
            String value = scope(actorUserId, status) + last.createdAt() + "|" + last.id();
            nextCursor = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }
        var meta = MatchApiResponse.Meta.create();
        return new Page(page.stream().map(MatchResponse::from).toList(),
                new PageMeta(meta.requestId(), meta.serverTime(), new Pagination(nextCursor, hasNext)));
    }

    private Cursor decode(String token, UUID actor, String status) {
        if (token.length() > 256) {
            throw new InvalidListRequest("INVALID_CURSOR");
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String prefix = scope(actor, status);
            if (value.startsWith(prefix)) {
                String[] boundary = value.substring(prefix.length()).split("\\|", -1);
                if (boundary.length == 2) {
                    var time = LocalDateTime.parse(boundary[0]);
                    var id = UUID.fromString(boundary[1]);
                    if (time.getYear() >= 1000 && time.getYear() <= 9999 && id.toString().equals(boundary[1])) {
                        return new Cursor(time, id);
                    }
                }
            }
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new InvalidListRequest("INVALID_CURSOR");
        }
        throw new InvalidListRequest("INVALID_CURSOR");
    }

    private String scope(UUID actor, String status) {
        return "1|" + actor + "|" + Objects.toString(status, "") + "|";
    }

    private record Cursor(LocalDateTime createdAt, UUID id) {}

    public record Page(List<MatchResponse> data, PageMeta meta) {}

    public record PageMeta(UUID requestId, OffsetDateTime serverTime, Pagination pagination) {}

    public record Pagination(String nextCursor, boolean hasNext) {}

    public static final class InvalidListRequest extends RuntimeException {
        InvalidListRequest(String code) {
            super(code);
        }

        public static InvalidListRequest of(String code) {
            return new InvalidListRequest(code);
        }
    }
}
