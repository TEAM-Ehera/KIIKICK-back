package com.teamehera.kiikick.venue.repository;

import com.teamehera.kiikick.venue.domain.Venue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class VenueRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<Venue> rowMapper = (rs, rowNum) -> new Venue(
            bytesToUuid(rs.getBytes("id")),
            bytesToUuid(rs.getBytes("owner_id")),
            rs.getString("name"),
            rs.getString("address"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class)
    );

    public VenueRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Venue venue) {
        jdbcTemplate.update(
                """
                INSERT INTO venues (id, owner_id, name, address, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                uuidToBytes(venue.id()), uuidToBytes(venue.ownerId()), venue.name(),
                venue.address(), venue.createdAt(), venue.updatedAt()
        );
    }

    public Optional<Venue> findByIdAndOwnerId(UUID id, UUID ownerId) {
        return jdbcTemplate.query(
                "SELECT * FROM venues WHERE id = ? AND owner_id = ?",
                rowMapper, uuidToBytes(id), uuidToBytes(ownerId)
        ).stream().findFirst();
    }

    public List<Venue> findPageByOwnerId(UUID ownerId, String query, int limit) {
        return findPageByOwnerId(ownerId, query, null, null, limit);
    }

    public List<Venue> findPageByOwnerId(UUID ownerId, String query, LocalDateTime before, UUID beforeId, int limit) {
        var sql = new StringBuilder("SELECT * FROM venues WHERE owner_id = ?");
        var args = new java.util.ArrayList<Object>();
        args.add(uuidToBytes(ownerId));
        if (query != null && !query.isEmpty()) {
            sql.append(" AND name LIKE ? ESCAPE '!'");
            args.add("%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (before != null) {
            sql.append(" AND (updated_at < ? OR (updated_at = ? AND id < ?))");
            args.add(before);
            args.add(before);
            args.add(uuidToBytes(beforeId));
        }
        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), rowMapper, args.toArray());
    }

    public int update(UUID id, UUID ownerId, String name, String address) {
        return jdbcTemplate.update(
                """
                UPDATE venues SET name = ?, address = ?, updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND owner_id = ?
                """,
                name, address, uuidToBytes(id), uuidToBytes(ownerId)
        );
    }

    public int deleteByIdAndOwnerId(UUID id, UUID ownerId) {
        return jdbcTemplate.update(
                "DELETE FROM venues WHERE id = ? AND owner_id = ?",
                uuidToBytes(id), uuidToBytes(ownerId)
        );
    }
}
