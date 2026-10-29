package com.teamehera.kiikick.workout.repository;

import com.teamehera.kiikick.workout.domain.HeartRateSample;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HeartRateSampleRepository extends JpaRepository<HeartRateSample, Long> {

    List<HeartRateSample> findAllByWorkoutPayloadIdOrderBySampleSeqAsc(UUID workoutPayloadId);
}
