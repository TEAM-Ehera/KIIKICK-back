package com.teamehera.kiikick.repository;

import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.domain.Quarter;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterRepository;
import com.teamehera.kiikick.match.service.MatchOwnership;
import com.teamehera.kiikick.user.repository.UserRepository;
import com.teamehera.kiikick.venue.domain.Venue;
import com.teamehera.kiikick.venue.repository.VenueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
