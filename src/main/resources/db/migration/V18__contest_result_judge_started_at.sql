-- When a judge worker picked the submission up (the start of ContestSubmissionJudgeProcessor.judge,
-- on the judge JVM's clock). Together with contest_submission.submitted_time it gives the queue wait
-- directly, and with provisional_judged_at (end of the judgement) and result_saved_at (the insert)
-- it splits a worker's occupancy into judging and post-judge work. Nullable: rows written before
-- this column existed, and results republished from a stored row, have no start instant.
ALTER TABLE contest_submission_result
    ADD COLUMN judge_started_at DATETIME(6) NULL AFTER provisional_judged_at;
