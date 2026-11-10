package com.teamehera.kiikick.friend.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class FriendHeatmapRepository {
    public static final double CELL_SIZE_DEGREES = 0.00005;
    private final JdbcTemplate jdbc;

    public FriendHeatmapRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Cell> cells(UUID workoutId) {
        // Aggregate in the database: friends receive sample density, without timestamps or a raw route.
        return jdbc.query("""
                SELECT FLOOR(latitude * 20000) AS lat_cell,
                       FLOOR(longitude * 20000) AS lon_cell, COUNT(*) AS sample_count
                FROM track_points
                WHERE workout_payload_id = ?
                  AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180
                  AND horizontal_accuracy BETWEEN 0 AND 20 AND speed BETWEEN 0 AND 12
                GROUP BY FLOOR(latitude * 20000), FLOOR(longitude * 20000)
                ORDER BY lat_cell, lon_cell
                """, (rs, row) -> new Cell(Math.min(90, (rs.getLong("lat_cell") + 0.5) * CELL_SIZE_DEGREES),
                Math.min(180, (rs.getLong("lon_cell") + 0.5) * CELL_SIZE_DEGREES), rs.getLong("sample_count")),
                uuidToBytes(workoutId));
    }

    public record Cell(double latitude, double longitude, long sampleCount) {}
}
