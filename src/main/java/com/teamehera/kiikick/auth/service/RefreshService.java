package com.teamehera.kiikick.auth.service;

import com.teamehera.kiikick.auth.security.RefreshTokenIssuer;
import org.springframework.dao.DataAccessException;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.stereotype.Service;

@Service
public class RefreshService {
    private final RefreshTransactionService transactions;
    private final RefreshTokenIssuer tokens;
    public RefreshService(RefreshTransactionService transactions, RefreshTokenIssuer tokens) {
        this.transactions=transactions; this.tokens=tokens;
    }

    public LoginResult refresh(String token) {
        if (token==null || !token.matches("[A-Za-z0-9_-]{43}"))
            throw VerificationApiException.invalidRefreshToken(false);
        RefreshTransactionService.Attempt attempt;
        try { attempt=transactions.refresh(tokens.sha256(token)); }
        catch (DataAccessException | JwtEncodingException exception) {
            throw VerificationApiException.refreshUnavailable();
        }
        // 트랜잭션 프록시가 커밋한 뒤 HTTP 오류로 변환한다.
        if (attempt.result()==null) throw VerificationApiException.invalidRefreshToken(attempt.revoked());
        return attempt.result();
    }
}
