-- MySQL 8.4. 활성 계정만 닉네임을 점유한다. NULL 닉네임(온보딩 전)은 여러 계정에 허용한다.
-- 문자열 원문 UTF-8 바이트 기준으로 비교하며 대소문자·앞뒤 공백을 자동 변환하지 않는다.
-- 적용 전에 아래 쿼리 결과가 비어 있는지 확인한다. 중복이 있으면 먼저 대상 계정을 확인한다.
-- SELECT HEX(CAST(nickname AS BINARY)) AS nickname_key, COUNT(*) AS account_count
-- FROM users WHERE deleted_at IS NULL AND nickname IS NOT NULL
-- GROUP BY CAST(nickname AS BINARY) HAVING COUNT(*) > 1;
-- 기존 계정을 자동 삭제/변경하지 않으며, 중복이 남으면 ALTER가 실패한다.
ALTER TABLE users
    ADD COLUMN active_nickname VARBINARY(64)
        GENERATED ALWAYS AS (CASE WHEN deleted_at IS NULL THEN nickname ELSE NULL END) STORED,
    ADD CONSTRAINT uk_users_active_nickname UNIQUE (active_nickname);
