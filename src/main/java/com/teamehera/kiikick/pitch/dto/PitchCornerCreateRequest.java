package com.teamehera.kiikick.pitch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@Schema(description = "운동장 모서리 등록 요청")
public record PitchCornerCreateRequest(
        @Schema(description = "모서리 순서", example = "1")
        @NotNull
        Byte cornerOrder,

        @Schema(description = "iOS와 합의한 모서리 역할 문자열", example = "TOP_LEFT")
        @NotBlank
        String cornerRole,

        @Schema(description = "위도", example = "37.5665")
        @NotNull
        @DecimalMin("-90.0")
        @DecimalMax("90.0")
        BigDecimal latitude,

        @Schema(description = "경도", example = "126.9780")
        @NotNull
        @DecimalMin("-180.0")
        @DecimalMax("180.0")
        BigDecimal longitude
) {
}
