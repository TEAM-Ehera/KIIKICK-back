package com.teamehera.kiikick.auth.service;
import com.teamehera.kiikick.testsupport.*;
import com.teamehera.kiikick.auth.repository.*;
import com.teamehera.kiikick.auth.security.RefreshTokenIssuer;
import com.teamehera.kiikick.global.idempotency.repository.IdempotencyRecordRepository;
import com.teamehera.kiikick.match.repository.MatchRepository;
import com.teamehera.kiikick.match.service.MatchMutation;
import com.teamehera.kiikick.user.repository.*;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.*;
@JdbcTest
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({LogoutService.class,MatchMutation.class,MatchRepository.class,IdempotencyRecordRepository.class,
    RefreshService.class,RefreshTransactionService.class,RefreshTokenIssuer.class,AuthSessionRepository.class,
    RefreshTokenHistoryRepository.class,UserRepository.class,UserConsentRepository.class,RefreshRotationContract.Beans.class})
class LogoutServiceTest extends LogoutContract { }
