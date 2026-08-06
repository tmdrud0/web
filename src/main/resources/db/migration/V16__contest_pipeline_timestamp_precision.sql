-- These columns used to store only whole seconds. Existing rows keep their original second
-- precision (fractional seconds remain .000000), so latency distributions that include rows
-- written before this migration are not trustworthy.
ALTER TABLE contest_submission
    MODIFY COLUMN submitted_time DATETIME(6) NOT NULL;

ALTER TABLE contest_submission_result
    MODIFY COLUMN provisional_judged_at DATETIME(6) NULL,
    MODIFY COLUMN final_judged_at DATETIME(6) NULL;

ALTER TABLE contest_submission_outbox
    MODIFY COLUMN submitted_time DATETIME(6) NOT NULL,
    MODIFY COLUMN judged_at DATETIME(6) NULL,
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN processed_at DATETIME(6) NULL;
