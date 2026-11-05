package com.teamehera.kiikick.venue.service;

import com.teamehera.kiikick.match.api.MatchApiResponse;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.service.MatchListQuery.InvalidListRequest;
import com.teamehera.kiikick.match.service.MatchListQuery.Pagination;
import com.teamehera.kiikick.match.service.MatchOwnership.ResourceNotFound;
import com.teamehera.kiikick.venue.domain.Venue;
import com.teamehera.kiikick.venue.repository.VenueRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.teamehera.kiikick.match.api.MatchApiResponse.fromUtc;

@Service
public class VenueService {
    private final VenueRepository venues;
    private final MatchRepository matches;

    public VenueService(VenueRepository venues, MatchRepository matches) {
        this.venues = venues;
        this.matches = matches;
    }

    public Page list(UUID actor, String query, String cursor, Integer limit) {
        String search = query == null || query.isBlank() ? null : query.strip();
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100 || (search != null && search.codePointCount(0, search.length()) > 255)) {
            throw InvalidListRequest.of("INVALID_REQUEST");
        }
        String scope = "1|" + actor + "|" + encode(Objects.toString(search, "")) + "|";
        var boundary = cursor == null ? null : decode(cursor, scope);
        var rows = venues.findPageByOwnerId(actor, search, boundary == null ? null : boundary.updatedAt(),
                boundary == null ? null : boundary.id(), size + 1);
        boolean hasNext = rows.size() > size;
        var visible = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (hasNext) {
            var last = visible.getLast();
            next = encode(scope + last.updatedAt() + "|" + last.id());
        }
        var meta = MatchApiResponse.Meta.create();
        return new Page(visible.stream().map(View::from).toList(),
                new PageMeta(meta.requestId(), meta.serverTime(), new Pagination(next, hasNext)));
    }

    public View detail(UUID actor, UUID venueId) {
        return View.from(requireVenue(actor, venueId));
    }

    public View create(UUID actor, String name, String address) {
        var now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var venue = new Venue(UUID.randomUUID(), actor, name, address, now, now);
        venues.save(venue);
        return View.from(venue);
    }

    public View update(UUID actor, UUID venueId, String name, String address, boolean addressPresent) {
        var venue = requireVenue(actor, venueId);
        String nextName = name == null ? venue.name() : name;
        String nextAddress = addressPresent ? address : venue.address();
        if (!nextName.equals(venue.name()) || !Objects.equals(nextAddress, venue.address())) {
            venues.update(venueId, actor, nextName, nextAddress);
        }
        return detail(actor, venueId);
    }

    public void delete(UUID actor, UUID venueId) {
        requireVenue(actor, venueId);
        // Detach before the foreign key cascade so affected matches get a new version and retain their snapshots.
        matches.detachVenue(venueId);
        if (venues.deleteByIdAndOwnerId(venueId, actor) != 1) {
            throw new ResourceNotFound();
        }
    }

    private Venue requireVenue(UUID actor, UUID venueId) {
        return venues.findByIdAndOwnerId(venueId, actor).orElseThrow(ResourceNotFound::new);
    }

    private static Boundary decode(String cursor, String scope) {
        if (cursor.length() <= 2048) {
            try {
                String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                if (value.startsWith(scope)) {
                    String[] fields = value.substring(scope.length()).split("\\|", -1);
                    if (fields.length == 2) {
                        var updatedAt = LocalDateTime.parse(fields[0]);
                        var id = UUID.fromString(fields[1]);
                        if (id.toString().equals(fields[1]) && updatedAt.getYear() >= 1000 && updatedAt.getYear() <= 9999) {
                            return new Boundary(updatedAt, id);
                        }
                    }
                }
            } catch (IllegalArgumentException | DateTimeParseException ignored) {
                // Invalid cursor encodings and cursors from another search use the same response.
            }
        }
        throw InvalidListRequest.of("INVALID_CURSOR");
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Boundary(LocalDateTime updatedAt, UUID id) {}

    public record View(UUID id, String name, String address, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        static View from(Venue venue) {
            return new View(venue.id(), venue.name(), venue.address(), fromUtc(venue.createdAt()), fromUtc(venue.updatedAt()));
        }
    }

    public record Page(List<View> data, PageMeta meta) {}

    public record PageMeta(UUID requestId, OffsetDateTime serverTime, Pagination pagination) {}
}
