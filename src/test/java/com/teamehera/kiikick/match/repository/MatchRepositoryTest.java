package com.teamehera.kiikick.match.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.domain.MatchPeriod;
import com.teamehera.kiikick.pitch.domain.Pitch;
import com.teamehera.kiikick.pitch.repository.PitchRepository;
import com.teamehera.kiikick.user.domain.User;
import com.teamehera.kiikick.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class MatchRepositoryTest {

    @Autowired
    private MatchRepository matchRepository;

    @Autowired
    private MatchPeriodRepository matchPeriodRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PitchRepository pitchRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void findsMatchByClientMatchId() {
        User user = userRepository.save(new User("match-owner"));
        Pitch pitch = pitchRepository.save(new Pitch(user, UUID.randomUUID(), "match-pitch"));
        UUID clientMatchId = UUID.randomUUID();
        Match match = matchRepository.saveAndFlush(createMatch(user, pitch, clientMatchId));

        entityManager.clear();

        Optional<Match> foundMatch = matchRepository.findByClientMatchId(clientMatchId);

        assertAll(
                () -> assertTrue(foundMatch.isPresent()),
                () -> assertEquals(match.getId(), foundMatch.orElseThrow().getId()),
                () -> assertEquals(user.getId(), foundMatch.orElseThrow().getUser().getId()),
                () -> assertEquals(pitch.getId(), foundMatch.orElseThrow().getPitch().getId())
        );
    }

    @Test
    void findsOnlyActiveMatchesOwnedByUserAndHidesDeletedDetail() {
        User owner = userRepository.save(new User("owner"));
        User otherOwner = userRepository.save(new User("other-owner"));
        Pitch ownerPitch = pitchRepository.save(new Pitch(owner, UUID.randomUUID(), "owner-pitch"));
        Pitch otherPitch = pitchRepository.save(new Pitch(otherOwner, UUID.randomUUID(), "other-pitch"));
        Match activeMatch = matchRepository.save(createMatch(owner, ownerPitch, UUID.randomUUID()));
        Match deletedMatch = matchRepository.save(createMatch(owner, ownerPitch, UUID.randomUUID()));
        Match otherMatch = matchRepository.save(createMatch(otherOwner, otherPitch, UUID.randomUUID()));
        deletedMatch.delete();
        matchRepository.flush();

        entityManager.clear();

        List<Match> foundMatches = matchRepository
                .findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(owner.getId());
        Optional<Match> activeDetail = matchRepository
                .findByIdAndUserIdAndDeletedAtIsNull(activeMatch.getId(), owner.getId());
        Optional<Match> deletedDetail = matchRepository
                .findByIdAndUserIdAndDeletedAtIsNull(deletedMatch.getId(), owner.getId());
        Optional<Match> otherOwnerDetail = matchRepository
                .findByIdAndUserIdAndDeletedAtIsNull(otherMatch.getId(), owner.getId());

        assertAll(
                () -> assertEquals(1, foundMatches.size()),
                () -> assertEquals(activeMatch.getId(), foundMatches.getFirst().getId()),
                () -> assertTrue(activeDetail.isPresent()),
                () -> assertFalse(deletedDetail.isPresent()),
                () -> assertFalse(otherOwnerDetail.isPresent())
        );
    }

    @Test
    void findsPeriodsByMatchIdInPeriodNumberOrder() {
        User user = userRepository.save(new User("match-owner"));
        Pitch pitch = pitchRepository.save(new Pitch(user, UUID.randomUUID(), "match-pitch"));
        Match match = matchRepository.save(createMatch(user, pitch, UUID.randomUUID()));
        matchPeriodRepository.saveAllAndFlush(List.of(
                new MatchPeriod(match, 3, "READY", "LEFT_TO_RIGHT", null, null),
                new MatchPeriod(match, 1, "READY", "LEFT_TO_RIGHT", null, null),
                new MatchPeriod(match, 2, "READY", "RIGHT_TO_LEFT", null, null)
        ));

        entityManager.clear();

        List<MatchPeriod> periods = matchPeriodRepository.findAllByMatchIdOrderByPeriodNoAsc(match.getId());

        assertEquals(List.of(1, 2, 3), periods.stream().map(MatchPeriod::getPeriodNo).toList());
    }

    private Match createMatch(User user, Pitch pitch, UUID clientMatchId) {
        return new Match(
                user,
                clientMatchId,
                pitch,
                "FIVE_V_FIVE",
                "SCHEDULED",
                LocalDateTime.of(2026, 11, 1, 19, 0)
        );
    }
}
