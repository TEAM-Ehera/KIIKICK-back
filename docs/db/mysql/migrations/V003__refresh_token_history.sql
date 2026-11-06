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
