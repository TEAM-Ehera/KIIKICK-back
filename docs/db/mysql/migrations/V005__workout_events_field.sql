-- 운동 당시의 이벤트·경기장 사본은 저장 경기장과 독립적으로 보존한다.
-- 기존 원본은 수정하지 않으며 NULL 이벤트는 API에서 빈 배열로 조회한다.
ALTER TABLE workout_payloads
    ADD COLUMN events_json JSON NULL,
    ADD COLUMN field_snapshot JSON NULL;

-- iOS가 현재 사용하는 사용자별 업로드 경로도 같은 데이터를 보존한다.
ALTER TABLE workoutPayloads
    ADD COLUMN events_json JSON NULL,
    ADD COLUMN field_snapshot JSON NULL;
