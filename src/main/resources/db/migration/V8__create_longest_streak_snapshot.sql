CREATE TABLE IF NOT EXISTS longest_streak_rank_snapshot (
    snapshot_rank BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    longest_streak INT NOT NULL,
    last_solved_time DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_longest_streak_snapshot_user FOREIGN KEY (user_id) REFERENCES user(id)
);

CREATE INDEX idx_longest_streak_snapshot_user ON longest_streak_rank_snapshot (user_id);
CREATE INDEX idx_longest_streak_snapshot_rank ON longest_streak_rank_snapshot (snapshot_rank);
