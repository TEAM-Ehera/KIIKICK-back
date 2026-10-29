package com.teamehera.kiikick.workout.domain;

import java.time.LocalDateTime;
import java.util.UUID;

public record HeartRateSample(
        UUID workoutPayloadId,
        int sampleSeq,
        // Apple epoch에서 UTC로 변환된 심박수 측정 시각
        LocalDateTime timestamp,
        double bpm
) {
}