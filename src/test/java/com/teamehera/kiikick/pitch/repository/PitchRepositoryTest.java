package com.teamehera.kiikick.pitch.repository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.teamehera.kiikick.pitch.domain.Pitch;
import com.teamehera.kiikick.pitch.domain.PitchCorner;
import com.teamehera.kiikick.user.domain.User;
import com.teamehera.kiikick.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class PitchRepositoryTest {

    @Autowired
    private PitchRepository pitchRepository;

    @Autowired
    private PitchCornerRepository pitchCornerRepository;

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void findsPitchByClientPitchId() {
        User user = userRepository.saveAndFlush(new User("pitch-owner"));
        UUID clientPitchId = UUID.randomUUID();
        Pitch pitch = pitchRepository.saveAndFlush(new Pitch(user, clientPitchId, "client-pitch"));

        entityManager.clear();

        Optional<Pitch> foundPitch = pitchRepository.findByClientPitchId(clientPitchId);

        assertAll(
                () -> assertTrue(foundPitch.isPresent()),
                () -> assertEquals(pitch.getId(), foundPitch.orElseThrow().getId()),
                () -> assertEquals("client-pitch", foundPitch.orElseThrow().getName())
        );
    }

    @Test
    void findsOnlyActivePitchesOwnedByUser() {
        User owner = userRepository.save(new User("owner"));
        User otherOwner = userRepository.save(new User("other-owner"));
        Pitch activePitch = new Pitch(owner, UUID.randomUUID(), "active-pitch");
        Pitch deletedPitch = new Pitch(owner, UUID.randomUUID(), "deleted-pitch");
        Pitch otherPitch = new Pitch(otherOwner, UUID.randomUUID(), "other-pitch");
        deletedPitch.delete();
        pitchRepository.saveAllAndFlush(List.of(activePitch, deletedPitch, otherPitch));

        entityManager.clear();

        List<Pitch> foundPitches = pitchRepository
                .findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(owner.getId());

        assertAll(
                () -> assertEquals(1, foundPitches.size()),
                () -> assertEquals(activePitch.getId(), foundPitches.getFirst().getId()),
                () -> assertFalse(foundPitches.stream().anyMatch(pitch -> pitch.getId().equals(deletedPitch.getId()))),
                () -> assertFalse(foundPitches.stream().anyMatch(pitch -> pitch.getId().equals(otherPitch.getId())))
        );
    }

    @Test
    void findsCornersByPitchId() {
        User user = userRepository.save(new User("pitch-owner"));
        Pitch pitch = pitchRepository.save(new Pitch(user, UUID.randomUUID(), "corner-pitch"));
        List<PitchCorner> corners = List.of(
                new PitchCorner(pitch, (byte) 1, "TOP_LEFT", new BigDecimal("37.1001"), new BigDecimal("127.1001")),
                new PitchCorner(pitch, (byte) 2, "TOP_RIGHT", new BigDecimal("37.1002"), new BigDecimal("127.1002")),
                new PitchCorner(pitch, (byte) 3, "BOTTOM_RIGHT", new BigDecimal("37.1003"), new BigDecimal("127.1003")),
                new PitchCorner(pitch, (byte) 4, "BOTTOM_LEFT", new BigDecimal("37.1004"), new BigDecimal("127.1004"))
        );
        pitchCornerRepository.saveAllAndFlush(corners);

        entityManager.clear();

        List<PitchCorner> foundCorners = pitchCornerRepository
                .findAllByPitchIdOrderByCornerOrderAsc(pitch.getId());

        assertAll(
                () -> assertEquals(4, foundCorners.size()),
                () -> assertTrue(foundCorners.stream().allMatch(corner -> corner.getPitch().getId().equals(pitch.getId())))
        );
    }
}
