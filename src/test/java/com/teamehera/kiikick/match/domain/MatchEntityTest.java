package com.teamehera.kiikick.match.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.teamehera.kiikick.pitch.domain.Pitch;
import com.teamehera.kiikick.user.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class MatchEntityTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void persistsMatchWithUserPitchAndPeriods() {
        User user = new User("match-owner");
        Pitch pitch = new Pitch(user, UUID.randomUUID(), "match-pitch");
        Match match = new Match(
                user,
                UUID.randomUUID(),
                pitch,
                "FIVE_V_FIVE",
                "SCHEDULED",
                LocalDateTime.of(2026, 11, 1, 19, 0)
        );
        List<MatchPeriod> periods = List.of(
                new MatchPeriod(match, 1, "READY", "LEFT_TO_RIGHT", null, null),
                new MatchPeriod(match, 2, "READY", "RIGHT_TO_LEFT", null, null)
        );

        entityManager.persist(user);
        entityManager.persist(pitch);
        entityManager.persist(match);
        periods.forEach(entityManager::persist);
        entityManager.flush();

        UUID matchId = match.getId();
        UUID userId = user.getId();
        UUID pitchId = pitch.getId();
        entityManager.clear();

        Match foundMatch = entityManager.find(Match.class, matchId);

        assertAll(
                () -> assertNotNull(foundMatch),
                () -> assertEquals(userId, foundMatch.getUser().getId()),
                () -> assertEquals(pitchId, foundMatch.getPitch().getId()),
                () -> assertEquals("FIVE_V_FIVE", foundMatch.getMatchFormat()),
                () -> assertEquals("SCHEDULED", foundMatch.getStatus()),
                () -> assertEquals(2, foundMatch.getPeriods().size()),
                () -> assertNotNull(foundMatch.getCreatedAt()),
                () -> assertNotNull(foundMatch.getUpdatedAt())
        );
    }

    @Test
    void rejectsDuplicateClientMatchId() {
        User user = new User("match-owner");
        Pitch pitch = new Pitch(user, UUID.randomUUID(), "match-pitch");
        UUID clientMatchId = UUID.randomUUID();
        Match firstMatch = createMatch(user, pitch, clientMatchId);
        Match secondMatch = createMatch(user, pitch, clientMatchId);

        entityManager.persist(user);
        entityManager.persist(pitch);
        entityManager.persist(firstMatch);
        entityManager.persist(secondMatch);

        assertThrows(PersistenceException.class, entityManager::flush);
    }

    @Test
    void rejectsDuplicatePeriodNumberInSameMatch() {
        User user = new User("match-owner");
        Pitch pitch = new Pitch(user, UUID.randomUUID(), "match-pitch");
        Match match = createMatch(user, pitch, UUID.randomUUID());
        MatchPeriod firstPeriod = new MatchPeriod(match, 1, "READY", "LEFT_TO_RIGHT", null, null);
        MatchPeriod secondPeriod = new MatchPeriod(match, 1, "READY", "RIGHT_TO_LEFT", null, null);

        entityManager.persist(user);
        entityManager.persist(pitch);
        entityManager.persist(match);
        entityManager.persist(firstPeriod);
        entityManager.persist(secondPeriod);

        assertThrows(PersistenceException.class, entityManager::flush);
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
