-- 읽기 경로의 equality 필터 뒤에 실제 커서 정렬 키를 배치한다.
CREATE INDEX idx_match_owner_created ON matches(owner_id, created_at, id);
CREATE INDEX idx_match_owner_status_created ON matches(owner_id, status, created_at, id);
CREATE INDEX idx_match_owner_status_ended ON matches(owner_id, status, ended_at, id);
CREATE INDEX idx_friend_low_created ON friend_pairs(user_low_id, status, created_at, id);
CREATE INDEX idx_friend_high_created ON friend_pairs(user_high_id, status, created_at, id);
CREATE INDEX idx_friend_low_accepted ON friend_pairs(user_low_id, status, accepted_at, id);
CREATE INDEX idx_friend_high_accepted ON friend_pairs(user_high_id, status, accepted_at, id);
CREATE INDEX idx_user_active_created ON users(deleted_at, created_at DESC, id ASC);
CREATE INDEX idx_user_active_nickname ON users(deleted_at, nickname, id);
CREATE INDEX idx_notification_unread_page ON notifications(recipient_id, read_at, created_at, id);
CREATE INDEX idx_notification_unread_type ON notifications(recipient_id, read_at, target_type);
CREATE INDEX idx_quarter_completed_end ON quarters(match_id, status, ended_at, id);
