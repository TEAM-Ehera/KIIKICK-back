-- Requires V001__match_quarter_schema.sql.
-- Adds email/password authentication storage without changing existing tables.

CREATE TABLE `email_credentials` (
    `user_id` BINARY(16) NOT NULL,
    `email` VARCHAR(320) NOT NULL,
    `normalized_email` VARCHAR(320) NOT NULL,
    `password_hash` VARCHAR(100) NOT NULL,
    `email_verified_at` DATETIME(6) NOT NULL,
    `password_changed_at` DATETIME(6) NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`user_id`),
    UNIQUE KEY `uk_email_credentials_normalized` (`normalized_email`),
    CONSTRAINT `fk_email_credentials_user`
        FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `email_verification_flows` (
    `id` BINARY(16) NOT NULL,
    `normalized_email` VARCHAR(320) NOT NULL,
    `purpose` VARCHAR(32) NOT NULL,
    `code_hash` CHAR(64) NULL,
    `failed_attempts` INT NOT NULL,
    `code_expires_at` DATETIME(6) NOT NULL,
    `last_sent_at` DATETIME(6) NOT NULL,
    `verified_at` DATETIME(6) NULL,
    `invalidated_at` DATETIME(6) NULL,
    `action_token_hash` CHAR(64) NULL,
    `action_token_expires_at` DATETIME(6) NULL,
    `action_token_consumed_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_email_flow_action_token` (`action_token_hash`),
    KEY `idx_email_flow_latest` (`normalized_email`, `purpose`, `created_at`),
    KEY `idx_email_flow_code_expiry` (`code_expires_at`),
    KEY `idx_email_flow_action_expiry` (`action_token_expires_at`),
    CONSTRAINT `ck_email_flow_failed_attempts`
        CHECK (`failed_attempts` BETWEEN 0 AND 5),
    CONSTRAINT `ck_email_flow_action_token_state` CHECK (
        (`action_token_hash` IS NULL AND `action_token_expires_at` IS NULL)
        OR (`action_token_hash` IS NOT NULL AND `action_token_expires_at` IS NOT NULL
            AND `verified_at` IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `auth_rate_limit_keys` (
    `rate_action` VARCHAR(64) NOT NULL,
    `limit_type` VARCHAR(16) NOT NULL,
    `key_value` VARCHAR(320) NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`rate_action`, `limit_type`, `key_value`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `auth_rate_limit_events` (
    `id` BINARY(16) NOT NULL,
    `rate_action` VARCHAR(64) NOT NULL,
    `limit_type` VARCHAR(16) NOT NULL,
    `key_value` VARCHAR(320) NOT NULL,
    `occurred_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_auth_rate_limit_window`
        (`rate_action`, `limit_type`, `key_value`, `occurred_at`),
    CONSTRAINT `fk_auth_rate_limit_events_key`
        FOREIGN KEY (`rate_action`, `limit_type`, `key_value`)
        REFERENCES `auth_rate_limit_keys` (`rate_action`, `limit_type`, `key_value`)
        ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
