package com.teamehera.kiikick.auth.dto;

import com.teamehera.kiikick.match.api.MatchApiResponse;

public record AuthApiResponse<T>(T data, AuthApiMeta meta) implements MatchApiResponse.Envelope {
}
