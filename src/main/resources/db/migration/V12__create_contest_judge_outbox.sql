CREATE TABLE contest_judge_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    submission_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    claim_token VARCHAR(36),
    claimed_at DATETIME(6),
    attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    published_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_contest_judge_outbox_submission (submission_id),
    KEY idx_contest_judge_outbox_claim (status, claimed_at, id),
    CONSTRAINT fk_contest_judge_outbox_submission
        FOREIGN KEY (submission_id) REFERENCES contest_submission (id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
