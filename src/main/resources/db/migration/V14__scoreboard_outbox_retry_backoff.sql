ALTER TABLE contest_submission_outbox
    ADD COLUMN next_attempt_at DATETIME(6) NULL;

CREATE INDEX idx_cs_outbox_retry
    ON contest_submission_outbox (status, next_attempt_at, created_at, id);
