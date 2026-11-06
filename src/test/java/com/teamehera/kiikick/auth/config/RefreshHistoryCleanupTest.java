package com.teamehera.kiikick.auth.config;

import com.teamehera.kiikick.auth.repository.RefreshTokenHistoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefreshHistoryCleanupTest {
    @Test void 시간대와_무관하게_UTC를_전달하고_대상이_없어도_성공한다() {
        var history=mock(RefreshTokenHistoryRepository.class);
        Instant now=Instant.parse("2026-11-06T00:00:00.123456Z");
        var cleanup=new RefreshHistoryCleanup(history,Clock.fixed(now,ZoneId.of("Asia/Seoul")));
        assertThatCode(cleanup::deleteExpired).doesNotThrowAnyException();
        verify(history).deleteExpired(LocalDateTime.ofInstant(now,ZoneOffset.UTC));
    }
    @Test void 실패를_격리하고_다음실행도_가능하다() {
        var history=mock(RefreshTokenHistoryRepository.class);
        var time=LocalDateTime.of(2026,11,6,0,0);
        when(history.deleteExpired(time)).thenThrow(new DataAccessResourceFailureException("test failure")).thenReturn(0);
        var cleanup=new RefreshHistoryCleanup(history,Clock.fixed(time.toInstant(ZoneOffset.UTC),ZoneOffset.UTC));
        assertThatCode(cleanup::deleteExpired).doesNotThrowAnyException();
        assertThatCode(cleanup::deleteExpired).doesNotThrowAnyException();
        verify(history,times(2)).deleteExpired(time);
    }
    @Test void 승인된_1시간_주기로_실행한다() throws Exception {
        var scheduled=RefreshHistoryCleanup.class.getMethod("deleteExpired").getAnnotation(Scheduled.class);
        assertThat(scheduled.initialDelay()).isEqualTo(3600000);
        assertThat(scheduled.fixedDelay()).isEqualTo(3600000);
    }
}
