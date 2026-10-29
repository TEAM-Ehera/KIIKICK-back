package com.teamehera.kiikick.pitch.repository;

import com.teamehera.kiikick.pitch.domain.Pitch;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PitchRepository extends JpaRepository<Pitch, UUID> {

    List<Pitch> findAllByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(UUID userId);

    Optional<Pitch> findByIdAndUserIdAndDeletedAtIsNull(UUID id, UUID userId);

    Optional<Pitch> findByClientPitchId(UUID clientPitchId);
}
