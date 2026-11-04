package com.teamehera.kiikick.match.repository;

import com.teamehera.kiikick.match.domain.Match;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.nullableDateTime;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class MatchRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Match> rowMapper = (rs, rowNum) -> new Match(
            bytesToUuid(rs.getBytes("id")), bytesToUuid(rs.getBytes("owner_id")),
            bytesToUuid(rs.getBytes("venue_id")), rs.getString("title"),
            rs.getString("venue_name_snapshot"), rs.getString("venue_address_snapshot"),
            nullableDateTime(rs, "scheduled_at"), rs.getString("status"),
            nullableDateTime(rs, "started_at"), nullableDateTime(rs, "ended_at"),
            rs.getLong("version"), rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );

    public MatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Match match) {
        jdbcTemplate.update(
                """
                INSERT INTO matches (
                    id, owner_id, venue_id, title, venue_name_snapshot,
                    venue_address_snapshot, scheduled_at, status, started_at,
                    ended_at, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                uuidToBytes(match.id()), uuidToBytes(match.ownerId()),
                uuidToBytes(match.venueId()), match.title(), match.venueNameSnapshot(),
                match.venueAddressSnapshot(), match.scheduledAt(), match.status(),
                match.startedAt(), match.endedAt(), match.version(),
                match.createdAt(), match.updatedAt()
        );
    }

    public Optional<Match> findByIdAndOwnerId(UUID id, UUID ownerId) {
        return queryOne(
                "SELECT * FROM matches WHERE id = ? AND owner_id = ?",
                id, ownerId
        );
    }

    public Optional<Match> findByIdAndOwnerIdForUpdate(UUID id, UUID ownerId) {
        return queryOne(
                "SELECT * FROM matches WHERE id = ? AND owner_id = ? FOR UPDATE",
                id, ownerId
        );
    }

    public List<Match> findPageByOwnerId(UUID ownerId, String status, int limit) {
        return findPageByOwnerId(ownerId, status, null, null, limit);
    }

    public List<Match> findPageByOwnerId(UUID ownerId, String status, LocalDateTime beforeTime,
                                       UUID beforeId, int limit) {
        var sql = new StringBuilder("SELECT * FROM matches WHERE owner_id = ?");
        var args = new ArrayList<Object>();
        args.add(uuidToBytes(ownerId));
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status);
        }
        if (beforeTime != null) {
            sql.append(" AND (created_at < ? OR (created_at = ? AND id < ?))");
            args.add(beforeTime);
            args.add(beforeTime);
            args.add(uuidToBytes(beforeId));
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), rowMapper, args.toArray());
    }

    public int updateSettings(Match match, long expectedVersion) {
        return jdbcTemplate.update(
                """
                UPDATE matches
                SET venue_id = ?, title = ?, venue_name_snapshot = ?,
                    venue_address_snapshot = ?, scheduled_at = ?,
                    version = version + 1, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND owner_id = ? AND version = ?
                  AND status IN ('DRAFT', 'READY')
                """,
                uuidToBytes(match.venueId()), match.title(), match.venueNameSnapshot(),
                match.venueAddressSnapshot(), match.scheduledAt(),
                uuidToBytes(match.id()), uuidToBytes(match.ownerId()), expectedVersion
        );
    }

    public int incrementVersion(UUID id, UUID ownerId, long expectedVersion) {
        return jdbcTemplate.update(
                """
                UPDATE matches SET version = version + 1, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND owner_id = ? AND version = ?
                """,
                uuidToBytes(id), uuidToBytes(ownerId), expectedVersion
        );
    }

    public int updateStatus(
            UUID id, UUID ownerId, long expectedVersion, String currentStatus,
            String nextStatus, java.time.LocalDateTime startedAt,
            java.time.LocalDateTime endedAt
    ) {
        return jdbcTemplate.update(
                """
                UPDATE matches
                SET status = ?, started_at = COALESCE(?, started_at),
                    ended_at = COALESCE(?, ended_at), version = version + 1,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND owner_id = ? AND version = ? AND status = ?
                """,
                nextStatus, startedAt, endedAt, uuidToBytes(id),
                uuidToBytes(ownerId), expectedVersion, currentStatus
        );
    }

    private Optional<Match> queryOne(String sql, UUID id, UUID ownerId) {
        return jdbcTemplate.query(
                sql, rowMapper, uuidToBytes(id), uuidToBytes(ownerId)
        ).stream().findFirst();
    }
}
