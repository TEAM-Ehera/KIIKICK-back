-- KIIKICK full ERD design candidate for MySQL/dbdiagram.
-- NOT the current deployed/PR schema. NOT an ALTER migration.
-- Derived from docs/erd.md plus workout measurement entities from PR #7.
-- UUIDs: BINARY(16). DATETIME(6) values must be UTC at application/session level.
-- All physical string lengths except PR-derived nickname(16) are review placeholders.
-- Names are normalized to snake_case; the PR currently uses camelCase for workout tables/columns.
-- Nullable nickname before onboarding differs from the PR NOT NULL constraint.
-- State transitions, single active quarter, consent and authorization require service transactions.
-- Personal matches: owner_id identifies the sole player. Quarter totals are source records.
-- One workout per quarter is the design assumption; uploads may arrive after quarter completion.
-- Validate workout user_id = quarter.match.owner_id in the service transaction.
-- Full GPS/heart-rate samples are retained; Apple epoch dates must be converted to UTC.
-- docs/functional-spec.md, docs/api-spec.md and docs/erd.md use this personal-quarter model.

CREATE TABLE `users` (
    `id` BINARY(16) NOT NULL,
    `nickname` VARCHAR(16) NULL,
    `avatar_url` TEXT NULL,
    `onboarding_completed_at` DATETIME(6) NULL,
    `deletion_requested_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    `deleted_at` DATETIME(6) NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `auth_identities` (
    `id` BINARY(16) NOT NULL,
    `user_id` BINARY(16) NOT NULL,
    `provider` VARCHAR(64) NOT NULL,
    `subject` VARCHAR(255) NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_identity_provider_subject` (`provider`, `subject`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
    UNIQUE KEY `uk_email_credentials_normalized` (`normalized_email`)
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
    CONSTRAINT `ck_email_flow_failed_attempts`
        CHECK (`failed_attempts` BETWEEN 0 AND 5),
    CONSTRAINT `ck_email_flow_action_token_state` CHECK (
        (`action_token_hash` IS NULL AND `action_token_expires_at` IS NULL)
        OR (`action_token_hash` IS NOT NULL AND `action_token_expires_at` IS NOT NULL
            AND `verified_at` IS NOT NULL)
    ),
    KEY `idx_email_flow_latest` (`normalized_email`, `purpose`, `created_at`),
    KEY `idx_email_flow_code_expiry` (`code_expires_at`),
    KEY `idx_email_flow_action_expiry` (`action_token_expires_at`)
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
        (`rate_action`, `limit_type`, `key_value`, `occurred_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `user_consents` (
    `id` BINARY(16) NOT NULL,
    `user_id` BINARY(16) NOT NULL,
    `purpose` VARCHAR(64) NOT NULL,
    `policy_version` VARCHAR(64) NOT NULL,
    `granted` BOOLEAN NOT NULL,
    `recorded_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_consent_current` (`user_id`, `purpose`, `recorded_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `venues` (
    `id` BINARY(16) NOT NULL,
    `owner_id` BINARY(16) NOT NULL,
    `name` VARCHAR(255) NOT NULL,
    `address` TEXT NULL,
    `corner_a_latitude` DOUBLE NULL,
    `corner_a_longitude` DOUBLE NULL,
    `corner_b_latitude` DOUBLE NULL,
    `corner_b_longitude` DOUBLE NULL,
    `corner_c_latitude` DOUBLE NULL,
    `corner_c_longitude` DOUBLE NULL,
    `corner_d_latitude` DOUBLE NULL,
    `corner_d_longitude` DOUBLE NULL,
    `raw_corner_a_latitude` DOUBLE NULL,
    `raw_corner_a_longitude` DOUBLE NULL,
    `raw_corner_b_latitude` DOUBLE NULL,
    `raw_corner_b_longitude` DOUBLE NULL,
    `raw_corner_c_latitude` DOUBLE NULL,
    `raw_corner_c_longitude` DOUBLE NULL,
    `raw_corner_d_latitude` DOUBLE NULL,
    `raw_corner_d_longitude` DOUBLE NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_venue_owner` (`owner_id`, `updated_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `matches` (
    `id` BINARY(16) NOT NULL,
    `owner_id` BINARY(16) NOT NULL,
    `venue_id` BINARY(16) NULL,
    `title` VARCHAR(255) NULL,
    `venue_name_snapshot` VARCHAR(255) NULL,
    `venue_address_snapshot` TEXT NULL,
    `scheduled_at` DATETIME(6) NULL,
    `status` VARCHAR(32) NOT NULL,
    `started_at` DATETIME(6) NULL,
    `ended_at` DATETIME(6) NULL,
    `version` BIGINT NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_match_owner_status` (`owner_id`, `status`, `scheduled_at`, `id`),
    KEY `idx_match_completed` (`status`, `ended_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `quarters` (
    `id` BINARY(16) NOT NULL,
    `match_id` BINARY(16) NOT NULL,
    `number` INT NOT NULL,
    `duration_seconds` INT NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `started_at` DATETIME(6) NULL,
    `ended_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_quarter_number` (`match_id`, `number`),
    KEY `idx_quarter_status` (`match_id`, `status`, `number`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `quarter_player_stats` (
    `quarter_id` BINARY(16) NOT NULL,
    `goals` INT NOT NULL,
    `assists` INT NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`quarter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `quarter_positions` (
    `quarter_id` BINARY(16) NOT NULL,
    `position_code` VARCHAR(32) NOT NULL,
    `updated_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`quarter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `quarter_summaries` (
    `quarter_id` BINARY(16) NOT NULL,
    `workout_payload_id` BINARY(16) NOT NULL,
    `calculation_job_id` BINARY(16) NOT NULL,
    `calculation_status` VARCHAR(32) NOT NULL,
    `calculation_version` VARCHAR(64) NOT NULL,
    `calculation_parameters` JSON NOT NULL,
    `metric_status` JSON NOT NULL,
    `source_payload_hash` VARCHAR(64) NOT NULL,
    `duration_seconds` DOUBLE NULL,
    `max_speed_mps` DOUBLE NULL,
    `total_distance_m` DOUBLE NULL,
    `total_calories_kcal` DOUBLE NULL,
    `average_speed_mps` DOUBLE NULL,
    `high_intensity_distance_m` DOUBLE NULL,
    `acceleration_count` INT NULL,
    `deceleration_count` INT NULL,
    `direction_change_count` INT NULL,
    `average_heart_rate_bpm` DOUBLE NULL,
    `max_heart_rate_bpm` DOUBLE NULL,
    `trimp_edwards` DOUBLE NULL,
    `hr_max_reference_bpm` DOUBLE NULL,
    `valid_hr_duration_seconds` DOUBLE NULL,
    `valid_gps_duration_seconds` DOUBLE NULL,
    `calculated_at` DATETIME(6) NULL,
    `updated_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`quarter_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `friend_pairs` (
    `id` BINARY(16) NOT NULL,
    `user_low_id` BINARY(16) NOT NULL,
    `user_high_id` BINARY(16) NOT NULL,
    `requester_id` BINARY(16) NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `accepted_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_friend_pair` (`user_low_id`, `user_high_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `user_blocks` (
    `blocker_id` BINARY(16) NOT NULL,
    `blocked_id` BINARY(16) NOT NULL,
    `created_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`blocker_id`, `blocked_id`),
    KEY `idx_block_reverse` (`blocked_id`, `blocker_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `notifications` (
    `id` BINARY(16) NOT NULL,
    `recipient_id` BINARY(16) NOT NULL,
    `type` VARCHAR(32) NOT NULL,
    `title` VARCHAR(255) NOT NULL,
    `body` TEXT NULL,
    `target_type` VARCHAR(32) NOT NULL,
    `target_id` BINARY(16) NOT NULL,
    `dedup_key` VARCHAR(64) NOT NULL,
    `read_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_notification_dedup` (`dedup_key`),
    KEY `idx_notification_list` (`recipient_id`, `created_at`, `id`),
    KEY `idx_notification_unread` (`recipient_id`, `read_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `auth_sessions` (
    `id` BINARY(16) NOT NULL,
    `user_id` BINARY(16) NOT NULL,
    `device_id` BINARY(16) NULL,
    `refresh_token_hash` VARCHAR(64) NOT NULL,
    `refresh_generation` BIGINT NOT NULL,
    `expires_at` DATETIME(6) NOT NULL,
    `revoked_at` DATETIME(6) NULL,
    `created_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- #62: V002 적용 후 실행. 기존 행/만료일 변경 및 토큰 원문 저장 없음.
CREATE TABLE auth_refresh_token_history (
    token_hash VARCHAR(64) NOT NULL,
    session_id BINARY(16) NOT NULL,
    generation BIGINT NOT NULL,
    consumed_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (token_hash),
    CONSTRAINT uq_refresh_history_generation UNIQUE (session_id, generation),
    CONSTRAINT fk_refresh_history_session FOREIGN KEY (session_id) REFERENCES auth_sessions(id),
    CONSTRAINT chk_refresh_history_generation CHECK (generation >= 0),
    CONSTRAINT chk_refresh_history_expiry CHECK (consumed_at < expires_at),
    INDEX idx_refresh_history_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE INDEX idx_auth_sessions_refresh_hash ON auth_sessions(refresh_token_hash);

CREATE TABLE `devices` (
    `id` BINARY(16) NOT NULL,
    `user_id` BINARY(16) NOT NULL,
    `platform` VARCHAR(32) NOT NULL,
    `push_token_ciphertext` TEXT NULL,
    `push_enabled` BOOLEAN NOT NULL,
    `last_seen_at` DATETIME(6) NOT NULL,
    `revoked_at` DATETIME(6) NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `account_deletion_requests` (
    `id` BINARY(16) NOT NULL,
    `user_id` BINARY(16) NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `requested_at` DATETIME(6) NOT NULL,
    `completed_at` DATETIME(6) NULL,
    `failure_code` VARCHAR(64) NULL,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `idempotency_records` (
    `id` BINARY(16) NOT NULL,
    `user_id` BINARY(16) NOT NULL,
    `method` VARCHAR(16) NOT NULL,
    `canonical_path` VARCHAR(255) NOT NULL,
    `idempotency_key` BINARY(16) NOT NULL,
    `request_hash` VARCHAR(64) NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `response_status` INT NULL,
    `response_headers` JSON NULL,
    `response_body` JSON NULL,
    `created_at` DATETIME(6) NOT NULL,
    `expires_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_idempotency_scope` (`user_id`, `method`, `canonical_path`, `idempotency_key`),
    KEY `idx_idempotency_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `sync_operations` (
    `id` BINARY(16) NOT NULL,
    `user_id` BINARY(16) NOT NULL,
    `device_id` BINARY(16) NULL,
    `operation_id` BINARY(16) NOT NULL,
    `requested_match_id` BINARY(16) NOT NULL,
    `match_id` BINARY(16) NULL,
    `expected_version` BIGINT NOT NULL,
    `type` VARCHAR(32) NOT NULL,
    `payload_hash` VARCHAR(64) NOT NULL,
    `status` VARCHAR(32) NOT NULL,
    `result` JSON NOT NULL,
    `processed_at` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sync_operation` (`user_id`, `operation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `workout_payloads` (
    `id` BINARY(16) NOT NULL,
    `quarter_id` BINARY(16) NOT NULL,
    `user_id` BINARY(16) NOT NULL,
    `start_date` DATETIME(6) NOT NULL,
    `end_date` DATETIME(6) NOT NULL,
    `total_calories` DOUBLE NOT NULL,
    `total_distance` DOUBLE NOT NULL,
    `events_json` JSON NULL,
    `field_snapshot` JSON NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_workout_quarter` (`quarter_id`),
    UNIQUE KEY `uk_workout_quarter_id` (`quarter_id`, `id`),
    KEY `idx_workout_user_start` (`user_id`, `start_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `track_points` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workout_payload_id` BINARY(16) NOT NULL,
    `sample_seq` INT NOT NULL,
    `latitude` DOUBLE NOT NULL,
    `longitude` DOUBLE NOT NULL,
    `altitude` DOUBLE NOT NULL,
    `horizontal_accuracy` DOUBLE NOT NULL,
    `speed` DOUBLE NOT NULL,
    `course` DOUBLE NOT NULL,
    `timestamp` DATETIME(6) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_track_payload_seq` (`workout_payload_id`, `sample_seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `heart_rate_samples` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `workout_payload_id` BINARY(16) NOT NULL,
    `sample_seq` INT NOT NULL,
    `timestamp` DATETIME(6) NOT NULL,
    `bpm` DOUBLE NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_heart_payload_seq` (`workout_payload_id`, `sample_seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Add relationships after all tables exist.

ALTER TABLE `auth_identities`
    ADD CONSTRAINT `fk_01_auth_identities`
    FOREIGN KEY (`user_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `email_credentials`
    ADD CONSTRAINT `fk_email_credentials_user`
    FOREIGN KEY (`user_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `auth_rate_limit_events`
    ADD CONSTRAINT `fk_auth_rate_limit_events_key`
    FOREIGN KEY (`rate_action`, `limit_type`, `key_value`)
    REFERENCES `auth_rate_limit_keys` (`rate_action`, `limit_type`, `key_value`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `user_consents`
    ADD CONSTRAINT `fk_02_user_consents`
    FOREIGN KEY (`user_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `venues`
    ADD CONSTRAINT `fk_03_venues`
    FOREIGN KEY (`owner_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `matches`
    ADD CONSTRAINT `fk_04_matches`
    FOREIGN KEY (`owner_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `friend_pairs`
    ADD CONSTRAINT `fk_08_friend_pairs`
    FOREIGN KEY (`user_low_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `friend_pairs`
    ADD CONSTRAINT `fk_09_friend_pairs`
    FOREIGN KEY (`user_high_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `friend_pairs`
    ADD CONSTRAINT `fk_10_friend_pairs`
    FOREIGN KEY (`requester_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `user_blocks`
    ADD CONSTRAINT `fk_11_user_blocks`
    FOREIGN KEY (`blocker_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `user_blocks`
    ADD CONSTRAINT `fk_12_user_blocks`
    FOREIGN KEY (`blocked_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `notifications`
    ADD CONSTRAINT `fk_13_notifications`
    FOREIGN KEY (`recipient_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `auth_sessions`
    ADD CONSTRAINT `fk_14_auth_sessions`
    FOREIGN KEY (`user_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `devices`
    ADD CONSTRAINT `fk_15_devices`
    FOREIGN KEY (`user_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `account_deletion_requests`
    ADD CONSTRAINT `fk_16_account_deletion_requests`
    FOREIGN KEY (`user_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `idempotency_records`
    ADD CONSTRAINT `fk_17_idempotency_records`
    FOREIGN KEY (`user_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `sync_operations`
    ADD CONSTRAINT `fk_18_sync_operations`
    FOREIGN KEY (`user_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `workout_payloads`
    ADD CONSTRAINT `fk_19_workout_payloads`
    FOREIGN KEY (`user_id`)
    REFERENCES `users` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `matches`
    ADD CONSTRAINT `fk_20_matches`
    FOREIGN KEY (`venue_id`)
    REFERENCES `venues` (`id`)
    ON DELETE SET NULL ON UPDATE RESTRICT;

ALTER TABLE `quarters`
    ADD CONSTRAINT `fk_22_quarters`
    FOREIGN KEY (`match_id`)
    REFERENCES `matches` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `sync_operations`
    ADD CONSTRAINT `fk_26_sync_operations`
    FOREIGN KEY (`match_id`)
    REFERENCES `matches` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `auth_sessions`
    ADD CONSTRAINT `fk_36_auth_sessions`
    FOREIGN KEY (`device_id`)
    REFERENCES `devices` (`id`)
    ON DELETE SET NULL ON UPDATE RESTRICT;

ALTER TABLE `sync_operations`
    ADD CONSTRAINT `fk_37_sync_operations`
    FOREIGN KEY (`device_id`)
    REFERENCES `devices` (`id`)
    ON DELETE SET NULL ON UPDATE RESTRICT;

ALTER TABLE `track_points`
    ADD CONSTRAINT `fk_38_track_points`
    FOREIGN KEY (`workout_payload_id`)
    REFERENCES `workout_payloads` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `heart_rate_samples`
    ADD CONSTRAINT `fk_39_heart_rate_samples`
    FOREIGN KEY (`workout_payload_id`)
    REFERENCES `workout_payloads` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `quarter_player_stats`
    ADD CONSTRAINT `fk_quarter_player_stats_quarter`
    FOREIGN KEY (`quarter_id`)
    REFERENCES `quarters` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `quarter_positions`
    ADD CONSTRAINT `fk_quarter_positions_quarter`
    FOREIGN KEY (`quarter_id`)
    REFERENCES `quarters` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `workout_payloads`
    ADD CONSTRAINT `fk_workout_payloads_quarter`
    FOREIGN KEY (`quarter_id`)
    REFERENCES `quarters` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `quarter_summaries`
    ADD CONSTRAINT `fk_summary_quarter_id`
    FOREIGN KEY (`quarter_id`)
    REFERENCES `quarters` (`id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

ALTER TABLE `quarter_summaries`
    ADD CONSTRAINT `fk_summary_workout_payload_id`
    FOREIGN KEY (`quarter_id`, `workout_payload_id`)
    REFERENCES `workout_payloads` (`quarter_id`, `id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT;

-- 친구 경기 댓글은 경기 버전과 독립적이다. 댓글 작성으로 경기 ETag를 변경하지 않는다.
CREATE TABLE match_comments (
    id BINARY(16) NOT NULL PRIMARY KEY,
    match_id BINARY(16) NOT NULL,
    author_id BINARY(16) NOT NULL,
    content VARCHAR(1000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_match_comment_match FOREIGN KEY (match_id) REFERENCES matches(id) ON DELETE CASCADE,
    CONSTRAINT fk_match_comment_author FOREIGN KEY (author_id) REFERENCES users(id),
    INDEX idx_match_comments_page (match_id, created_at DESC, id DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE quarter_friends (
    quarter_id BINARY(16) NOT NULL,
    friend_user_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (quarter_id, friend_user_id),
    CONSTRAINT fk_quarter_friend_quarter FOREIGN KEY (quarter_id) REFERENCES quarters(id) ON DELETE CASCADE,
    CONSTRAINT fk_quarter_friend_user FOREIGN KEY (friend_user_id) REFERENCES users(id) ON DELETE CASCADE
);
