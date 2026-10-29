package com.teamehera.kiikick.pitch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

@Schema(description = "운동장 등록 요청")
public record PitchCreateRequest(
        @Schema(description = "iOS가 생성한 운동장 UUID")
        @NotNull
        UUID clientPitchId,

        @Schema(description = "운동장 이름", example = "서울 운동장")
        @NotBlank
        String name,

        @Schema(description = "정확히 네 개의 모서리")
        @NotNull
        @Size(min = 4, max = 4)
        List<@Valid PitchCornerCreateRequest> corners
) {
}
