package com.teamehera.kiikick.workout.controller;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.teamehera.kiikick.workout.domain.HeartRateSample;
import com.teamehera.kiikick.workout.domain.TrackPoint;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import com.teamehera.kiikick.workout.dto.WorkoutPayloadCreateRequest;
import com.teamehera.kiikick.workout.repository.HeartRateSampleRepository;
import com.teamehera.kiikick.workout.repository.TrackPointRepository;
import com.teamehera.kiikick.workout.repository.WorkoutPayloadRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WorkoutPayloadControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkoutPayloadRepository workoutPayloadRepository;

    @Autowired
    private TrackPointRepository trackPointRepository;

    @Autowired
    private HeartRateSampleRepository heartRateSampleRepository;

    @Autowired
    private Validator validator;

    @Test
    void uploadsIosExampleWithoutTransformingAppleEpochDoubleValues() throws Exception {
        String responseBody = mockMvc.perform(post("/api/workout-payloads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadJson()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/workout-payloads/")))
                .andExpect(jsonPath("$.workoutPayloadId").isString())
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID payloadId = UUID.fromString(JsonPath.read(responseBody, "$.workoutPayloadId"));
        WorkoutPayload payload = workoutPayloadRepository.findById(payloadId).orElseThrow();
        List<TrackPoint> points = trackPointRepository
                .findAllByWorkoutPayloadIdOrderBySampleSeqAsc(payloadId);
        List<HeartRateSample> heartRates = heartRateSampleRepository
                .findAllByWorkoutPayloadIdOrderBySampleSeqAsc(payloadId);

        assertAll(
                () -> assertEquals(LocalDateTime.parse("2024-10-07T13:20:00"), payload.getStartDate()),
                () -> assertEquals(LocalDateTime.parse("2024-10-07T13:30:00"), payload.getEndDate()),
                () -> assertEquals(List.of(0), points.stream().map(TrackPoint::getSampleSeq).toList()),
                () -> assertEquals(2.3, points.getFirst().getSpeed()),
                () -> assertEquals(87.5, points.getFirst().getCourse()),
                () -> assertEquals(
                        LocalDateTime.parse("2024-10-07T13:20:01.200000"),
                        points.getFirst().getTimestamp()
                ),
                () -> assertEquals(List.of(0, 1), heartRates.stream().map(HeartRateSample::getSampleSeq).toList())
        );
    }

    @Test
    void preservesNegativeOneSpeedAndCourse() throws Exception {
        String responseBody = mockMvc.perform(post("/api/workout-payloads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadJson()
                                .replace("\"speed\": 2.3", "\"speed\": -1.0")
                                .replace("\"course\": 87.5", "\"course\": -1.0")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID payloadId = UUID.fromString(JsonPath.read(responseBody, "$.workoutPayloadId"));
        TrackPoint point = trackPointRepository
                .findAllByWorkoutPayloadIdOrderBySampleSeqAsc(payloadId)
                .getFirst();

        assertEquals(-1.0, point.getSpeed());
        assertEquals(-1.0, point.getCourse());
    }

    @Test
    void roundsAndStoresAppleEpochAtMicrosecondPrecision() throws Exception {
        String request = payloadJson()
                .replace("750000000.0", "750000000.1234564")
                .replace("750000600.0", "750000600.1234565")
                .replace("750000001.2", "750000001.1234565");

        String responseBody = mockMvc.perform(post("/api/workout-payloads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID payloadId = UUID.fromString(JsonPath.read(responseBody, "$.workoutPayloadId"));
        WorkoutPayload payload = workoutPayloadRepository.findById(payloadId).orElseThrow();
        TrackPoint point = trackPointRepository
                .findAllByWorkoutPayloadIdOrderBySampleSeqAsc(payloadId)
                .getFirst();

        assertEquals(LocalDateTime.parse("2024-10-07T13:20:00.123456"), payload.getStartDate());
        assertEquals(LocalDateTime.parse("2024-10-07T13:30:00.123457"), payload.getEndDate());
        assertEquals(LocalDateTime.parse("2024-10-07T13:20:01.123457"), point.getTimestamp());
    }

    @Test
    void acceptsEmptySampleArrays() throws Exception {
        mockMvc.perform(post("/api/workout-payloads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyArraysJson()))
                .andExpect(status().isCreated());

        assertAll(
                () -> assertEquals(1, workoutPayloadRepository.count()),
                () -> assertEquals(0, trackPointRepository.count()),
                () -> assertEquals(0, heartRateSampleRepository.count())
        );
    }

    @Test
    void rejectsEndDateBeforeStartDateAndCoordinateOutsideRange() throws Exception {
        mockMvc.perform(post("/api/workout-payloads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyArraysJson().replace("750000600.0", "749999999.0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));

        mockMvc.perform(post("/api/workout-payloads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadJson().replace("37.5665", "91.0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        assertEquals(0, workoutPayloadRepository.count());
    }

    @Test
    void finiteNumberBeanValidationRejectsNan() {
        WorkoutPayloadCreateRequest request = new WorkoutPayloadCreateRequest(
                Double.NaN,
                1.0,
                1.0,
                1.0,
                List.of(),
                List.of()
        );

        Set<ConstraintViolation<WorkoutPayloadCreateRequest>> violations = validator.validate(request);

        assertFalse(violations.isEmpty());
        assertEquals("startDate", violations.iterator().next().getPropertyPath().toString());
    }

    @Test
    void exposesWorkoutPayloadApiAndExamplesThroughOpenApi() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/workout-payloads'].post").exists())
                .andExpect(jsonPath(
                        "$.paths['/api/workout-payloads'].post.requestBody.content['application/json'].examples"
                ).exists())
                .andExpect(jsonPath(
                        "$.paths['/api/workout-payloads'].post.responses['201'].content['application/json'].examples"
                ).exists());

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    private String payloadJson() {
        return """
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
                    {"timestamp": 750000001.0, "bpm": 142.0},
                    {"timestamp": 750000006.0, "bpm": 145.0}
                  ]
                }
                """;
    }

    private String emptyArraysJson() {
        return """
                {
                  "startDate": 750000000.0,
                  "endDate": 750000600.0,
                  "totalCalories": 0.0,
                  "totalDistance": 0.0,
                  "points": [],
                  "heartRateSamples": []
                }
                """;
    }
}
