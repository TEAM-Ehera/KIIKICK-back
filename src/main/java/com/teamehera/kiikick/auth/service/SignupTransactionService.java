package com.teamehera.kiikick.auth.service;

import com.teamehera.kiikick.auth.domain.AuthSession;
import com.teamehera.kiikick.auth.domain.EmailCredential;
import com.teamehera.kiikick.auth.repository.AuthSessionRepository;
import com.teamehera.kiikick.auth.repository.EmailCredentialRepository;
import com.teamehera.kiikick.auth.security.AccessTokenService;
import com.teamehera.kiikick.auth.security.IssuedAccessToken;
import com.teamehera.kiikick.auth.security.IssuedRefreshToken;
import com.teamehera.kiikick.auth.security.RefreshTokenIssuer;
import com.teamehera.kiikick.user.domain.User;
import com.teamehera.kiikick.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class SignupTransactionService {

    private final RegistrationTokenService registrationTokenService;
    private final UserRepository userRepository;
    private final EmailCredentialRepository credentialRepository;
    private final AuthSessionRepository sessionRepository;
    private final AccessTokenService accessTokenService;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final Clock clock;

    public SignupTransactionService(
            RegistrationTokenService registrationTokenService,
            UserRepository userRepository,
            EmailCredentialRepository credentialRepository,
            AuthSessionRepository sessionRepository,
            AccessTokenService accessTokenService,
            RefreshTokenIssuer refreshTokenIssuer,
            Clock clock
    ) {
        this.registrationTokenService = registrationTokenService;
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.sessionRepository = sessionRepository;
        this.accessTokenService = accessTokenService;
        this.refreshTokenIssuer = refreshTokenIssuer;
        this.clock = clock;
    }

    @Transactional
    public SignupResult create(String registrationToken, String passwordHash) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        RegistrationTokenContext registration = registrationTokenService.consume(
                registrationToken
        );

        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        userRepository.save(userId, null);
        credentialRepository.save(new EmailCredential(
                userId,
                registration.normalizedEmail(),
                registration.normalizedEmail(),
                passwordHash,
                registration.verifiedAt(),
                now,
                now,
                now
        ));

        IssuedRefreshToken refreshToken = refreshTokenIssuer.issue();
        sessionRepository.save(new AuthSession(
                sessionId,
                userId,
                null,
                refreshToken.tokenHash(),
                0,
                LocalDateTime.ofInstant(refreshToken.expiresAt(), ZoneOffset.UTC),
                null,
                now
        ));
        IssuedAccessToken accessToken = accessTokenService.issue(userId, sessionId);
        User user = userRepository.findById(userId).orElseThrow();

        // 토큰 소비부터 사용자·Credential·세션 생성까지 하나라도 실패하면 전부 롤백한다.
        return new SignupResult(
                sessionId,
                accessToken.token(),
                refreshToken.token(),
                accessToken.expiresAt(),
                userId,
                user.createdAt()
        );
    }
}
