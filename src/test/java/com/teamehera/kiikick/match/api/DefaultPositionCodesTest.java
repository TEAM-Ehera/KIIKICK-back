package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.auth.security.AccessTokenService;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static com.teamehera.kiikick.auth.security.TestJwtRequests.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DefaultPositionCodesTest {
    @Autowired MockMvc mvc;
    @Autowired AccessTokenService tokens;
    @Autowired UserRepository users;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:h2:mem:default_positions;MODE=MySQL");
    }

    @Test
    void submissionsAcceptEveryAgreedUppercaseCodeWithDefaultConfiguration() throws Exception {
        UUID actor = UUID.randomUUID();
        users.save(actor, null);
        for (String code : List.of("GK", "LB", "CB", "RB", "LM", "CM", "RM", "LW", "ST", "RW")) {
            UUID match = UUID.randomUUID();
            mvc.perform(post("/api/v1/matches/{match}/workout-submissions", match)
                            .with(bearer(tokens, actor)).header("Idempotency-Key", UUID.randomUUID())
                            .contentType("application/json").content(body(code)))
                    .andExpect(status().isAccepted());
            mvc.perform(get("/api/v1/matches/{match}/result/quarters", match).with(bearer(tokens, actor)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].position.positionCode").value(code));
        }
    }

    @Test
    void submissionsRejectLowercaseAndUnknownCodes() throws Exception {
        UUID actor = UUID.randomUUID();
        users.save(actor, null);
        for (String code : List.of("gk", "cm", "PIVOT", "UNKNOWN")) {
            mvc.perform(post("/api/v1/matches/{match}/workout-submissions", UUID.randomUUID())
                            .with(bearer(tokens, actor)).header("Idempotency-Key", UUID.randomUUID())
                            .contentType("application/json").content(body(code)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.details.fields[0]").value("positionCode"));
        }
    }

    private static String body(String position) {
        return """
                {"quarters":[{"quarterId":"%s","position":"%s","workout":{
                  "startDate":815274000,"endDate":815274600,"totalCalories":100,"totalDistance":1500,
                  "points":[],"heartRateSamples":[]}}]}
                """.formatted(UUID.randomUUID(), position);
    }
}
