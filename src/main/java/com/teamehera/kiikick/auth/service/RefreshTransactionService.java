package com.teamehera.kiikick.auth.service;

import com.teamehera.kiikick.auth.domain.AuthSession;
import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import com.teamehera.kiikick.auth.repository.RefreshTokenHistoryRepository;
import com.teamehera.kiikick.auth.security.AccessTokenService;
import com.teamehera.kiikick.auth.security.RefreshTokenIssuer;
import com.teamehera.kiikick.user.repository.UserRepository;
import com.teamehera.kiikick.user.repository.UserConsentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.ConcurrencyFailureException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Service
public class RefreshTransactionService {
    private final AuthSessionRepository sessions;
    private final RefreshTokenHistoryRepository history;
    private final RefreshTokenIssuer refreshTokens;
    private final AccessTokenService accessTokens;
    private final UserRepository users;
    private final UserConsentRepository consents;
    private final Clock clock;

    public RefreshTransactionService(AuthSessionRepository sessions, RefreshTokenHistoryRepository history,
            RefreshTokenIssuer refreshTokens, AccessTokenService accessTokens, UserRepository users,
            UserConsentRepository consents, Clock clock) {
        this.sessions=sessions; this.history=history; this.refreshTokens=refreshTokens;
        this.accessTokens=accessTokens; this.users=users; this.consents=consents; this.clock=clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Attempt refresh(String hash) {
        var id=sessions.findSessionIdForRefresh(hash).orElse(null);
        if (id==null) return new Attempt(null,false);
        // 같은 세션의 갱신을 직렬화한 후 현재 해시를 다시 확인한다.
        AuthSession session=sessions.findByIdForUpdate(id).orElse(null);
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDateTime utc=LocalDateTime.ofInstant(now,ZoneOffset.UTC);
        if (session==null || session.revokedAt()!=null || !session.expiresAt().toInstant(ZoneOffset.UTC).isAfter(now))
            return new Attempt(null,false);
        if (!session.refreshTokenHash().equals(hash)) {
            if (!history.contains(hash,id)) return new Attempt(null,false);
            sessions.revoke(id,session.userId(),utc);
            // 예외 대신 결과를 반환해야 재사용에 따른 세션 폐기가 커밋된다.
            return new Attempt(null,true);
        }
        var user=users.findById(session.userId())
                .filter(found -> found.deletionRequestedAt() == null).orElse(null);
        if (user==null) return new Attempt(null,false);
        var refresh=refreshTokens.issue(session.expiresAt().toInstant(ZoneOffset.UTC));
        history.save(hash,id,session.refreshGeneration(),utc,session.expiresAt());
        if (sessions.rotateCurrentToken(id,session.refreshGeneration(),hash,refresh.tokenHash(),utc)!=1)
            throw new ConcurrencyFailureException("Refresh rotation conflict");
        var access=accessTokens.issue(user.id(),id);
        boolean consent=consents.findLatest(user.id(),"FRIEND_COMPARISON").map(c -> c.granted()).orElse(false);
        return new Attempt(new LoginResult(id,access.token(),refresh.token(),access.expiresAt(),user,consent),false);
    }

    public record Attempt(LoginResult result, boolean revoked) { }
}
