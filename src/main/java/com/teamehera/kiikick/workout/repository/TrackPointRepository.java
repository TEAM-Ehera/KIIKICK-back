package com.teamehera.kiikick.workout.repository;

import com.teamehera.kiikick.workout.domain.TrackPoint;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TrackPointRepository extends JpaRepository<TrackPoint, Long> {

    List<TrackPoint> findAllByWorkoutPayloadIdOrderBySampleSeqAsc(UUID workoutPayloadId);
}
