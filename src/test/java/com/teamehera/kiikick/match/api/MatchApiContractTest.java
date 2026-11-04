package com.teamehera.kiikick.match.api;

import com.teamehera.kiikick.match.domain.Quarter;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.repository.QuarterRepository;
import com.teamehera.kiikick.match.service.MatchOwnership;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MatchApiContractTest {

    private static final String QUARTERS = "/api/v1/matches/{matchId}/quarters";
    private static final String QUARTER = QUARTERS + "/{quarterId}";

    private final UUID actor = UUID.randomUUID();
    private final UUID match = UUID.randomUUID();
    private final UUID quarter = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var quarters = mock(QuarterRepository.class);
        when(quarters.findByIdAndOwnerId(quarter, actor)).thenReturn(Optional.of(
                new Quarter(quarter, match, 1, 60, "READY", null, null, null, null)));
        mvc = MockMvcBuilders.standaloneSetup(
                        new TestController(actor, quarter, new MatchOwnership(mock(MatchRepository.class), quarters)))
                .setControllerAdvice(new MatchApiExceptionHandler())
                .build();
    }

    @Test
    void addQuarterReturnsCreatedQuarterAndMetadata() throws Exception {
        mvc.perform(change(post(QUARTERS, match))
                        .contentType("application/json").content("{\"durationSeconds\":60}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(quarter.toString()))
                .andExpect(jsonPath("$.data.matchId").value(match.toString()))
                .andExpect(jsonPath("$.data.number").value(1))
                .andExpect(jsonPath("$.data.durationSeconds").value(60))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.startedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.endedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.matchVersion").value(2))
                .andExpect(jsonPath("$.data.createdAt").value("2026-11-04T09:00:00+09:00"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-11-04T09:00:00+09:00"))
                .andExpect(jsonPath("$.meta.requestId").isString())
                .andExpect(jsonPath("$.meta.serverTime").value(endsWith("+09:00")));
    }

    @Test
    void addQuarterRejectsMissingOrNonPositiveDuration() throws Exception {
        for (String body : new String[]{"{}", "{\"durationSeconds\":0}"}) {
            mvc.perform(change(post(QUARTERS, match)).contentType("application/json").content(body))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.error.details.fields[0]").value("durationSeconds"));
        }
    }

    @Test
    void deleteQuarterReturnsNoContent() throws Exception {
        mvc.perform(change(delete(QUARTER, match, quarter)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    @Test
    void deleteQuarterRejectsWrongParentAndInvisibleQuarter() throws Exception {
        mvc.perform(change(delete(QUARTER, UUID.randomUUID(), quarter)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(change(delete(QUARTER, match, UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    private static MockHttpServletRequestBuilder change(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer contract-test")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("If-Match", "\"1\"");
    }

    // Test fixture for MVC serialization, validation and exception handling.
    @MatchApiController
    static class TestController {
        private final UUID actor;
        private final UUID quarterId;
        private final MatchOwnership ownership;

        TestController(UUID actor, UUID quarterId, MatchOwnership ownership) {
            this.actor = actor;
            this.quarterId = quarterId;
            this.ownership = ownership;
        }

        @PostMapping(QUARTERS)
        ResponseEntity<MatchApiResponse.Success<QuarterResponse>> create(
                @PathVariable UUID matchId, @Valid @RequestBody CreateQuarter input
        ) {
            var timestamp = MatchApiResponse.fromUtc(LocalDateTime.of(2026, 11, 4, 0, 0));
            var data = new QuarterResponse(quarterId, matchId, 1, input.durationSeconds(), "READY",
                    null, null, 2, timestamp, timestamp);
            return ResponseEntity.status(201)
                    .body(new MatchApiResponse.Success<>(data, MatchApiResponse.Meta.create()));
        }

        @DeleteMapping(QUARTER)
        ResponseEntity<Void> deleteQuarter(@PathVariable UUID matchId, @PathVariable UUID quarterId) {
            ownership.requireQuarter(actor, matchId, quarterId);
            return ResponseEntity.noContent().build();
        }
    }

    record QuarterResponse(UUID id, UUID matchId, int number, int durationSeconds, String status,
                           OffsetDateTime startedAt, OffsetDateTime endedAt, long matchVersion,
                           OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    record CreateQuarter(@NotNull @Positive Integer durationSeconds) {}
}
