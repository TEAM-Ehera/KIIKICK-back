package com.teamehera.kiikick.pitch.repository;

import com.teamehera.kiikick.pitch.domain.PitchCorner;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PitchCornerRepository extends JpaRepository<PitchCorner, UUID> {

    List<PitchCorner> findAllByPitchId(UUID pitchId);
}
