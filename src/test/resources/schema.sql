CREATE TABLE IF NOT EXISTS users (
    id BINARY(16) NOT NULL,
    nickname VARCHAR(16) NOT NULL,
    createdAt TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updatedAt TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deletedAt TIMESTAMP(6),
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS workoutPayloads (
    id BINARY(16) NOT NULL,
    userId BINARY(16) NOT NULL,
    startDate TIMESTAMP(6) NOT NULL,
    endDate TIMESTAMP(6) NOT NULL,
    totalCalories DOUBLE NOT NULL,
    totalDistance DOUBLE NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_workout_user
        FOREIGN KEY (userId)
        REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS trackPoints (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workoutPayloadId BINARY(16) NOT NULL,
    sampleSeq INT NOT NULL,
    latitude DOUBLE NOT NULL,
    longitude DOUBLE NOT NULL,
    altitude DOUBLE NOT NULL,
    horizontalAccuracy DOUBLE NOT NULL,
    speed DOUBLE NOT NULL,
    course DOUBLE NOT NULL,
    timestamp TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_track_points_payload_seq
        UNIQUE (workoutPayloadId, sampleSeq),
    CONSTRAINT fk_track_workout
        FOREIGN KEY (workoutPayloadId)
        REFERENCES workoutPayloads(id)
);

CREATE TABLE IF NOT EXISTS heartRateSamples (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workoutPayloadId BINARY(16) NOT NULL,
    sampleSeq INT NOT NULL,
    timestamp TIMESTAMP(6) NOT NULL,
    bpm DOUBLE NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_heart_rate_samples_payload_seq
        UNIQUE (workoutPayloadId, sampleSeq),
    CONSTRAINT fk_heart_workout
        FOREIGN KEY (workoutPayloadId)
        REFERENCES workoutPayloads(id)
);