# 보고서 2: full-replay 개선판의 두 문제

- 브랜치: `codex/full-replay-background-replay`. 기준 5eec3fd, 수정 commit 757c451.
- **1부 (이 문서의 §1~§6):** 코드 수정과 기존 Run B 산출물 분석. Docker 스택과 DB는 쓰지 않았고, 새 run은 없다.
- **2부 (§7):** replay 페이지 쿼리 EXPLAIN과 수정본 Run B 재실행. 스택이 필요하다.
- 분석 대상: `lifullreplay_r1_20260926160914` (Run B, 단일 run)
  - 원본: `var/scoreboard-recovery-live-impact/lifullreplay_r1_20260926160914/` (git-ignored)
  - 요약: `docs/scoreboard-recovery-experiment/live-impact/results/lifullreplay_r1_20260926160914/`
- 단일 run이므로 배수나 개선율은 계산하지 않는다. 측정하지 못한 값은 unavailable로 적는다.

## 1. 질문
1. **문제 A:** Run B가 exit 2로 끝난 원인은 `contest:scoreboard:stream:db-pending` 152건이 부하 종료 뒤 600초 동안 비워지지 않은 것이다. 왜 남았고, 어떻게 고치나?
2. **문제 B:** tail 복귀에 10,362 ms가 걸렸다. 예상은 "첫 청크 근처, 약 0.3초"였다. 시간은 어디에 들었나?

## 2. 맥락
- 개선판(`rollback-replay=background`)은 롤백을 감지해도 consumer를 멈추지 않는다.
- 롤백 처리는 `ContestScoreboardBackgroundReplay`의 전용 스레드(`scoreboard-full-replay`)가 맡는다. 대회 id 내림차순, submission id 내림차순으로 500건 청크를 apply lock 안에서 다시 보낸다.
- Run B 결과:
  - 신규 반영 정지 1 s
  - 복구 중 반영 지연 p50/p95: 3.2 s / 4.4 s
  - tail 복귀 10.4 s
  - `finalConsistent=True`, `drained=False` (exit 2)

## 3. 문제 A: db-pending이 비워지지 않음

### 3.1 분석 검증
| 주장 | 확인 | 근거 |
|---|---|---|
| db-pending은 Redis에는 반영했지만 MySQL `scoreboard_applied_at`은 아직 못 쓴 id의 집합이다 | 맞음 | `ContestScoreboardRedisScript` KEYS[2]. offset이 있을 때만, 즉 라이브 경로에서만 `sadd`한다(이미 processed인 재배달 포함). replay 요청은 offset이 없어서 넣지 않는다 |
| 배치 뒤 `complete`가 MySQL 표시 후 `srem`한다 | 맞음 | `ContestScoreboardStreamProcessor.process`가 `applyLock.withLock(applyBatch)`로 감싸므로 apply lock 안에서 실행된다 |
| 남은 id는 `repairPending`이 치우고, 호출은 consumer (재)시작 때뿐이다 | 맞음 | 호출처는 `ContestScoreboardStreamLifecycle.startAt` 하나다. `startAt`은 최초 시작, rewind, 실패 배치 재구독 때 불린다 |
| injector가 `stream:db-pending`도 되돌린다 | 맞음 | `RecoveryExperiment.Injector.ps1`의 `Get-ShortPauseGlobalKeys` |
| 현재 코드 세 모드는 롤백 뒤 재구독한다 | 맞음 (경로는 모드마다 다름) | Run A `recovery-log-events.json`: full-replay와 redis-seq는 "Resubscribing ... to re-read a failed batch", stream-offset은 rewind다. 세 run 모두 `drained=True` |
| 개선판은 재구독하지 않는다 | 맞음 | Run B 로그에는 `detected-nonrewinding`과 `rebuilt`만 있다. 롤백 답이 즉시 COVERED라서 실패 배치도 생기지 않는다 |
| 운영의 복제 failover나 RDB 롤백에서도 같이 되돌아간다 | 코드상 맞음 (실측 아님) | 스코어보드와 같은 Redis의 일반 키다 |
| 정합성은 문제없다 | 맞음 | `finalConsistent=True`. Lua가 processed(KEYS[6])와 db-pending(KEYS[2])에 한 번에 `sadd`하므로, 되살아난 id는 스냅샷의 processed에도 들어 있다 |
| 진짜 MySQL 쓰기 실패로 남은 id도 재시작 전까지 안 치워진다 | **틀림** | `complete`에서 예외가 나면 `ContestScoreboardStreamListener.failBatch` → `recordFailedBatch` → supervisor 재구독 → `startAt` → `repairPending` 순으로 치워진다. 개선판도 같다. 치워지지 않던 것은 **롤백이 되살린 id뿐**이다. JVM이 죽은 경우는 다음 기동 때 치워진다 |

152건이 스냅샷에서 되살아난 id인지는 확인하지 못했다(unavailable). harness가 최종 SCARD만 남겼고, `sbrec:snap:*`는 cleanup에서 지워졌다. 2부에서 확인한다.

### 3.2 수정 (757c451)
- `stream/ContestScoreboardAppliedAtRepair` 인터페이스를 추가했다. `ContestScoreboardAppliedAtCompletion`이 이를 구현하고, 메서드 본문은 그대로다.
- `ContestScoreboardBackgroundReplay`에 `afterPass` 훅을 추가했다.
  - pass가 완료된 직후, 완료 수를 세고 running을 해제하기 전에 replay 스레드에서 한 번 실행한다.
  - 인자 3개짜리 생성자는 no-op 훅으로 남겼다.
- `ContestScoreboardRecoveryStrategyConfig`는 background 분기에서만 `() -> applyLock.withLock(appliedAtRepair::repairPending)`을 넘긴다.

| 항목 | 결정 | 이유 |
|---|---|---|
| 호출 위치 | pass 완료 직후만. 주기 호출 없음 | 롤백마다 그 뒤에 시작하는 pass가 반드시 하나 있으므로, 모든 롤백 뒤에 drain이 돈다. 주기 호출은 롤백이 없어도 SMEMBERS를 계속 보낸다 |
| gate | 밖 | drain은 replay가 아니다. startup replay나 운영자 pass를 막을 이유가 없다 |
| 락 | apply lock 안 | 라이브 `complete`가 같은 lock 안에서 같은 행을 UPDATE하고 같은 id를 `srem`한다. drain을 `startAt`과 같은 조건에서 돌리기 위해서다. lock이 없어도 불변식(표시 뒤에만 제거, COALESCE로 멱등)은 유지된다 |
| lock 보유 | 집합 전체를 한 번에 처리 (`batchSize` 단위 UPDATE) | 평소 집합은 진행 중인 배치 크기 정도다. 롤백이 큰 집합을 되살리면 그동안 라이브 배치가 기다린다. 그 크기는 2부에서 측정한다 |
| 실패 | 로그만 남김 | pass는 이미 끝났다. 남은 id는 다음 pass나 다음 consumer 시작 때 치워진다 |
| 다른 모드 | 불변 | synchronous full-replay, redis-seq, stream-offset, `startAt` 경로는 바꾸지 않았다 |

### 3.3 테스트
| 클래스 | 테스트 | 확인 내용 |
|---|---|---|
| BackgroundReplayTests | `aCompletedPassDrainsTheIdsTheRollbackPutBackInDbPending` | 되살아난 {101,102,103}이 pass 뒤 비워진다. 순서는 replay → repair |
| 〃 | `theDrainWaitsForThePassThatCompletes` | 순서가 failed → replay → repair다 |
| 〃 | `aFailedDrainNeitherFailsThePassNorStopsTheWorker` | drain에서 예외가 나도 pass는 완료로 세고, worker는 다음 요청을 처리한다 |
| 〃 | `aPassHeldOutByTheGateDoesNotDrain` | 끝나지 않은 pass는 drain하지 않는다 |
| RecoveryModeWiringTests | `theBackgroundFullReplayDrainsDbPendingUnderTheApplyLockAfterItsPass` | 실제 설정 클래스로 만든 전략이 apply lock 안에서 repair를 1회 호출한다 |
| 〃 | `theOtherPathsDoNotDrainDbPendingFromTheStrategy` | synchronous, stream-offset, redis-seq는 전략 경로에서 repair를 호출하지 않는다 |
| StreamLifecycleTests | `theRewindThatAnswersARollbackDrainsTheDbPendingSet` | stream-offset은 시작과 rewind에서 한 번씩, 총 2회 drain한다 |
| 〃 | `aNonRewindingModeDrainsTheDbPendingSetOnlyWhenAFailedBatchRestartsTheConsumer` | 비rewind 모드는 COVERED만으로는 drain하지 않고, 실패 배치 재구독 때만 drain한다 |

- 실행: `$env:TEST_DB_URL='jdbc:mysql://127.0.0.1:1/none'`, `gradlew.bat :test --tests "my.oj.web.contest.scoreboard.*" --continue`
- 결과: 286 tests, 5 failed, 30 skipped
  - 실패 5건은 모두 DB 연결 실패다: `ContestScoreboardRecoveryModeStartupTests` 1건, `ContestScoreboardSequenceRecoveryMySqlIntegrationTests` 4건
  - skip 30건은 Redis/Rabbit 통합 suite다
  - 위 세 클래스는 15/13/33 전부 통과했다

## 4. 문제 B: tail 복귀 10.4초 분해

### 4.1 순서 (코드)
- `replayContestsNewestFirst`는 대회 id를 내림차순으로 돈다.
- `findReplayRowsByContestIdNewestFirst`의 조건은 `contestId = ? and submission.id < :beforeId and 결과 <> PENDING order by submission.id desc`이고, 페이지 크기는 1,000(`dbBatchSize`)이다.
- keyset은 직전 페이지 마지막 행의 id다. 한 페이지는 500행 청크 2개로 나눠 apply한다.
- 결론: submission id 내림차순이 맞다. 다만 여기서 "최신"은 **MySQL에 저장된 최신**이다. 롤백이 가져간 Redis 반영분의 최신과는 다르다.

### 4.2 분해 (T_fault = 1790406853242 기준 ms, 시계 불확실도 ±101)
| 사건 | 시각 |
|---|---|
| T_rollback | −2,388 |
| GAP 감지 | −27 |
| PASS_START | −24 |
| 첫 페이지 읽기 끝 = 청크 1 요청 (PASS_START부터 899) | +875 |
| 청크 1 lock 획득 / 종료 | +1,599 / +2,198 |
| poller 시작 | +3,443 |
| 첫 lost 복귀 (0 → 8) | +4,418 |
| T_tail (3,812/3,812) | +10,362 |

| 청크 | 페이지 | 요청 | 직전 간격 | lock 대기 | 보유 | 종료 | 돌아온 lost |
|---|---|---|---|---|---|---|---|
| 1 | 1 | 875 | 899 | 724 | 599 | 2,198 | 0 |
| 2 | 1 | 2,263 | 65 | 332 | 385 | 2,980 | 0 |
| 3 | 2 | 3,423 | 443 | 72 | 313 | 3,808 | 0 |
| 4 | 2 | 3,811 | 3 | 355 | 304 | 4,470 | 8 |
| 5 | 3 | 4,935 | 465 | 27 | 407 | 5,369 | 498 |
| 6 | 3 | 5,373 | 4 | 363 | 329 | 6,065 | 500 |
| 7 | 4 | 6,470 | 405 | 0 | 238 | 6,708 | 500 |
| 8 | 4 | 6,712 | 4 | 133 | 389 | 7,234 | 500 |
| 9 | 5 | 7,683 | 449 | 8 | 280 | 7,971 | 500 |
| 10 | 5 | 7,975 | 4 | 309 | 300 | 8,584 | 500 |
| 11 | 6 | 8,966 | 382 | 322 | 537 | 9,825 | 500 |
| 12 | 6 | 9,835 | 10 | 346 | 254 | 10,435 | 306 |
| 합 | | | 페이지 3,043 / 페이지 안 90 | 2,991 | 4,335 | | 3,812 |

- present는 청크 보유 구간 안에서만 늘어난다. 그래서 poller의 계단 하나하나를 청크에 대응시킬 수 있다.
- 홀수 청크 앞의 간격이 페이지 쿼리 시간이다. 첫 페이지는 899 ms, 이후는 382~465 ms였다.
- pass 1 전체(211청크) 기준: 페이지 간격 중앙값 210 / p90 418 / 최대 565 ms, lock 대기 중앙값 94 / 최대 725 ms
- lock은 라이브 배치와 공유하는 비공정 ReentrantLock이다. 이 구간은 fault 직후 backlog가 1,774에서 2,320으로 늘던 구간과 겹친다. 라이브 배치의 lock 보유 시간은 unavailable이다.

### 4.3 판단
| 후보 | 판정 | 수치 |
|---|---|---|
| lost가 여러 청크에 걸침 | 주요 원인 | 청크 4~12, 9청크(최소 8청크 필요). 지표는 lost **전부**가 돌아온 시각이다 |
| lost 앞에 id가 더 큰 비손실 행이 옴 (새 후보) | 주요 원인 | 약 1,992행, 약 4.4 s. 롤백부터 청크 1까지 채점된 1,584건 + 롤백 시점 backlog 556건. 재구성한 첫 lost 위치 1,867~2,140이 관측과 맞는다 |
| 청크 lock 경합 | 부분 원인 | 2,991 ms, 약 29% |
| 페이지 쿼리 | 부분 원인 | 3,043 ms, 약 29% (2부 EXPLAIN으로 확인) |
| 청크 보유 | 기저 비용 | 4,335 ms, 약 42% |

- lost 집합은 `processed-prerollback − processed-K`와 정확히 같다(3,812건).
- "lost가 가장 큰 id"라는 가정은 대체로만 맞다. lost id 범위 안에 비손실 행이 57건 섞여 있다. 55건은 스냅샷 전에 이미 반영된 행(늦게 제출됐지만 먼저 채점됨)이고, 2건은 backlog다. 그래도 청크 5~11은 500건 전부가 lost였다.
- lost의 채점 시각은 스냅샷 기준 −1,389 ~ +6,676 ms다. 옛 제출이 늦게 채점되어 순서를 흐트러뜨린 효과는 확인되지 않았다.
- 결론: 예상치 0.3 s는 두 주요 원인을 모두 빠뜨린 값이다. lost 9청크의 보유 시간만 더해도 약 2.9 s다.

## 5. 2부에서 확인할 것
1. `findReplayRowsByContestIdNewestFirst`의 EXPLAIN: 역방향 range scan인지 filesort인지, 초반 페이지가 느린 이유
2. 757c451로 Run B를 같은 조건에서 재실행
   - `drained=True`가 되고 dbPending이 0이 되는지
   - 스냅샷, 롤백 직후, 각 pass 종료 뒤의 db-pending SCARD (harness에 기록 추가 필요)
   - pass마다 drain 로그가 한 번씩 찍히는지
   - lost 앞 비손실 행이 이번처럼 약 2,000건인지
3. 선택: trace에 PAGE 이벤트 추가

## 6. 한계 (1부)
- Run B 단일 run이다.
- 152건의 출처는 unavailable이다. 수정의 효과는 스택에서 아직 검증하지 않았다.
- drain은 lock 안에서 집합 전체를 처리하는데, 그 크기는 측정하지 않았다.
- 청크 대응은 약 110 ms 간격 폴링을 바탕으로 한 추정이다. 페이지 시간은 청크 간격에서 추정했다. 이 구간의 GC 최대 정지는 84 ms다.
- 첫 페이지 재구성은 judged.csv의 채점 시각을 썼으므로 MySQL 커밋 시각과 어긋날 수 있다. 그래서 범위로 적었다.
- DB가 필요한 suite 2개(5건)와 Redis/Rabbit 통합 suite(skip 30건)는 이번 변경을 검증하지 않았다.

## 7. 2부 결과

- 실행 위치: `web-full-replay-bg`, 브랜치 `codex/full-replay-background-replay`, HEAD `683c717`(harness에 db-pending SCARD 기록 추가, 757c451 위에 쌓임).
- run: `lifullreplay_r1_20260927014450` (단일 run). `-Mode full-replay -Phase run -StackMySql -ResetMySqlVolume -SkipCleanup -TargetRps 500 -JudgedRatePerSecond 457.733 -SubmitIntervalMillis 5000`.
  - 원본: `var/scoreboard-recovery-live-impact/lifullreplay_r1_20260927014450/`(git-ignored). 요약·EXPLAIN·digest는 `docs/scoreboard-recovery-experiment/followup/report2/`.
  - `run.drained=True`, `run.finalConsistent=True`, `run.gatlingExitCode=0`, `outcome: complete (exit 0)`.
- 첫 시도(같은 커맨드, `-SkipCleanup` 없이)는 이전 시도의 `web-1` 컨테이너가 살아 있어 `Ensure-StackMySqlReady`가 빈 스키마에 마이그레이션을 다시 걸지 못해 실패했다(Flyway 버전이 300초 안에 18에 못 미침). `web-1/web-2/batch-1/judge-1/judge-2`를 강제로 지우고 재실행해 해결했다 — 이번 절차 자체의 함정이므로 기록만 남긴다.

### 7.1 db-pending 추이 (문제 A 검증)

| 시점 | SCARD | 비고 |
|---|---|---|
| 스냅샷 직후 | 60 | 평시 in-flight 배치 수준(기준선) |
| 롤백 직후 | 191 | 스냅샷이 되살린 id 포함 |
| 부하 종료 직후 | 54 | 두 pass가 각자 끝난 뒤 자체 drain을 이미 돌렸고, 그 뒤 진행된 라이브 트래픽의 평시 변동 |
| drain(Wait-PipelineQuiescent) 후 | **0** | 최종 |

- 이전 Run B(`lifullreplay_r1_20260926160914`)는 부하 종료 뒤 600초가 지나도 152건이 안 비워져 `drained=False`(exit 2)였다. 이번 run은 같은 지표가 0으로 수렴했고 `drained=True`(exit 0)다.
- trace(`recovery-trace.csv`)로 확인한 두 pass의 시작/종료: PASS_START `1790441392848` → PASS_END `1790441502902`(1차, 110,054 ms, 109,732행, 220청크), PASS_START `1790441503047` → PASS_END `1790441655094`(2차, 152,047 ms, offered 162,625행). 두 pass 사이 간격은 145 ms — pass 종료 → drain(afterPass) → 다음 pass 시작이 거의 끊김 없이 이어졌다.
- `repairPending` 성공 호출은 로그를 남기지 않는다(코드상 실패만 로그, `ContestScoreboardBackgroundReplay.runAfterPass`). 그래서 drain이 정확히 언제 얼마나 걸렸는지는 trace/로그로 직접 잡을 수 없고, SCARD 스냅샷과 pass 경계 시각으로만 추론했다 — **drain 자체의 lock 보유 시간은 unavailable**이다. 다만 `repairPending`이 처리하는 집합 크기(191건, batchSize=500 미만이라 한 번의 UPDATE 배치)로 볼 때 apply lock을 오래 붙잡을 규모는 아니었다고 판단한다.
- **판단**: 757c451의 drain 수정은 의도대로 동작한다. 롤백이 되살린 id는 그 롤백을 처리하는 pass가 끝날 때마다 drain되고, 두 번째 pass 이후에는 db-pending이 정상적인 라이브 in-flight 수준으로만 남았다가 부하 종료 뒤 0에 도달했다.

### 7.2 EXPLAIN ANALYZE (문제 B 원인 확인)

`performance_schema.events_statements_summary_by_digest`를 정렬(align) 직후(`2026-09-27T01:48:43+09:00`, 부하 시작 직후)에 TRUNCATE했다. 이 시점 이후 JVM 시작 시 도는 오름차순 startup replay는 이미 끝나 있었으므로, 이 digest가 잡은 것은 **롤백 뒤 두 background pass가 실제로 낸 페이지 쿼리 부하**다.

| 순위 | 쿼리(요약) | COUNT_STAR | SUM_ROWS_EXAMINED | SUM_ROWS_SENT | 비고 |
|---|---|---|---|---|---|
| 1 | `findReplayRowsByContestIdNewestFirst`(rollback replay 페이지 쿼리) | 275 | **39,039,842** | 272,357 | 호출당 평균 141,963행 조사 / 990행 반환 — **143배 증폭** |
| 2 | `scoreboard_applied_at IS NULL` 카운트(오라클류) | 5 | 1,031,389 | 5 | |
| 3 | `contest_judge_outbox` claim 조회 | 1,400 | 480,066 | 207,523 | 라이브 채점 파이프라인, 무관 |
| 4 | `scoreboard_applied_at` UPDATE | 479,907 | 479,907 | 0 | PK UPDATE, 1건당 1행이 정상 |

(전체 top-15는 `docs/scoreboard-recovery-experiment/followup/report2/digest-top15.txt`.)

`findReplayRowsByContestIdNewestFirst`의 실제 SQL(digest에서 복원, `digest-replay-query-full-text.txt`):

```sql
SELECT s1_0.id, csr1_0.contest_id, s1_0.problem_id, s1_0.user_id, c1_0.start_time, s1_0.submitted_time,
       COALESCE(csr1_0.final_result, csr1_0.provisional_result)
  FROM contest_submission_result csr1_0
  JOIN contest_submission s1_0 ON s1_0.id = csr1_0.submission_id
  JOIN contest c1_0 ON c1_0.id = s1_0.contest_id
 WHERE csr1_0.contest_id = ?
   AND (? IS NULL OR s1_0.id < ?)
   AND COALESCE(csr1_0.final_result, csr1_0.provisional_result) != ?
 ORDER BY s1_0.id DESC
 LIMIT ?
```

같은 대회(contest_id=1, 이 시점 286,753행)에 대해 세 변형을 `EXPLAIN ANALYZE`했다(원문 `explain-q*.txt`):

| 쿼리 | beforeId | 계획 | 실제 조사 행수 | 실제 시간(ms, Limit 단계) |
|---|---|---|---|---|
| Q1 newest-first, 첫 페이지 | NULL | `idx_csr_contest_submission(contest_id)` 인덱스로 전체를 읽고 → `contest_submission` PK로 nested-loop join → **Sort(전체) → Limit 1000** | 286,753 | 771 |
| Q2 newest-first, 중간 페이지 | 중간값 | `idx_csr_contest_result_submission(contest_id, provisional_result, submission_id)` + index condition(`submission_id < ?`)으로 남은 범위를 읽고 → 마찬가지로 join → **Sort(범위 전체) → Limit 1000** | 122,708 | 347 |
| Q3 ascending, 첫 페이지 | (afterId NULL) | Q1과 동일한 계획, ORDER BY만 ASC | 286,753 | 743 |

- **역방향 range scan이 아니다.** `submission_id`가 인덱스의 두 번째(또는 세 번째) 컬럼이라 정렬 순서 그대로 읽으며 1000행에서 멈출 수 있는 인덱스가 있는데도, 옵티마이저는 `contest_id`(와 있는 경우 `submission_id <` 조건)로 후보 전체를 인덱스 스캔한 뒤 `contest_submission`·`contest`와 조인하고, **그 다음에** `Sort`와 `Limit`을 적용한다. 페이지당 비용은 "이 페이지에 필요한 1000행"이 아니라 "beforeId보다 작은 남은 행 전체"에 비례한다.
- **원인은 `COALESCE(final_result, provisional_result) <> 'PENDING'`이 sargable하지 않다는 것이다.** 컬럼에 함수를 씌운 식이라 인덱스 조건으로 못 쓰고 Filter로만 평가되는데, 옵티마이저는 이 필터의 선택도를 신뢰할 수 없어 "인덱스 순서대로 읽으며 1000개 통과하면 멈추기"를 선택하지 않고 "후보를 다 모아 정렬 후 자르기"를 선택한다. 게다가 이 실험 데이터에서는 이 필터가 사실상 아무것도 거르지 않는다(대부분 이미 채점됨) — 그런데도 옵티마이저는 이를 활용하지 못한다.
- **첫 페이지가 느렸던 이유(보고서 1의 899 ms)**: newest-first에서 beforeId가 없는 첫 페이지는 상한이 전혀 없어 대회 전체(N행)를 다 읽어야 한다. beforeId가 생기는 이후 페이지는 "이미 지나온 만큼"만 줄어들 뿐 여전히 O(남은 행)이다. Q1(N=286,753, 771 ms)과 Q2(N의 약 43%인 122,708, 347 ms)의 시간 비율(0.45)이 행수 비율(0.43)과 거의 일치해, 페이지 비용이 "남은 행 수에 선형"이라는 설명과 정확히 들어맞는다.
- **N vs N²**: 페이지 하나의 비용이 O(남은 행)이고 페이지가 N/1000개 있으므로, 대회 하나를 처음부터 끝까지 재전송하는 총 비용은 O(N²/1000) — 대회 전체를 "페이지마다 다시 읽는" 것에 가깝다. 이번 run의 digest가 그 증거다: 275번의 호출로 39,039,842행을 조사해 272,357행을 반환했다(호출당 평균 반환의 143배를 조사). 대회가 자라는 도중(109,732 → 162,625행) 두 pass가 걸렸으니 실측 배율은 이보다 더 커질 수 있다.

### 7.3 Run B 대비 비교 (단일 run 대 단일 run, 배수 계산 없음)

| 지표 | Run B(현재 코드, 이전) | Run B(개선판, 이번) |
|---|---|---|
| `run.drained` / exit | False / 2 | **True / 0** |
| db-pending(부하 종료 시점) | 152(600초 뒤에도 안 비워짐) | 54 → drain 후 0 |
| 신규 반영 정지(`newResumedAfterFaultMs`) | 1,426 ms | 327 ms |
| tail 복귀(`tailReturnedAfterFaultMs`) | 10,362 ms | 8,283 ms |
| 복구 중 반영 지연 p50/p95(`during`) | 3,181 / 4,398 ms | 2,568 / 3,183 ms |
| `passesAfterRollback` | 2 | 2 |
| 1차 pass `replayRows` / `replayChunks` / `replayDurationMs` | 105,308 / 211 / 116,705 | 109,732 / 220 / 110,054 |
| Innodb_rows_read(fault→부하 종료) | 37,479,042 | 40,961,023 |
| verdict | C(backlog 1,774→2,320) | C(backlog 1,055→1,642) |
| `finalConsistent` | True | True |

두 run 모두 verdict=C(backlog가 fault에서 늘어남)로 같은 급이고, 반영 지연·tail 복귀는 이번 run이 소폭 낮지만 각각 단일 run이라 변동 범위 안일 수 있다 — 유의미한 개선으로 주장하지 않는다. 확실히 달라진 것은 **db-pending이 비워지고 drained=True로 끝났다는 것** 하나다.

### 7.4 tail 분해 (2부, `lifullreplay_r1_20260927014450`)

T_fault = `1790441392944`(ms). lost = 3,539건(스냅샷~롤백 사이). PASS_START(scoreboard-full-replay 스레드) = `1790441392848`(T_fault 96 ms 전 — 시계 불확실도 안). tail-poll.csv 기준 T_tail_returned = `1790441401227` → **T_fault+8,283 ms**(요약 CSV의 `tailReturnedAfterFaultMs`와 일치).

| 청크 | 시작(+ms) | lock 획득(+ms) | 종료(+ms) | lock 대기 | 보유 |
|---|---|---|---|---|---|
| 1 | 515 | 1,037 | 2,133 | 522 | 1,096 |
| 2 | 2,136 | 2,522 | 3,107 | 386 | 585 |
| 3 | 3,504 | 4,103 | 4,505 | 599 | 402 |
| 4 | 4,507 | 4,705 | 5,004 | 198 | 299 |
| 5 | 5,349 | 5,349 | 5,770 | 0 | 421 |
| 6 | 5,773 | 5,928 | 6,305 | 155 | 377 |
| 7 | 6,604 | 6,664 | 6,907 | 60 | 243 |
| 8 | 6,909 | 6,952 | 7,364 | 43 | 412 |
| 9 | 7,707 | 7,726 | 8,085 | 19 | 359 |
| 10 | 8,088 | 8,212 | 8,506 | 124 | 294 |

- T_tail_returned(+8,283)는 청크 10이 lock을 잡은 뒤(+8,212), 끝나기 전(+8,506) 사이에 찍힌다 — poller가 잡는 "present"는 청크 보유 구간 안에서 갱신된다는 보고서 1의 관찰이 이번 run에도 그대로다.
- 홀수 청크 앞의 간격(=페이지 쿼리 시간)은 611 ms(1페이지, PASS_START~청크1 시작) → 397 → 345 → 299 → 343 ms로, §7.2에서 확인한 "페이지 비용이 남은 행 수에 선형"과 같은 모양으로 줄어든다.
- 500건씩 10청크(5,000행)를 지나서야 3,539건이 다 돌아왔다 — newest-first로 가장 최근 id부터 훑지만, lost가 아닌 행(롤백 시점 backlog 등)이 섞여 있어 lost 전부를 담으려면 500건 단위로 몇 청크를 더 지나야 했다. 보고서 1(청크 1~12, 9청크 필요)과 같은 급의 현상이다.
- 원인 배분(보고서 1과 같은 틀로): lost가 여러 청크에 걸침(주 원인, 청크 4~10) · 페이지 쿼리(청크 1~9 앞의 간격 합 1,995 ms, 약 24%) · lock 경합(대기 합 2,106 ms, 약 25%) · 청크 보유(합 4,488 ms, 약 54%, 여기엔 실제 적용 비용과 §7.2의 페이지 조회 비용 일부가 섞여 있다 — 청크의 hold 구간은 SELECT+APPLY를 함께 재는 값이라 완전히 분리되지 않는다).

### 7.5 판단

1. **db-pending 수정은 효과가 있다.** 이번 run은 `drained=True`(exit 0)로 끝났고, db-pending은 두 pass의 `afterPass` drain을 거쳐 0으로 수렴했다. 이전 Run B가 exit 2로 끝난 원인(롤백이 되살린 id가 안 비워짐)은 재현되지 않았다.
2. **replay 페이지 쿼리는 N에 비례하지 않는다 — 페이지당 O(남은 N), 전체 재전송은 O(N²/pageSize)에 가깝다.** EXPLAIN이 보여주듯 인덱스 순서를 타고 1000행에서 멈추는 대신, 대상 범위 전체를 인덱스로 모아 조인·정렬한 뒤 자른다. 원인은 `COALESCE(...) <> 'PENDING'` 필터가 인덱스 조건으로 못 쓰이는 것과, 조인해야 하는 두 테이블(`contest_submission`, `contest`)이 있어 ORDER BY+LIMIT을 인덱스만으로 처리할 수 없다는 것이다.
3. **개선안(제안만, 프로덕션 코드는 고치지 않음)**:
   - "id만 먼저 뽑고 나중에 조인"(deferred join) 패턴으로 쿼리를 둘로 나눈다: ①`SELECT csr.submission_id FROM contest_submission_result csr WHERE csr.contest_id=? AND csr.submission_id < ? ORDER BY csr.submission_id DESC LIMIT 1000` — 이 서브쿼리는 `idx_csr_contest_submission(contest_id, submission_id)`만으로 인덱스 순서를 타고 1000행에서 멈출 수 있다(PENDING 필터가 없어도 됨). ②그 1000개 id만 `contest_submission`/`contest`와 조인해 나머지 컬럼을 채운다. 이러면 페이지당 비용이 O(pageSize)로 떨어진다.
   - PENDING 필터가 꼭 필요하다면 `COALESCE(final_result, provisional_result)`를 저장 생성 컬럼(generated column)으로 만들어 `(contest_id, effective_result, submission_id)` 인덱스에 포함시키면 sargable해진다. 다만 이번 데이터처럼 필터의 선택도가 낮다면(대부분 이미 채점됨) 위 deferred-join만으로 충분할 가능성이 크다.
4. **한계(2부)**
   - 단일 run이다. §7.3의 비교는 두 개별 run 사이의 차이일 뿐 배수·유의성을 주장하지 않는다.
   - drain(`repairPending`)의 lock 보유 시간은 로그가 없어 직접 측정하지 못했다(§7.1). SCARD 스냅샷과 pass 경계로 간접 추론했다.
   - 청크-lost 대응은 보고서 1과 같이 poller ~110 ms 간격 폴링에 기반한 근사다.
   - EXPLAIN ANALYZE는 대회가 286,753행으로 자란 뒤의 스냅샷 하나에 대한 것이다. pass 진행 중 대회가 계속 자라므로(109,732→162,625) 실제 각 페이지가 본 N은 이보다 작았을 수 있다 — 방향은 같지만 절대 수치는 근사다.
   - 첫 시도가 stale 컨테이너 때문에 마이그레이션에 실패해 재시도했다(§7 서두). 재현 절차에 주의가 필요하다.
