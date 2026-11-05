-- V001/V002 적용 후 실행한다. 완료 이력 전체를 매번 스캔하지 않고 대기 작업 순서대로 읽는다.
CREATE INDEX idx_summary_pending
    ON quarter_summaries (calculation_status, updated_at, quarter_id);
