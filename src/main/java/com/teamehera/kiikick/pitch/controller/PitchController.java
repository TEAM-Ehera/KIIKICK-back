package com.teamehera.kiikick.pitch.controller;

import com.teamehera.kiikick.pitch.dto.PitchCreateRequest;
import com.teamehera.kiikick.pitch.dto.PitchCreationResult;
import com.teamehera.kiikick.pitch.dto.PitchResponse;
import com.teamehera.kiikick.pitch.dto.PitchSummaryResponse;
import com.teamehera.kiikick.pitch.dto.PitchUpdateRequest;
import com.teamehera.kiikick.pitch.service.PitchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Pitches", description = "운동장 등록·조회·수정·소프트 삭제 API")
@RestController
@RequestMapping("/api/pitches")
public class PitchController {

    private static final String TEST_USER_HEADER = "X-Test-User-Id";
    private static final String TEST_USER_DESCRIPTION =
            "개발용 임시 사용자 UUID이며 실제 인증 수단이 아닙니다.";

    private final PitchService pitchService;

    public PitchController(PitchService pitchService) {
        this.pitchService = pitchService;
    }

    @Operation(summary = "운동장 등록", description = "운동장과 정확히 네 개의 모서리를 함께 등록합니다.")
    @PostMapping
    public ResponseEntity<PitchResponse> create(
            @Parameter(description = TEST_USER_DESCRIPTION, required = true)
            @RequestHeader(TEST_USER_HEADER) UUID userId,
            @Valid @RequestBody PitchCreateRequest request
    ) {
        PitchCreationResult result = pitchService.create(userId, request);
        if (!result.created()) {
            return ResponseEntity.ok(result.response());
        }

        URI location = URI.create("/api/pitches/" + result.response().id());
        return ResponseEntity.created(location).body(result.response());
    }

    @Operation(summary = "운동장 목록 조회", description = "소유한 활성 운동장을 최신 생성 순으로 조회합니다.")
    @GetMapping
    public List<PitchSummaryResponse> findAll(
            @Parameter(description = TEST_USER_DESCRIPTION, required = true)
            @RequestHeader(TEST_USER_HEADER) UUID userId
    ) {
        return pitchService.findAll(userId);
    }

    @Operation(summary = "운동장 상세 조회", description = "모서리를 cornerOrder 오름차순으로 조회합니다.")
    @GetMapping("/{pitchId}")
    public PitchResponse findOne(
            @Parameter(description = TEST_USER_DESCRIPTION, required = true)
            @RequestHeader(TEST_USER_HEADER) UUID userId,
            @PathVariable UUID pitchId
    ) {
        return pitchService.findOne(userId, pitchId);
    }

    @Operation(summary = "운동장 수정", description = "이름과 네 모서리 좌표 전체를 수정합니다.")
    @PutMapping("/{pitchId}")
    public PitchResponse update(
            @Parameter(description = TEST_USER_DESCRIPTION, required = true)
            @RequestHeader(TEST_USER_HEADER) UUID userId,
            @PathVariable UUID pitchId,
            @Valid @RequestBody PitchUpdateRequest request
    ) {
        return pitchService.update(userId, pitchId, request);
    }

    @Operation(summary = "운동장 소프트 삭제", description = "행을 삭제하지 않고 deletedAt을 기록합니다.")
    @DeleteMapping("/{pitchId}")
    public ResponseEntity<Void> delete(
            @Parameter(description = TEST_USER_DESCRIPTION, required = true)
            @RequestHeader(TEST_USER_HEADER) UUID userId,
            @PathVariable UUID pitchId
    ) {
        pitchService.delete(userId, pitchId);
        return ResponseEntity.noContent().build();
    }
}
