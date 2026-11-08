package com.teamehera.kiikick.repository;

import com.teamehera.kiikick.testsupport.LocalMySqlInitializer;
import com.teamehera.kiikick.testsupport.QueryAccessContract;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

@SpringBootTest(properties = "spring.sql.init.mode=never")
@ContextConfiguration(initializers = LocalMySqlInitializer.class)
class QueryAccessMySqlIntegrationTest extends QueryAccessContract {
    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.junit.jupiter.api.Test
    @org.springframework.transaction.annotation.Transactional
    void createdTimeIndexCanServeListWithoutSortingOwnerRows() {
        var owner = java.util.UUID.randomUUID();
        byte[] ownerBytes = com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes(owner);
        jdbc.update("INSERT INTO users(id, nickname, created_at, updated_at) VALUES (?, 'perf', NOW(6), NOW(6))", ownerBytes);
        jdbc.update("""
                INSERT INTO matches(id, owner_id, status, version, created_at, updated_at)
                WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000)
                SELECT UUID_TO_BIN(UUID()), ?, 'COMPLETED', 1,
                       TIMESTAMPADD(SECOND, n, '2026-01-01 00:00:00'), '2026-01-01 00:00:00' FROM seq
                """, ownerBytes);
        String tail = " WHERE owner_id = ? AND status = 'COMPLETED' ORDER BY created_at DESC, id DESC LIMIT 21";
        String before = jdbc.queryForObject("EXPLAIN ANALYZE SELECT * FROM matches FORCE INDEX (idx_match_owner_status)" + tail,
                String.class, ownerBytes);
        String after = jdbc.queryForObject("EXPLAIN ANALYZE SELECT * FROM matches FORCE INDEX (idx_match_owner_status_created)" + tail, String.class, ownerBytes);
        org.assertj.core.api.Assertions.assertThat(after).contains("idx_match_owner_status_created").doesNotContain("Sort:");
        System.out.println("MATCH LIST BEFORE INDEX\n" + before + "\nMATCH LIST AFTER INDEX\n" + after);
    }
}
