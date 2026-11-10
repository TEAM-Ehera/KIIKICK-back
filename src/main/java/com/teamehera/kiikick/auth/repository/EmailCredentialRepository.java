package com.teamehera.kiikick.auth.repository;

import com.teamehera.kiikick.auth.domain.EmailCredential;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.bytesToUuid;
import static com.teamehera.kiikick.global.jdbc.JdbcValueConverter.uuidToBytes;

@Repository
public class EmailCredentialRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<EmailCredential> rowMapper = (rs, rowNum) -> new EmailCredential(
            bytesToUuid(rs.getBytes("user_id")),
            rs.getString("email"),
            rs.getString("normalized_email"),
            rs.getString("password_hash"),
            rs.getTimestamp("email_verified_at").toLocalDateTime(),
            rs.getTimestamp("password_changed_at").toLocalDateTime(),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );

    public EmailCredentialRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(EmailCredential credential) {
        jdbcTemplate.update(
                """
                INSERT INTO email_credentials (
                    user_id, email, normalized_email, password_hash,
                    email_verified_at, password_changed_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                uuidToBytes(credential.userId()), credential.email(),
                credential.normalizedEmail(), credential.passwordHash(),
                credential.emailVerifiedAt(), credential.passwordChangedAt(),
                credential.createdAt(), credential.updatedAt()
        );
    }

    public Optional<EmailCredential> findByNormalizedEmail(String normalizedEmail) {
        return jdbcTemplate.query(
                """
                SELECT user_id, email, normalized_email, password_hash,
                       email_verified_at, password_changed_at, created_at, updated_at
                FROM email_credentials
                WHERE normalized_email = ?
                """,
                rowMapper, normalizedEmail
        ).stream().findFirst();
    }

    public Optional<EmailCredential> findByUserId(UUID userId) {
        return jdbcTemplate.query("SELECT * FROM email_credentials WHERE user_id = ?",
                rowMapper, uuidToBytes(userId)).stream().findFirst();
    }

    public int deleteByUserId(UUID userId) {
        return jdbcTemplate.update("DELETE FROM email_credentials WHERE user_id = ?", uuidToBytes(userId));
    }

    public int updatePassword(UUID userId, String passwordHash, LocalDateTime changedAt) {
        return jdbcTemplate.update(
                """
                UPDATE email_credentials
                SET password_hash = ?, password_changed_at = ?, updated_at = ?
                WHERE user_id = ?
                """,
                passwordHash, changedAt, changedAt, uuidToBytes(userId)
        );
    }
}
