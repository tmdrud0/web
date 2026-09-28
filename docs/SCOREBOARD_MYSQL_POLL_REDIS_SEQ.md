# redis-seq over MySQL polling

`redis-seq` 복구 모드는 이제 **MySQL polling delivery**(`contest.scoreboard.delivery=mysql-poll`)로만
동작한다. RabbitMQ Stream은 이 모드의 스코어보드 경로에서 신규 반영, 롤백 감지, 복구 데이터, checkpoint
어디에도 쓰이지 않는다. 채점 작업 분배용 RabbitMQ queue(`contest.judge.live`)는 그대로다.

- MySQL이 채점 결과의 원장이다.
- Redis가 스코어보드 적용 순서(seq)를 발급한다.
- MySQL이 결과별 seq(`scoreboard_applied_seq`)와 전역 durable watermark(`scoreboard_sequence_watermark`)를 보존한다.

## 설정

| 프로퍼티 | 기본값 |
|---|---|
| `contest.scoreboard.delivery` | `rabbit-stream` (`redis-seq`는 `mysql-poll` 필수) |
| `contest.scoreboard.mysql-poll.batch-size` | `500` |
| `contest.scoreboard.mysql-poll.poll-interval` | `200ms` |
| `contest.scoreboard.mysql-poll.rollback-check-interval` | `5s` |
| `contest.scoreboard.mysql-poll.recovery-interval` | `1s` |
| `contest.scoreboard.mysql-poll.recovery-chunk-size` | `500` |
| `contest.scoreboard.mysql-poll.recovery-max-iterations` | `1000` |
| `contest.scoreboard.mysql-poll.ownership-lock-name` | `oj.contest.scoreboard.mysql-poll` |

프로파일 `redis-seq-poll`을 역할 프로파일 뒤에 붙이면(`multi-batch,redis-seq-poll`) mode, delivery,
Stream consumer/publisher 플래그가 한 번에 바뀐다. `ContestScoreboardRecoveryValidator`가 기동 시 다음을 거부한다.

- `redis-seq` + `rabbit-stream`, `stream-offset`/`full-replay` + `mysql-poll`
- `mysql-poll`에서 `contest.scoreboard.stream.consumer.enabled=true` 또는
  `contest.submission.judge.result-stream.publisher.enabled=true`
- `delivery`의 비정규 표기(`MYSQL_POLL` 등)

플래그와 별개로 Stream 빈(consumer container, listener, processor, lifecycle, judge result publisher,
result stream queue/binding/template, Stream recovery strategy)은 `RabbitStreamDeliveryCondition`으로
`mysql-poll`에서 **빈 정의 자체가 없다.** 같은 결과가 Stream과 poller로 동시에 반영될 수 없다.

## 신규 반영 (`ContestScoreboardMySqlPoller`)

1. poll 시작 시 `throughId = MAX(submission_id) WHERE seq IS NULL AND 채점 완료`를 고정한다.
2. `seq IS NULL AND COALESCE(final_result, provisional_result) <> 'PENDING' AND id > afterId AND id <= throughId
   ORDER BY id LIMIT batch-size`로 keyset 조회.
3. batch마다 apply lock을 잡고 rollback 검사 → Lua 적용(seq 발급) → **같은 MySQL 트랜잭션**에서
   `scoreboard_applied_seq` 갱신 + `highest_durable_seq = GREATEST(highest_durable_seq, batchMax)`.
   marker가 실패하면 watermark도 롤백된다.
4. marker가 유실된 행(Redis 적용 성공, MySQL 기록 전 장애)은 여전히 `seq IS NULL`이라 다음 poll이 다시
   조회한다. Lua는 이미 processed인 submission에 대해 채점을 다시 반영하지 않고 가진 seq를 돌려준다.

## 롤백 감지 (`ContestScoreboardRollbackDetector`)

`R`(Redis allocator)과 `H`(MySQL watermark)를 비교한다: `R < H`만 롤백이고, `R = H`는 정상,
`R > H`는 Redis 적용 후 MySQL marker가 뒤따르는 중이다. 검사 시점은 (1) 기동 시 첫 poll 이전,
(2) `rollback-check-interval` 주기(유입이 없어도), (3) 각 poll batch 적용 직전.

`R < H`이면 apply lock 안에서: R/H 재확인 → pending `(R, H]` MySQL 저장 → Lua로
`allocator = max(allocator, H)` fence → lock 해제 → 신규 poll 재개 → 백그라운드 복구.
저장 실패 시 fence하지 않는다. fence 실패 시 pending은 남고 다음 검사가 같은 pending 행을 재사용해 다시 fence한다.

### expected-watermark Lua 검사

검사 직후 Redis가 복원되는 race를 막기 위해, 모든 적용 요청이 MySQL에서 읽은 H(그리고 같은 batch에서
이미 발급된 seq까지 올린 값)를 Lua에 넘긴다. Lua는 **첫 쓰기 전에** `allocator < expected`이면 `-1`을
반환하고 스코어보드·processed·seq 어느 것도 바꾸지 않는다. batch는 그때까지 적용된 행만 기록하고 검사를
다시 돌린다. batch 안에서 expected를 발급 seq로 올리는 이유: batch 도중 찍힌 snapshot으로 복원되면
allocator가 batch 시작 H 이상이라 H만으로는 드러나지 않는다.

## pending range 복구 (`ContestScoreboardRangeRecovery`)

`scoreboard_sequence_recovery_range(generation, from_exclusive, through_inclusive, status, created_at, completed_at)`.
각 generation에 대해 `seq > from AND seq <= through AND 채점 완료 ORDER BY seq, submission_id LIMIT chunk`의
**첫 페이지를 매번 다시** 읽어 적용한다. 적용된 행은 fence 이후 발급된 H보다 큰 seq를 받아 범위 밖으로
나가므로 keyset 누락이 없다(processed 상태인 행도 `resequenceFloor=through`로 새 seq를 받는다).
결과가 0건이면 `UPDATE ... WHERE generation = ? AND status = 'PENDING'`로 완료한다 — 이전 generation 완료가
새 generation을 지우지 않는다. apply lock은 chunk 단위로만 잡고, `recovery-max-iterations`를 다 쓰면
pending으로 남겨 다음 pass가 이어간다. 기동 후 pending은 recovery 스레드가 재개한다.
전역 duplicate seq 검사(`GROUP BY/HAVING`)와 전체 replay는 없다.

## 단일 owner

poller·검사·복구 빈은 `contest.scoreboard.recovery.owner.enabled=true`(batch-role) 인스턴스에만 생긴다.
여기에 더해 전용 커넥션에서 MySQL `GET_LOCK(ownership-lock-name, 0)`을 잡고, 매 tick마다
`IS_USED_LOCK = CONNECTION_ID()`로 확인한다. 락을 얻지 못한 두 번째 owner는 아무것도 하지 않고 오류를 남긴다.
이는 **가드이지 failover 프로토콜이 아니다**: apply lock은 JVM 로컬이므로 운영은 owner 1대를 전제한다.

## 제거된 것

`ContestScoreboardRedisSequenceRecoveryService`(전역 duplicate scan, lost-tail walk),
`...LiveRecovery`, `...Scheduler`, `...StartupCheck`, `RedisSequenceRecoveryStrategy`(Stream offset 범위를
seq 복구로 바꾸던 경로)와 duplicate-scan 쿼리. `stream-offset`/`full-replay`의 Stream 코드는 그대로이며
`rabbit-stream` delivery에서만 빈이 된다. `contest.scoreboard.recovery.redis-seq.*` 프로퍼티는 바인딩만 되고
더 이상 읽히지 않는다. 기동 보고(`Contest scoreboard recovery: ...`)는 모든 모드에 `delivery=`를 찍고,
redis-seq에서는 `mysql-poll.*` 설정을 찍는다.

## 실험 harness

- `run-recovery-pilot.ps1`: redis-seq는 `Set-ScoreboardDeliveryEnvironment`로 delivery와 Stream 플래그를 함께
  설정한다. 감지 시각은 `Redis scoreboard rollback detected: ...` 로그(`detected-watermark`)로,
  범위 복구 완료는 `Recovered scoreboard sequence range ...` 로그(`range-recovered`, `rangeRecoveryMs`)로 잡는다.
  quiescence는 Stream 지연·consumer 대신 미반영 행 0 + pending recovery range 0이다.
  수집 지표는 `contest_scoreboard_mysql_poll_*`(`pollRollbacksDelta`, `pollRecoveryAppliedDelta`,
  `pollResumeMaxSeconds`, `maxPendingRecoveryRanges` 등)이고, Stream consumer 관련 수치는 `unavailable`이다.
- `run-recovery-live-impact.ps1`: redis-seq도 같은 요약 지표(이름·의미 동일)를 낸다. Stream 전용 소스 세 가지의 대응:
  - "new" 기준: Stream checkpoint 대신 롤백 직전 Redis allocator R(`preRollbackSeq`). snapshot/rollback Lua가
    checkpoint 자리에서 `contest:scoreboard:seq`를 읽는다. `preRollbackOffset`·`snapshotCheckpoint`·`restoredCheckpoint`는
    `unavailable`, 값은 `preRollbackSeq`·`snapshotSeq`·`restoredSeq`.
  - per-apply trace: 실험 trace 스위치가 켜지면 `ContestScoreboardSequencedApplication`이 Redis가 적용한 chunk마다
    live batch를 쓴다(offset = 읽을 때의 seq, 없으면 새로 발급된 seq). range recovery는 잃은 결과를 범위 안의 옛 seq로
    기록하므로 `reconsumedAfterFault` = 복구 범위에서 다시 적용된 결과 수다. 탐지기는 `ROLLBACK_DETECTED`(outcome = 찾은
    검사: startup/periodic/poll-batch/script-refusal/recovery-refusal), range recovery는 `PASS_START`/`CHUNK`/`PASS_END`.
  - 정렬(rebuild endpoint): mysql-poll에는 endpoint가 없고 필요도 없다. seed 행은 seq가 없는 채점 완료 행이라 poller가
    직접 적용하고, drain 후 digest 비교가 정렬을 증명한다. poller가 적용한 seed 행은 요약에서 `liveRowsSeed`로 따로 센다.
  - baseline gate: Stream pending 대신 poller backlog(`baselinePollBacklog`, seq 없는 채점 완료 행).
  - 로그에서 `detectedAllocator`/`detectedWatermark`/`detectedRangeSize`/`recoveryRangeGeneration`/`fencedTo`/
    `rangeRecoveredLogged`를 run-events에 남긴다. `gapQuestionsAfterRollback`/`passesSkippedAfterRollback`은 `unavailable`.
