package com.teamehera.kiikick.workout.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;


public final class AppleEpochTimeConverter {

    private static final BigDecimal APPLE_TO_UNIX_SECONDS =
            new BigDecimal("978307200");
    private static final long MICROS_PER_SECOND = 1_000_000L;

    private AppleEpochTimeConverter() {
    }

    public static LocalDateTime toUtcLocalDateTime(double appleEpochSeconds) {

        if (!Double.isFinite(appleEpochSeconds)) {
            throw new IllegalArgumentException(
                    "Apple epoch는 유한한 숫자여야 합니다."
            );
        }

        try {

            long unixMicros = BigDecimal.valueOf(appleEpochSeconds)
                    .add(APPLE_TO_UNIX_SECONDS)
                    .movePointRight(6)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();

            long unixSeconds = Math.floorDiv(
                    unixMicros,
                    MICROS_PER_SECOND
            );

            long microAdjustment = Math.floorMod(
                    unixMicros,
                    MICROS_PER_SECOND
            );

            Instant instant = Instant.ofEpochSecond(
                    unixSeconds,
                    microAdjustment * 1_000L
            );

            return LocalDateTime.ofInstant(
                    instant,
                    ZoneOffset.UTC
            );

        } catch (ArithmeticException | DateTimeException exception) {

            throw new IllegalArgumentException(
                    "Apple epoch가 지원 가능한 날짜 범위를 벗어났습니다.",
                    exception
            );
        }
    }
}