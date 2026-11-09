package com.teamehera.kiikick.venue.domain;

/** 위도·경도는 iOS가 계산한 정밀도를 유지하며 저장한다. */
public record FieldCorner(double latitude, double longitude) {
    public FieldCorner {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Invalid geographic coordinate");
        }
    }
}
