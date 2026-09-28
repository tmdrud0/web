-- The durable watermark of the redis-seq / mysql-poll delivery: the highest Redis scoreboard sequence
-- MySQL has recorded against a result. It is raised in the same transaction as the per-result
-- scoreboard_applied_seq markers, so it never claims a sequence no result holds. A Redis allocator below
-- it means Redis was restored to an older snapshot.
CREATE TABLE scoreboard_sequence_watermark (
    id BIGINT NOT NULL,
    highest_durable_seq BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO scoreboard_sequence_watermark (id, highest_durable_seq, updated_at)
SELECT 1, COALESCE(MAX(scoreboard_applied_seq), 0), CURRENT_TIMESTAMP(6)
FROM contest_submission_result;
