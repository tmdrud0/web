# Max RPS Measurement

Use `ContestSubmissionStepLoadSimulation` when you want a sustainable-throughput
number instead of a single fixed-RPS pass/fail result.

## Why this is better

- Lowering the Hikari pool mainly makes connection starvation happen earlier.
- A fixed `targetRps=1000` run tells you where the system breaks, but not the
  highest rate it can sustain cleanly.
- A step load made it easier to compare `immediate` vs `bulk` under the same
  traffic envelope.

## Run

```powershell
.\gradlew.bat :gatling:contestSubmissionStepLoadRun `
  -Dperf.baseUrl=http://localhost:8080 `
  -Dperf.startRps=200 `
  -Dperf.stepRps=200 `
  -Dperf.maxRps=1000 `
  -Dperf.rampSeconds=5 `
  -Dperf.stepHoldSeconds=10 `
  -Dperf.userId.start=1 `
  -Dperf.userId.end=10000 `
  -Dperf.problemId.start=1 `
  -Dperf.problemId.end=5
```

## Writer selection record

Previous runs compared the `immediate` and `bulk` writers with the exact same
seed and step profile. The comparison is complete: `bulk` was selected and is
now the only writer path.

For the selected bulk path, also capture:

```powershell
Invoke-RestMethod http://localhost:8080/perf/contest/submission-bulk-stats
```

## Suggested acceptance line

Treat the highest clean step as the max sustainable RPS when all of these hold:

- success rate stays at or above `99%`
- there are no `500` errors from `POST /api/problems/{id}/submissions` (`503` and `429` are backpressure, not faults)
- there are no `HikariPool` timeout or `Too many connections` errors
- latency is still within the threshold you care about, usually `p95`

## Practical advice

- Start with pool size fixed to establish a bulk-writer baseline.
- After that, vary `spring.datasource.hikari.maximum-pool-size` to see whether
  throughput changes when connections are constrained.
- If the bulk writer fails with connection exhaustion, the current
  bottleneck is the DB connection layer, not the insert path itself.
