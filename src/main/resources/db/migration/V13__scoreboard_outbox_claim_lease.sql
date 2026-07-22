ALTER TABLE contest_submission_outbox
    ADD COLUMN claim_token VARCHAR(36) NULL,
    ADD COLUMN claimed_at DATETIME(6) NULL,
    ADD COLUMN attempts INT NOT NULL DEFAULT 0;

CREATE INDEX idx_cs_outbox_claim
    ON contest_submission_outbox (status, claimed_at, created_at, id);
