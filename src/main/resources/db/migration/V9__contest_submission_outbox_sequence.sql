ALTER TABLE contest_submission_outbox
    ADD COLUMN redis_seq BIGINT NULL;

CREATE INDEX idx_cs_outbox_redis_seq ON contest_submission_outbox (redis_seq);

