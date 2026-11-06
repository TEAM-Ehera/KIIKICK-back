package com.teamehera.kiikick.auth.service;

import com.teamehera.kiikick.auth.domain.EmailVerificationFlow;
import com.teamehera.kiikick.auth.repository.EmailVerificationFlowRepository;
import com.teamehera.kiikick.auth.security.AuthHashEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class RegistrationTokenService {

    private static final String PURPOSE = "SIGN_UP";

    private final EmailVerificationFlowRepository flowRepository;
    private final AuthHashEncoder hashEncoder;
    private final Clock clock;

    public RegistrationTokenService(
            EmailVerificationFlowRepository flowRepository,
            AuthHashEncoder hashEncoder,
            Clock clock
    ) {
        this.flowRepository = flowRepository;
        this.hashEncoder = hashEncoder;
        this.clock = clock;
    }

    @Transactional
    public RegistrationTokenContext consume(String registrationToken) {
        if (registrationToken == null || registrationToken.isBlank()) {
            throw VerificationApiException.invalidRegistrationToken();
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String tokenHash = hashEncoder.hashActionToken(registrationToken);
        EmailVerificationFlow flow = flowRepository.findActiveByActionToken(
                tokenHash, PURPOSE, now
        ).orElseThrow(VerificationApiException::invalidRegistrationToken);

        // 조건부 소비로 동시에 제출된 회원가입 요청 중 하나만 성공할 수 있다.
        if (flowRepository.consumeActionToken(flow.id(), tokenHash, now) != 1) {
            throw VerificationApiException.invalidRegistrationToken();
        }
        return new RegistrationTokenContext(
                flow.id(), flow.normalizedEmail(), flow.verifiedAt().toInstant(ZoneOffset.UTC)
        );
    }
}
