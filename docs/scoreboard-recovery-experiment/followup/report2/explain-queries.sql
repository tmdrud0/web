-- Newest-first (background replay), first page: beforeId = NULL
EXPLAIN ANALYZE
SELECT csr.submission_id, csr.contest_id, s.problem_id, s.user_id, s.contest_id, s.submitted_time,
       COALESCE(csr.final_result, csr.provisional_result)
  FROM contest_submission_result csr
  JOIN contest_submission s ON s.id = csr.submission_id
 WHERE csr.contest_id = 1
   AND COALESCE(csr.final_result, csr.provisional_result) <> 'PENDING'
 ORDER BY csr.submission_id DESC
 LIMIT 1000;

-- Newest-first, middle page: beforeId = :mid
-- EXPLAIN ANALYZE ... AND csr.submission_id < :mid ...

-- Ascending (startup full replay), comparison
EXPLAIN ANALYZE
SELECT csr.submission_id, csr.contest_id, s.problem_id, s.user_id, s.contest_id, s.submitted_time,
       COALESCE(csr.final_result, csr.provisional_result)
  FROM contest_submission_result csr
  JOIN contest_submission s ON s.id = csr.submission_id
 WHERE csr.contest_id = 1
   AND COALESCE(csr.final_result, csr.provisional_result) <> 'PENDING'
 ORDER BY csr.submission_id ASC
 LIMIT 1000;
