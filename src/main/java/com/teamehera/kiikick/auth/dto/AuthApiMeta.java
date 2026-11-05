package com.teamehera.kiikick.auth.dto;

import java.time.Instant;

public record AuthApiMeta(String requestId, Instant serverTime) {
}
