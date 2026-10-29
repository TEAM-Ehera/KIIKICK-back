package com.teamehera.kiikick.workout.controller;

import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateRequest;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateResponse;
import com.teamehera.kiikick.workout.service.WorkoutPayloadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "WorkoutPayloads", description = "iOS WorkoutPayload 원본 저장 API")
@RestController
@RequestMapping("/api/workout-payloads")
public class WorkoutPayloadController {

    private final WorkoutPayloadService workoutPayloadService;

    public WorkoutPayloadController(WorkoutPayloadService workoutPayloadService) {
        this.workoutPayloadService = workoutPayloadService;
    }

    @Operation(
            summary = "WorkoutPayload 업로드",
            description = "iOS WorkoutPayload JSON과 GPS·심박수 배열을 하나의 트랜잭션으로 저장합니다.",
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
                                              "points": [{
                                                "latitude": 37.5665,
                                                "longitude": 126.978,
                                                "altitude": 20.1,
                                                "horizontalAccuracy": 4.8,
                                                "speed": 2.3,
                                                "course": 87.5,
                                                "timestamp": 750000001.2
                                              }],
                                              "heartRateSamples": [{
                                                "timestamp": 750000001.0,
                                                "bpm": 142.0
                                              }]
                                            }
                                            """
                            )
                    )
            )
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "저장 성공",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = WorkoutPayloadCreateResponse.class),
                            examples = @ExampleObject(name = "Created", value = """
                                    {"workoutPayloadId":"550e8400-e29b-41d4-a716-446655440000"}
                                    """)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "요청 형식 또는 검증 실패",
                    content = @Content(
                            mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)
                    )
            )
    })
    @PostMapping
    public ResponseEntity<WorkoutPayloadCreateResponse> create(
            @Valid @RequestBody WorkoutPayloadCreateRequest request
    ) {
        WorkoutPayloadCreateResponse response = workoutPayloadService.create(request);
        URI location = URI.create("/api/workout-payloads/" + response.workoutPayloadId());
        return ResponseEntity.created(location).body(response);
    }
}
