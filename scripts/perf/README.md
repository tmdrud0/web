# Performance CSV Generator

`generate_perf_csv.py` builds CSV files that mirror the schema in `oj_perf`
so you can bulk-load large synthetic datasets straight into MySQL without
waiting on the HTTP seed endpoints.

## Usage

```bash
cd scripts/perf
python generate_perf_csv.py ../../var/perf_csv \
  --total-users 10000000 \
  --inactive-users 5000000 \
  --total-submissions 20000000 \
  --contest-count 2 \
  --problem-count 10
```

Outputs (`contest.csv`, `problem.csv`, `user.csv`, `submission.csv`,
`accepted_submission.csv`, `user_problem_guard.csv`) are written to the target
directory.

> **Tip:** start with a much smaller dataset (e.g. 50 000 users / 100 000
> submissions) to validate the workflow before generating multi‑million rows.

### Rank benchmark model

For rank-focused validation, use the reusable `oj-year1-100` user model. It
targets a service that has been open for about a year with `100` problems and
generates `solved_count`, `current_streak`, and `longest_streak` with a more
realistic skew than a single normal distribution.

- `current_streak > 0` is generated for about `1%` of total users
- `rank-only` skips contest/problem/submission CSVs and emits only `user.csv`

```bash
cd scripts/perf
python generate_perf_csv.py ../../var/rank_bench_csv \
  --user-model oj-year1-100 \
  --dataset rank-only \
  --total-users 1000000 \
  --problem-count 100 \
  --contest-count 1
```

The script prints a summary of the generated distributions so you can quickly
check whether the sample looks right before loading it.

## Loading into MySQL

1. **Prepare schema** – create an empty benchmark database. Use `oj_perf` for
   generic write/load experiments, or `oj_rank_bench` for reusable rank
   datasets.

2. **Enable LOCAL INFILE** if needed:
   ```sql
   SET GLOBAL local_infile = 1;
   ```

3. **Bulk load** tables (order matters because of FK constraints):
   ```sql
   USE oj_rank_bench;

   LOAD DATA LOCAL INFILE '/path/contest.csv'
     INTO TABLE contest
     FIELDS TERMINATED BY ',' ENCLOSED BY '"'
     LINES TERMINATED BY '\n'
     (id, name, start_time, end_time);

   LOAD DATA LOCAL INFILE '/path/problem.csv'
     INTO TABLE problem
     FIELDS TERMINATED BY ',' ENCLOSED BY '"'
     LINES TERMINATED BY '\n'
     (id, name, contest_id, contest_num);

   LOAD DATA LOCAL INFILE '/path/user.csv'
     INTO TABLE `user`
     FIELDS TERMINATED BY ',' ENCLOSED BY '"'
     LINES TERMINATED BY '\n'
     (name, pass, solved_count, streak_last_solved_date,
      streak_current_streak, streak_longest_streak);

   LOAD DATA LOCAL INFILE '/path/submission.csv'
     INTO TABLE submission
     FIELDS TERMINATED BY ',' ENCLOSED BY '"'
     LINES TERMINATED BY '\n'
     (user_id, submitted_time, problem_id, code, code_hash, result);

   LOAD DATA LOCAL INFILE '/path/accepted_submission.csv'
     INTO TABLE accepted_submission
     FIELDS TERMINATED BY ',' ENCLOSED BY '"'
     LINES TERMINATED BY '\n'
     (user_id, problem_id, submitted_time);

   LOAD DATA LOCAL INFILE '/path/user_problem_guard.csv'
     INTO TABLE user_problem_guard
     FIELDS TERMINATED BY ',' ENCLOSED BY '"'
     LINES TERMINATED BY '\n'
     (user_id, problem_id);
   ```

4. **Post-load maintenance** – run `/perf/buckets/rebuild` and snapshot
   rebuilding through the API, or execute equivalent SQL to refresh
   `solved_count_bucket` / streak snapshots if needed.

## Running rank bench profile

`rank-bench` is a profile group that enables the existing `perf` endpoints
while pointing the app at `oj_rank_bench`.

```bash
./gradlew bootRun --args='--spring.profiles.active=rank-bench'
```

After the app starts, rebuild the derived ranking tables once for the loaded
dataset.

```bash
curl -X POST http://localhost:8080/perf/buckets/rebuild
curl -X POST http://localhost:8080/perf/streak/rebuild
```

## Notes

- The script assumes tables are empty so IDs begin at 1. If data already
  exists, truncate tables or adjust the generated IDs accordingly.
- Generated timestamps place contests in the past and submissions after the
  contests, matching the “post-contest” scenario.
- Randomness is deterministic thanks to `--random-seed`; change it if you want
  different distributions.
