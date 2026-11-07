package com.teamehera.kiikick.auth.domain;

import java.util.Map;

/** 온보딩과 동의 API가 공유하는 승인된 목적·버전이다. */
public final class ConsentPolicy {
    public static final String GPS_COLLECTION = "GPS_COLLECTION";
    public static final String HEALTH_DATA = "HEALTH_DATA";
    public static final String INITIAL_VERSION = "1.0";
    private static final Map<String, String> VERSIONS = Map.of(
            GPS_COLLECTION, INITIAL_VERSION, HEALTH_DATA, INITIAL_VERSION);

    private ConsentPolicy() {}

    public static boolean supportsPurpose(String purpose) {
        return purpose != null && VERSIONS.containsKey(purpose);
    }

    public static boolean supports(String purpose, String version) {
        return supportsPurpose(purpose) && VERSIONS.get(purpose).equals(version);
    }
}
