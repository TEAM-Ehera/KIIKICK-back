package com.teamehera.kiikick.match.service;

import com.teamehera.kiikick.global.idempotency.domain.IdempotencyRecord;
import com.teamehera.kiikick.global.idempotency.repository.IdempotencyRecordRepository;
import com.teamehera.kiikick.match.domain.Match;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@Service
public class MatchMutation {

    private final MatchRepository matches;
    private final IdempotencyRecordRepository records;
    private final UserRepository users;

    public MatchMutation(MatchRepository matches, IdempotencyRecordRepository records, UserRepository users) {
        this.matches = matches;
        this.records = records;
        this.users = users;
    }

    public record Request(UUID actorUserId, UUID matchId, String method, String canonicalPath,
                          UUID key, String ifMatch, byte[] body) {}

    public record Response(int status, String headersJson, String bodyJson) {}

    public static final class Rejected extends RuntimeException {
        private final int status;
        private final Long currentVersion;

        private Rejected(int status, String code, Long currentVersion) {
            super(code);
            this.status = status;
            this.currentVersion = currentVersion;
        }

        public int status() { return status; }
        public Long currentVersion() { return currentVersion; }
    }

    public static String etag(long version) {
        return "\"" + version + "\"";
    }

    /**
     * Call with a server-derived canonical path and retention deadline.
     * The access check runs under the parent lock on both new requests and replays.
     * The change must increment the parent version exactly once and return its serialized response.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Response execute(Request request, LocalDateTime expiresAt,
                            Consumer<Match> checkAccess, Function<Match, Response> change) {
        return execute(request, expiresAt, checkAccess, match -> {}, change);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Response execute(Request request, LocalDateTime expiresAt, Consumer<Match> checkAccess,
                            Consumer<Match> checkNewRequest, Function<Match, Response> change) {
        Match match = lockMatch(request);
        checkAccess.accept(match);
        return executeOnce(request, expiresAt, () -> {
            checkNewRequest.accept(match);
            if (request.ifMatch() == null) {
                throw new Rejected(428, "PRECONDITION_REQUIRED", null);
            }
            if (!etag(match.version()).equals(request.ifMatch())) {
                throw new Rejected(412, "VERSION_CONFLICT", match.version());
            }
        }, () -> {
            Response response = change.apply(match);
            if (lockMatch(request).version() != Math.addExact(match.version(), 1)) {
                throw new IllegalStateException("A successful mutation must increment the match version once");
            }
            return response;
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Response create(Request request, LocalDateTime expiresAt, Supplier<Response> change) {
        return executeUnversioned(request, expiresAt, change);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Response executeUnversioned(Request request, LocalDateTime expiresAt, Supplier<Response> change) {
        return executeUnversioned(request, expiresAt, () -> {}, change);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Response executeUnversioned(Request request, LocalDateTime expiresAt, Runnable checkAccess,
                                       Supplier<Response> change) {
        // Serialize writes and retries for resources without a version precondition.
        users.findByIdForUpdate(request.actorUserId())
                .orElseThrow(() -> new Rejected(401, "UNAUTHENTICATED", null));
        checkAccess.run();
        return executeOnce(request, expiresAt, () -> {
            if (request.ifMatch() != null) {
                throw new Rejected(400, "INVALID_REQUEST", null);
            }
        }, change);
    }

    private Response executeOnce(Request request, LocalDateTime expiresAt,
                                 Runnable checkPreconditions, Supplier<Response> change) {
        if (request.key() == null) {
            throw new Rejected(400, "INVALID_REQUEST", null);
        }

        String hash = hash(request.ifMatch(), request.body());
        var existing = records.findByScopeForUpdate(
                request.actorUserId(), request.method(), request.canonicalPath(), request.key());
        if (existing.isPresent()) {
            var saved = existing.orElseThrow();
            if (!saved.requestHash().equals(hash)) {
                throw new Rejected(409, "IDEMPOTENCY_KEY_REUSED", null);
            }
            if (!"COMPLETED".equals(saved.status())) {
                throw new Rejected(409, "REQUEST_IN_PROGRESS", null);
            }
            return new Response(saved.responseStatus(), saved.responseHeaders(), saved.responseBody());
        }

        checkPreconditions.run();

        UUID id = UUID.randomUUID();
        records.save(new IdempotencyRecord(id, request.actorUserId(), request.method(),
                request.canonicalPath(), request.key(), hash, "PROCESSING",
                null, null, null, LocalDateTime.now(ZoneOffset.UTC), expiresAt));
        Response response = change.get();
        if (response.status() < 200 || response.status() >= 300) {
            throw new IllegalStateException("A mutation must return a successful response");
        }
        if (records.complete(id, hash, response.status(), response.headersJson(), response.bodyJson()) != 1) {
            throw new IllegalStateException("Could not store the mutation response");
        }
        return response;
    }

    private Match lockMatch(Request request) {
        return matches.findByIdAndOwnerIdForUpdate(request.matchId(), request.actorUserId())
                .orElseThrow(() -> new Rejected(404, "RESOURCE_NOT_FOUND", null));
    }

    private static String hash(String ifMatch, byte[] body) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] header = ifMatch == null ? new byte[0] : ifMatch.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(ifMatch == null ? -1 : header.length).array());
            digest.update(header);
            if (body != null) {
                digest.update(body);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
