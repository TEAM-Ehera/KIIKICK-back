ALTER TABLE match_comments
    ADD COLUMN parent_comment_id BINARY(16) NULL DEFAULT NULL,
    ADD COLUMN deleted_at DATETIME(6) NULL DEFAULT NULL,

    ADD UNIQUE KEY uq_match_comments_match_id_id
        (match_id, id),

    ADD INDEX idx_match_comments_parent_page
        (match_id, parent_comment_id, created_at DESC, id DESC);

-- Install the referenced unique key before adding the self-referential FK.
ALTER TABLE match_comments
    ADD CONSTRAINT fk_match_comments_parent
        FOREIGN KEY (match_id, parent_comment_id)
        REFERENCES match_comments (match_id, id)
        ON DELETE RESTRICT;
