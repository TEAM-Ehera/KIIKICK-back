package com.teamehera.kiikick.pitch.domain;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.teamehera.kiikick.user.domain.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class PitchEntityTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void persistsPitchWithFourCorners() {
        User user = new User("pitch-owner");
        Pitch pitch = new Pitch(user, UUID.randomUUID(), "test-pitch");
        List<PitchCorner> corners = List.of(
                new PitchCorner(pitch, (byte) 1, "TOP_LEFT", new BigDecimal("37.1001"), new BigDecimal("127.1001")),
                new PitchCorner(pitch, (byte) 2, "TOP_RIGHT", new BigDecimal("37.1002"), new BigDecimal("127.1002")),
                new PitchCorner(pitch, (byte) 3, "BOTTOM_RIGHT", new BigDecimal("37.1003"), new BigDecimal("127.1003")),
                new PitchCorner(pitch, (byte) 4, "BOTTOM_LEFT", new BigDecimal("37.1004"), new BigDecimal("127.1004"))
        );

        entityManager.persist(user);
        entityManager.persist(pitch);
        corners.forEach(entityManager::persist);
        entityManager.flush();

        UUID pitchId = pitch.getId();
        UUID userId = user.getId();
        entityManager.clear();

        Pitch foundPitch = entityManager.find(Pitch.class, pitchId);

        assertAll(
                () -> assertNotNull(foundPitch),
                () -> assertEquals(userId, foundPitch.getUser().getId()),
                () -> assertEquals("test-pitch", foundPitch.getName()),
                () -> assertEquals(4, foundPitch.getCorners().size()),
                () -> assertNotNull(foundPitch.getCreatedAt()),
                () -> assertNotNull(foundPitch.getUpdatedAt())
        );
    }

    @Test
    void recordsSoftDeletionTimeWithoutRemovingPitch() {
        User user = new User("pitch-owner");
        Pitch pitch = new Pitch(user, UUID.randomUUID(), "deleted-pitch");

        entityManager.persist(user);
        entityManager.persist(pitch);
        pitch.delete();
        entityManager.flush();

        UUID pitchId = pitch.getId();
        entityManager.clear();

        Pitch foundPitch = entityManager.find(Pitch.class, pitchId);

        assertAll(
                () -> assertNotNull(foundPitch),
                () -> assertNotNull(foundPitch.getDeletedAt())
        );
    }

    @Test
    void rejectsDuplicateClientPitchId() {
        User user = new User("pitch-owner");
        UUID clientPitchId = UUID.randomUUID();
        Pitch firstPitch = new Pitch(user, clientPitchId, "first-pitch");
        Pitch secondPitch = new Pitch(user, clientPitchId, "second-pitch");

        entityManager.persist(user);
        entityManager.persist(firstPitch);
        entityManager.persist(secondPitch);

        assertThrows(PersistenceException.class, entityManager::flush);
    }

    @Test
    void rejectsDuplicateCornerOrderInSamePitch() {
        User user = new User("pitch-owner");
        Pitch pitch = new Pitch(user, UUID.randomUUID(), "test-pitch");
        PitchCorner firstCorner = new PitchCorner(
                pitch, (byte) 1, "TOP_LEFT", new BigDecimal("37.1001"), new BigDecimal("127.1001")
        );
        PitchCorner secondCorner = new PitchCorner(
                pitch, (byte) 1, "TOP_RIGHT", new BigDecimal("37.1002"), new BigDecimal("127.1002")
        );

        entityManager.persist(user);
        entityManager.persist(pitch);
        entityManager.persist(firstCorner);
        entityManager.persist(secondCorner);

        assertThrows(PersistenceException.class, entityManager::flush);
    }

    @Test
    void rejectsDuplicateCornerRoleInSamePitch() {
        User user = new User("pitch-owner");
        Pitch pitch = new Pitch(user, UUID.randomUUID(), "test-pitch");
        PitchCorner firstCorner = new PitchCorner(
                pitch, (byte) 1, "TOP_LEFT", new BigDecimal("37.1001"), new BigDecimal("127.1001")
        );
        PitchCorner secondCorner = new PitchCorner(
                pitch, (byte) 2, "TOP_LEFT", new BigDecimal("37.1002"), new BigDecimal("127.1002")
        );

        entityManager.persist(user);
        entityManager.persist(pitch);
        entityManager.persist(firstCorner);
        entityManager.persist(secondCorner);

        assertThrows(PersistenceException.class, entityManager::flush);
    }
}
