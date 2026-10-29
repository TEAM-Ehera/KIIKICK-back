package com.teamehera.kiikick.workout.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record WorkoutPayload(
        UUID id,
        UUID userId,
        LocalDateTime startDate,
        LocalDateTime endDate,
        double totalCalories,
        double totalDistance
) {
}