CREATE TABLE IF NOT EXISTS user_streak_rank_snapshot (
    snapshot_rank BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    current_streak INT NOT NULL,
    last_solved_time DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_rank),
    UNIQUE KEY uk_user_streak_rank_snapshot_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
