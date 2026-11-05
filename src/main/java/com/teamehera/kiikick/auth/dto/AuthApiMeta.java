package com.teamehera.kiikick.auth.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.teamehera.kiikick.match.api.MatchApiResponse;

public record AuthApiMeta(UUID requestId, OffsetDateTime serverTime) implements MatchApiResponse.Metadata {
}
