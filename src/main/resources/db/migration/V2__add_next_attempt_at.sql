ALTER TABLE processing_records
    ADD COLUMN next_attempt_at DATETIME NULL;

CREATE INDEX idx_processing_retry
    ON processing_records (status, next_attempt_at);