package com.teamehera.kiikick.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "공통 성공 봉투. 204 로그아웃은 이 봉투 없이 본문 없음")
public record AuthApiResponse<T>(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "API별 성공 데이터") T data, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AuthApiMeta meta) {
}
