package com.teamehera.kiikick.workout.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;
import java.util.UUID;

public record QuarterSummary(
        UUID quarterId,
        UUID workoutPayloadId,
        @JsonIgnore
        UUID calculationJobId,
        String calculationStatus,
        String calculationVersion,
        String calculationParameters,
        String metricStatus,
        String sourcePayloadHash,
        Double durationSeconds,
        Double maxSpeedMps,
        Double totalDistanceM,
        Double totalCaloriesKcal,
        Double averageSpeedMps,
        Double highIntensityDistanceM,
        Integer accelerationCount,
        Integer decelerationCount,
        Integer directionChangeCount,
        Double averageHeartRateBpm,
        Double maxHeartRateBpm,
        Double trimpEdwards,
        Double hrMaxReferenceBpm,
        Double validHrDurationSeconds,
        Double validGpsDurationSeconds,
        LocalDateTime calculatedAt,
        LocalDateTime updatedAt
) {
}
