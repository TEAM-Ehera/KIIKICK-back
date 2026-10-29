package com.teamehera.kiikick.workout.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

class AppleEpochTimeConverterTest {

    @Test
    void roundsAppleEpochToNearestMicrosecondUsingHalfUp() {
        LocalDateTime roundedDown = AppleEpochTimeConverter.toUtcLocalDateTime(750000000.1234564);
        LocalDateTime roundedUp = AppleEpochTimeConverter.toUtcLocalDateTime(750000000.1234565);

        assertEquals(LocalDateTime.parse("2024-10-07T13:20:00.123456"), roundedDown);
        assertEquals(LocalDateTime.parse("2024-10-07T13:20:00.123457"), roundedUp);
    }

    @Test
    @ResourceLock("default-time-zone")
    void conversionDoesNotDependOnJvmDefaultTimeZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
            LocalDateTime fromSeoul = AppleEpochTimeConverter.toUtcLocalDateTime(750000001.2);

            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"));
            LocalDateTime fromLosAngeles = AppleEpochTimeConverter.toUtcLocalDateTime(750000001.2);

            LocalDateTime expected = LocalDateTime.parse("2024-10-07T13:20:01.200000");
            assertEquals(expected, fromSeoul);
            assertEquals(expected, fromLosAngeles);
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
