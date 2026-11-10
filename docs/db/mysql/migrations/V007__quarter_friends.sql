-- Apply after V001–V006. Historical entries are independent of friend_pairs.
CREATE TABLE quarter_friends (
    quarter_id BINARY(16) NOT NULL,
    friend_user_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (quarter_id, friend_user_id),
    CONSTRAINT fk_quarter_friend_quarter FOREIGN KEY (quarter_id) REFERENCES quarters(id) ON DELETE CASCADE,
    CONSTRAINT fk_quarter_friend_user FOREIGN KEY (friend_user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
