package com.teamehera.kiikick.pitch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@Schema(description = "운동장 이름과 네 모서리 좌표 전체 수정 요청")
public record PitchUpdateRequest(
        @Schema(description = "수정할 운동장 이름", example = "수정된 운동장")
        @NotBlank
        String name,

        @Schema(description = "순서와 역할은 유지하고 좌표만 수정할 네 모서리")
        @NotNull
        @Size(min = 4, max = 4)
        List<@Valid PitchCornerUpdateRequest> corners
) {
}
