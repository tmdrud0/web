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
(2부 완료 후 추가)
