DELETE o1
FROM contest_submission_outbox o1
JOIN contest_submission_outbox o2
  ON o1.contest_submission_id = o2.contest_submission_id
 AND o1.id > o2.id;

ALTER TABLE contest_submission_outbox
    ADD CONSTRAINT uk_cs_outbox_submission UNIQUE (contest_submission_id);
