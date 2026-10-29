CREATE TABLE users (
    id BINARY(16) NOT NULL,
    nickname VARCHAR(16) NOT NULL,
    createdAt DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updatedAt DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deletedAt DATETIME(6) DEFAULT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;


CREATE TABLE workoutPayloads (
    id BINARY(16) NOT NULL,
    userId BINARY(16) NOT NULL,
    startDate DATETIME(6) NOT NULL,
    endDate DATETIME(6) NOT NULL,
    totalCalories DOUBLE NOT NULL,
    totalDistance DOUBLE NOT NULL,
    PRIMARY KEY (id),
    KEY idx_workout_user_start (userId, startDate),
    CONSTRAINT fk_workout_user FOREIGN KEY (userId) REFERENCES users (id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;


CREATE TABLE trackPoints (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workoutPayloadId BINARY(16) NOT NULL,
    sampleSeq INT NOT NULL,
    latitude DOUBLE NOT NULL,
    longitude DOUBLE NOT NULL,
    altitude DOUBLE NOT NULL,
    horizontalAccuracy DOUBLE NOT NULL,
    speed DOUBLE NOT NULL,
    course DOUBLE NOT NULL,
    timestamp DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_track_points_payload_seq (workoutPayloadId, sampleSeq),
    CONSTRAINT fk_track_workout FOREIGN KEY (workoutPayloadId) REFERENCES workoutPayloads (id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;


CREATE TABLE heartRateSamples (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workoutPayloadId BINARY(16) NOT NULL,
    sampleSeq INT NOT NULL,
    timestamp DATETIME(6) NOT NULL,
    bpm DOUBLE NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_heart_rate_samples_payload_seq (workoutPayloadId, sampleSeq),
    CONSTRAINT fk_heart_workout FOREIGN KEY (workoutPayloadId) REFERENCES workoutPayloads (id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci;