package com.teamehera.kiikick.workout.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record TrackPoint(
        UUID workoutPayloadId,
        int sampleSeq,
        double latitude,
        double longitude,
        double altitude,
        double horizontalAccuracy,
        double speed,
        double course,
        LocalDateTime timestamp
) {
}