-- Sequence ranges (from_exclusive, through_inclusive] a Redis rollback took away, persisted before the
-- allocator is fenced so a crash between the two leaves the range to be recovered on the next start.
-- generation orders the ranges and is the compare key of completion: finishing one generation never
-- completes a newer one written while it ran.
CREATE TABLE scoreboard_sequence_recovery_range (
    generation BIGINT NOT NULL AUTO_INCREMENT,
    from_exclusive BIGINT NOT NULL,
    through_inclusive BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (generation),
    KEY idx_ssrr_status_generation (status, generation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
