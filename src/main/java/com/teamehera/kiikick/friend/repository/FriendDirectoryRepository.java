package com.teamehera.kiikick.friend.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.nullableDateTime;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class FriendDirectoryRepository {
    private static final String PROJECTION = """
            SELECT u.id AS user_id, u.nickname, u.avatar_url, p.id AS pair_id,
                   p.requester_id, p.status, p.created_at, p.accepted_at
            """;
    private static final String RELATIONSHIP_JOIN = """
            LEFT JOIN friend_pairs p
              ON (p.user_low_id = :actor AND p.user_high_id = u.id)
              OR (p.user_high_id = :actor AND p.user_low_id = u.id)
            """;
    private static final String PROFILE_FILTER = """
            WHERE u.deleted_at IS NULL AND u.onboarding_completed_at IS NOT NULL AND u.nickname IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM user_blocks b
                  WHERE (b.blocker_id = :actor AND b.blocked_id = u.id)
                     OR (b.blocked_id = :actor AND b.blocker_id = u.id)
              )
            """;
    private static final String VISIBLE_PROFILES = PROJECTION + "FROM users u " + RELATIONSHIP_JOIN + PROFILE_FILTER;

    private final NamedParameterJdbcTemplate jdbc;

    public FriendDirectoryRepository(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    public Optional<Row> profile(UUID actor, UUID target) {
        return query(actor, " AND u.id = :target",
                new MapSqlParameterSource("target", uuidToBytes(target))).stream().findFirst();
    }

    public List<Row> search(UUID actor, String search, String afterName, UUID afterId, int limit) {
        String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var parameters = new MapSqlParameterSource("pattern", pattern).addValue("limit", limit);
        String filter = " AND u.id <> :actor AND u.nickname LIKE :pattern ESCAPE '!'";
        if (afterId != null) {
            filter += " AND (u.nickname > :name OR (u.nickname = :name AND u.id > :id))";
            parameters.addValue("name", afterName).addValue("id", uuidToBytes(afterId));
        }
        // 관계 정보는 검색·공개 범위·커서로 확정한 한 페이지에 대해서만 결합한다.
        String candidates = "SELECT u.id, u.nickname, u.avatar_url FROM users u " + PROFILE_FILTER + filter
                + " ORDER BY u.nickname, u.id LIMIT :limit";
        String sql = PROJECTION + "FROM (" + candidates + ") u " + RELATIONSHIP_JOIN
                + " ORDER BY u.nickname, u.id";
        return rows(sql, parameters.addValue("actor", uuidToBytes(actor)));
    }

    public List<Row> friends(UUID actor, LocalDateTime before, UUID beforeId, int limit) {
        return relationshipPage(actor, " AND p.status = 'ACCEPTED'", "accepted_at", before, beforeId, limit);
    }

    public List<Row> requests(UUID actor, boolean sent, LocalDateTime before, UUID beforeId, int limit) {
        String direction = sent ? " AND p.requester_id = :actor" : " AND p.requester_id <> :actor";
        return relationshipPage(actor, " AND p.status = 'PENDING'" + direction, "created_at", before, beforeId, limit);
    }

    private List<Row> relationshipPage(UUID actor, String filter, String timestamp,
                                       LocalDateTime before, UUID beforeId, int limit) {
        var parameters = new MapSqlParameterSource("limit", limit).addValue("actor", uuidToBytes(actor));
        if (beforeId != null) {
            filter += " AND (p." + timestamp + " < :before OR (p." + timestamp + " = :before AND p.id < :id))";
            parameters.addValue("before", before).addValue("id", uuidToBytes(beforeId));
        }
        // 정규화된 low/high 쌍은 서로 겹치지 않는다. 각 방향에서 최대 한 페이지만 병합한다.
        String low = relationshipBranch("user_low_id", "user_high_id", filter, timestamp);
        String high = relationshipBranch("user_high_id", "user_low_id", filter, timestamp);
        return rows("SELECT * FROM ((" + low + ") UNION ALL (" + high + ")) candidates ORDER BY "
                + timestamp + " DESC, pair_id DESC LIMIT :limit", parameters);
    }

    private static String relationshipBranch(String self, String other, String filter, String timestamp) {
        return """
                SELECT u.id AS user_id, u.nickname, u.avatar_url, p.id AS pair_id,
                       p.requester_id, p.status, p.created_at, p.accepted_at
                FROM friend_pairs p JOIN users u ON u.id = p.%s
                """.formatted(other) + PROFILE_FILTER + " AND p." + self + " = :actor" + filter
                + " ORDER BY p." + timestamp + " DESC, p.id DESC LIMIT :limit";
    }

    private List<Row> query(UUID actor, String tail, MapSqlParameterSource parameters) {
        return rows(VISIBLE_PROFILES + tail, parameters.addValue("actor", uuidToBytes(actor)));
    }

    private List<Row> rows(String sql, MapSqlParameterSource parameters) {
        return jdbc.query(sql, parameters,
                (rs, index) -> new Row(bytesToUuid(rs.getBytes("user_id")), rs.getString("nickname"),
                        rs.getString("avatar_url"), bytesToUuid(rs.getBytes("pair_id")),
                        bytesToUuid(rs.getBytes("requester_id")), rs.getString("status"),
                        nullableDateTime(rs, "created_at"), nullableDateTime(rs, "accepted_at")));
    }

    public record Row(UUID userId, String nickname, String avatarUrl, UUID pairId, UUID requesterId,
                      String status, LocalDateTime createdAt, LocalDateTime acceptedAt) {}
}
