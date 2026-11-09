package com.teamehera.kiikick.friend.service;

import com.teamehera.kiikick.friend.domain.FriendPair;
import com.teamehera.kiikick.friend.domain.UserBlock;
import com.teamehera.kiikick.friend.repository.FriendPairRepository;
import com.teamehera.kiikick.friend.repository.UserBlockRepository;
import com.teamehera.kiikick.friend.service.FriendQuery.Request;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.match.service.MatchOwnership.ResourceNotFound;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.teamehera.kiikick.match.api.MatchApiResponse.fromUtc;

@Service
public class FriendCommands {
    private final FriendPairRepository pairs;
    private final UserBlockRepository blocks;
    private final UserRepository users;
    private final FriendQuery query;
    private final MatchMutation mutation;

    public FriendCommands(FriendPairRepository pairs, UserBlockRepository blocks, UserRepository users,
                          FriendQuery query, MatchMutation mutation) {
        this.pairs = pairs;
        this.blocks = blocks;
        this.users = users;
        this.query = query;
        this.mutation = mutation;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MatchMutation.Response forUser(MatchMutation.Request request, LocalDateTime expiresAt, UUID target,
                                          Supplier<MatchMutation.Response> command) {
        return forUser(request, expiresAt, target, () -> {}, command);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MatchMutation.Response forUser(MatchMutation.Request request, LocalDateTime expiresAt, UUID target,
                                          Runnable checkAccess, Supplier<MatchMutation.Response> command) {
        lockUsers(request.actorUserId(), target);
        return mutation.executeUnversioned(request, expiresAt, checkAccess, command);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MatchMutation.Response forRequest(MatchMutation.Request request, LocalDateTime expiresAt, UUID requestId,
                                             Supplier<MatchMutation.Response> command) {
        return forRequestWithAccess(request, expiresAt, requestId, () -> {}, command);
    }

    private MatchMutation.Response forRequestWithAccess(MatchMutation.Request request, LocalDateTime expiresAt,
                                                       UUID requestId, Runnable checkAccess,
                                                       Supplier<MatchMutation.Response> command) {
        UUID actor = request.actorUserId();
        pairs.findByIdAndUserId(requestId, actor).ifPresent(pair -> lockUsers(actor, other(pair, actor)));
        return mutation.executeUnversioned(request, expiresAt, checkAccess, command);
    }

    private void lockUsers(UUID actor, UUID target) {
        if (!actor.equals(target)) {
            // Lock both users in pair order before idempotency takes its actor lock.
            var ids = FriendPairRepository.normalize(actor, target);
            users.findByIdForUpdate(ids[0]);
            users.findByIdForUpdate(ids[1]);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MatchMutation.Response send(MatchMutation.Request request, LocalDateTime expiresAt, UUID target,
                                       Function<Request, MatchMutation.Response> serialize) {
        UUID actor = request.actorUserId();
        lockUsers(actor, target);
        return mutation.executeUnversioned(request, expiresAt, () -> {
            requireDistinct(actor, target);
            query.profile(actor, target);
        }, () -> serialize.apply(createPending(actor, target)));
    }

    private Request createPending(UUID actor, UUID target) {
        pairs.findByUsers(actor, target).ifPresent(pair -> {
            throw rejected("PENDING".equals(pair.status()) && !actor.equals(pair.requesterId())
                    ? "REQUEST_ALREADY_RECEIVED" : "RELATIONSHIP_EXISTS");
        });
        var ordered = FriendPairRepository.normalize(actor, target);
        var pair = new FriendPair(UUID.randomUUID(), ordered[0], ordered[1], actor, "PENDING", null, now());
        pairs.save(pair);
        return response(pair);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public MatchMutation.Response accept(MatchMutation.Request request, LocalDateTime expiresAt, UUID requestId,
                                         Function<Request, MatchMutation.Response> serialize) {
        UUID actor = request.actorUserId();
        return forRequestWithAccess(request, expiresAt, requestId,
                () -> requireVisibleRequestRecipient(actor, requestId),
                () -> serialize.apply(acceptPending(actor, requestId)));
    }

    private void requireVisibleRequestRecipient(UUID actor, UUID requestId) {
        var pair = pairs.findById(requestId).orElseThrow(ResourceNotFound::new);
        if (!actor.equals(other(pair, pair.requesterId()))) {
            throw rejected("FORBIDDEN");
        }
        query.profile(actor, pair.requesterId());
    }

    private Request acceptPending(UUID actor, UUID requestId) {
        if (pairs.accept(requestId, actor, now()) != 1) {
            throw rejected("INVALID_STATE");
        }
        return response(pairs.findByIdAndUserId(requestId, actor).orElseThrow(ResourceNotFound::new));
    }

    public void cancel(UUID actor, UUID requestId) {
        var pair = pairs.findByIdAndUserId(requestId, actor).orElseThrow(ResourceNotFound::new);
        if (!"PENDING".equals(pair.status())) {
            throw rejected("INVALID_STATE");
        }
        pairs.deleteByIdAndUserId(requestId, actor);
    }

    public void remove(UUID actor, UUID target) {
        requireDistinct(actor, target);
        pairs.findByUsers(actor, target).filter(pair -> "ACCEPTED".equals(pair.status()))
                .ifPresent(pair -> pairs.deleteByIdAndUserId(pair.id(), actor));
    }

    public void block(UUID actor, UUID target) {
        requireDistinct(actor, target);
        users.findById(target).orElseThrow(ResourceNotFound::new);
        if (!blocks.exists(actor, target)) {
            blocks.save(new UserBlock(actor, target, now()));
        }
        pairs.deleteByUsers(actor, target);
    }

    public void unblock(UUID actor, UUID target) {
        requireDistinct(actor, target);
        blocks.delete(actor, target);
    }

    private static void requireDistinct(UUID actor, UUID target) {
        if (actor.equals(target)) {
            throw rejected("SELF_RELATION_NOT_ALLOWED");
        }
    }

    private static UUID other(FriendPair pair, UUID actor) {
        return actor.equals(pair.userLowId()) ? pair.userHighId() : pair.userLowId();
    }

    private static Request response(FriendPair pair) {
        return new Request(pair.id(), pair.requesterId(), other(pair, pair.requesterId()),
                pair.status(), fromUtc(pair.createdAt()));
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static Rejected rejected(String code) {
        return new Rejected(code);
    }

    public static final class Rejected extends RuntimeException {
        private Rejected(String code) {
            super(code);
        }
    }
}
