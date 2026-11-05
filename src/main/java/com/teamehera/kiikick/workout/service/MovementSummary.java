package com.teamehera.kiikick.workout.service;

import com.teamehera.kiikick.workout.domain.StoredTrackPoint;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import com.teamehera.kiikick.workout.service.HeartRateSummary.TimeRange;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

final class MovementSummary {
    static final int MAX_ACCURACY_M = 20;
    static final int MAX_SPEED_MPS = 12;
    static final int MAX_GAP_SECONDS = 5;
    static final int MIN_MOVING_SPEED_MPS = 1;
    static final int ACCELERATION_THRESHOLD = 2;
    static final int TURN_DEGREES = 45;
    static final int MIN_EVENT_SECONDS = 1;
    private static final long MIN_EVENT_NANOS = MIN_EVENT_SECONDS * 1_000_000_000L;

    private MovementSummary() {}

    static Result calculate(WorkoutPayload workout, List<StoredTrackPoint> points, HeartRateSummary.Result heart) {
        var totals = new Totals(heart.validRanges(), heart.highIntensityRanges());
        StoredTrackPoint previous = null;
        StoredTrackPoint current = null;
        for (var point : points) {
            if (current == null || !point.timestamp().equals(current.timestamp())) {
                totals.accept(previous, current);
                previous = current;
                current = point;
            } else if (validSpeed(point)) {
                current = point;
            }
        }
        totals.accept(previous, current);
        double elapsed = seconds(workout.startDate(), workout.endDate());
        double averageSpeed = elapsed > 0 ? workout.totalDistance() / elapsed : Double.NaN;
        boolean canEstimateIntensity = heart.referenceBpm() != null && heart.validSeconds() != null;
        return new Result(elapsed > 0 ? elapsed : null, Double.isFinite(averageSpeed) ? averageSpeed : null,
                totals.maximum, totals.validSeconds > 0 ? totals.validSeconds : null,
                totals.validSeconds > 0 ? totals.accelerations.count : null,
                totals.validSeconds > 0 ? totals.decelerations.count : null,
                totals.hasCourseInterval ? totals.turns.count : null,
                canEstimateIntensity && totals.hasHeartOverlap ? totals.highIntensityDistance : null);
    }

    private static boolean validSpeed(StoredTrackPoint point) {
        return point != null && point.horizontalAccuracy() >= 0 && point.horizontalAccuracy() <= MAX_ACCURACY_M
                && point.speed() >= 0 && point.speed() <= MAX_SPEED_MPS;
    }

    private static boolean validCourse(double course) {
        return course >= 0 && course < 360;
    }

    private static double seconds(LocalDateTime from, LocalDateTime to) {
        var duration = Duration.between(from, to);
        return duration.getSeconds() + duration.getNano() / 1_000_000_000.0;
    }

    private static double angleDelta(double first, double second) {
        double difference = Math.abs(first - second);
        return Math.min(difference, 360 - difference);
    }

    record Result(Double elapsedSeconds, Double averageSpeed, Double maxSpeed, Double validSeconds,
                  Integer accelerations, Integer decelerations, Integer turns, Double highIntensityDistance) {}

    private static final class Totals {
        private final List<TimeRange> validHeartRanges;
        private final List<TimeRange> heartRanges;
        private final EventCounter accelerations = new EventCounter();
        private final EventCounter decelerations = new EventCounter();
        private final TurnCounter turns = new TurnCounter();
        private Double maximum;
        private double validSeconds;
        private double highIntensityDistance;
        private int heartIndex;
        private int validHeartIndex;
        private boolean hasCourseInterval;
        private boolean hasHeartOverlap;

        private Totals(List<TimeRange> validHeartRanges, List<TimeRange> heartRanges) {
            this.validHeartRanges = validHeartRanges;
            this.heartRanges = heartRanges;
        }

        private void accept(StoredTrackPoint previous, StoredTrackPoint current) {
            if (validSpeed(current)) {
                maximum = maximum == null ? current.speed() : Math.max(maximum, current.speed());
            }
            double elapsed = validSpeed(previous) && validSpeed(current)
                    ? seconds(previous.timestamp(), current.timestamp()) : 0;
            if (elapsed <= 0 || elapsed > MAX_GAP_SECONDS) {
                accelerations.accept(false, 0);
                decelerations.accept(false, 0);
                turns.accept(false, false, 0);
                return;
            }
            validSeconds += elapsed;
            double acceleration = (current.speed() - previous.speed()) / elapsed;
            long elapsedNanos = Math.round(elapsed * 1_000_000_000);
            accelerations.accept(acceleration >= ACCELERATION_THRESHOLD, elapsedNanos);
            decelerations.accept(acceleration <= -ACCELERATION_THRESHOLD, elapsedNanos);
            boolean courseAvailable = validCourse(previous.course()) && validCourse(current.course());
            hasCourseInterval |= courseAvailable;
            boolean moving = courseAvailable && Math.min(previous.speed(), current.speed()) >= MIN_MOVING_SPEED_MPS;
            turns.accept(moving, moving && angleDelta(previous.course(), current.course()) >= TURN_DEGREES, elapsedNanos);
            checkHeartOverlap(previous.timestamp(), current.timestamp());
            integrateDistance(previous, current, elapsed);
        }

        private void checkHeartOverlap(LocalDateTime start, LocalDateTime end) {
            if (hasHeartOverlap) {
                return;
            }
            while (validHeartIndex < validHeartRanges.size()) {
                var range = validHeartRanges.get(validHeartIndex);
                if (range.end().isAfter(start)) {
                    hasHeartOverlap = range.start().isBefore(end);
                    return;
                }
                validHeartIndex++;
            }
        }

        // Both streams are ordered. Each HR range is consumed once, or carried to the next GPS interval.
        private void integrateDistance(StoredTrackPoint previous, StoredTrackPoint current, double elapsed) {
            double startSpeed = previous.speed();
            double speedDelta = current.speed() - startSpeed;
            double movingStart = 0;
            double movingEnd = elapsed;
            if (startSpeed < MIN_MOVING_SPEED_MPS && current.speed() < MIN_MOVING_SPEED_MPS) {
                movingEnd = 0;
            } else if (startSpeed < MIN_MOVING_SPEED_MPS) {
                movingStart = elapsed * (MIN_MOVING_SPEED_MPS - startSpeed) / speedDelta;
            } else if (current.speed() < MIN_MOVING_SPEED_MPS) {
                movingEnd = elapsed * (MIN_MOVING_SPEED_MPS - startSpeed) / speedDelta;
            }
            while (heartIndex < heartRanges.size()) {
                var range = heartRanges.get(heartIndex);
                if (!range.start().isBefore(current.timestamp())) {
                    break;
                }
                double from = Math.max(movingStart, seconds(previous.timestamp(), range.start()));
                double to = Math.min(movingEnd, seconds(previous.timestamp(), range.end()));
                if (to > from) {
                    double meanSpeed = startSpeed + speedDelta * (from + to) / (2 * elapsed);
                    highIntensityDistance += meanSpeed * (to - from);
                }
                if (range.end().isAfter(current.timestamp())) {
                    break;
                }
                heartIndex++;
            }
        }
    }

    private static final class EventCounter {
        private long sustained;
        private int count;

        private void accept(boolean active, long nanos) {
            if (!active) {
                sustained = 0;
                return;
            }
            long before = sustained;
            sustained += nanos;
            if (before < MIN_EVENT_NANOS && sustained >= MIN_EVENT_NANOS) {
                count++;
            }
        }
    }

    private static final class TurnCounter {
        private long movingAfterTurn;
        private boolean pending;
        private boolean counted;
        private int count;

        private void accept(boolean moving, boolean sharpTurn, long nanos) {
            if (!moving || (counted && !sharpTurn)) {
                pending = false;
                counted = false;
                movingAfterTurn = 0;
                return;
            }
            if (pending && !counted) {
                movingAfterTurn += nanos;
                if (movingAfterTurn >= MIN_EVENT_NANOS) {
                    count++;
                    counted = true;
                }
            }
            // A turn is detected at the interval endpoint. Only subsequent movement confirms it.
            pending |= sharpTurn;
        }
    }
}
