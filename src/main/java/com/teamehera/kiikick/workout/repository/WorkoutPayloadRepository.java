package com.teamehera.kiikick.workout.repository;

import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkoutPayloadRepository extends JpaRepository<WorkoutPayload, UUID> {
}
