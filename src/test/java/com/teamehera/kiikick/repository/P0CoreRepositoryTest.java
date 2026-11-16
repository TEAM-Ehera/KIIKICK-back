package com.teamehera.kiikick.repository;

import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.domain.Quarter;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterRepository;
import com.teamehera.kiikick.match.service.MatchOwnership;
import com.teamehera.kiikick.match.service.MatchDetailQuery;
import com.teamehera.kiikick.match.service.MatchListQuery;
import com.teamehera.kiikick.user.repository.UserRepository;
import com.teamehera.kiikick.venue.domain.Venue;
import com.teamehera.kiikick.venue.repository.VenueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@JdbcTest
@Import({
        UserRepository.class, VenueRepository.class,
        MatchRepository.class, QuarterRepository.class
})
class P0CoreRepositoryTest {

    @Autowired UserRepository userRepository;
    @Autowired VenueRepository venueRepository;
    @Autowired MatchRepository matchRepository;
    @Autowired QuarterRepository quarterRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void softDeleteRequiresOwnerAndVersionAndPreservesTheMatchRow() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID venue = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        LocalDateTime time = LocalDateTime.of(2026, 11, 4, 0, 0);
        userRepository.save(owner, null);
        userRepository.save(other, null);
        venueRepository.save(new Venue(venue, owner, "풋살장", null, time, time));
        var match = new Match(id, owner, venue, null, null, null,
                null, "DRAFT", null, null, 7, time, time);
        matchRepository.save(match);

        assertTrue(matchRepository.findDeletionStateForUpdate(id, other).isEmpty());
        assertEquals(0, matchRepository.softDelete(id, other, 7));
        assertEquals(0, matchRepository.softDelete(id, owner, 6));
        assertEquals(new MatchRepository.DeletionState(7, null),
                matchRepository.findDeletionStateForUpdate(id, owner).orElseThrow());

        assertEquals(1, matchRepository.softDelete(id, owner, 7));
        var deleted = matchRepository.findDeletionStateForUpdate(id, owner).orElseThrow();
        assertNotNull(deleted.deletedAt());
        assertEquals(8, deleted.version());
        assertEquals(deleted.deletedAt(), jdbc.queryForObject(
                "SELECT updated_at FROM matches WHERE id = ?", LocalDateTime.class, uuidToBytes(id)));
        assertAll(
                () -> assertTrue(matchRepository.existsById(id)),
                () -> assertFalse(matchRepository.existsById(UUID.randomUUID())),
                () -> assertTrue(matchRepository.findById(id).isEmpty()),
                () -> assertTrue(matchRepository.findByIdAndOwnerId(id, owner).isEmpty()),
                () -> assertTrue(matchRepository.findByIdAndOwnerIdForUpdate(id, owner).isEmpty()),
                () -> assertEquals(0, matchRepository.softDelete(id, owner, 8)),
                () -> assertEquals(0, matchRepository.updateSettings(match, 8)),
                () -> assertEquals(0, matchRepository.updateStatus(id, owner, 8, "DRAFT", "READY", null, null)),
                () -> assertEquals(0, matchRepository.incrementVersion(id, owner, 8))
        );
        matchRepository.detachVenue(venue);
        assertEquals(deleted, matchRepository.findDeletionStateForUpdate(id, owner).orElseThrow());
    }

    @Test
    void deletedCompletedMatchIsSkippedByPagesAndQuarterLookups() {
        UUID owner = UUID.randomUUID();
        UUID newest = UUID.randomUUID();
        UUID removed = UUID.randomUUID();
        UUID oldest = UUID.randomUUID();
        UUID quarter = UUID.randomUUID();
        LocalDateTime time = LocalDateTime.of(2026, 11, 4, 0, 0);
        userRepository.save(owner, null);
        matchRepository.save(new Match(newest, owner, null, null, null, null,
                null, "COMPLETED", null, null, 1, time.plusDays(1), time.plusDays(1)));
        matchRepository.save(new Match(removed, owner, null, null, null, null,
                null, "COMPLETED", null, null, 1, time, time));
        matchRepository.save(new Match(oldest, owner, null, null, null, null,
                null, "COMPLETED", null, null, 1, time.minusDays(1), time.minusDays(1)));
        var savedQuarter = new Quarter(quarter, removed, 1, 60, "COMPLETED", null, null, time, time);
        quarterRepository.save(savedQuarter);
        assertEquals(savedQuarter, quarterRepository.findByIdAndOwnerId(quarter, owner).orElseThrow());

        assertEquals(1, matchRepository.softDelete(removed, owner, 1));
        assertEquals(List.of(newest, oldest), matchRepository.findPageByOwnerId(owner, null, 2)
                .stream().map(Match::id).toList());
        assertEquals(List.of(oldest),
                matchRepository.findPageByOwnerId(owner, "COMPLETED", time.plusDays(1), newest, 1)
                        .stream().map(Match::id).toList());
        assertAll(
                () -> assertTrue(quarterRepository.findByIdAndOwnerId(quarter, owner).isEmpty()),
                () -> assertTrue(quarterRepository.findByIdAndMatchId(quarter, removed).isEmpty()),
                () -> assertTrue(quarterRepository.findByMatchId(removed, 10).isEmpty()),
                () -> assertTrue(quarterRepository.findPageAfter(removed, 0, quarter, 10).isEmpty()),
                () -> assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM quarters WHERE id = ?", Integer.class, uuidToBytes(quarter)))
        );
    }

    @Test
    void matchListUsesOwnerStatusAndBothCursorSortKeys() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        userRepository.save(owner, null);
        userRepository.save(other, null);
        LocalDateTime time = LocalDateTime.of(2026, 11, 4, 0, 0, 0, 123_456_000);
        UUID first = UUID.fromString("00000000-0000-4000-8000-000000000002");
        UUID second = UUID.fromString("00000000-0000-4000-8000-000000000001");
        for (UUID id : new UUID[]{second, first}) {
            matchRepository.save(new Match(id, owner, null, null, null, null,
                    null, "DRAFT", null, null, 1, time, time));
        }
        matchRepository.save(new Match(UUID.randomUUID(), other, null, null, null, null,
                null, "DRAFT", null, null, 1, time.plusDays(1), time.plusDays(1)));
        matchRepository.save(new Match(UUID.randomUUID(), owner, null, null, null, null,
                null, "READY", null, null, 1, time.plusDays(1), time.plusDays(1)));

        var query = new MatchListQuery(matchRepository);
        var page = query.find(owner, "DRAFT", null, 1);
        assertEquals(first, page.data().getFirst().id());
        var cursor = page.meta().pagination().nextCursor();
        var next = query.find(owner, "DRAFT", cursor, 1);
        assertEquals(second, next.data().getFirst().id());
        assertEquals(new MatchListQuery.Pagination(null, false), next.meta().pagination());
        assertEquals(0, query.find(owner, "COMPLETED", null, null).data().size());
        assertEquals("INVALID_CURSOR", assertThrows(MatchListQuery.InvalidListRequest.class,
                () -> query.find(other, "DRAFT", cursor, 1)).getMessage());
        assertEquals("INVALID_CURSOR", assertThrows(MatchListQuery.InvalidListRequest.class,
                () -> query.find(owner, "READY", cursor, 1)).getMessage());
    }

    @Test
    void ownershipRejectsAnotherUserAndAQuarterUnderTheWrongMatch() {
        UUID owner = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID match = UUID.randomUUID();
        UUID otherMatch = UUID.randomUUID();
        UUID quarter = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.of(2026, 11, 4, 0, 0);
        userRepository.save(owner, null);
        userRepository.save(otherUser, null);
        for (UUID matchId : new UUID[]{match, otherMatch}) {
            matchRepository.save(new Match(matchId, owner, null, null, null, null,
                    null, "DRAFT", null, null, 1, now, now));
        }
        quarterRepository.save(new Quarter(quarter, match, 1, 60, "READY", null, null, now, now));
        var ownership = new MatchOwnership(matchRepository, quarterRepository);

        var detail = new MatchDetailQuery(ownership).find(owner, match);
        var body = java.util.Objects.requireNonNull(detail.getBody());
        assertEquals("\"1\"", detail.getHeaders().getETag());
        assertEquals(1, body.meta().matchVersion());
        assertEquals(owner, body.data().ownerId());
        assertEquals("2026-11-04T09:00+09:00", body.data().createdAt().toString());
        assertNull(body.data().venueSnapshot());

        assertAll(
                () -> assertEquals(match, ownership.requireMatch(owner, match).id()),
                () -> assertEquals(quarter, ownership.requireQuarter(owner, match, quarter).id()),
                () -> assertThrows(MatchOwnership.ResourceNotFound.class,
                        () -> ownership.requireMatch(otherUser, match)),
                () -> assertThrows(MatchOwnership.ResourceNotFound.class,
                        () -> ownership.requireQuarter(otherUser, match, quarter)),
                () -> assertThrows(MatchOwnership.ResourceNotFound.class,
                        () -> ownership.requireQuarter(owner, otherMatch, quarter))
        );
    }

    @Test
    void 사용자_경기장_경기_쿼터를_FK순서로_저장하고_조회수정삭제한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 2, 0);
        UUID userId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID quarterId = UUID.randomUUID();

        userRepository.save(userId, null);
        assertEquals(1, userRepository.completeOnboarding(userId, "키키커", now));

        venueRepository.save(new Venue(
                venueId, userId, "풋살장", null, now, now
        ));
        assertEquals(1, venueRepository.update(
                venueId, userId, "수정 풋살장", "서울"
        ));
        matchRepository.save(new Match(
                matchId, userId, venueId, "연습 경기", "수정 풋살장", "서울",
                now.plusDays(1), "DRAFT", null, null, 1, now, now
        ));
        quarterRepository.save(new Quarter(
                quarterId, matchId, 1, 1200, "READY", null, null, now, now
        ));

        assertAll(
                () -> assertEquals("키키커", userRepository.findById(userId).orElseThrow().nickname()),
                () -> assertEquals("서울", venueRepository.findByIdAndOwnerId(venueId, userId).orElseThrow().address()),
                () -> assertEquals(venueId, matchRepository.findByIdAndOwnerId(matchId, userId).orElseThrow().venueId()),
                () -> assertEquals(1, quarterRepository.findByMatchId(matchId, 10).size()),
                () -> assertEquals(2, quarterRepository.findNextNumber(matchId))
        );

        assertEquals(1, quarterRepository.updateDuration(quarterId, matchId, 1500));
        assertEquals(1, quarterRepository.deleteUnusedLast(quarterId, matchId));
        assertEquals(1, venueRepository.deleteByIdAndOwnerId(venueId, userId));
        assertNull(matchRepository.findByIdAndOwnerId(matchId, userId).orElseThrow().venueId());
    }

    @Test
    void 경기와_쿼터의_상태를_조건부로_수정하고_사용자를_soft_delete한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 3, 0);
        UUID userId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID quarterId = UUID.randomUUID();
        userRepository.save(userId, "사용자");
        Match match = new Match(
                matchId, userId, null, "경기", null, null, null,
                "DRAFT", null, null, 1, now, now
        );
        matchRepository.save(match);
        quarterRepository.save(new Quarter(
                quarterId, matchId, 1, 600, "READY", null, null, now, now
        ));

        assertAll(
                () -> assertEquals(1, matchRepository.updateSettings(match, 1)),
                () -> assertEquals(1, matchRepository.updateStatus(
                        matchId, userId, 2, "DRAFT", "READY", null, null
                )),
                () -> assertEquals(1, quarterRepository.updateStatus(
                        quarterId, matchId, "READY", "IN_PROGRESS", now, null
                )),
                () -> assertEquals(0, quarterRepository.updateDuration(quarterId, matchId, 900)),
                () -> assertEquals(1, userRepository.softDelete(userId)),
                () -> assertEquals(0, userRepository.findById(userId).stream().count())
        );
    }

    @Test
    void 부모가_없는_경기장은_FK로_거절한다() {
        LocalDateTime now = LocalDateTime.now();
        assertThrows(DataIntegrityViolationException.class, () ->
                venueRepository.save(new Venue(
                        UUID.randomUUID(), UUID.randomUUID(), "없는 사용자 경기장",
                        null, now, now
                ))
        );
    }
}
