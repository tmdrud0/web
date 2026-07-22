-- Add tables and indexes for longest streak ranking
CREATE TABLE IF NOT EXISTS longest_streak_bucket (
    n INT NOT NULL,
    user_count BIGINT NOT NULL,
    cum_higher_count BIGINT NOT NULL,
    PRIMARY KEY (n),
    KEY idx_lsb_cum_higher (cum_higher_count)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_user_longest_streak
    ON user (streak_longest_streak DESC, streak_last_solved_date ASC, id ASC);
