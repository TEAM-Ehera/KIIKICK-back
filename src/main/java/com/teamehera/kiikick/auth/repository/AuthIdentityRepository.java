package com.teamehera.kiikick.auth.repository;

import com.teamehera.kiikick.auth.domain.AuthIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class AuthIdentityRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<AuthIdentity> rowMapper = (rs, rowNum) -> new AuthIdentity(
            bytesToUuid(rs.getBytes("id")), bytesToUuid(rs.getBytes("user_id")),
            rs.getString("provider"), rs.getString("subject"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    public AuthIdentityRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(AuthIdentity identity) {
        jdbcTemplate.update(
                """
                INSERT INTO auth_identities (id, user_id, provider, subject, created_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                uuidToBytes(identity.id()), uuidToBytes(identity.userId()),
                identity.provider(), identity.subject(), identity.createdAt()
        );
    }

    public Optional<AuthIdentity> findByProviderAndSubject(String provider, String subject) {
        return jdbcTemplate.query(
                "SELECT * FROM auth_identities WHERE provider = ? AND subject = ?",
                rowMapper, provider, subject
        ).stream().findFirst();
    }
}
