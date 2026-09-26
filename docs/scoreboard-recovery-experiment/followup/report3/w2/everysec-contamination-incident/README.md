# Incident: contest/user ID reuse collided with the undrained shared stream backlog

Two W2 `everysec` attempts failed before any load started:

1. `listreamoffset_c1_20260927034539` (-ResetMySqlVolume): "the pipeline before the load did not reach
   operational quiescence within 900 seconds (pending=473843, stream=1849484/0/c1, dbPending=93)".
   `quiescence-timeout-failure.json` is this attempt's run-events.
2. `listreamoffset_c1_20260927040628` (-ResetMySqlVolume again, no reset on retry): passed quiescence,
   but failed align with "Redis and MySQL disagree before any load (10000 vs 10000 participants)".
   `consistency-before-load.json` here shows the digest mismatch detail: same 10000 user ids on both
   sides (participant *count* matched), but 2499 users' (solved, penalty) were wrong, e.g.
   `10156:got(10,28)want(8,7)` - the scoreboard (Redis) held *more* than MySQL says this run's own seed
   produced.

Root cause: `-ResetMySqlVolume` resets MySQL's auto-increment counters, so a new run's seeded contest and
users get the *same* ids (contest 2, users 1..10000) as the immediately preceding `no`-condition run's
contest. The shared RabbitMQ stream (`contest.judge.result.stream`, ~1.85M retained messages at the
time - accumulated by unrelated earlier experiments in this shared docker-compose project, not by this
report's own runs) was still being drained by the `stream-offset` consumer when this run's contest 2 was
seeded and rebuilt. Once the consumer's backlog reader reached the *previous* run's contest-2 events
(same contest id, same user id range), it applied them onto *this* run's freshly rebuilt scoreboard,
inflating `solved`/`penalty` for exactly the users whose ids the two runs' seeds happened to share.

Confirmed NOT present in the completed `no`-condition run
(`docs/scoreboard-recovery-experiment/followup/report3/w2/no/`): both its
`consistency-before-load.json` and its final digest matched exactly (SHA-256 digest equality, not just
participant count) - it was the first run against this stack in this report and had no identically
numbered predecessor to collide with.

Fix adopted (per coordinator's option (a)): stop passing `-ResetMySqlVolume` for W2/W3 runs. MySQL's
`AUTO_INCREMENT` does not reset on `DELETE` (only on `TRUNCATE`/volume-reset), so as long as the volume
is never reset, every run's contest and user ids are ones no earlier run in this stack ever used, and a
stale backlog event can never be misapplied onto a live run's own keys again. Verified: the next
`everysec` attempt (`listreamoffset_c1_20260927055413`, no `-ResetMySqlVolume`) seeded contest 3 and
completed with a matching digest throughout.

The RabbitMQ stream itself was never purged or truncated (forbidden by the harness's own rules); the
mismatched Redis keys were removed by this run's own `Remove-ShortPauseKeys`/`Remove-ExperimentData`
cleanup, scoped to its own contest id, same as every other run.
