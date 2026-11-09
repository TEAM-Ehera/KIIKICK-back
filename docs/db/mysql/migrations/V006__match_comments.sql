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
