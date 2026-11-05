package com.teamehera.kiikick.auth.service;
import com.teamehera.kiikick.testsupport.RefreshRotationContract;
import com.teamehera.kiikick.auth.repository.*;
import com.teamehera.kiikick.auth.security.RefreshTokenIssuer;
import com.teamehera.kiikick.user.repository.*;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.*;
@JdbcTest
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({RefreshService.class,RefreshTransactionService.class,AuthSessionRepository.class,
    RefreshTokenHistoryRepository.class,RefreshTokenIssuer.class,UserRepository.class,UserConsentRepository.class,
    RefreshRotationContract.Beans.class})
class RefreshServiceTest extends RefreshRotationContract { }
