package com.teamehera.kiikick.repository;

import com.teamehera.kiikick.match.repository.QuarterPlayerStatsRepository;
import com.teamehera.kiikick.match.repository.QuarterPositionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void 완료_경기의_완료_쿼터에_등록된_포지션을_집계한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 4, 0);
        UUID userId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID quarterId = UUID.randomUUID();
        insertUser(userId, now);
        insertMatch(matchId, userId, "COMPLETED", now);
        insertQuarter(quarterId, matchId, 1, "COMPLETED", now);
        positionRepository.upsert(quarterId, "FW", now);

        assertEquals(1, positionRepository.countByOwnerId(userId).get("FW"));
    }

    @Test
    void 포지션이_없는_완료_쿼터를_null_버킷에_집계한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 4, 0);
        UUID userId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID quarterId = UUID.randomUUID();
        insertUser(userId, now);
        insertMatch(matchId, userId, "COMPLETED", now);
        insertQuarter(quarterId, matchId, 1, "COMPLETED", now);

        Map<String, Integer> counts = positionRepository.countByOwnerId(userId);

        assertAll(
                () -> assertTrue(counts.containsKey(null)),
                () -> assertEquals(1, counts.get(null))
        );
    }

    @Test
    void 진행_중_경기의_완료_쿼터는_집계에서_제외한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 4, 0);
        UUID userId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID quarterId = UUID.randomUUID();
        insertUser(userId, now);
        insertMatch(matchId, userId, "IN_PROGRESS", now);
        insertQuarter(quarterId, matchId, 1, "COMPLETED", now);
        positionRepository.upsert(quarterId, "MF", now);

        assertTrue(positionRepository.countByOwnerId(userId).isEmpty());
    }

    @Test
    void 완료_경기의_미완료_쿼터는_집계에서_제외한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 4, 0);
        UUID userId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID quarterId = UUID.randomUUID();
        insertUser(userId, now);
        insertMatch(matchId, userId, "COMPLETED", now);
        insertQuarter(quarterId, matchId, 1, "IN_PROGRESS", now);
        positionRepository.upsert(quarterId, "DF", now);

        assertTrue(positionRepository.countByOwnerId(userId).isEmpty());
    }

    @Test
    void 대상_완료_쿼터가_없으면_빈_맵을_반환한다() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 3, 4, 0);
        UUID userId = UUID.randomUUID();
        insertUser(userId, now);

        assertTrue(positionRepository.countByOwnerId(userId).isEmpty());
    }

    private void insertParents(UUID userId, UUID matchId, UUID quarterId, LocalDateTime now) {
        insertUser(userId, now);
        insertMatch(matchId, userId, "COMPLETED", now);
        insertQuarter(quarterId, matchId, 1, "COMPLETED", now);
    }

    private void insertUser(UUID userId, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO users (id, created_at, updated_at) VALUES (?, ?, ?)",
                uuidToBytes(userId), now, now
        );
    }

    private void insertMatch(UUID matchId, UUID userId, String status, LocalDateTime now) {
        jdbcTemplate.update(
                """
                INSERT INTO matches (id, owner_id, status, version, created_at, updated_at)
                VALUES (?, ?, ?, 1, ?, ?)
                """,
                uuidToBytes(matchId), uuidToBytes(userId), status, now, now
        );
    }

    private void insertQuarter(
            UUID quarterId,
            UUID matchId,
            int number,
            String status,
            LocalDateTime now
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO quarters (
                    id, match_id, number, duration_seconds, status, created_at, updated_at
                ) VALUES (?, ?, ?, 1200, ?, ?, ?)
                """,
                uuidToBytes(quarterId), uuidToBytes(matchId), number, status, now, now
        );
    }
}
