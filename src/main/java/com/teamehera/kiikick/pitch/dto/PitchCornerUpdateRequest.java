package com.teamehera.kiikick.pitch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@Schema(description = "운동장 모서리 좌표 수정 요청")
public record PitchCornerUpdateRequest(
        @Schema(description = "변경하지 않는 모서리 순서", example = "1")
        @NotNull
        Byte cornerOrder,

        @Schema(description = "수정할 위도", example = "37.5666")
        @NotNull
        @DecimalMin("-90.0")
        @DecimalMax("90.0")
        BigDecimal latitude,

        @Schema(description = "수정할 경도", example = "126.9781")
        @NotNull
        @DecimalMin("-180.0")
        @DecimalMax("180.0")
        BigDecimal longitude
) {
}
