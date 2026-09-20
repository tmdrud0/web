ALTER TABLE contest_submission_result
    ADD COLUMN scoreboard_applied_seq BIGINT NULL;

CREATE INDEX idx_csr_scoreboard_applied_seq ON contest_submission_result (scoreboard_applied_seq);

