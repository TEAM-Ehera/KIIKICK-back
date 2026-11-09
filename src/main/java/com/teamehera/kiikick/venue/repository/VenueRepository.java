package com.teamehera.kiikick.venue.repository;

import com.teamehera.kiikick.venue.domain.Venue;
import com.teamehera.kiikick.venue.domain.FieldCorner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
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
            corner(rs, "corner_a"),
            corner(rs, "corner_b"),
            corner(rs, "corner_c"),
            corner(rs, "corner_d"),
            corner(rs, "raw_corner_a"),
            corner(rs, "raw_corner_b"),
            corner(rs, "raw_corner_c"),
            corner(rs, "raw_corner_d"),
            rs.getObject("created_at", LocalDateTime.class),
            rs.getObject("updated_at", LocalDateTime.class)
    );

    public VenueRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(Venue venue) {
        jdbcTemplate.update(
                """
                INSERT INTO venues (id, owner_id, name, address,
                    corner_a_latitude, corner_a_longitude, corner_b_latitude, corner_b_longitude,
                    corner_c_latitude, corner_c_longitude, corner_d_latitude, corner_d_longitude,
                    raw_corner_a_latitude, raw_corner_a_longitude, raw_corner_b_latitude, raw_corner_b_longitude,
                    raw_corner_c_latitude, raw_corner_c_longitude, raw_corner_d_latitude, raw_corner_d_longitude,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                uuidToBytes(venue.id()), uuidToBytes(venue.ownerId()), venue.name(),
                venue.address(),
                latitude(venue.cornerA()), longitude(venue.cornerA()),
                latitude(venue.cornerB()), longitude(venue.cornerB()),
                latitude(venue.cornerC()), longitude(venue.cornerC()),
                latitude(venue.cornerD()), longitude(venue.cornerD()),
                latitude(venue.rawCornerA()), longitude(venue.rawCornerA()),
                latitude(venue.rawCornerB()), longitude(venue.rawCornerB()),
                latitude(venue.rawCornerC()), longitude(venue.rawCornerC()),
                latitude(venue.rawCornerD()), longitude(venue.rawCornerD()),
                venue.createdAt(), venue.updatedAt()
        );
    }

    public Optional<Venue> findByIdAndOwnerId(UUID id, UUID ownerId) {
        return jdbcTemplate.query(
                "SELECT * FROM venues WHERE id = ? AND owner_id = ?",
                rowMapper, uuidToBytes(id), uuidToBytes(ownerId)
        ).stream().findFirst();
    }

    public List<Venue> findPageByOwnerId(UUID ownerId, String query, int limit) {
        String pattern = query == null ? "%" : "%" + query + "%";
        return jdbcTemplate.query(
                """
                SELECT * FROM venues
                WHERE owner_id = ? AND name LIKE ?
                ORDER BY updated_at DESC, id DESC
                LIMIT ?
                """,
                rowMapper, uuidToBytes(ownerId), pattern, limit
        );
    }

    public List<Venue> findPageByOwnerId(UUID ownerId, String query, LocalDateTime before, UUID beforeId, int limit) {
        String pattern = query == null ? "%" : "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        if (before == null) {
            return jdbcTemplate.query(
                    """
                    SELECT * FROM venues
                    WHERE owner_id = ? AND name LIKE ? ESCAPE '!'
                    ORDER BY updated_at DESC, id DESC LIMIT ?
                    """, rowMapper, uuidToBytes(ownerId), pattern, limit);
        }
        return jdbcTemplate.query(
                """
                SELECT * FROM venues
                WHERE owner_id = ? AND name LIKE ? ESCAPE '!'
                  AND (updated_at < ? OR (updated_at = ? AND id < ?))
                ORDER BY updated_at DESC, id DESC LIMIT ?
                """, rowMapper, uuidToBytes(ownerId), pattern, before, before, uuidToBytes(beforeId), limit);
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

    public int update(Venue venue) {
        return jdbcTemplate.update(
                """
                UPDATE venues SET name = ?, address = ?,
                    corner_a_latitude = ?, corner_a_longitude = ?, corner_b_latitude = ?, corner_b_longitude = ?,
                    corner_c_latitude = ?, corner_c_longitude = ?, corner_d_latitude = ?, corner_d_longitude = ?,
                    raw_corner_a_latitude = ?, raw_corner_a_longitude = ?, raw_corner_b_latitude = ?, raw_corner_b_longitude = ?,
                    raw_corner_c_latitude = ?, raw_corner_c_longitude = ?, raw_corner_d_latitude = ?, raw_corner_d_longitude = ?,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND owner_id = ?
                """,
                venue.name(), venue.address(),
                latitude(venue.cornerA()), longitude(venue.cornerA()),
                latitude(venue.cornerB()), longitude(venue.cornerB()),
                latitude(venue.cornerC()), longitude(venue.cornerC()),
                latitude(venue.cornerD()), longitude(venue.cornerD()),
                latitude(venue.rawCornerA()), longitude(venue.rawCornerA()),
                latitude(venue.rawCornerB()), longitude(venue.rawCornerB()),
                latitude(venue.rawCornerC()), longitude(venue.rawCornerC()),
                latitude(venue.rawCornerD()), longitude(venue.rawCornerD()),
                uuidToBytes(venue.id()), uuidToBytes(venue.ownerId()));
    }

    private static FieldCorner corner(ResultSet rs, String prefix) throws SQLException {
        Double latitude = rs.getObject(prefix + "_latitude", Double.class);
        Double longitude = rs.getObject(prefix + "_longitude", Double.class);
        return latitude == null ? null : new FieldCorner(latitude, longitude);
    }

    private static Double latitude(FieldCorner corner) {
        return corner == null ? null : corner.latitude();
    }

    private static Double longitude(FieldCorner corner) {
        return corner == null ? null : corner.longitude();
    }

    public int deleteByIdAndOwnerId(UUID id, UUID ownerId) {
        return jdbcTemplate.update(
                "DELETE FROM venues WHERE id = ? AND owner_id = ?",
                uuidToBytes(id), uuidToBytes(ownerId)
        );
    }
}
