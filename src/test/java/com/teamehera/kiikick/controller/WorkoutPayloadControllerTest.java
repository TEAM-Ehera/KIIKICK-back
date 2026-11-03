package com.teamehera.kiikick.workout.controller;

import com.teamehera.kiikick.global.exception.GlobalExceptionHandler;
import com.teamehera.kiikick.global.exception.UserNotFoundException;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateRequest;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateResponse;
import com.teamehera.kiikick.workout.service.WorkoutPayloadService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * 실제 서버와 DB를 실행하지 않고 WorkoutPayload HTTP API를 검증한다.
 */
@WebMvcTest(WorkoutPayloadController.class)
@Import(GlobalExceptionHandler.class)
class WorkoutPayloadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkoutPayloadService workoutPayloadService;

    @Test
    void 유효한_운동기록을_업로드하면_201과_운동UUID를_반환한다()
            throws Exception {

        UUID userId = UUID.randomUUID();
        UUID workoutPayloadId = UUID.randomUUID();

        when(workoutPayloadService.create(
                eq(userId),
                any(WorkoutPayloadCreateRequest.class)
        )).thenReturn(
                new WorkoutPayloadCreateResponse(
                        workoutPayloadId
                )
        );

        mockMvc.perform(
                        post(
                                "/api/v1/user/{userId}/workout-payloads",
                                userId
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequestJson())
                )
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.workoutPayloadId")
                                .value(workoutPayloadId.toString())
                );
    }

    @Test
    void 존재하지_않는_사용자의_운동기록은_404를_반환한다()
            throws Exception {

        UUID userId = UUID.randomUUID();

        when(workoutPayloadService.create(
                eq(userId),
                any(WorkoutPayloadCreateRequest.class)
        )).thenThrow(
                new UserNotFoundException(userId)
        );

        mockMvc.perform(
                        post(
                                "/api/v1/user/{userId}/workout-payloads",
                                userId
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validRequestJson())
                )
                .andExpect(status().isNotFound())
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "사용자를 찾을 수 없습니다. id="
                                                + userId
                                )
                );
    }

    @Test
    void 종료시각이_시작시각보다_빠르면_400을_반환한다()
            throws Exception {

        UUID userId = UUID.randomUUID();

        String invalidRequest = """
                {
                  "startDate": 60.0,
                  "endDate": 0.0,
                  "totalCalories": 420.5,
                  "totalDistance": 3182.4,
                  "points": [],
                  "heartRateSamples": []
                }
                """;

        mockMvc.perform(
                        post(
                                "/api/v1/user/{userId}/workout-payloads",
                                userId
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidRequest)
                )
                .andExpect(status().isBadRequest());

        // DTO 검증에서 중단되므로 Service는 호출되지 않아야 한다.
        verifyNoInteractions(workoutPayloadService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"NaN\"", "1e309", "-1e309"})
    void totalCalories가_유한하지_않으면_400을_반환한다(
            String invalidValue
    ) throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(
                        post(
                                "/api/v1/user/{userId}/workout-payloads",
                                userId
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestJson(invalidValue, "3182.4"))
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "totalCalories는 유한한 숫자여야 합니다."
                                )
                );

        verifyNoInteractions(workoutPayloadService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"NaN\"", "1e309", "-1e309"})
    void totalDistance가_유한하지_않으면_400을_반환한다(
            String invalidValue
    ) throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(
                        post(
                                "/api/v1/user/{userId}/workout-payloads",
                                userId
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestJson("420.5", invalidValue))
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value(
                                        "totalDistance는 유한한 숫자여야 합니다."
                                )
                );

        verifyNoInteractions(workoutPayloadService);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidArrayRequests")
    void 배열_검증에_실패하면_400을_반환한다(
            String caseName,
            String points,
            String heartRateSamples,
            String expectedMessage
    ) throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(
                        post(
                                "/api/v1/user/{userId}/workout-payloads",
                                userId
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(arrayRequestJson(
                                        points,
                                        heartRateSamples
                                ))
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.message")
                                .value(expectedMessage)
                );

        verifyNoInteractions(workoutPayloadService);
    }

    @Test
    void 빈_배열은_허용한다() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID workoutPayloadId = UUID.randomUUID();

        when(workoutPayloadService.create(
                eq(userId),
                any(WorkoutPayloadCreateRequest.class)
        )).thenReturn(
                new WorkoutPayloadCreateResponse(workoutPayloadId)
        );

        mockMvc.perform(
                        post(
                                "/api/v1/user/{userId}/workout-payloads",
                                userId
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(arrayRequestJson("[]", "[]"))
                )
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.workoutPayloadId")
                                .value(workoutPayloadId.toString())
                );
    }

    private static Stream<Arguments> invalidArrayRequests() {
        return Stream.of(
                Arguments.of(
                        "points 배열이 null",
                        "null",
                        "[]",
                        "points는 필수입니다."
                ),
                Arguments.of(
                        "points 배열 요소가 null",
                        "[null]",
                        "[]",
                        "points 배열 요소는 null일 수 없습니다."
                ),
                Arguments.of(
                        "heartRateSamples 배열이 null",
                        "[]",
                        "null",
                        "heartRateSamples는 필수입니다."
                ),
                Arguments.of(
                        "heartRateSamples 배열 요소가 null",
                        "[]",
                        "[null]",
                        "heartRateSamples 배열 요소는 null일 수 없습니다."
                )
        );
    }

    private String arrayRequestJson(
            String points,
            String heartRateSamples
    ) {
        return """
                {
                  "startDate": 750000000.0,
                  "endDate": 750000600.0,
                  "totalCalories": 420.5,
                  "totalDistance": 3182.4,
                  "points": %s,
                  "heartRateSamples": %s
                }
                """.formatted(points, heartRateSamples);
    }

    private String requestJson(
            String totalCalories,
            String totalDistance
    ) {
        return """
                {
                  "startDate": 750000000.0,
                  "endDate": 750000600.0,
                  "totalCalories": %s,
                  "totalDistance": %s,
                  "points": [],
                  "heartRateSamples": []
                }
                """.formatted(totalCalories, totalDistance);
    }

    private String validRequestJson() {
        return """
                {
                  "startDate": 750000000.0,
                  "endDate": 750000600.0,
                  "totalCalories": 420.5,
                  "totalDistance": 3182.4,
                  "points": [
                    {
                      "latitude": 37.5665,
                      "longitude": 126.9780,
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
                """;
    }
}
