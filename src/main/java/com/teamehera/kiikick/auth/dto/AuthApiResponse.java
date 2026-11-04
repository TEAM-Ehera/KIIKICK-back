package com.teamehera.kiikick.auth.dto;

public record AuthApiResponse<T>(T data, AuthApiMeta meta) {
}
