package com.teamehera.kiikick.auth.dto;

import java.util.Map;

public record AuthApiErrorResponse(AuthApiError error, AuthApiMeta meta) {

    public record AuthApiError(
            String code,
            String message,
            boolean retryable,
            String persistence,
            Map<String, Object> details
    ) {
    }
}
