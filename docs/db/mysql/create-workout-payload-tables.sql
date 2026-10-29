-- KIIKICK WorkoutPayload storage schema for MySQL 8
-- Scope: new tables only. Existing users, pitches, pitch_corners, matches,
-- and match_periods tables are not modified.
--
-- Date contract:
-- Convert the iOS Apple epoch seconds to UTC DATETIME(6), rounding to the
-- nearest microsecond with HALF_UP.

CREATE TABLE `workoutPayloads` (
    `id` BINARY(16) NOT NULL,
    `startDate` DATETIME(6) NOT NULL,
    `endDate` DATETIME(6) NOT NULL,
    `totalCalories` DOUBLE NOT NULL,
    `totalDistance` DOUBLE NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB;

CREATE TABLE `trackPoints` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workoutPayloadId` BINARY(16) NOT NULL,
    `sampleSeq` INT NOT NULL,
    `latitude` DOUBLE NOT NULL,
    `longitude` DOUBLE NOT NULL,
    `altitude` DOUBLE NOT NULL,
    `horizontalAccuracy` DOUBLE NOT NULL,
    `speed` DOUBLE NOT NULL,
    `course` DOUBLE NOT NULL,
    `timestamp` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_track_points_payload_seq`
        UNIQUE (`workoutPayloadId`, `sampleSeq`),
    CONSTRAINT `fk_track_points_workout_payload`
        FOREIGN KEY (`workoutPayloadId`)
        REFERENCES `workoutPayloads` (`id`)
) ENGINE = InnoDB;

CREATE TABLE `heartRateSamples` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workoutPayloadId` BINARY(16) NOT NULL,
    `sampleSeq` INT NOT NULL,
    `timestamp` DATETIME(6) NOT NULL,
    `bpm` DOUBLE NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `uk_heart_rate_samples_payload_seq`
        UNIQUE (`workoutPayloadId`, `sampleSeq`),
    CONSTRAINT `fk_heart_rate_samples_workout_payload`
        FOREIGN KEY (`workoutPayloadId`)
        REFERENCES `workoutPayloads` (`id`)
) ENGINE = InnoDB;
