package com.teamehera.kiikick.auth.dto;

import java.time.OffsetDateTime;

public record AuthApiMeta(String requestId, OffsetDateTime serverTime) {
}
