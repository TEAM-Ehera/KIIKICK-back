package com.teamehera.kiikick.workout.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record WorkoutPayload(
        UUID id,
        UUID quarterId,
        UUID userId,
        LocalDateTime startDate,
        LocalDateTime endDate,
        double totalCalories,
        double totalDistance,
        String eventsJson,
        String fieldJson
) {
    public WorkoutPayload(UUID id, UUID quarterId, UUID userId, LocalDateTime startDate,
                          LocalDateTime endDate, double totalCalories, double totalDistance) {
        this(id, quarterId, userId, startDate, endDate, totalCalories, totalDistance, null, null);
    }

    public WorkoutPayload(
            UUID id,
            UUID userId,
            LocalDateTime startDate,
            LocalDateTime endDate,
            double totalCalories,
            double totalDistance
    ) {
        this(id, null, userId, startDate, endDate, totalCalories, totalDistance);
    }
}
