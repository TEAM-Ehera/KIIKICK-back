package com.teamehera.kiikick.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class AdminUserRepository {
    private final JdbcTemplate jdbc;

    public AdminUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isActiveAdmin(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM admin_users a
                    JOIN users u ON u.id = a.user_id
                    WHERE a.user_id = ? AND u.deleted_at IS NULL
                )
                """, Boolean.class, uuidToBytes(userId)));
    }
}
