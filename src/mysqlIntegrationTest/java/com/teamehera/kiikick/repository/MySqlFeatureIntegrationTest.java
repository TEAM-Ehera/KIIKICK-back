package com.teamehera.kiikick.repository;

import com.teamehera.kiikick.auth.domain.AuthSession;
import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import com.teamehera.kiikick.auth.security.AccessTokenService;
import com.teamehera.kiikick.user.repository.UserRepository;
import com.teamehera.kiikick.workout.repository.QuarterSummaryRepository;
import com.teamehera.kiikick.workout.service.QuarterSummaryCalculation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = {"spring.sql.init.mode=never", "kiikick.workout.summary-worker-enabled=false"})
@AutoConfigureMockMvc
class MySqlFeatureIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.teamehera.kiikick.auth.email.VerificationEmailSender emailSender;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired AuthSessionRepository sessions;
    @Autowired AccessTokenService tokens;
    @Autowired QuarterSummaryRepository summaries;
    @Autowired QuarterSummaryCalculation calculation;
    private String bearer;

    @BeforeEach
    void isolatedDatabaseAndActiveSession() {
        assertEquals("kiikick_repository_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        UUID actor = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        var now = LocalDateTime.now(ZoneOffset.UTC);
        users.save(actor, "mysql-feature");
        sessions.save(new AuthSession(session, actor, null, UUID.randomUUID().toString(), 0,
                now.plusDays(1), null, now));
        bearer = "Bearer " + tokens.issue(actor, session).token();
    }

    @Test
    void concurrentCreateReplaysAndCompetingVersionsAllowOnlyOneWriter() throws Exception {
        UUID key = UUID.randomUUID();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return write(post("/api/v1/matches"), key, null, "{}");
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            var first = tasks.get(0).get(15, TimeUnit.SECONDS);
            var replay = tasks.get(1).get(15, TimeUnit.SECONDS);
            assertEquals(201, first.getStatus());
            assertEquals(201, replay.getStatus());
            assertEquals(json.readTree(first.getContentAsString()), json.readTree(replay.getContentAsString()));
            assertEquals(first.getHeader("X-Request-Id"), replay.getHeader("X-Request-Id"));
            String location = first.getHeader("Location");
            var conflictStart = new CountDownLatch(1);
            var updates = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                assertTrue(conflictStart.await(10, TimeUnit.SECONDS));
                return write(patch(location), UUID.randomUUID(), "\"1\"", "{\"title\":\"updated\"}").getStatus();
            })).toList();
            conflictStart.countDown();
            var statuses = List.of(updates.get(0).get(15, TimeUnit.SECONDS), updates.get(1).get(15, TimeUnit.SECONDS));
            assertTrue(statuses.containsAll(List.of(200, 412)), statuses.toString());
        }
    }

    @Test
    void lifecycleUploadWorkerAndAnalyticsUseRealMysqlJsonAndTimestamps() throws Exception {
        var match = write(post("/api/v1/matches"), UUID.randomUUID(), null, "{}");
        assertEquals(201, match.getStatus());
        String path = match.getHeader("Location");
        var quarter = write(post(path + "/quarters"), UUID.randomUUID(), "\"1\"", "{\"durationSeconds\":60}");
        assertEquals(201, quarter.getStatus());
        String quarterPath = quarter.getHeader("Location");
        String tag = quarter.getHeader("ETag");
        for (String command : List.of(path + "/ready", path + "/start", quarterPath + "/start", quarterPath + "/end", quarterPath + "/complete", path + "/end")) {
            var response = write(post(command), UUID.randomUUID(), tag, null);
            assertEquals(200, response.getStatus(), response.getContentAsString());
            tag = response.getHeader("ETag");
        }
        String quarterId = json.readTree(quarter.getContentAsString()).at("/data/id").asText();
        var upload = write(post("/api/v1/quarters/" + quarterId + "/workout-payloads"), UUID.randomUUID(), tag,
                "{\"startDate\":1000.0,\"endDate\":1060.0,\"totalCalories\":10,\"totalDistance\":120,\"points\":[],\"heartRateSamples\":[]}");
        assertEquals(201, upload.getStatus(), upload.getContentAsString());
        var job = summaries.findProcessing(100).stream().filter(item -> item.summary().quarterId().toString().equals(quarterId)).findFirst().orElseThrow();
        calculation.process(job);
        calculation.process(job); // 같은 계산 시도 재실행이 version을 두 번 올리지 않아야 한다.
        var result = mvc.perform(get(path + "/result").header("Authorization", bearer)).andReturn().getResponse();
        assertEquals(200, result.getStatus());
        assertEquals("\"10\"", result.getHeader("ETag"));
        var analytics = mvc.perform(get("/api/v1/analytics/summary").header("Authorization", bearer)).andReturn().getResponse();
        assertEquals(200, analytics.getStatus(), analytics.getContentAsString());
        assertEquals(120.0, json.readTree(analytics.getContentAsString()).at("/data/metrics/totalDistanceM/value").asDouble());
        assertEquals(2.0, json.readTree(analytics.getContentAsString()).at("/data/metrics/averageSpeedMps/value").asDouble());
    }

    private MockHttpServletResponse write(MockHttpServletRequestBuilder request, UUID key, String etag, String body) throws Exception {
        request.header("Authorization", bearer).header("Idempotency-Key", key);
        if (etag != null) request.header("If-Match", etag);
        if (body != null) request.contentType("application/json").content(body);
        return mvc.perform(request).andReturn().getResponse();
    }
}
