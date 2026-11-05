package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.match.api.MatchApiResponse;
import com.teamehera.kiikick.match.api.MatchSettings;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.venue.repository.VenueRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class MatchSettingsService {

    private final MatchRepository matches;
    private final MatchOwnership ownership;
    private final VenueRepository venues;

    public MatchSettingsService(MatchRepository matches, MatchOwnership ownership, VenueRepository venues) {
        this.matches = matches;
        this.ownership = ownership;
        this.venues = venues;
    }

    // Called inside the mutation transaction that also stores the HTTP response.
    public Match create(UUID actor, MatchSettings settings) {
        var now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        var draft = new Match(UUID.randomUUID(), actor, null, null, null, null,
                null, "DRAFT", null, null, 1, now, now);
        var match = apply(draft, settings);
        matches.save(match);
        return ownership.requireMatch(actor, match.id());
    }

    // The mutation transaction holds the owner-scoped match lock.
    public Match update(Match current, MatchSettings settings) {
        if (!"DRAFT".equals(current.status()) && !"READY".equals(current.status())) {
            throw new InvalidState();
        }
        if (matches.updateSettings(apply(current, settings), current.version()) != 1) {
            throw new IllegalStateException("Could not update the locked match");
        }
        return ownership.requireMatch(current.ownerId(), current.id());
    }

    private Match apply(Match match, MatchSettings settings) {
        UUID venueId = match.venueId();
        String name = match.venueNameSnapshot();
        String address = match.venueAddressSnapshot();
        if (settings.venueId().present() || settings.venueSnapshot().present()) {
            venueId = settings.venueId().value();
            if (venueId != null) {
                var venue = venues.findByIdAndOwnerId(venueId, match.ownerId())
                        .orElseThrow(MatchOwnership.ResourceNotFound::new);
                name = venue.name();
                address = venue.address();
            } else {
                var snapshot = settings.venueSnapshot().value();
                name = snapshot == null ? null : snapshot.name();
                address = snapshot == null ? null : snapshot.address();
            }
        }
        LocalDateTime scheduledAt = settings.scheduledAt().present()
                ? MatchApiResponse.toUtc(settings.scheduledAt().value()) : match.scheduledAt();
        return new Match(match.id(), match.ownerId(), venueId, settings.title().orElse(match.title()),
                name, address, scheduledAt == null ? null : scheduledAt.truncatedTo(ChronoUnit.MICROS),
                match.status(), match.startedAt(), match.endedAt(), match.version(),
                match.createdAt(), match.updatedAt());
    }

    public static final class InvalidState extends RuntimeException {}
}
