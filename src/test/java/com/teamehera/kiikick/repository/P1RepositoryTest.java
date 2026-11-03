package com.teamehera.kiikick.repository;

import com.teamehera.kiikick.match.repository.QuarterPlayerStatsRepository;
import com.teamehera.kiikick.match.repository.QuarterPositionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@JdbcTest
@Import({QuarterPlayerStatsRepository.class, QuarterPositionRepository.class})
class P1RepositoryTest {

    @Autowired QuarterPlayerStatsRepository statsRepository;
    @Autowired QuarterPositionRepository positionRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void 쿼터_통계와_포지션을_upsert하고_집계한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 4, 0);
        UUID userId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID quarterId = UUID.randomUUID();
        insertParents(userId, matchId, quarterId, now);

        statsRepository.upsert(quarterId, 1, 2, now);
        statsRepository.upsert(quarterId, 3, 4, now.plusSeconds(1));
        positionRepository.upsert(quarterId, "FW", now);
        positionRepository.upsert(quarterId, "MF", now.plusSeconds(1));

        assertAll(
                () -> assertEquals(3, statsRepository.findByQuarterId(quarterId).orElseThrow().goals()),
                () -> assertEquals(4, statsRepository.sumAssistsByMatchId(matchId)),
                () -> assertEquals("MF", positionRepository.findByQuarterId(quarterId).orElseThrow().positionCode()),
                () -> assertEquals(1, positionRepository.countByOwnerId(userId).get("MF"))
        );
    }

    private void insertParents(UUID userId, UUID matchId, UUID quarterId, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO users (id, created_at, updated_at) VALUES (?, ?, ?)",
                uuidToBytes(userId), now, now
        );
        jdbcTemplate.update(
                """
                INSERT INTO matches (id, owner_id, status, version, created_at, updated_at)
                VALUES (?, ?, 'COMPLETED', 1, ?, ?)
                """,
                uuidToBytes(matchId), uuidToBytes(userId), now, now
        );
        jdbcTemplate.update(
                """
                INSERT INTO quarters (
                    id, match_id, number, duration_seconds, status, created_at, updated_at
                ) VALUES (?, ?, 1, 1200, 'COMPLETED', ?, ?)
                """,
                uuidToBytes(quarterId), uuidToBytes(matchId), now, now
        );
    }
}
