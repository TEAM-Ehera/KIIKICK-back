package com.teamehera.kiikick.auth.service;

import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import com.teamehera.kiikick.auth.security.AuthenticatedUser;
import com.teamehera.kiikick.global.idempotency.repository.IdempotencyRecordRepository;
import com.teamehera.kiikick.match.service.MatchMutation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class LogoutService {
    private static final String PATH="/api/v1/auth/logout";
    private final MatchMutation mutation;
    private final AuthSessionRepository sessions;
    private final IdempotencyRecordRepository records;
    private final Clock clock;
    private final Duration retention;

    public LogoutService(MatchMutation mutation, AuthSessionRepository sessions,
            IdempotencyRecordRepository records, Clock clock,
            @Value("${kiikick.match.idempotency-retention:P1D}") Duration retention) {
        this.mutation=mutation; this.sessions=sessions; this.records=records;
        this.clock=clock; this.retention=retention;
    }

    public MatchMutation.Response logout(AuthenticatedUser actor, UUID requestedSession, UUID key,
            String ifMatch, byte[] body, String headersJson) {
        UUID target=requestedSession==null ? actor.sessionId() : requestedSession;
        // 같은 {}도 인증 세션이 다르면 다른 동작이므로 대상 ID를 요청 해시에 묶는다.
        byte[] boundBody=ByteBuffer.allocate(16+body.length)
                .putLong(target.getMostSignificantBits()).putLong(target.getLeastSignificantBits()).put(body).array();
        var request=new MatchMutation.Request(actor.userId(),null,"POST",PATH,key,ifMatch,boundBody);
        try {
            // 기존 멱등 트랜잭션: 사용자 잠금 → 대상 세션 잠금 → 권한 → 재생/폐기·성공 저장.
            return mutation.executeUnversioned(request,utcNow().plus(retention),() -> {
                var session=sessions.findByIdForUpdate(target).orElseThrow(VerificationApiException::logoutForbidden);
                if (!session.userId().equals(actor.userId())) throw VerificationApiException.logoutForbidden();
                records.deleteExpiredByScope(actor.userId(),"POST",PATH,key,utcNow());
            },() -> {
                sessions.revoke(target,actor.userId(),utcNow());
                return new MatchMutation.Response(204,headersJson,null);
            });
        } catch (DataAccessException exception) {
            // 프록시가 롤백한 다음 내부 DB 정보를 노출하지 않는 오류로 변환한다.
            throw VerificationApiException.logoutUnavailable();
        }
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC);
    }
}
