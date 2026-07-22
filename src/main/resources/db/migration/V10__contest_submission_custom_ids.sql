ALTER TABLE contest_submission_result
    DROP FOREIGN KEY fk_cs_result_submission;

ALTER TABLE contest_submission_outbox
    DROP FOREIGN KEY fk_cs_outbox_submission;

ALTER TABLE contest_submission
    MODIFY COLUMN id BIGINT NOT NULL;

ALTER TABLE contest_submission_result
    ADD CONSTRAINT fk_cs_result_submission
        FOREIGN KEY (submission_id) REFERENCES contest_submission (id)
        ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE contest_submission_outbox
    ADD CONSTRAINT fk_cs_outbox_submission
        FOREIGN KEY (contest_submission_id) REFERENCES contest_submission (id)
        ON DELETE CASCADE ON UPDATE CASCADE;
