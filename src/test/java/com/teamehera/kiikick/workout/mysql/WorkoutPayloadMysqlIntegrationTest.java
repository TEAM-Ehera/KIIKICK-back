package com.teamehera.kiikick.workout.mysql;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.teamehera.kiikick.workout.domain.HeartRateSample;
import com.teamehera.kiikick.workout.domain.TrackPoint;
import com.teamehera.kiikick.workout.domain.WorkoutPayload;
import com.teamehera.kiikick.workout.repository.HeartRateSampleRepository;
import com.teamehera.kiikick.workout.repository.TrackPointRepository;
import com.teamehera.kiikick.workout.repository.WorkoutPayloadRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@EnabledIfEnvironmentVariable(named = "KIIKICK_MYSQL_VERIFY", matches = "true")
@SpringBootTest(classes = WorkoutPayloadMysqlIntegrationTest.TestApplication.class)
@AutoConfigureMockMvc
class WorkoutPayloadMysqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkoutPayloadRepository workoutPayloadRepository;

    @Autowired
    private TrackPointRepository trackPointRepository;

    @Autowired
    private HeartRateSampleRepository heartRateSampleRepository;

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnvironment("KIIKICK_MYSQL_URL"));
        registry.add("spring.datasource.username", () -> requiredEnvironment("KIIKICK_MYSQL_USERNAME"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("KIIKICK_MYSQL_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.open-in-view", () -> "false");
    }

    @BeforeEach
    void clearWorkoutTables() {
        jdbcTemplate.update("DELETE FROM `heartRateSamples`");
        jdbcTemplate.update("DELETE FROM `trackPoints`");
        jdbcTemplate.update("DELETE FROM `workoutPayloads`");
    }

    @Test
    void validatesSchemaAndStoresIosPayloadInMysql() throws Exception {
        String responseBody = mockMvc.perform(post("/api/workout-payloads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workoutPayloadId").isString())
                .andReturn()
                .getResponse()
                .getContentAsString();

        UUID payloadId = UUID.fromString(JsonPath.read(responseBody, "$.workoutPayloadId"));
        Map<String, Object> payload = jdbcTemplate.queryForMap("""
                SELECT HEX(`id`) AS id,
                       `startDate`, `endDate`, `totalCalories`, `totalDistance`
                FROM `workoutPayloads`
                WHERE `id` = ?
                """, uuidBytes(payloadId));
        Map<String, Object> point = jdbcTemplate.queryForMap("""
                SELECT `sampleSeq`, `latitude`, `longitude`, `speed`, `course`, `timestamp`
                FROM `trackPoints`
                WHERE `workoutPayloadId` = ?
                """, uuidBytes(payloadId));
        List<Map<String, Object>> heartRates = jdbcTemplate.queryForList("""
                SELECT `sampleSeq`, `timestamp`, `bpm`
                FROM `heartRateSamples`
                WHERE `workoutPayloadId` = ?
                ORDER BY `sampleSeq`
                """, uuidBytes(payloadId));
        WorkoutPayload repositoryPayload = workoutPayloadRepository.findById(payloadId).orElseThrow();
        TrackPoint repositoryPoint = trackPointRepository
                .findAllByWorkoutPayloadIdOrderBySampleSeqAsc(payloadId)
                .getFirst();
        List<HeartRateSample> repositoryHeartRates = heartRateSampleRepository
                .findAllByWorkoutPayloadIdOrderBySampleSeqAsc(payloadId);

        assertAll(
                () -> assertEquals(payloadId.toString().replace("-", "").toUpperCase(), payload.get("id")),
                () -> assertEquals(LocalDateTime.parse("2024-10-07T13:20:00.123456"), payload.get("startDate")),
                () -> assertEquals(LocalDateTime.parse("2024-10-07T13:30:00.123457"), payload.get("endDate")),
                () -> assertEquals(420.5, ((Number) payload.get("totalCalories")).doubleValue()),
                () -> assertEquals(3182.4, ((Number) payload.get("totalDistance")).doubleValue()),
                () -> assertEquals(0, ((Number) point.get("sampleSeq")).intValue()),
                () -> assertEquals(37.5665, ((Number) point.get("latitude")).doubleValue()),
                () -> assertEquals(126.978, ((Number) point.get("longitude")).doubleValue()),
                () -> assertEquals(-1.0, ((Number) point.get("speed")).doubleValue()),
                () -> assertEquals(-1.0, ((Number) point.get("course")).doubleValue()),
                () -> assertEquals(LocalDateTime.parse("2024-10-07T13:20:01.123457"), point.get("timestamp")),
                () -> assertEquals(List.of(0, 1), heartRates.stream()
                        .map(row -> ((Number) row.get("sampleSeq")).intValue())
                        .toList()),
                () -> assertEquals(142.25, ((Number) heartRates.getFirst().get("bpm")).doubleValue()),
                () -> assertEquals(LocalDateTime.parse("2024-10-07T13:20:00.123456"),
                        repositoryPayload.getStartDate()),
                () -> assertEquals(LocalDateTime.parse("2024-10-07T13:30:00.123457"),
                        repositoryPayload.getEndDate()),
                () -> assertEquals(LocalDateTime.parse("2024-10-07T13:20:01.123457"),
                        repositoryPoint.getTimestamp()),
                () -> assertEquals(
                        List.of(
                                LocalDateTime.parse("2024-10-07T13:20:01"),
                                LocalDateTime.parse("2024-10-07T13:20:06")
                        ),
                        repositoryHeartRates.stream().map(HeartRateSample::getTimestamp).toList()
                )
        );
    }

    private static byte[] uuidBytes(UUID uuid) {
        byte[] bytes = new byte[16];
        long mostSignificantBits = uuid.getMostSignificantBits();
        long leastSignificantBits = uuid.getLeastSignificantBits();
        for (int index = 0; index < 8; index++) {
            bytes[index] = (byte) (mostSignificantBits >>> (56 - index * 8));
            bytes[index + 8] = (byte) (leastSignificantBits >>> (56 - index * 8));
        }
        return bytes;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " environment variable is required");
        }
        return value;
    }

    private String payloadJson() {
        return """
                {
                  "startDate": 750000000.1234564,
                  "endDate": 750000600.1234565,
                  "totalCalories": 420.5,
                  "totalDistance": 3182.4,
                  "points": [{
                    "latitude": 37.5665,
                    "longitude": 126.978,
                    "altitude": 20.1,
                    "horizontalAccuracy": 4.8,
                    "speed": -1.0,
                    "course": -1.0,
                    "timestamp": 750000001.1234565
                  }],
                  "heartRateSamples": [
                    {"timestamp": 750000001.0, "bpm": 142.25},
                    {"timestamp": 750000006.0, "bpm": 145.0}
                  ]
                }
                """;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = WorkoutPayload.class)
    @EnableJpaRepositories(basePackages = "com.teamehera.kiikick.workout.repository")
    @ComponentScan(basePackages = {
            "com.teamehera.kiikick.workout.controller",
            "com.teamehera.kiikick.workout.service",
            "com.teamehera.kiikick.workout.validation",
            "com.teamehera.kiikick.common.exception"
    })
    static class TestApplication {
    }
}
