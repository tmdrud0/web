CREATE INDEX idx_user_current_streak
    ON user (streak_current_streak DESC, streak_last_solved_date ASC, id ASC);

