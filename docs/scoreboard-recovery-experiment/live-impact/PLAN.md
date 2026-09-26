# 복구 중 신규 처리 영향 실험 — 실험 계획 (C1)

**상태: harness 코드와 문서만 있다. 아직 한 번도 실행하지 않았다. 이 문서의 어떤 값도 측정값이 아니다.**

- 기준 브랜치 / commit: `codex/scoreboard-recovery-tradeoff` @ `d4647cd`
- 작업 브랜치: `codex/scoreboard-recovery-live-impact`
- 실행 방법·안전 규칙: [README.md](README.md)

---

## 1. 질문

포트폴리오 피드백: *"10만 건 전체 재구성이 6초면 왜 복구 설계를 두 번이나 했나?"*

6초는 **부하가 없을 때** 재구성 한 번에 걸린 시간이다. 이 실험은 그 숫자가 답하지 못하는 두 가지를 묻는다.

1. 대회가 진행 중이라 신규 유입이 계속되는 동안 Redis 스코어보드가 롤백되어 복구가 돌 때,
   **신규 처리(접수 → 채점 → 스코어보드 반영)의 처리량이나 반영이 실제로 줄어드는가?**
2. 롤백으로 잃은 결과(tail)가 스코어보드에 **돌아오기까지 얼마나 걸리는가?**

같은 harness로 다음 run들을 돌린다. 모드는 `-Mode` 파라미터 하나로 바꾼다.

| run | 코드 | `-Mode` |
|---|---|---|
| Run A | 현재 코드 | `full-replay` |
| — | 현재 코드 | `redis-seq` |
| — | 현재 코드 | `stream-offset` |
| Run B | full-replay 개선판 (별도 작업 C2) | `full-replay` (C2 브랜치의 jar로) |

---

## 2. 고정 변수

| 항목 | 값 | 파라미터 | 비고 |
|---|---|---|---|
| 대상 대회 | 1개, run마다 새로 만든다 | — | 이름 `sbrec_<runId>_contest`, runId는 호출마다 고유 |
| 반복 | 모드당 1 run | — | 1 run이므로 분포가 아니다. 판정은 run 단위 |
| N | T_fault 시점에 대회의 판정 완료 결과 수, 약 10만 | `-TargetN 100000` | 실제 N은 `run.N`으로 기록한다 |
| seed 수 | `TargetN − JudgedRate × (Ramp/2 + Baseline + Tail)` | `-JudgedRatePerSecond` | ramp는 선형이라 절반만 센다. runner가 계산해 `run.seedCount`에 남긴다 |
| N_total | fault 시점에 판정 완료된 **모든 대회**의 결과 수 | — | `replayAllContests`가 스캔하는 행 수. `run.N_total` |
| seed 분포 | 참가자 1만 × 10문제, 결과 n → user (n mod U)+1, problem ((n div U) mod P)+1, 400‰ ACCEPTED | `-UserCount 10000 -ProblemCount 10 -AcceptPermille 400` | seed 행은 Snowflake worker 1023 (스택의 어떤 역할도 쓰지 않는 값) |
| 라이브 부하 사용자 | seed와 같은 1만 명 | — | 같은 대회의 참가자. feeder는 순환(`perf.feeder.circular=true`) |
| 유입률 | calibration으로 정한다. 목표 제출 1,000/s, 채점 약 850/s, 최대 3단계 | `-TargetRps`, `-SubmitIntervalMillis 10000` | 1,000/s × 10 s = 세션 1만 개 = 사용자 전원 |
| warm-up | Gatling ramp | `-RampSeconds 30` | 측정 구간은 ramp가 끝난 뒤부터 |
| baseline | ramp 뒤 정상 운전 구간 | `-BaselineSeconds 30` | 이 구간 끝에서 평탄 확인(§6.1) |
| tail 깊이 | 스냅샷 → 롤백 간격 | `-TailSeconds 5` | 잃은 건수는 결과로 기록한다(`lostCount`) |
| 복구 관측 | fault 이후 부하 유지 시간 | `-RecoveryBudgetSeconds 300` | 모든 모드에 같은 부하 길이 |
| 복구 뒤 관찰 | 복구 완료 뒤 최소 관찰 | `-ObserveAfterRecoverySeconds 60` | 모자라면 run은 불완결(exit 2) |
| oracle polling | 측정 구간에서 끔 | `-OraclePollSeconds 0` | 최종 정합성은 drain 뒤 digest 1회 |
| 청크 크기 | 500 (replay-batch-size 기본값), DB 페이지 1,000 | 설정 변경 없음 | `run.replayChunkSize` |
| 계측 | batch-1만 `contest.scoreboard.experiment.trace.enabled=true` | `compose.live-impact.yaml` | production 기본값은 off |
| DB | 스택 MySQL(`oj-loadtest-mysql`), pilot의 `oj_test`와 다른 인스턴스 | `-StackMySql` | `oj_loadtest`, 이 실험 전용 볼륨 `oj-loadtest-mysql-live-impact-data`(공유 볼륨 `oj-loadtest-mysql-data`에는 다른 브랜치의 V18이 적용돼 있어 분리), `compose.loadtest.yaml`이 커밋한 테스트 root 비밀번호로 자체 초기화. 컨테이너 CPU 2 / 메모리 2560M(`compose.yaml`의 `mysql` 서비스 `deploy.resources.limits`, `compose.loadtest.yaml`은 이름·환경변수만 바꾸고 한도는 바꾸지 않는다). 외부 DB 모드(`oj-test-mysql`, `oj_test`)는 대안으로 남아 있다 |

## 3. 지표 정의

모든 시각은 **컨테이너 시계**(Docker VM 커널 시계, batch JVM·MySQL·Redis 공통) 기준 epoch ms다. §4 참조.

### 3.1 시각

| 이름 | 정의 | 출처 |
|---|---|---|
| `T_snapshot` | 스냅샷 Lua가 실행된 Redis `TIME` | injector |
| `T_rollback` | 롤백 Lua가 실행된 Redis `TIME` | injector |
| `T_fault` | 롤백 후 batch-1 **resume** 시각 (`max(unpause 반환 시각, T_rollback)`) | runner |
| `T_detected` | `T_rollback` 이후 첫 `GAP`·`PASS_START`·`PASS_SKIPPED` 기록 | recovery-trace.csv. stream-offset의 rewind는 이 기록을 남기지 않으므로 `recovery-log-events.json`으로 보완 |
| `T_replay_start` / `T_replay_end` | `T_rollback` 이후 첫 `PASS_START`와 같은 스레드·같은 시작의 `PASS_END` | recovery-trace.csv. `replayThread`로 **감지 스레드인지 consumer 스레드인지** 기록 |
| `T_new_resumed` | `T_fault` 이후 첫 **신규** 반영 | live-apply.csv |
| `T_tail_returned` | 잃은 집합 L이 processed set에 **전부** 있는 첫 poller 판독 | tail-poll.csv |
| `T_backlog_drained` | backlog 최고점 이후 backlog가 baseline 최대치 이하로 돌아온 첫 초. 최고점이 baseline 이하면 `T_fault` | 요약기 |
| `T_recovered` | `max(T_fault + 10 s, T_tail_returned, T_backlog_drained)`. 어느 하나라도 없으면 측정 끝 | 요약기 |

### 3.2 "신규"

> **신규 = stream offset이 `H`보다 큰 이벤트.** `H`는 롤백 직전 스코어보드가 들고 있던 checkpoint
> (롤백 Lua가 삭제 전에 읽은 `contest:scoreboard:stream:offset`)이다.

- 모든 모드에 같은 정의를 쓴다. 어떤 코드 경로(라이브 consumer, 재구독, replay)가 적용했는지는 보지 않는다.
- `stream-offset`은 잃은 tail을 라이브 consumer로 다시 읽는다. `full-replay`도 재구독으로 스냅샷 checkpoint부터
  다시 읽는다(§7-4). 그 행들은 offset ≤ `H`이므로 **재소비**(`reconsumed`)로 따로 센다. 신규에 섞지 않는다.
- 한 제출의 "반영 시각"은 그 제출을 실은 **첫** live 행의 적용 시각이다. 뒤의 행은 중복이다.
- `ApplyResult`가 "새로 적용"과 "이미 적용"을 구분하지 않으므로(§7) 행 단위로는 구분할 수 없다. 첫 행 기준이 그 대체다.

### 3.3 backlog

`backlog(t) = |{s : judgedAt(s) ≤ t}| − |{s : firstApplied(s) ≤ t}|`, s는 **부하가 만든(seed가 아닌) 대회 제출**.

- 양변이 같은 집합이라 drain이 끝나면 baseline으로 돌아온다.
- seed 행은 스트림이 아니라 사전 rebuild로 들어가므로 양변에서 뺀다.
- fault 전 제출은 전부 fault 전에 첫 반영되므로 fault 뒤 증가분은 전부 신규 결과다.

### 3.4 신규 반영 정지 시간

`[T_fault, T_recovered]` 안에서 **(초당 채점 > 0) 이고 (초당 신규 반영 = 0)** 인 초.
합계(`newApplyStallTotalSeconds`)와 최장 연속 구간(`newApplyStallLongestSeconds`, 시작 `T_longest_stall_start`)을 낸다.

### 3.5 구간과 구간별 지표

| 구간 | 범위 | 반영·지연에 쓰는 이벤트 |
|---|---|---|
| before | `[측정 시작, T_snapshot)` | 모든 live 제출 |
| tail | `[T_snapshot, T_fault)` | 모든 live 제출 |
| during | `[T_fault, T_recovered)` | 신규만 |
| after | `[T_recovered, 부하 종료]` | 신규만 |

구간마다 초당 접수 OK/KO(Gatling 요청 **종료** 시각 기준), 초당 채점, 초당 반영, 반영 지연
(`firstApplied − judgedAt`, 채점 시각이 그 구간에 속한 제출) p50/p95/p99/max, 반영되지 않은 채점 수.

### 3.6 그 밖

| 지표 | 정의 | 없으면 |
|---|---|---|
| backlog 최대치·해소 시간 | `maxBacklogAfterFault`, `backlogDrainedAfterFaultMs` | — |
| Gatling OK/KO, ingress p95 | 측정 구간의 `api-contest-submit` | — |
| 복구가 읽은 MySQL 행 수 | 복구 pass의 청크 행 수 합(`replayRows`) — replay가 읽은 행이 곧 적용한 행이다 | `unavailable` (replay가 없는 모드) |
| MySQL 전체 행 읽기 | `Innodb_rows_read`의 fault→부하 종료 증분(부하분 포함, 참고치) | `unavailable` |
| Redis 명령 수 | `INFO commandstats` 호출 수의 fault→부하 종료 증분(전체, eval 등 명령별) | `unavailable` |
| batch GC | Prometheus `jvm_gc_pause_seconds_{sum,count}` 증분, `_max` | `unavailable` |
| 청크 | 수, 행 수, 락 대기(`lockedAt − start`)·보유(`end − lockedAt`) p50/max | `unavailable` |
| metadata | commit, 모드, N, N_total, seed 분포, 달성 유입률(요약기), lostCount, 두 pause 길이와 Lua 시간, 청크 크기, 컨테이너 CPU·메모리 제한, 시계 차이와 불확실성 | — |

측정하지 못한 값은 `unavailable`이다. **0으로 쓰지 않는다.**

## 4. 시계 정렬

| 출처 | 시계 | 변환 |
|---|---|---|
| live-apply.csv, recovery-trace.csv | batch JVM `currentTimeMillis` | 없음 (컨테이너 시계) |
| tail-poll.csv, injector | Redis `TIME` | 없음 |
| judged.csv | MySQL `COALESCE(final_judged_at, provisional_judged_at)` — judge JVM이 쓴 LocalDateTime | UTC로 읽음(`time_zone='+00:00'`). 스택은 UTC로 돈다 |
| Gatling simulation.log | **Windows 시계** | `+ gatlingClockOffsetMs` |
| `T_fault`, 부하 시작·종료 | Windows 시계 | `+ gatlingClockOffsetMs` |

`gatlingClockOffsetMs`는 run 시작 때 Windows에서 `docker exec … SELECT UNIX_TIMESTAMP(NOW(6))`를 7번 재고
왕복이 가장 짧은 표본의 중점으로 정한다. 불확실성은 그 왕복의 절반(`clockOffsetUncertaintyMs`)이다. 같은 방법으로
Redis `TIME`도 재서 MySQL−Redis 차이(`clockMySqlMinusRedisMs`)를 남긴다. 0에서 벗어나면 두 컨테이너 시계가 다르다는 뜻이다.

**한계**: Gatling 시계열과 `T_fault`는 ±불확실성만큼 흔들린다(보통 수십 ms). 1초 bin에는 충분하지만
100 ms 단위 비교에는 쓰지 않는다. judge JVM의 `judgedAt`과 batch JVM의 적용 시각은 같은 VM 시계라 이 보정이 필요 없다.

## 5. 가설 (측정 전이며, 결과가 아니다)

| | 모드 | 예상 |
|---|---|---|
| H1 | full-replay (현재) | 신규 반영이 replay 내내 멈춘다. backlog ≈ 유입률 × replay 시간. tail은 replay가 끝날 때 돌아온다 |
| H2 | redis-seq | 같은 동기 경로라 멈추지만 기간은 tail 후보 수와 탐지 round 수에 비례한다 |
| H3 | stream-offset | consumer 재시작과 tail 재소비 동안 멈춘다. 기간 = 재시작 고정비 + tail에 비례 |
| H4 | full-replay 개선안 (C2, §8) | 멈추지 않는다. 지연이 청크 단위만큼 늘어난다. tail은 첫 청크 근처에서 돌아온다 |

§7의 코드 확인으로 H1에 붙는 조건: replay가 **consumer 스레드**에서 돌 수 있고, 감지 스레드에서 돌면 그동안 들어온
배치는 거절 후 재구독으로 다시 읽힌다. 어느 쪽이든 "replay 동안 신규 반영 0"이라는 예측은 같다. 어느 스레드였는지는
`replayThread`가 답한다.

## 6. 판정 기준

### 6.1 run 전 게이트

- 정렬: 부하 전 per-user (solved, penalty) digest가 API와 MySQL에서 같아야 한다. 다르면 run 중단.
- baseline 평탄: baseline 끝에서 judge 큐 ready와 stream pending이 모두 `max(1000, 5 × JudgedRate)` 이하. 아니면 중단
  (`-AllowUnflatBaseline`으로 통과시키면 `run.baselineFlat=False`로 남는다).
- trace: batch-1 안에 trace 파일이 생겨야 한다(구버전 jar 거부).

### 6.2 A/B/C (요약기, 순서대로 판정)

| 판정 | 조건 (기본 임계값) |
|---|---|
| **C** 처리량 감소·backlog 지속 증가·반영 정지 | 최장 정지 ≥ 2 s, **또는** during 신규 반영/채점 < 0.90, **또는** fault 뒤 backlog 최고점 − max(baseline 최대치, fault 직전 초의 backlog) > baseline 채점률 × 1 s |
| **B** 지연만 증가 | C가 아니고, during 반영 지연 p95 > before p95 × 1.2 |
| **A** 변화 없음 | 그 밖 |
| `unavailable` | C가 아니고 before나 during에 지연 표본이 없음 |

fault 직전 backlog를 기준에 넣는 이유: 롤백 pause 동안 batch-1이 멈춰 있어 **모든 모드가** 그만큼의 backlog를 안고
복구를 시작한다. 그 backlog를 비우기만 한 모드를 "backlog 증가"로 판정하지 않기 위해서다. 같은 이유로 fault 직후 채점된
결과의 반영 지연에는 pause backlog를 비우는 시간이 들어가며, 그 몫은 세 모드에 같다(`faultPauseMs`로 읽는다).

live 행은 judged.csv에 있는 제출(실험 대회)만 센다. trace에는 consumer가 적용한 모든 대회가 기록되므로 다른 대회의 반영을
신규로 세면 실험 대회의 정지가 가려진다. 걸러낸 수는 `liveRowsOutsideContest`.

임계값은 요약기 옵션이며(`--stall-seconds`, `--throughput-tolerance`, `--backlog-tolerance-seconds`, `--latency-tolerance`,
`--min-during-seconds`) 결과 파일에 `threshold.*`로 같이 기록된다. Little의 법칙상 처리량이 같아도 지연이 늘면 backlog도
늘어나므로, backlog 허용치를 "baseline 1초 분량"으로 둔 것이 B와 C를 가르는 선이다.

### 6.3 run 완결성 (runner 종료 코드)

| exit | 의미 |
|---|---|
| 0 | complete: drain 완료, 최종 digest 일치, trace 누락 0, 복구 뒤 관찰 ≥ `-ObserveAfterRecoverySeconds` |
| 2 | 측정됐지만 불완결: 위 조건 중 하나가 깨짐. 수치는 버리지 않는다 |
| 1 | 실패: 수치 없음 (`failure.json`) |

### 6.4 calibration

steady 60 s, 복구 없음. `stable`: KO 비율 ≤ 1 %, backlog 기울기 ≤ max(1/s, 채점률 × 1 %), 달성 접수율 ≥ 목표 × 0.95.
아니면 `ko` / `backlog-growing` / `under-target`. 1,000/s에서 stable이 아니면 한 단계씩 낮춰 최대 3단계까지 돌리고
stable인 가장 높은 값을 쓴다. 그 run의 `calibration.judgedPerSecond`가 본 run의 `-JudgedRatePerSecond`다.

---

## 7. 코드 분석 검증 결과

작업 지시서의 코드 분석을 코드와 대조했다. ✅ 일치, ⚠️ 일부 다름, ➕ 지시서에 없던 사실.

| # | 지시서의 주장 | 확인 | 근거 |
|---|---|---|---|
| 1 | 롤백 감지 시 full-replay는 replay를 동기로 실행: `handleRollback → strategy.rebuildHistory → FullReplayRecoveryStrategy.rebuildHistory → gate.tryRun(MYSQL_REPLAY, replayAllContests)` | ✅ | `ContestScoreboardStreamLifecycle.java:361,375`, `FullReplayRecoveryStrategy.java:rebuildHistory` |
| 2 | `ContestScoreboardRecoveryPassGate.tryRun`은 동기 실행 | ✅ | `ContestScoreboardRecoveryPassGate.java:90` — CAS 후 호출 스레드에서 `pass.get()` |
| 3 | 그동안 라이브 배치는 `resolveAdvance → anchorAfterRebuild`에서 `rebuildHistory`를 다시 부르고, gate가 busy면 covers=false라 거절되고 재시도된다 | ⚠️ | 아래 7-3, 7-4 |
| 4 | 예상: replay가 끝날 때까지 신규 반영 0건 | ✅ (조건부) | 아래 7-2 |
| 5 | `replayAllContests()`는 DB의 모든 대회를 submission id 오름차순으로 스캔 | ⚠️ | 전역 id 순서가 아니다. `findDistinctContestIds()`(contest id 순, `ContestSubmissionResultRepository.java:16-17`)로 대회를 돌고, 대회마다 submission id keyset(`:86`), DB 페이지 1,000, 청크 500 |
| 6 | 청크마다 비공정 `ReentrantLock`(`ContestScoreboardApplyLock`) | ✅ | `ContestScoreboardApplyLock.java:18`, `ContestScoreboardReplayApplication.java:152` |
| 7 | 청크 = Redis EVAL 500번 + marker UPDATE 500행 | ✅ | `applyAll` 기본 구현이 요청마다 `apply`를 순서대로 부른다(파이프라인 아님, `RedisContestScoreboardApplier.java:82-95`). marker는 같은 락 안에서 별도 트랜잭션(`ContestScoreboardReplayApplication.java:167`) |
| 8 | `scoreboard_applied_at`은 비동기 보정되는 비권위 열 | ✅ | `ContestScoreboardAppliedAtCompletion`, calibration.md §8-4. 이 harness는 반영 시각에 쓰지 않는다 |
| 9 | `ApplyResult`는 "새로 적용"과 "이미 적용"을 구분하지 않는다 | ✅ | `ContestScoreboardApplier.ApplyResult(correlationId, appliedOffset, errorMessage)` |
| 10 | `ContestScoreboardUpdate`와 stream 메시지에 judgedAt이 있다 | ✅ / ➕ | 있다. 단 **replay가 만드는 update의 judgedAt은 `null`**(`ContestScoreboardFullReplayService.java:97`). replay가 적용한 결과에는 채점 시각이 없으므로 신규 반영 지연은 live 행으로만 계산한다 |

### 7-1. `deferred-harness-fixes.md`는 저장소에 없다

지시서가 조사 대상으로 든 `deferred-harness-fixes.md`는 `var/`(git-ignored)에 있다(README §8의 링크도
`var/deferred-harness-fixes.md`). 이 환경에서는 읽지 못했다. 그 문서가 기록한 결함 중 README·calibration.md에 옮겨진
것(항목 4·8·16: lostComplete 조건, `@(Get-RedisSetMembers)` 문자열 붕괴, 모드 이전 jar)은 반영했다.

### 7-2. replay가 도는 스레드는 감지 스레드로 정해져 있지 않다 ➕

롤백 직후 두 경로가 먼저 도착하는 쪽이 replay를 돈다.

- **supervisor**(`recoverConsumption`, 1 s fixed-delay 스케줄러 스레드): `handleRollback`.
- **consumer 스레드**: `resolveAdvance`는 `checkpoint < highestApplied`면 anchor를 지운다(`ContestScoreboardStreamProcessor.java:173`).
  그러면 checkpoint보다 큰 첫 배달이 `anchorAfterRebuild → strategy.rebuildHistory`(`:219, :265`)를 부르고,
  gate가 비어 있으면 **replay 전체가 consumer 스레드에서** 돈다.

부하 중에는 1초 주기보다 배달이 먼저 올 가능성이 높다. 어느 쪽이든 replay 동안 consumer는 새 배치를 적용하지 못한다
(consumer가 replay 중이거나, 아래 7-3처럼 거절 중이다). 그래서 "신규 반영 0" 예측은 두 경우 모두 같다.
어느 스레드였는지는 trace의 `PASS_START.thread`가 기록한다.

### 7-3. 거절된 라이브 배치는 브로커가 다시 주지 않는다 ⚠️

supervisor가 gate를 쥐고 있으면 `anchorAfterRebuild`가 `BUSY_RETRY_LATER`를 받아 `IllegalStateException`을 던진다(`:280`).
이 예외는 `applyBatch` **이전**이라 `recordUnappliedRange`가 불리지 않는다. listener의 `failBatch`는
`recordFailedBatch`, anchor 해제, **`retry-backoff`(1 s) park**, `ImmediateRequeueAmqpException`(`ContestScoreboardStreamListener.java:82,103,107`).
stream 큐는 requeue된 메시지를 실행 중인 consumer에 다시 주지 않는다. 따라서 "재시도"는 브로커가 아니라,
replay가 끝난 뒤 supervisor가 실패 배치를 보고 하는 **재구독**(`container.stop` → `startAt(storedOffset)`, `Lifecycle:334,338`)이다.
그 사이 도착하는 배치도 매번 같은 이유로 거절되고 1 s씩 park한다.

### 7-4. full-replay도 재구독으로 스냅샷 checkpoint부터 stream을 다시 읽는다 ➕

full-replay는 checkpoint를 움직이지 않으므로 재구독 위치 `storedOffset`은 **스냅샷의 checkpoint**다. 재구독한 consumer는
잃은 tail(offset ≤ H, replay가 이미 복원했으므로 script가 흡수)과 replay 중 거절된 배달을 모두 다시 읽는다.
그래서 full-replay에서도 offset ≤ H인 live 행이 fault 뒤에 나타나며, "신규"를 offset으로 정의해야 하는 이유가 된다(§3.2).

### 7-5. 재구독 전에 두 번째 replay가 돌 수 있다 (코드상 경로, 미검증) ➕

supervisor가 replay를 마치면 `markRebuiltThrough(H)`를 기록한다. 재구독 전에 consumer에 H+1보다 큰 배달이 오면
`lastLostOffset = firstDelivery − 1 > H = rebuiltThrough`이므로 `rebuiltAlready()`가 false이고, gate가 비어 있으므로
consumer 스레드에서 **두 번째 full replay**가 시작될 수 있다. `container.stop()`은 진행 중인 리스너 호출을 기다린다.
실제로 일어나는지는 `passesAfterRollback`(요약기)로 확인한다.

### 7-6. stream-offset의 첫 거절 배치 ➕

stream-offset의 `rebuildHistory`는 범위가 적용 이력 안이면(`lastLost ≤ H`) `RETRYABLE_FAILURE`로 거절하고 supervisor의
rewind를 기다린다. 첫 배달이 H+1이 아닌 곳에서 시작하면(offset이 연속이 아니거나 배치 경계가 다르면) 범위가 적용 이력
밖이라 **MySQL fallback replay**(`recoverRetentionGap`, MYSQL_REPLAY)가 consumer 스레드에서 돈다(`StreamOffsetRecoveryStrategy.java:77-90`).
H3의 "재시작 고정비"에 이 경로가 섞일 수 있으며, trace의 `PASS_START detail=mysql-replay`로 구분된다.

### 7-7. 기존 harness와 달라진 전제 ➕

| 기존 pilot | live-impact | 이유 |
|---|---|---|
| run 사이 `FLUSHALL`, stream 큐 삭제·재선언 | 하지 않는다. 대회 키·run 키만 지운다 | 이번 안전 규칙 |
| 오라클 digest는 순위 포함, 참가자 ≤ 200, user id 폭 < 1000 전제 (`Assert-OraclePreconditions`) | 참가자별 (solved, penalty) digest, 순위 없음 | 참가자 1만이면 두 전제가 깨진다 |
| seeder는 제출 행을 넣지 않는다 | worker 1023 Snowflake id로 판정 결과를 넣는다 | N≈10만을 API로 만들면 run당 수십 분 |
| key 단위 `docker exec` 캡처(pause 32–47 s) | 대회 키만 Lua `COPY` 한 번 | pause를 신규 처리 측정에서 분리 |
| 매 poll oracle 판독 | 측정 구간에서 끔 | pilot에서 run당 MySQL 4만–13만 행 |

### 7-8. injector의 비용 ➕

Lua 스크립트는 실행 동안 **Redis 전체**를 막는다(batch-1뿐 아니라 web의 세션·중복 제출 키도). 키 약 11만 개 규모에서
Redis CPU 제한 0.5가 걸려 있으므로 수백 ms~수 초일 수 있다. 두 pause 길이(`snapshotPauseMs`, `faultPauseMs`)와 Lua 시간
(`snapshotEvalMs`, `rollbackEvalMs`)을 기록하고, Gatling 시계열에서 그 구간의 ingress 흔들림은 injector 몫으로 읽는다.
세 모드 모두 같다.

---

## 8. full-replay 개선안 (C2) — Run B가 재는 코드

브랜치 `codex/full-replay-background-replay` (C1 `7416969`에서 분기). 설정
`contest.scoreboard.recovery.full-replay.rollback-replay=background|synchronous`, 기본 `background`.
`synchronous`는 C1 동작 그대로이며 같은 이미지에서 Run A를 재현할 때 쓴다.

### 8.1 무엇이 바뀌었나

| | C1 (synchronous) | C2 (background) |
|---|---|---|
| replay가 도는 곳 | 먼저 물은 스레드: consumer 또는 supervisor (§7-2) | 전용 스레드 `scoreboard-full-replay` |
| 라이브 경로의 답 | replay가 끝나야 COVERED. 그동안 거절(§7-3) | 요청을 넘기고 즉시 COVERED, anchor 후 계속 적용 |
| 재구독 | replay 뒤 스냅샷 checkpoint부터 다시 읽음(§7-4) | 거절이 없으므로 일어나지 않음(예상) |
| 범위 | DB의 모든 대회 | 롤백된 checkpoint 이상에서 이 JVM이 쓴 대회(`ContestScoreboardTouchedContests`). 범위가 적용 이력 밖이면 모든 대회 |
| 순서 | 대회 id 오름차순, 대회 안 submission id 오름차순 | 대회 id 내림차순, 대회 안 submission id **내림차순** — 잃은 tail(최신)이 첫 청크에 |
| 청크 | 500, 청크마다 apply lock | 같음 |

### 8.2 즉시 COVERED가 안전한 이유 (이 모드에서만)

1. 범위의 결과는 이미 MySQL에 있다(judge는 MySQL에 먼저 쓰고 stream에 publish한다).
2. 요청은 **요청이 받아들여진 뒤에 시작하는** pass로만 처리되고, 그 pass는 성공할 때까지 재시도된다. 진행 중인 pass가 두 번째
   롤백 전에 읽은 행은 믿지 않는다 — 두 번째 롤백은 다음 pass로 간다.
3. pass가 끝나기 전에 JVM이 죽으면, consumer를 켠 full-replay JVM은 startup replay가 필수이고(`ContestScoreboardRecoveryValidator`)
   그 replay가 끝나기 전에는 소비하지 않는다.
4. checkpoint가 범위를 지나가도 잃는 것이 없다. 범위의 결과는 stream이 아니라 MySQL에서 돌아온다.
5. 채점 규칙은 도착 순서에 대해 교환적이다(`RedisContestScoreboardApplierRedisIntegrationTests.lateEarlierAttemptsKeepCommutativeScoreboardRule`,
   `InMemoryContestScoreboardCommutativityTests`). 역순 replay와 라이브 적용이 섞여도 최종 순위는 같다.

**2번이 성립하도록 고친 것 (C2-fix, 방법 B).** 처음 구현에서는 2번이 요청이 background에 도달할 때만 참이었다. 즉시 COVERED를
받은 supervisor가 `markRebuiltThrough(H)`를 기록하므로, 새 적용 없이 다시 롤백되면 두 번째 범위의 top도 H라 `rebuiltAlready()`에서
걸러졌고, 같은 checkpoint면 `(checkpoint, H)` 중복 제거에도 걸렸다. 이제 background 모드는 `rebuiltAlready()`를 보지 않고 모든 질문을
background에 넘기며, background는 **아직 시작하지 않은 pass를 기다리는 요청끼리만** 합친다. pass가 시작된 뒤(또는 끝난 뒤)에 온
요청은 이름이 같아도 다음 pass를 예약한다. 방법 A(Outcome에 "예약됨"을 추가해 pass 완료 때 rebuiltThrough를 올림) 대신 B를 고른
이유: 두 번째 롤백과 첫 롤백에 대한 두 번째 질문은 offset 쌍으로 구분되지 않으므로 A도 결국 "시작 이후의 질문은 새 pass"라는 같은
규칙이 필요하고, A는 거기에 더해 세 모드가 공유하는 lifecycle·processor 경로와 Outcome 계약을 바꾼다. B는 background 모드 안에서
끝나고 synchronous·redis-seq·stream-offset 경로를 건드리지 않는다. 대가는 같은 롤백을 pass 시작 뒤에 다시 물으면 pass가 한 번 더
도는 것뿐이다(안전한 방향). **남는 한계**: supervisor는 이미 답한 `(checkpoint, H)` 쌍을 다시 묻지 않으므로, 새 적용 없이 **같은
스냅샷**으로 다시 롤백되면 누구도 묻지 않는다. offset만으로는 관측할 수 없는 경우이고 synchronous 모드에도 같은 한계가 있다
(synchronous는 pass가 끝난 뒤 rebuiltThrough를 기록하므로, pass 도중 다른 checkpoint로 다시 롤백된 경우도 걸러진다 — 이번 범위에서
바꾸지 않았다).

### 8.3 대회 범위의 근거

적용마다 stamp를 남긴다: live 이벤트는 자기 offset, replay 청크와 운영자 rebuild는 적용 시점의 checkpoint. 스냅샷 checkpoint가 S인
롤백이 무언가를 빼앗을 수 있는 대회는 스냅샷 **뒤에** 이 JVM이 쓴 대회뿐이고, 그런 쓰기의 stamp는 모두 ≥ S다. 그래서 "stamp ≥ S"는
하나 더 넣을 수는 있어도 빠뜨리지는 않는다. 이전 JVM이 쓴 것은 모르므로 적용 이력 밖의 범위는 모든 대회로 넓힌다.
startup replay가 모든 대회를 stamp하므로 재시작 뒤에도 빈틈이 없다.

### 8.4 Run B에서 확인할 것

- `replayThread` = `scoreboard-full-replay`, `newApplyStallLongestSeconds` < 2, `reconsumedAfterFault` ≈ 0(재구독 없음).
- `tailReturnedAfterFaultMs`가 첫 청크 시간 수준인가(H4). `replayRows`가 대회 하나의 행 수(N_total이 아니라 N)인가.
- `passesAfterRollback`: 부하 중에는 첫 배달이 먼저 묻고 anchor해 checkpoint가 H를 넘으므로 supervisor는 롤백을 보지 못해 1이 예상된다.
  supervisor가 먼저 묻고 pass가 시작된 뒤 첫 배달이 다시 물으면 2가 된다(C2-fix, §8.2). 2는 정상이며 3 이상이면 반복 롤백이나
  반복 질문을 의심한다.
- 청크 락 대기(`chunkLockWait*`)와 during 반영 지연 p95 — 판정 B의 크기가 여기서 나온다.

### 8.5 검증 상태

단위 테스트: 백그라운드 스레드에서만 replay, 즉시 COVERED, 같은 롤백 요청 병합, 실행 중 도착한 롤백은 다음 pass, 실패·gate 점유 시
재시도, 닫힌 뒤 거절, 범위 계산, 역순 페이지·대회 순서, live/replay stamp, 설정 바인딩. **MySQL·Redis 통합 테스트와 실제 run은 아직 돌리지 않았다.**
