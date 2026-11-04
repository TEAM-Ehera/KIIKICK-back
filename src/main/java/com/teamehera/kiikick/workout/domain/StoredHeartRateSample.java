package com.teamehera.kiikick.workout.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record StoredHeartRateSample(
        long id,
        UUID workoutPayloadId,
        int sampleSeq,
        LocalDateTime timestamp,
        double bpm
) {
}
