package com.teamehera.kiikick.repository;

import com.teamehera.kiikick.global.idempotency.repository.IdempotencyRecordRepository;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.match.service.MatchMutation.Rejected;
import com.teamehera.kiikick.match.service.MatchMutation.Request;
import com.teamehera.kiikick.match.service.MatchMutation.Response;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@JdbcTest
@Import({MatchMutation.class, MatchRepository.class, IdempotencyRecordRepository.class, UserRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MatchMutationTest {

    @Autowired MatchMutation mutation;
    @Autowired MatchRepository matches;
    @Autowired IdempotencyRecordRepository records;
    @Autowired UserRepository users;

    private final UUID actor = UUID.randomUUID();
    private final UUID matchId = UUID.randomUUID();
    private final UUID key = UUID.randomUUID();
    private final LocalDateTime now = LocalDateTime.of(2026, 11, 4, 0, 0);

    @BeforeEach
    void setUp() {
        users.save(actor, null);
        matches.save(new Match(matchId, actor, null, null, null, null,
                null, "DRAFT", null, null, 1, now, now));
    }

    @Test
    void replayPrecedesVersionCheckAndStillChecksAccess() {
        var request = request(key, MatchMutation.etag(1), new byte[0]);
        var response = mutation.execute(request, now.plusDays(1), match -> {}, this::update);
        assertEquals(response, mutation.execute(request, now.plusDays(1), match -> {},
                match -> fail("A replay must not repeat the change")));
        assertThrows(SecurityException.class, () -> mutation.execute(request, now.plusDays(1),
                match -> { throw new SecurityException(); }, match -> fail("Access was revoked")));
        assertEquals(2, version());

        assertRejected(409, "IDEMPOTENCY_KEY_REUSED", request(key, request.ifMatch(), new byte[]{1}));
        assertRejected(409, "IDEMPOTENCY_KEY_REUSED", request(key, MatchMutation.etag(2), new byte[0]));
        assertRejected(412, "VERSION_CONFLICT", request(UUID.randomUUID(), request.ifMatch(), new byte[0]));
        assertRejected(428, "PRECONDITION_REQUIRED", request(UUID.randomUUID(), null, new byte[0]));
    }

    @Test
    void failureRollsBackMutationAndReservation() {
        var request = request(key, MatchMutation.etag(1), new byte[0]);
        assertThrows(IllegalStateException.class, () -> mutation.execute(request, now.plusDays(1),
                match -> {}, match -> {
                    update(match);
                    throw new IllegalStateException("Response serialization failed");
                }));

        assertEquals(1, version());
        assertTrue(records.findByScope(actor, request.method(), request.canonicalPath(), key).isEmpty());
        assertEquals(200, mutation.execute(request, now.plusDays(1), match -> {}, this::update).status());
        assertEquals(2, version());
    }

    @Test
    void concurrentChangesWithTheSameVersionHaveOnlyOneWinner() throws Exception {
        var ready = new CountDownLatch(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> attempt = () -> {
                ready.countDown();
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                var request = request(UUID.randomUUID(), MatchMutation.etag(1), new byte[0]);
                try {
                    return mutation.execute(request, now.plusDays(1), match -> {}, this::update).status();
                } catch (Rejected failure) {
                    assertEquals("VERSION_CONFLICT", failure.getMessage());
                    return failure.status();
                }
            };
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            assertEquals(java.util.Set.of(200, 412),
                    java.util.Set.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)));
        }
        assertEquals(2, version());
    }

    private Request request(UUID key, String ifMatch, byte[] body) {
        return new Request(actor, matchId, "PATCH", "/api/v1/matches/" + matchId, key, ifMatch, body);
    }

    private void assertRejected(int status, String code, Request request) {
        var failure = assertThrows(Rejected.class, () -> mutation.execute(request, now.plusDays(1),
                match -> {}, match -> fail("A rejected request must not mutate")));
        assertEquals(status, failure.status());
        assertEquals(code, failure.getMessage());
    }

    private Response update(Match match) {
        assertEquals(1, matches.updateSettings(match, match.version()));
        return new Response(200, "{\"ETag\":\"\\\"2\\\"\"}", "{\"meta\":{\"matchVersion\":2}}");
    }

    private long version() {
        return matches.findByIdAndOwnerId(matchId, actor).orElseThrow().version();
    }
}
