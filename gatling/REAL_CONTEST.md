# Real Contest Submission Load Test

This scenario hits the real application path instead of `/perf/*`.

- `GET /login`
- `POST /login`
- `GET /problems/{id}/submission`
- `POST /problems/{id}/submission`

The target is the Docker Compose stack behind `nginx` on `http://localhost:8080`.

## 1. Start the stack

```powershell
docker compose up -d --build
```

## 2. Seed active contest data

The seed script creates:

- one active contest
- N contest problems
- N users named `{prefix}_user_{index}`
- password `pass`

It uses the database container clock so the contest is active from the app server's point of view.

```powershell
$seed = PowerShell -ExecutionPolicy Bypass -File .\gatling\seed-real-contest.ps1 -UserCount 1000 -ProblemCount 5 -DurationMinutes 240
$seed
```

Use these fields from the output:

- `prefix`
- `problemIdStart`, `problemIdEnd`
- `userIndexStart`, `userIndexEnd`

## 3. Run Gatling

In PowerShell, use `--%` so `-Dperf.*` arguments are passed through as-is.

```powershell
.\gradlew.bat --% :gatling:realContestSubmissionRun `
  -Dperf.baseUrl=http://localhost:8080 `
  -Dperf.userPrefix=$($seed.prefix) `
  -Dperf.userIndex.start=$($seed.userIndexStart) `
  -Dperf.userIndex.end=$($seed.userIndexEnd) `
  -Dperf.problemId.start=$($seed.problemIdStart) `
  -Dperf.problemId.end=$($seed.problemIdEnd) `
  -Dperf.targetRps=20 `
  -Dperf.rampSeconds=10 `
  -Dperf.holdSeconds=30
```

Start smaller first.

- `targetRps=2`
- `holdSeconds=5`

## 4. Verify result flow

Check these after the run:

- `contest_submission`
- `contest_submission_result`
- `contest_submission_result.scoreboard_applied_at`
- batch-1 `contest_scoreboard_pending_events`(AMQP tail offset - Redis 적용 offset)
- `judge-1` logs
- `batch-1` logs

Expected shape for a healthy short run:

- submissions inserted
- provisional results created
- outbox rows completed
