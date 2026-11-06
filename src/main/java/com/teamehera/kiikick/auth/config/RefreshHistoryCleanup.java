package com.teamehera.kiikick.auth.config;

import com.teamehera.kiikick.auth.repository.RefreshTokenHistoryRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Configuration
@EnableScheduling
public class RefreshHistoryCleanup {
    private static final Logger log=LoggerFactory.getLogger(RefreshHistoryCleanup.class);
    private final RefreshTokenHistoryRepository history;
    private final Clock clock;
    public RefreshHistoryCleanup(RefreshTokenHistoryRepository history, Clock clock) {
        this.history=history; this.clock=clock;
    }

    @Scheduled(initialDelay=3600000, fixedDelay=3600000)
    public void deleteExpired() {
        // 절대 만료 이전의 이력은 재사용 탐지를 위해 보존한다.
        try {
            history.deleteExpired(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
        } catch (DataAccessException exception) {
            // 정리 실패는 요청 경로에 전파하지 않고 다음 실행에서 다시 시도한다.
            log.warn("Expired refresh history cleanup failed; retrying on the next scheduled run");
        }
    }
}
