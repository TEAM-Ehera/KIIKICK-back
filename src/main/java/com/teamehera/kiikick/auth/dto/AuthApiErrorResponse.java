package com.teamehera.kiikick.auth.dto;

import com.teamehera.kiikick.match.api.MatchApiResponse;

import java.util.Map;

public record AuthApiErrorResponse(AuthApiError error, AuthApiMeta meta) implements MatchApiResponse.Envelope {

    public record AuthApiError(
            String code,
            String message,
            boolean retryable,
            String persistence,
            Map<String, Object> details
    ) {
    }
}
