-- Apply after create-tables.sql in a disposable MySQL database.
SET time_zone = '+00:00';
START TRANSACTION;

INSERT INTO users (id, nickname, createdAt, updatedAt, deletedAt)
VALUES
    (UNHEX('00000000000040008000000000000001'), 'legacy-player',
     '2026-11-01 01:00:00.123456', '2026-11-02 01:00:00.234567', NULL),
    (UNHEX('00000000000040008000000000000002'), 'deleted-player',
     '2026-11-01 01:00:00.123456', '2026-11-03 01:00:00.345678', '2026-11-03 01:00:00.345678');

INSERT INTO workoutPayloads (id, userId, startDate, endDate, totalCalories, totalDistance)
VALUES
    (UNHEX('00000000000040008000000000000003'),
     UNHEX('00000000000040008000000000000001'),
     '2026-11-02 02:00:00.123456', '2026-11-02 02:01:00.234567', 5.25, 12.5);

INSERT INTO trackPoints
    (workoutPayloadId, sampleSeq, latitude, longitude, altitude, horizontalAccuracy, speed, course, timestamp)
VALUES
    (UNHEX('00000000000040008000000000000003'), 0, 37.5, 127.0, 10.5, 1.25, -1, -1,
     '2026-11-02 02:00:00.123456'),
    (UNHEX('00000000000040008000000000000003'), 1, 37.5001, 127.0, 10.5, 1.25, 2.5, 359,
     '2026-11-02 02:00:01.234567');

INSERT INTO heartRateSamples (workoutPayloadId, sampleSeq, timestamp, bpm)
VALUES
    (UNHEX('00000000000040008000000000000003'), 0, '2026-11-02 02:00:00.123456', 135.5);

COMMIT;
