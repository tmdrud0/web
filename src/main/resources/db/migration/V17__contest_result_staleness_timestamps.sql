-- Existing rows stay NULL: their actual materialization/application instants cannot be reconstructed.
ALTER TABLE contest_submission_result
    ADD COLUMN result_saved_at DATETIME(6) NULL AFTER final_judged_at,
    ADD COLUMN scoreboard_applied_at DATETIME(6) NULL AFTER result_saved_at;
