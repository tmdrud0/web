-- Claiming used to OR three status branches and sort on COALESCE(next_attempt_at, claimed_at,
-- created_at). That forced a filesort over the whole eligible set, so LIMIT never bounded the
-- work: a 500-row claim read - and under FOR UPDATE locked - every claimable row, degrading to a
-- full table scan once the outbox backed up.
--
-- due_at collapses "when may this row be claimed again" into a single indexed column:
--   PENDING    -> created_at            (claimable now, FIFO by creation)
--   PROCESSING -> claimed_at + lease    (claimable again only once the lease expires)
--   FAILED     -> next_attempt_at       (claimable again after the retry backoff)
--   COMPLETED  -> NULL                  (never claimable; excluded from the index range)
ALTER TABLE contest_submission_outbox
    ADD COLUMN due_at DATETIME(6) NULL;

UPDATE contest_submission_outbox
SET due_at = CASE status
        WHEN 'PENDING'    THEN created_at
        WHEN 'FAILED'     THEN COALESCE(next_attempt_at, created_at)
        -- 30 seconds mirrors the default contest.outbox.claim-timeout
        WHEN 'PROCESSING' THEN TIMESTAMPADD(SECOND, 30, COALESCE(claimed_at, created_at))
        ELSE NULL
    END;

CREATE INDEX idx_cs_outbox_due ON contest_submission_outbox (due_at, id);
