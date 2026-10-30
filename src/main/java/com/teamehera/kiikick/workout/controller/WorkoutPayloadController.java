package com.teamehera.kiikick.workout.controller;

import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateRequest;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateResponse;
import com.teamehera.kiikick.workout.service.WorkoutPayloadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(
        name = "WorkoutPayload",
        description = "Apple Watch에서 측정한 운동 기록 업로드 API"
)
@RestController
@RequestMapping("/api/v1/user/{userId}/workout-payloads")
public class WorkoutPayloadController {

    private final WorkoutPayloadService workoutPayloadService;

    public WorkoutPayloadController(
            WorkoutPayloadService workoutPayloadService
    ) {
        this.workoutPayloadService = workoutPayloadService;
    }

    @Operation(
            summary = "WorkoutPayload 업로드",
            description = """
                    iOS가 전달한 운동 본체, GPS 포인트, 심박수 샘플을 하나의 트랜잭션으로 저장합니다.
                    날짜 숫자는 2001-01-01T00:00:00Z 기준 초 단위 Apple epoch이며 서버에서 UTC로 변환합니다.
                    userId는 인증이 없는 개발 단계에서 운동 소유자를 지정하기 위해 URL로 전달합니다.
                    """,
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = WorkoutPayloadCreateRequest.class),
                            examples = @ExampleObject(
                                    name = "iOS WorkoutPayload",
                                    value = """
                                            {
                                              "startDate": 750000000.0,
                                              "endDate": 750000600.0,
                                              "totalCalories": 420.5,
                                              "totalDistance": 3182.4,
                                              "points": [
                                                {
                                                  "latitude": 37.5665,
                                                  "longitude": 126.978,
                                                  "altitude": 20.1,
                                                  "horizontalAccuracy": 4.8,
                                                  "speed": 2.3,
                                                  "course": 87.5,
                                                  "timestamp": 750000001.2
                                                }
                                              ],
                                              "heartRateSamples": [
                                                {
                                                  "timestamp": 750000001.0,
                                                  "bpm": 142.0
                                                }
                                              ]
                                            }
                                            """
                            )
                    )
            )
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "운동 기록 저장 성공",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = WorkoutPayloadCreateResponse.class),
                            examples = @ExampleObject(
                                    value = "{\"workoutPayloadId\":\"550e8400-e29b-41d4-a716-446655440000\"}"
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "UUID 형식 오류, 요청값 검증 실패 또는 Apple epoch 변환 실패",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = {
                                    @ExampleObject(
                                            name = "요청값 검증 실패",
                                            value = "{\"message\":\"잘못된 요청입니다.\"}"
                                    ),
                                    @ExampleObject(
                                            name = "Apple epoch 오류",
                                            value = "{\"message\":\"Apple epoch 날짜가 유효하지 않습니다.\"}"
                                    )
                            }
                    )
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "사용자가 존재하지 않거나 이미 삭제됨",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(
                                    value = "{\"message\":\"사용자를 찾을 수 없습니다. id=550e8400-e29b-41d4-a716-446655440000\"}"
                            )
                    )
            )
    })
    @PostMapping
    public ResponseEntity<WorkoutPayloadCreateResponse> create(
            @Parameter(
                    description = "운동 기록을 소유할 사용자 UUID",
                    required = true,
                    example = "550e8400-e29b-41d4-a716-446655440000"
            )
            @PathVariable("userId") UUID userId,
            @Valid @RequestBody WorkoutPayloadCreateRequest request
    ) {
        WorkoutPayloadCreateResponse response =
                workoutPayloadService.create(userId, request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }
}
