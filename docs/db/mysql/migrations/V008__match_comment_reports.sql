ALTER TABLE match_comments
    ADD COLUMN hidden_at DATETIME(6) NULL;

CREATE TABLE match_comment_reports (
    id BINARY(16) NOT NULL PRIMARY KEY,
    reporter_id BINARY(16) NOT NULL,
    comment_author_id BINARY(16) NOT NULL,
    comment_id BINARY(16) NOT NULL,
    comment_content VARCHAR(1000) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    detail VARCHAR(1000) NULL,
    reported_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    processed_by BINARY(16) NULL,
    resolution VARCHAR(16) NULL,

    CONSTRAINT uq_match_comment_reports_reporter_comment
        UNIQUE (reporter_id, comment_id),
    CONSTRAINT chk_match_comment_reports_no_self_report
        CHECK (reporter_id <> comment_author_id),
    CONSTRAINT chk_match_comment_reports_reason
        CHECK (CHAR_LENGTH(TRIM(reason)) > 0),
    CONSTRAINT chk_match_comment_reports_resolution
        CHECK (resolution IN ('NO_ACTION', 'HIDDEN', 'DELETED')),
    CONSTRAINT chk_match_comment_reports_processing
        CHECK (
            (processed_at IS NULL AND processed_by IS NULL AND resolution IS NULL)
            OR
            (processed_at IS NOT NULL AND processed_by IS NOT NULL AND resolution IS NOT NULL)
        ),
    INDEX idx_match_comment_reports_queue (processed_at, reported_at DESC, id DESC),
    INDEX idx_match_comment_reports_comment (comment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
