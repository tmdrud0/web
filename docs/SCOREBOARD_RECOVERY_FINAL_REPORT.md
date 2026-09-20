# 최종 보고 — Redis 스코어보드 복구 3방식 (full-replay / redis-seq / stream-offset)

이 문서는 작업 완료 시점의 보고서다. 설계·경계는 [`ARCHITECTURE.md`](ARCHITECTURE.md) §3–§5,
폐기된 seq 설계와의 차이는 [`PORTFOLIO_SCOREBOARD_RECOVERY.md`](PORTFOLIO_SCOREBOARD_RECOVERY.md)를
본다.

**이 문서는 다섯 라운드로 이루어진다.** 1라운드(`0d36f26..60d98ec`)에서 세 모드를 구현했고, 그 결과에
대한 독립 검토(§9.1)가 **"세 모드가 실제로 분리되어 있지 않다"**는 지적을 포함해 14건을 냈다.
2라운드(`60d98ec..b98c83f`, §14)에서 그 지적과 함께 나온 다섯 결함을 고쳤다. 3라운드(`b815378..`,
§16)에서 **owner 설정이 실행 경계가 아니었던 것**, **busy recovery pass가 rollback 재시도를 잃던
것**, **JVM cold start에서 모드가 격리되지 않던 것** 세 결함을 고쳤다. 4라운드(`54c990e..aa979d2`,
§17)에서 **cutover 해제가 실패하면 consumer 시작이 영영 사라지던 것**과 **대기 중인 lifecycle이
Spring 종료 경로에 닿지 못하던 것** 두 결함을 고치고, 그 diff에 대한 읽기 전용 검토(§17.6)의 지적
중 이번 라운드가 만든 것만 반영했다. 5라운드(`aa979d2..`, §18)에서 **반쯤 실패한 start가 컨테이너를
재시도할 수 없는 상태로 남겨 4라운드가 근거로 든 "다음 주기 재시도"가 성립하지 않던 것**을 고쳤다.
**라운드별 서술과 뒤에서 정정된 서술을 구분해서 읽어야 한다** — 정정 대상은 각 절에 `[정정]`으로
표시했다.

3라운드가 왜 필요했는지는 §16.0에 한 문단으로 적었다: 앞의 두 라운드가 **rollback(Redis가 살아
있고 JVM도 살아 있는 상태에서의 회귀)** 만 다루었고, **JVM cold start**(JVM이 다시 뜨는 경우)는
어느 라운드에서도 검증되지 않았다. 2라운드 검토자가 "모드 격리 — clean"이라고 판정한 것도 그
rollback 경로에 대한 판정이며, **cold start 경로는 그 판정의 범위가 아니었다**(§15.1 정정).

## 1. worktree · 브랜치 · 기준 commit

| 항목 | 값 |
|---|---|
| 새 worktree | `C:\Users\Home\spring\web\web-scoreboard-recovery-tradeoff` |
| 새 브랜치 | `codex/scoreboard-recovery-tradeoff` |
| 기준 commit | `0d36f26481df6e75f2b22edd44b6f787ce6c7bc6` (`codex/contest-judge-stream-publish`, 계획의 예상값과 일치) |
| 1라운드 HEAD | `60d98ec` (기준 + 11 commit) |
| 2라운드 HEAD | `535855d` (기준 + 19 commit) |
| 검토 기준 HEAD | `7af681e` (기준 + 20 commit) — 읽기 전용 검토자가 본 지점 |
| 검토 수정 HEAD | `b98c83f` (기준 + 21 commit, 60 files, +5190 / −476) |
| 측정 지점 HEAD | `655838e` (기준 + 22 commit, 102 files, +10725 / −283) |
| 3라운드 기준 HEAD | `b815378` (기준 + 25 commit) — 3라운드 작업을 시작한 지점. 2라운드 문서 정정 commit 3건(`b6eff86`, `e8a5e8d`, `b815378`)의 마지막 |
| 3라운드 결과 commit | `ba25145` (기준 + 26 commit) — 세 결함과 검토 지적 수정. 소스 28 files, `+2004 / −144`(그중 신규 4 files = 435 lines) |
| 그 뒤 | 이 보고서·`ARCHITECTURE.md`의 문구·수치를 고치는 docs commit(§16.9) |
| 4라운드 기준 HEAD | `54c990e` (기준 + 27 commit) — 4라운드 작업을 시작한 지점 |
| 4라운드 결과 commit | `aa979d2` (기준 + 28 commit) — 두 결함과 검토 지적 수정. 소스 9 files, `+818 / −41`(그중 신규 1 file = 217 lines) |
| 그 뒤 | 이 보고서·`ARCHITECTURE.md`의 4라운드 절을 채우는 docs commit(§17) |
| 5라운드 기준 HEAD | `aa979d2` (기준 + 28 commit) — 5라운드 작업을 시작한 지점. 4라운드 문서 정정 commit의 마지막 |
| 5라운드 결과 commit | `04ab46b` (기준 + 29 commit) — 반쯤 실패한 start의 정상화. 소스 2 files(그중 신규 0), `+441 / −34` |
| 그 뒤 | 이 보고서·`ARCHITECTURE.md`의 5라운드 절을 채우는 docs commit `0001d8a`(§18), 그리고 최종 작업 트리 상태를 기록하는 docs commit(§18.4) |
| 작업 트리 | **3라운드 작업이 커밋되기 전에는 dirty였다** — 커밋 후 `git status --short`가 비는지는 §16.9에 적었다 |
| 원본 checkout | 건드리지 않음. `reset`/`clean`/강제 checkout 사용 안 함 |

마지막 한 commit(`655838e`)은 이 절을 측정값으로 채우는 commit이다 — 즉 위 수치는 그 commit 자신을
포함하고, `b98c83f..655838e`의 차이는 정확히 그 commit의 `+67 / −21`이다. 이것이 "clean 상태와 변경
파일 수는 보고서를 커밋한 다음에만 다시 기록한다"를 만족시키는 방식이다.

공개 API 변경 없음. **신규 의존성 없음**(`build.gradle` 무변경).

### 1.1 [정정] 작업 트리와 변경 파일 수

1라운드 보고서는 "작업 트리 | clean (미추적 파일 없음)"이라고 적었다. **그것은 사실이 아니었다** —
**이 보고서 자신이 미추적 상태**였고, 1라운드의 실물 통합 테스트 2건도 미추적이었다(그중 하나는
직후 `bcb4e03`으로 커밋됐고, 검토자 2.2가 stale로 판정한 항목이다). "clean"은 커밋된 파일만 세면
참이지만, 변경 파일 수를 보고하면서 보고서 자신을 빠뜨리면 그 수가 틀린다.

이 절의 값은 **이 보고서가 커밋된 뒤에** 다시 측정해 기록했다(요구사항: clean 상태와 변경 파일 수는
보고서를 커밋한 다음에만 다시 기록한다). 아래 첫 측정은 보고서를 커밋한 `535855d`에서, 최종 값은
검토 지적을 수정한 `b98c83f`에서 측정했고 그 측정 자체는 이 절을 채우는 `655838e`에 들어간다.

| 측정 | 값 (`535855d` 기준) | 값 (**`b98c83f`** 기준) |
|---|---|---|
| 작업 트리 | clean (미추적 파일 없음) | **clean — `git status --short`가 아무것도 출력하지 않는다** (미추적 파일 없음) |
| `7af681e..HEAD` (검토 이후) | — | 17 files changed, 1485 insertions(+), 124 deletions(−) |
| `60d98ec..HEAD` (2라운드) | 59 files, 4664 insertions(+), 456 deletions(−) | 60 files changed, 5190 insertions(+), 476 deletions(−) |
| `0d36f26..HEAD` (전체) | 101 files, 10171 insertions(+), 281 deletions(−) | 102 files changed, 10679 insertions(+), 283 deletions(−) |

이 표의 값을 적는 commit(`655838e`)이 그 자체로 diff를 하나 더 만든다 — 그 commit은 이 보고서 한
파일만 바꾸므로 `b98c83f..655838e`는 `+67 / −21`이고, 최종 HEAD에서 다시 세면 2라운드는 60 files
+5236/−476, 전체는 102 files +10725/−283이 된다(위 §1의 표가 그 값이다). §5.2·§8.2의 수치는 그래서
`b98c83f` 기준으로 표기하고, 그 뒤에 오는 **보고서 문구만 고치는 commit들은 포함하지 않는다** —
그 commit들은 코드·테스트를 바꾸지 않으므로 "무엇이 구현되었는가"의 수치가 아니라 "이 글을 몇 번
고쳤는가"의 수치이기 때문이다. 세 수치 모두 **보고서 자신을 포함한다** — 그것이 1라운드의 "clean"이
틀렸던 바로 그 지점이다.

## 2. 감사 결과 (요구 14항목: 이전 → 이번 작업 후)

| # | 감사 항목 | 이전 상태 | 이번 작업 후 |
|---|---|---|---|
| 1 | 채점 결과 MySQL 저장 | 구현 완료 | 그대로 |
| 2 | 정상 Redis 반영 경로 (단일 Lua EVAL) | 구현 완료 | 그대로 (세 모드가 같은 스크립트·같은 `applyAll` 공유) |
| 3 | 중복 적용 방지 | 구현 완료 | 그대로 + redis-seq에 seq 축 추가 |
| 4 | **미채점(PENDING) 행 재생 차단** | **미구현** (필터 없음, 검증 테스트는 fixture 결함으로 우연 통과) | **3개 재생 경로 전부 차단** (full-replay·rebuild는 쿼리에서, redis-seq는 후보 수집 시) |
| 5 | Redis seq 발급 + DB 저장 | **미구현** (`redis_seq` 읽는 코드 0건, 스키마만 잔재) | 구현 (`scoreboard_applied_seq`, V18) |
| 6 | 중복 seq / lost-tail 감지 | **미구현** | 구현 (중복 → lost-tail → 재생 순, DB 먼저 읽기) |
| 7 | redis-seq 검사 주기·범위·batch 설정 | **미구현** | 구현 (9개 값, `@Validated`+`@Min`, scheduler·기동 검사가 pass gate 공유) |
| 8 | RabbitMQ Stream consumer | 구현 완료 (AMQP 0-9-1 + `x-stream-offset`) | 그대로 |
| 9 | offset 저장 위치·갱신 원자성 | 구현 완료 (같은 EVAL) | 그대로, 실물 브로커로 재확인 |
| 10 | 재시작 소비 offset 결정 | 구현 완료 | + `startup-offset`(stored/first) 노출, 실제 소비 지점 연결. **2라운드: 재구독 인자를 checkpoint 자신으로 정정**(§14-2) |
| 11 | batch 중간 실패 처리 | 구현 완료로 보였으나 **실제로는 재시도가 없었음** | 실측 후 수정 (§4-3). **2라운드: 미적용 구간 계약 추가**(§14-3) |
| 12 | retention gap 처리 | **의미가 다름** (fallback이 모든 대회 Redis를 reset) | **비파괴 full-replay fallback** + `none` 스위치, 원인은 지표·ERROR 로그 |
| 13 | 전체 rebuild/replay 서비스 | 존재하나 파괴적 → full-replay 부적합 | full-replay 신규(비파괴), rebuild는 운영 endpoint·최종화용으로 유지 |
| 14 | 설정-실행경로 연결 / 잘못된 값 validation | recovery 항목 전부 없음, `@Validated` 전무 | recovery 15개 값 전부 실제 호출 경로 연결, 잘못된 `mode`·`@Min`·`@PositiveDuration` 위반은 **기동 실패** |

## 3. 각 방식이 이전에 갖추지 못한 것

- **full-replay** — 존재하지 않았다. 유일한 "전량 재적용" 수단인 `ContestScoreboardRebuildService`는
  첫 줄에서 `reset(contestId)`을 호출하는 **파괴적** 경로였고(요구 "Redis를 초기화하지 않는다" 위반),
  엔티티 hydration + top-N 조회였다.
- **redis-seq** — seq 발급자도, 검사기도, 설정도 없었다. 스키마에 `redis_seq` 잔재와 죽은 분류
  문자열만 남아 있었다.
- **stream-offset** — 기전 자체는 살아 있었지만 두 곳이 요구와 달랐다: ① retention gap fallback이
  reset을 포함했고, ② batch 실패 시 "requeue로 head에 남아 재시도된다"고 **설계·주석·지표 설명이
  모두 잘못** 적혀 있었다(실측 결과 재시도 자체가 일어나지 않음, §4-3).

**2라운드에서 추가로 드러난 것** — 세 모드는 **구현되어 있었지만 분리되어 있지 않았다**. rollback
감지·재구독 supervisor가 `contest.scoreboard.stream.consumer.enabled`만 보고 동작해 `full-replay`·
`redis-seq`에서도 stream offset 기반 복구가 **먼저** 수행됐다. 그 상태에서는 세 전략 비교가 성립하지
않는다(§10 [정정], §14-1).

## 4. 실제로 구현한 동작

**모드 스위치** — `contest.scoreboard.recovery.mode` = `stream-offset`(기본) | `full-replay` |
`redis-seq`. 모드별 빈은 `ContestScoreboardRecoveryStrategyConfig`가 `properties.mode()`에 대한
**exhaustive switch**로 정확히 하나 만든다 — 모드를 추가하면 컴파일이 깨진다.
`ContestScoreboardRecoveryValidator`는 `@ConditionalOnProperty`가 **원시 문자열**을 비교하는 반면
enum 바인딩은 관대하다는 사실 때문에 비정규 표기(`FULL_REPLAY`)를 거부한다(거부하지 않으면
`mode=full-replay`로 보고하면서 아무 모드도 실행하지 않는 상태가 된다). `redis-seq` + `store != redis`도
기동 실패, 기본 조합(`stream-offset` + `store=memory`)은 정상 기동.
**2라운드**: 모드가 "역사 복구 결정" 자체를 소유한다(`rewindsOnCheckpointRegression` /
`rebuildHistory`) — §14-1.

**full-replay** — `reset` 미호출, swap 없음. `findReplayRowsByContestId`가 projection +
keyset(`order by csr.submission.id`, 상위 N이 아니라 끝까지)을 **한 쿼리**로 가져오고,
`coalesce(final, provisional) <> 'PENDING'`이 SQL에서 미채점을 거른다. `ApplyRequest.rebuild`는
offset이 null이라 **체크포인트를 움직이지 않고**, 이미 반영된 행은 Lua `sismember`가 흡수한다(멱등).
잠금은 batch 단위로만 잡는다. **2라운드**: 적용이 DB 트랜잭션 밖으로 나갔다(§14-4).

**redis-seq** — seq는 Lua **한 EVAL 안에서** 발급한다: `KEYS[7]` 할당자를 `GET` → `allocator+1`,
이미 매핑이 있고 그 값 이상이면 `mapped+1`로 올리고 → `SET KEYS[7]` + `HSET KEYS[8]`. `INCR`가
아니며 gapless DB sequence는 도입하지 않았다(비목표). 이미 `processed`인 제출은 `sismember`에서
**먼저 반환**하므로 새 seq를 받지 않고, 응답값은 stream offset 그대로여서 호출자가 `KEYS[8]`에서
되읽는다. DB 영속화는 `scoreboard_applied_at = COALESCE(...)`와 `scoreboard_applied_seq =
COALESCE(?, ...)`를 한 배치 UPDATE로 쓴다. 한 회차는 **중복 검사 → lost-tail 검사 → 재생**, 모든
DB 읽기가 할당자 읽기보다 먼저 끝난다.

**stream-offset** — `startup-offset`(stored/first)은 lifecycle이, `retention-gap-fallback`
(full-replay/none)은 recovery service가 실제로 읽는다. `none`은 gap을 메우지 않아 모드의 basis가
`covered=false`를 돌려주고, batch가 적용되지 않아 checkpoint가 전진하지 않는다. 원인은
`stream.offset.gaps` + ERROR 로그로 확인된다.

**실측으로 드러난 수정** — 실물 브로커에 `basic.reject(requeue=true)`를 던져 보니 **오류 없이
수락되고 channel·connection은 열린 채로 남지만, 그 메시지를 실행 중 consumer에게 다시 주지
않는다.** 계획은 `NOT_IMPLEMENTED`로 연결이 끊기는 쪽을 예상했지만 실제 결과는 "아무 일도 일어나지
않음"이었고, 그대로 두면 consumer가 **영구히 멈춘다.** 그래서 재시도를 requeue가 아니라 **저장된
checkpoint에서의 재구독**으로 구현하고(`recoverConsumption`이 롤백/실패 두 원인을 구분), 거짓이던
로그·지표 설명을 고쳤다. 재현: guard를 되돌리면 신규 실패 테스트만 실패하고 롤백·정상 테스트는
통과한다.

## 5. 변경한 파일

### 5.1 1라운드 (`0d36f26..60d98ec`)

- **main 신규 23**: `recovery/` 14 (mode·properties·validator·reporter·summary·full-replay
  service/runner·redis-seq service/scheduler/startup-check/metrics/config·store property),
  `ContestScoreboardApplyLock`·`AppliedMarker`·`SequenceSource`·`SequenceTracking`,
  `RedisContestScoreboardSequenceSource`, `ContestScoreboardStreamScheduleConfiguration`,
  projection 3종, `V18__contest_result_scoreboard_applied_seq.sql`
- **main 수정 20**: Lua script(+`KEYS[7]`/`KEYS[8]`/`ARGV[10]`), `RedisContestScoreboardApplier`,
  `ContestSubmissionResultRepository`(쿼리 4종), `ContestSubmissionResult`(매핑),
  `ContestSubmissionBatchExecutor`(타입 오버로드), stream listener/lifecycle/processor/
  recovery-service/metrics/tail-monitor, `ContestScoreboardRebuildService`(PENDING 필터),
  in-memory scoreboard 2종, `application.properties`(mode 1줄 + 주석)
- **test 신규 17 / 수정 12**: 모드 wiring·properties·summary·full-replay unit/redis·redis-seq
  unit/MySQL, lifecycle, batch-failure Rabbit, requeue 실측 probe, 모드 기동 2종 등
- **docs 2**: `ARCHITECTURE.md`, `PORTFOLIO_SCOREBOARD_RECOVERY.md`

### 5.2 [정정] 2라운드 (`60d98ec..b98c83f`) — 60 files, +5190 / −476

- **main 신규 11**: `recovery/` 7 (`ContestScoreboardRecoveryStrategy`,
  `StreamOffsetRecoveryStrategy`, `FullReplayRecoveryStrategy`, `RedisSequenceRecoveryStrategy`,
  `ContestScoreboardRecoveryStrategyConfig`, `ContestScoreboardRecoveryPassGate`,
  `ContestScoreboardReplayApplication`), `scoreboard/CheckpointAdvance`,
  `scoreboard/PositiveDuration`(+Validator), `stream/ContestScoreboardStreamPosition`
- **main 수정**: Lua(`ARGV[2]`가 전진 정책 토큰), `RedisContestScoreboardApplier`,
  `ContestScoreboardApplier`, `InMemoryContestScoreboardApplier`,
  `stream/ContestScoreboardStreamProcessor`(anchor gate + 미적용 구간 gate),
  `stream/ContestScoreboardStreamLifecycle`(모드별 분기·관측당 1회),
  `stream/ContestScoreboardStreamListener`, `stream/ContestScoreboardStreamMetrics`,
  `stream/ContestScoreboardStreamConsumerProperties`(`@Validated`+`@PositiveDuration`),
  recovery properties·validator·summary, full-replay service·runner, redis-seq
  service·scheduler·startup-check, `submission/support/ContestSubmissionBatchExecutor`,
  `application.properties` · 4개 role properties · `application-test.properties`(env override)
- **test 신규 8**: `ContestScoreboardRecoveryPassGateTests`, `ContestScoreboardRedisSequenceSchedulerTests`,
  `ContestScoreboardReplayApplicationTests`, `ContestScoreboardReplayTransactionBoundaryTests`,
  `ContestScoreboardStreamListenerTests`, `StreamPublishConfirmOrderRabbitIntegrationTests`,
  `ContestScoreboardStreamPartialBatchFailureRabbitIntegrationTests`, `testsupport/NoOpTransactionManager`
- **test 수정**: lifecycle(포함 재구독·모드별 분기), processor(anchor·미적용 구간 13건),
  mode wiring, full-replay 계열, redis-seq service, batch-failure Rabbit(confirm 동기화),
  stream Redis·Rabbit 통합
- **docs 3**: `ARCHITECTURE.md`(§3.1–3.5, §4, §5.1), `ENVIRONMENT.md`(§8, §8.1), 이 보고서

**검토 이후 추가분 (`7af681e..b98c83f`, 17 files, +1485 / −124)**

- main 수정 6: `ContestScoreboardRecoveryStrategy`(구간 양끝·파생 바닥), `StreamOffsetRecoveryStrategy`
  (fallback에 구간의 실제 위쪽 끝), `ContestScoreboardStreamProcessor`(`GapReason`), 
  `ContestScoreboardStreamLifecycle`, `ContestScoreboardStreamPosition`(`consumerRestarted`),
  `ContestScoreboardStreamListener`·`ContestScoreboardStreamRecoveryService`(로그가 실제 구간을 보고)
- test 신규 1: `ContestScoreboardRecoveryStrategyTests`(§15.2)
- test 수정 5: lifecycle·processor·listener·부분 실패 Rabbit 통합
- docs 2: `ARCHITECTURE.md`(§3.1 구간 계약·§3.2 `GapReason`·§4 불변식), 이 보고서(§15)

## 6. 주요 설정과 effective 기본값

`application.properties`에는 **mode 한 줄만** 선언하고 나머지는
`ContestScoreboardRecoveryProperties`의 `@DefaultValue`에 둔다(기본값을 한 곳에 모으기 위해).
기동 시 `ContestScoreboardRecoveryReporter`가 effective 값을 INFO 1회 남기며, 아래는 **실제 기동
로그에서 캡처한 문자열**이다.

```
mode=stream-offset store=memory startup-offset=stored retention-gap-fallback=full-replay
mode=full-replay   store=memory db-batch-size=1000 replay-batch-size=500 startup-replay-enabled=false
mode=redis-seq     store=redis  duplicate-check-interval=3600s lost-tail-check-interval=3600s
                   check-window-size=1000 max-windows-per-pass=10 max-iterations=5
                   replay-batch-size=500 retry-max-attempts=3 retry-backoff=50ms startup-check-enabled=false
```

### 6.1 [정정] 위 로그의 값은 기본값과 테스트 override가 섞여 있다

1라운드 보고서는 위 블록을 "effective 기본값"이라 부르면서, 괄호로 "redis-seq 줄은 테스트에서 검사
주기를 1h로, 기동 검사를 off로 준 값이다"라고만 적었다. **섞인 값을 한 표에 놓고 "기본값"이라 부른
것이 잘못이다.** 분리하면 다음과 같다.

| 프로퍼티 | 실제 기본값 (`@DefaultValue`) | 위 로그의 값 | 그 값의 출처 |
|---|---|---|---|
| `full-replay.startup-replay-enabled` | **`true`** | `false` | `ContestScoreboardRecoveryModeStartupRedisIntegrationTests`의 `@TestPropertySource` override |
| `redis-seq.duplicate-check-interval` | `30s` | `3600s` | 같은 테스트의 override |
| `redis-seq.lost-tail-check-interval` | `30s` | `3600s` | 같은 테스트의 override |
| `redis-seq.startup-check-enabled` | **`true`** | `false` | 같은 테스트의 override |
| 그 외 (`mode`, `startup-offset`, `retention-gap-fallback`, batch 크기, window, retry) | 표의 값 그대로 | 동일 | 기본값 |

override를 준 이유는 그 테스트가 세 모드를 **실제 ApplicationContext로 기동**해 계약만 확인하려 하기
때문이다 — 기본 주기로 두면 scheduler와 startup replay가 실물 인프라를 향해 실제로 돌아버린다.
기본값 자체는 `ContestScoreboardRecoveryPropertiesTests`·`ContestScoreboardRecoverySummaryTests`가
고정한다.

**`contest.scoreboard.store`는 `application.properties`에 없고 `memory`가 `matchIfMissing`이므로
그것이 실제 기본값**이고, `redis`는 운영 프로파일에서만 켜진다. recovery 값은 `Math.max` 클램핑
대신 `@Validated`+`@Min`/`@PositiveDuration`으로 **기동 실패**를 택했다(§14-5).

## 7. 실행한 테스트와 결과

**2라운드 최종 HEAD(`b98c83f`) 기준으로 다시 측정한 값이다.** 검토 지적을 수정한 뒤 재실행한
결과이며, 4개 tier 모두 초록이다.

| tier | 명령 | 결과 |
|---|---|---|
| 기본 (실물 MySQL) | `.\gradlew.bat test` | 100 class / **408 tests / skipped 38 / failures 0 / errors 0** (45s) |
| Redis (실물 Redis) | `... -DredisIntegration=true -DredisPort=16379` | 408 tests / skipped 13 / **failures 0** (56s) |
| RabbitMQ (실물 브로커) | `... -DrabbitIntegration=true` | 408 tests / skipped 31 / **failures 0** (1m18s) |
| 스코어보드·복구 한정 | `... --tests "*ContestScoreboard*" --tests "*Recovery*"` | 41 class / 192 tests / skipped 29 / **failures 0** (20s) |

skip은 전부 **시스템 프로퍼티로 gate된 클래스**다: ① `-DredisIntegration`가 필요한 실물 Redis
클래스(25), ② `-DrabbitIntegration`가 필요한 실물 브로커 클래스(7), ③
`ContestSubmissionMySqlBatchRewriteIntegrationTests`(6, 별도 환경변수 — 이번 작업과 무관한 기존 상태).
25+7+6=38이 기본 tier의 skip이고, Redis tier는 25가, Rabbit tier는 7이 풀린다.

**1라운드 대비 증가** — 354 → 408 tests. 늘어난 54건은 2라운드가 추가한 것으로, 새 계약(anchor,
미적용 구간, pass gate, 트랜잭션 경계, Duration 거부, 실물 부분 실패, 구간 양끝)을 각각 고정한다.
그중 9건(전략 테스트 8 + lifecycle 테스트 1)은 §15의 검토 지적에 대응해 **검토 이후에** 추가했다.
같은 이유로 기본 tier의 class 수는 99 → 100이다.

이전 라운드에 있던 `-DrabbitIntegration=true -DredisIntegration=true` 동시 tier는 **수정 후 다시
돌리지 않았다**. 그 두 플래그는 위 2·3행에서 각각 따로 실행했고, 동시 실행이 덮는 교차 구간
(실물 Redis + 실물 브로커를 함께 쓰는 클래스)은 Rabbit tier가 이미 포함한다 — 다만 "다시 돌려
같은 수치를 얻었다"고는 적지 않는다.

**실물 인프라로 검증한 것**
- MySQL: 기본 tier 전체가 `spring.datasource.url=jdbc:mysql://localhost:3306/oj_test` +
  `flyway.enabled=true` + `ddl-auto=validate`로 뜬다. 즉 모든 `@SpringBootTest`가 **실물 스키마로
  Flyway·엔티티 검증을 통과**한다. 직접 확인도 했다 —
  `flyway_schema_history`에 `18 contest result scoreboard applied seq success=1`,
  `contest_submission_result.scoreboard_applied_seq bigint NULL` + `idx_csr_scoreboard_applied_seq` 존재.
- Redis: `RedisContestScoreboardSequenceRedisIntegrationTests`, `ContestScoreboardFullReplayRedisIntegrationTests`,
  `RedisContestScoreboardApplierRedisIntegrationTests` 등 전부 green — full-replay가 **reset을 호출하지
  않고 processed set·키를 보존**하며 2회 재생 후 순위·요약이 불변임을 실물 Redis에서 확인.
- RabbitMQ: ① 저장 offset 이후 재소비(재구독 인자가 checkpoint 자신임을 결과로 증명),
  ② batch 중간 실패 시 checkpoint 미전진, ③ **실물 Redis에서 batch 중간 실패**(§14-6),
  ④ 미적용 구간 위 delivery 거부, ⑤ requeue 실측 probe, ⑥ **연속 발행의 순서 보존 여부 측정**
  (`StreamPublishConfirmOrderRabbitIntegrationTests`). 6건 모두 실물 브로커 green.
- redis-seq: 단위 + **실물 MySQL**(중복 그룹, lost-tail, window keyset, **미채점 제외**).

**실물로 검증하지 못한 것 (mock·단위까지만)**
- **Redis RDB 스냅샷 롤백 자체**는 주입하지 않았다(비목표). 롤백 감지·재구독은 lifecycle 단위
  테스트로만 덮는다.
- RabbitMQ **stream replication·failover**는 단일 노드라 검증 불가.
- 실제 배포 프로파일(`multi-web`/`multi-batch`)에서의 기동 계약은 미검증 —
  `store=redis` + `mode=redis-seq` 조합을 운영 프로파일로 띄워 보지는 않았다.
- **cross-JVM 중복 실행**은 검증할 수 없다(방어가 없다). §12.
- PENDING이 **실제로 DB에 저장되는 경로는 현재 코드에 없다**(판정 구현체 둘 다 항상
  `PARTIAL_ACCEPTED` 반환). 그래서 PENDING 필터는 오늘 도달 불가한 **방어 로직**이며, "운영 버그"가
  아니라 "잠재 결함"으로 보고한다. 다만 영구 무시 회귀는 실물 Redis 테스트
  (`unjudgedSubmissionsAreLeftForTheirRealJudgement`)가 고정한다.
- 미채점 제외 테스트의 판별력은 실측했다: 필터를 끄고 실행하면 해당 테스트가 실패(중복 그룹이
  3회차까지 남아 `duplicateGroups` 3, 기대 1)하고 나머지는 통과한다.

**성능·복구 시간은 측정하지 않았다.** 이 보고서 어디에도 그런 수치를 추정해 적지 않는다.

## 8. 생성한 commit

### 8.1 1라운드 (`0d36f26` → `60d98ec`)

```
60d98ec fix: keep the sequence check from replaying an unjudged result
bcb4e03 test: boot each recovery mode against the real application context
55e8dfb docs: document the scoreboard recovery modes and their boundaries
a63eb33 fix: fall back to a non-destructive replay after a scoreboard stream retention gap
c00e937 feat: detect duplicate and lost-tail scoreboard sequences
7b646e6 feat: persist the Redis scoreboard sequence on the judging result
7529279 feat: replay the contest scoreboard from MySQL without resetting Redis
2b327d5 fix: refuse a recovery mode spelling the mode's beans are not selected by
500f2cd fix: skip unjudged contest submissions when replaying the scoreboard
c307ceb refactor: lift the scoreboard processing lock out of the stream package
0f7bb14 feat: select the contest scoreboard recovery mode from configuration
```

### 8.2 2라운드 (`60d98ec` → `b98c83f`, 이후 보고서 정정 commit)

```
e8a5e8d docs: name the commit each diff figure was measured at
b6eff86 docs: label the tree-state measurements by the commit they describe
655838e docs: record the tree state and the discrimination the review fixes left
b98c83f fix: read the scoreboard recovery range from both of its ends
136440e docs: record the tree state the report describes
535855d docs: correct the scoreboard recovery report against the implementation
7af681e test: fail a scoreboard batch halfway through real Redis
dc008cc fix: declare the recovery owner in the two stream consumer tests
801e6fc fix: refuse a non-positive recovery duration at startup
a2c446b fix: apply the scoreboard replay outside the database transaction
326555f fix: keep a single recovery pass per JVM and declare the owner
30d3641 fix: anchor the scoreboard stream consumer at the stored checkpoint
274b389 refactor: decide scoreboard history recovery per mode
```

순서에 이유가 있다. 읽기 전용 검토자를 `7af681e`에 붙였으므로 보고서 정정(`535855d`)과 그 측정
기록(`136440e`)은 **검토 대상 밖**이고, 검토 지적 수정(`b98c83f`)은 검토 **뒤**에 온다. 이 보고서
§1.1의 최종 값은 그 `b98c83f`에서 측정했으며, 그 측정을 적는 커밋이 하나 더 뒤따른다(보고서를
커밋한 다음에만 clean 상태와 변경 파일 수를 기록하라는 요구사항).

## 9. 독립 검토 결과

### 9.1 1라운드 검토 (`0d36f264..60d98ec`)

읽기 전용 검토자를 붙여 `0d36f264..HEAD`(`60d98ec`) 전체를 검토시켰고, 지적 14건 전부를 판정했다.

**고친 것 (8건)**

| # | 지적 | 조치 |
|---|---|---|
| 1.1 | redis-seq 재생 경로 2곳에 PENDING 필터가 없음(잠재, 도달 불가) | `collect`에서 제외 + 실물 MySQL 테스트 + 판별력 실측 |
| 1.2 | `handledFailures`가 monitor 밖에서 읽힘 (CONFIRMED, low) | `volatile` + 사유 javadoc |
| 2.1 | `redis-seq.db-batch-size`가 **아무도 읽지 않는데 기동 로그가 effective 값으로 광고** | 프로퍼티·summary 필드 제거, 문서 표·테스트 정리 |
| 3.1 | wiring 테스트가 자기 mock을 등록해 "모든 모드에서 사용 가능" 주장이 **반증 불가** | 프로덕션 클래스를 등록하도록 수정 |
| 3.4 | redis-seq 경로에 PENDING 케이스 테스트 없음 | 1.1과 함께 추가 |
| 4.1 | 스크립트 javadoc이 `INCR`라고 설명 (실제는 `GET`+`SET`) | 실제 메커니즘으로 재작성 |
| 4.2 | writer javadoc이 "COALESCE하지 않고 덮어쓴다"라고 설명 (SQL은 `COALESCE(?, col)`) | SQL과 일치하도록 수정 |
| 4.3 | 실패 재구독 로그가 `storedOffset+1`을 찍지만 실제 인자는 `"first"`일 수 있음 | 실제 요청값을 로깅 |
| 4.4 | ARCHITECTURE가 PENDING 불변식을 "두 replay 경로"로 한정 | "세 경로 전부"로 수정 |

**조치 불필요로 판정 (5건)** — 1.3(window 예산 초과 시에도 두 검출기가 교대로 잡아 영구 은닉
불가), 2.3(빈 gating·conditional 오배선 없음), 2.4(**측정하지 않은 성능·복구 시간 추정 없음** —
이번 라운드도 같은 규율을 지킨다), 3.2(실물 테스트 실행 증거가 브랜치에 커밋되지 않음 — 코드 결함은
아니며 실행 결과는 이 보고가 증거다), 3.3(실물/mock 구분이 사실과 일치).

**2.2는 stale** — 검토 시점에 미추적이던 모드 기동 테스트 2건은 그 직후 `bcb4e03`으로 커밋됐다.

검토자가 **end-to-end로 확인하고 clean으로 남긴 것**: full-replay는 `reset` 호출이 없고, redis-seq는
모든 DB 읽기가 할당자 읽기보다 먼저 끝나고, stream-offset은 apply+offset이 한 EVAL로 원자적이다.

**그러나 이 검토는 핵심 결함 하나를 놓쳤다.** "세 모드가 실제로 분리되어 있는가"는 검토 관점에
없었고, `lifecycle`이 `properties.mode()`를 읽는 곳이 없다는 사실은 점검되지 않았다(§14-1). 이번
라운드의 검토 관점에 그것을 명시적으로 넣었다.

### 9.2 2라운드 검토 (`60d98ec..7af681e`)

읽기 전용 검토자를 별도로 붙여 `60d98ec..HEAD`를 검토시켰다. **검토 관점**: 모드 격리, offset
비연속성, 다중 인스턴스 실행권(그리고 그 부재), 트랜잭션 경계, 테스트 판별력.

검토 결과와 판정은 §15에 적는다.

## 10. [정정] 성능 비교 전 남은 준비 사항

1라운드 보고서는 "**완료**: 세 모드가 같은 전송·같은 Lua·같은 `applyAll`을 지나므로 비교의 공정성
조건은 확보"라고 적었다. **그것은 틀렸다.** 전송·Lua·`applyAll`을 공유하는 것은 사실이지만, 그
셋을 공유하는 것만으로는 공정성이 확보되지 않는다 — **모드가 실제로 분리되어 있지 않으면 세 모드가
비교 대상이 아니라 같은 것의 세 이름이기 때문이다.** 1라운드 시점에는 rollback supervisor가
`consumer.enabled`만 보고 동작해서, `full-replay`·`redis-seq`에서도 **stream offset 기반 복구가
먼저** 수행됐다.

**2라운드에서 확보한 것과 근거:**

| 확보한 조건 | 근거 (테스트) |
|---|---|
| 모드가 자기 복구 결정을 소유한다 — 되감기 여부가 모드별로 다르다 | `ContestScoreboardStreamLifecycleTests`(되감지 않는 모드가 consumer를 멈추지 않음), `ContestScoreboardRecoveryModeWiringTests` |
| 모드 선택이 exhaustive switch라 모드 추가 시 컴파일이 깨진다 | `ContestScoreboardRecoveryStrategyConfig` |
| 되감지 않는 모드가 돌려도 **복구 중 발행된 신규 결과를 잃지 않는다** | `ContestScoreboardStreamLifecycleTests`(live 적용이 계속됨) |
| 세 모드가 같은 write path를 지난다 | `ContestScoreboardRecoveryModeWiringTests`(프로덕션 클래스 등록) |
| 복구 pass가 한 JVM에서 겹치지 않는다 | `ContestScoreboardRecoveryPassGateTests` |
| replay가 DB 트랜잭션 밖에서 Redis에 쓴다 | `ContestScoreboardReplayTransactionBoundaryTests`(`isActualTransactionActive()==false` 단언) |
| 모드별 effective 값이 기동 로그로 고정된다 | `ContestScoreboardRecoverySummaryTests`(§6.1의 기본값/override 분리 포함) |

- **남음**: ① 운영 프로파일에서 세 모드 각각을 실제 배포·기동해 계약 확정, ② 복구 트리거를
  운영자가 재현 가능하게 만드는 절차 정리(장애 주입은 이번 범위 밖), ③ 동일 대회 데이터·동일
  이벤트 순서·동일 MySQL/Redis 형상으로 변수 통제, ④ **부하 조건에서의 성능·복구 시간 측정**.
- **이번 범위 밖으로 남긴 판단 3건**(전부 **지표 관측만** 한다): 전역 seq 매핑 해시의 누적 정리
  정책, window 포화 경보 임계, `scoreboard_applied_seq` 인덱스의 live 쓰기 비용.

## 11. 계획 대비 편차 (승인 범위 안, 보고 필요분)

### 11.1 1라운드

a. **`redis-seq.db-batch-size`를 계획 §3.1 목록에서 제외** — 계획 §3.4-7의 "선언만 하고 소비하지
   않는 프로퍼티를 만들지 않는다"를 근거로 삭제(검토자 2.1도 같은 지적).
b. **`ContestScoreboardContinuityRepair` 인터페이스 + exhaustive switch 팩토리를 도입하지 않음.**
   **→ 2라운드에서 `ContestScoreboardRecoveryStrategy` + exhaustive switch config로 되찾았다.**
   계획이 포기했던 안전장치가 이번에 들어왔다.
c. **실패 처리**는 계획 §3.4-4의 결론(requeue에 의존하지 않고 checkpoint에서 재구독)은 그대로
   따르되, **원인이 예상과 달랐다**(연결 종료가 아니라 조용한 미재전달).
d. `findSequencedRowsDescending`/`findRowsByAppliedSequences`는 **의도적으로 미채점 필터를 쿼리에
   넣지 않았다** — 이 walk는 sequence로 페이징하고 window보다 짧은 page를 "집합의 끝"으로 읽으므로,
   쿼리에서 행을 떨어뜨리면 아직 필요한 행 위에서 walk가 끝난 것처럼 보인다.

### 11.2 2라운드

e. **분산 실행권을 도입하지 않았다** — 계획의 사용자 결정대로 단일 owner 전제를 설정·기동 검증·
   문서로 강제했다. cross-JVM 중복 실행 위험은 §12에 남는다.
   **[정정, 3라운드]** "설정으로 강제했다"는 이 문장은 2라운드 시점에 **과장이었다.** 그때
   `owner.enabled`는 검증기·로그에만 반영됐고 트리거 빈은 그대로 등록됐다(§13.1.1). 설정이 실제
   실행 경계가 된 것은 3라운드다(§16.1). 그리고 3라운드가 고친 것도 그것뿐이다 — **단일 인스턴스
   실행은 여전히 강제되지 않는다.**
f. **`redis-seq`의 retention gap fallback은 `covered=false`로 요란하게 실패한다.** 메우는 척하지
   않는다 — 이 모드의 기준(중복 seq + lost-tail)은 Redis에 한 번도 적용되지 않은 이벤트를 찾을 수
   없다. 계획 §3.3의 "설계된 한계"를 구현으로 고정했다.
g. **미적용 구간 계약은 계획에 없던 것이다.** 계획의 "batch 중간 실패 테스트"를 쓰다가 드러난
   결함을 고치면서 들어왔다(§14-3). 계획보다 범위가 늘었고, 그 근거는 측정이다.
h. **`application-test.properties`에 env override를 추가**했다(`TEST_DB_URL`/`TEST_DB_USERNAME`/
   `TEST_DB_PASSWORD`, 기본값은 현행 유지). 기존 컨테이너를 계속 쓰는 결정을 깨지 않으면서 안전한
   DB로 돌릴 출구를 남기기 위해서다. **새 자격 증명은 커밋하지 않았다.**

## 12. 남겨야 할 미해결 상태

- **모든 구성원이 이미 `processed`인 중복 그룹, 또는 구성원에 미채점 행이 있는 중복 그룹은 고칠 수
  없다.** 전자는 `max-iterations`로 자른 뒤 `redis-seq.unresolved`로 보고되고, 후자는 후보 0건
  회차로 끝나 `unresolved`가 서지 않지만 `redis-seq.duplicates`가 0이 아닌 채로 남아 지표로
  드러난다. 미채점 행을 적용해 "해소"하려는 시도는 해소가 아니라 악화다 — 이 두 가지는
  `ARCHITECTURE.md` §3.4와 `ContestScoreboardSequenceRecoveryMySqlIntegrationTests`에 함께 적어
  두었다.
- **[신규] cross-JVM 중복 실행 방어가 없다.** §13.2.
- **[신규] live 연결 중 브로커가 offset을 건너뛰는 경우는 탐지하지 못한다.** `applied.offset` 지표와
  tail monitor의 `pending`을 맞춰 보는 것이 유일한 관측 수단이다(`ARCHITECTURE.md` §3.2).
- **[신규] 미적용 구간은 JVM 안에만 있다.** durable하지 않으며, 그것이 안전한 이유는 재시작한
  consumer가 checkpoint 포함 지점에서 재개해 그 구간을 다시 읽기 때문이다. JVM이 죽으면서 그 구간이
  사라지는 것 자체는 손실이 아니다.
- **[신규, 3라운드] 지원하는 장애 모델은 "JVM은 살아 있고 Redis만 RDB 스냅샷으로 되돌아간다"로
  고정했다.** 이 모델 밖의 시나리오 — **JVM이 죽었다가 다시 뜨는 cold start**(Redis도 함께 되돌아간
  경우), 여러 인스턴스가 동시에 뜨는 경우 — 는 코드가 격리를 주장하는 범위가 아니며, 그중 JVM cold
  start는 **부분적으로만** 다뤘다(§16.3·§16.4). 실물 브로커를 세운 cold start 기동은 이번에도
  실행하지 않았다(§16.7).
- **[신규, 3라운드] cold start에서 "복구 중 발행된 결과"를 실측하지 않았다.** 근거는 코드 경로와
  단위 테스트까지다 — 기동 pass는 stream offset을 쓰지 않고, consumer는 저장 checkpoint **포함**
  지점에서 재개하므로(§16.3) 재개 지점이 복구 중 앞당겨지지 않는다. 실물 브로커·실물 MySQL로
  재현한 증거는 아직 없다.
- **[신규, 3라운드 검토 반영] redis-seq에서 역사를 영원히 덮지 못하는 pass만 남으면 consumer가
  해제되지 않아 그 인스턴스는 아무것도 소비하지 않는다.** 대기 해제를 coverage 기준으로 맞춘
  결과이며(§16.3), 이 모드가 `UNRECOVERABLE`에 대해 내리는 판단과 같은 방향이다 — 메운 척하지
  않고 ERROR로 남는다. 운영자가 할 일(MySQL replay 또는 reset 기반 재번호)은 로그가 말한다.
- **[신규, 3라운드 검토 반영] full-replay runner의 gate 점유 분기는 ERROR만 남기고 해제를 보고하지
  않는다.** 그 분기에 도달하면 consumer가 영원히 대기하며, 유일한 출구는 재시작이다. 독립 검토는
  full-replay 모드에서 그 시점에 `MYSQL_REPLAY` gate를 점유할 경로를 찾지 못했다(다른 호출자인
  retention-gap fallback은 `stream-offset` 전용이다)고 보고했다 — **도달 불가로 보고된 잔여
  위험이며, 고치지 않았다.**

## 13. [신규] 실행권과 검증 환경의 신뢰 한계

### 13.1 실행권은 JVM 내부 전용이다

`ContestScoreboardApplyLock`과 `ContestScoreboardRecoveryPassGate`는 **JVM 내부 전용**이며 **전체
시스템 lock이 아니다.** 1라운드 문서·보고서가 이 둘을 "lock"이라고만 적어 마치 분산 실행권이 있는
것처럼 읽힐 여지를 남겼다. 정확히 적는다 — **분산 실행권(Redis lock, lease, DB advisory lock)은
도입하지 않았다.**

대신 단일 복구 owner 전제를 **명시적 설정 + 기동 검증 + 이 문서**로 강제한다.

- `contest.scoreboard.recovery.owner.enabled`(기본 `true`) — `application-batch-role.properties`는
  `true`, web·judge 역할은 `false`.
- stream consumer를 켠 JVM이 owner를 `false`로 선언하면 **기동 실패**(실제로 복구하는 인스턴스가
  선언 밖에 남는 것을 막는다), owner인데 트리거가 하나도 없으면(consumer off + `mode=stream-offset`)
  **기동 실패**.
- 기동 로그에 `recovery-owner=`가 함께 남는다.

### 13.1.1 [정정] owner 설정은 실행 경계다 — 그러나 "단일 실행"을 강제하지는 않는다

위 절의 "단일 복구 owner 전제를 … 강제한다"는 문장은 두 가지로 읽힐 수 있고, 3라운드 전에는 **한
쪽으로만 참이었다.**

1. **"owner 설정이 이 인스턴스의 복구 트리거를 실제로 켜고 끈다"** — 3라운드 전에는 **거짓이었다.**
   `owner.enabled=false`는 검증기(§13.1의 기동 실패)와 로그에만 반영됐고, `full-replay` 기동
   replay·`redis-seq` 기동 검사·주기 scheduler는 **그대로 빈으로 등록되어 실행됐다.** 즉 web 역할이
   `mode=full-replay`로 뜨면(로그·기동 검증은 "복구 안 함"이라 말하는데) 실제로는 replay를 돌렸다.
2. **"owner 설정이 시스템 전체에서 복구를 한 인스턴스로 제한한다"** — 여전히 **거짓이다.**
   분산 실행권이 없으므로(§13.2), 두 인스턴스가 모두 `owner.enabled=true`로 올바르게 선언하면
   둘 다 실행한다.

3라운드는 1번을 코드로 고쳤다: `ContestScoreboardRecoveryOwnerCondition`(`@Conditional`)을 세
트리거에 붙여, `owner.enabled=false`면 **빈 자체가 등록되지 않는다**(§16.1). 2번은 고치지 않았고
고칠 수 없다 — 그것은 배포 토폴로지의 문제이며 §13.2에 남는다. **"owner 설정으로 단일 실행을
강제했다"는 서술은 어느 라운드에서도 참이 아니었다.**

### 13.2 그 부재의 위험 (남은 위험)

**두 `batch-role` 인스턴스가 뜨면 두 인스턴스가 각자 복구 pass를 돌린다.** 런타임 방어가 없고,
`pass gate`는 같은 JVM 안에서만 유효하다. 결과:

- `full-replay`에서는 양쪽이 같은 MySQL을 replay한다 — 멱등이라 결과는 옳지만 `applyAll`이 apply
  lock을 두고 경합한다(JVM마다 다른 lock이므로 경합 자체가 직렬화되지 않는다).
- `redis-seq`에서는 **계약이 깨진다.** 이 모드는 "모든 DB 읽기가 할당자 읽기보다 먼저"를 전제로
  `seq > allocator`를 유실로 판정하는데, 두 인스턴스가 교차하면 한쪽이 읽은 할당자가 다른 쪽의
  in-flight 결과보다 낮아 **정상 결과를 유실로 오판하고 재적용**한다(멱등이라 데이터는 옳지만
  지표가 오염되고 불필요한 Redis 쓰기가 발생한다).
- `stream-offset`에서는 두 consumer가 같은 stream을 나눠 읽어 **offset checkpoint가 서로를
  앞지른다.**

배포 토폴로지(단일 `batch-role`)가 유일한 방어이며, 기동 검증은 **한 인스턴스가 자기 역할을 잘못
선언하는 것**만 막는다. 두 인스턴스가 모두 올바르게 선언하는 것은 막지 못한다.

### 13.3 [정정] `oj-test-mysql` 랜섬 사고와 검증의 신뢰 한계

작업 중 `oj-test-mysql` 컨테이너가 **모든 비시스템 DB를 다시 잃었다**(세션 중 1회, 이전에도 같은
사고). 조사 내용:

- `SHOW DATABASES` 결과가 `RECOVER_YOUR_DATA` + 시스템 스키마만 남음.
- 마지막 binlog 이벤트가 `DROP DATABASE \`oj_test\`` 였고, 시각은 테스트가 green이던 시점 몇 분
  뒤(07:01경).
- 랜섬 노트: `0.0105 BTC` 요구, `DATAID: 29NI1`, onionmail 주소.
- `root@%` 계정이 존재하고 비밀번호가 `application-test.properties`의 약한 `1234`이며, `3306`이
  호스트에 공개되어 있다.

1라운드 보고서는 "컨테이너는 **강화하지 않았다**(승인 범위 밖)"라고만 적었다. **그것이 이 보고서의
증거에 갖는 함의를 명시하지 않은 것이 빠뜨린 부분이다.**

**이 컨테이너는 침해된 것으로 간주한다.** 2라운드에서도 추가 검증을 위해 그 컨테이너를 **새로 쓰지
않았고**, 삭제·중지도 하지 않았다(사용자 결정). 다만 **§7의 MySQL 증거는 이 침해된 인스턴스에서
나온 것이다.** 따라서:

- 스키마·Flyway·커밋 순서·`ddl-auto=validate` 통과 같은 **구조적 사실**은 신뢰할 수 있다 —
  공격자가 그것을 위조할 이유도, 위조한 흔적도 없다.
- 그러나 이 환경에서 **"데이터가 정확히 이렇다"는 종류의 주장은 신뢰할 수 없다.** 이 보고서는 그런
  주장을 하지 않는다 — 모든 수치는 구조(스키마·제약·인덱스 존재)이거나 테스트 결과이며, "DB 내용이
  원래 이랬다"는 서술은 없다.
- 컨테이너를 폐기하지 않았으므로 **다시 지워질 수 있다.** 복구는 `CREATE DATABASE IF NOT EXISTS
  oj_test`로 DB를 다시 만들고 Flyway를 돌린 것뿐이다.

**권고**: 그 컨테이너를 폐기하고 ① named volume을 쓰고 ② 포트를 **loopback에만** 바인딩하고
③ root 원격 접속을 만들지 않으며 ④ 비밀번호를 비기본값으로 바꾼 새 컨테이너로 재구축할 것.
`ENVIRONMENT.md` §8.1에 실행 가능한 구성으로 적어 두었고, `application-test.properties`는 자격
증명을 커밋하지 않고 `TEST_DB_*` env override로 그 구성을 가리킬 수 있게 했다.

## 14. [신규] 2라운드 — 검토 지적과 다섯 결함

### 14-1 모드 격리

**결함.** rollback supervisor(`ContestScoreboardStreamLifecycle.recoverConsumption`)가
`contest.scoreboard.stream.consumer.enabled`만 보고 동작했고, `properties.mode()`를 읽는 곳은
lifecycle에 없었다. 그래서 `full-replay`·`redis-seq`에서도 **stream offset 기반 복구가 먼저**
수행됐다 — 세 모드가 비교 대상이 아니라 같은 것의 세 이름이었다.

**수정.** 모드가 "역사 복구 결정"을 소유한다. `ContestScoreboardRecoveryStrategy`가 두 질문에 답하고
(`rewindsOnCheckpointRegression()` / `rebuildHistory(LostRange)`), 모드별 빈은
`ContestScoreboardRecoveryStrategyConfig`의 **exhaustive switch**가 정확히 하나 만든다. 되감지 않는
모드에서 supervisor는 `container.stop()`을 하지 않는다 — live 연결이 유지되므로 복구 중 발행된 신규
결과는 replay에 흡수되지 않고 live 경로로 적용된다(cutover 근거, `ARCHITECTURE.md` §3.1). rollback에
대한 pass는 회귀를 관측한 `(storedOffset, appliedOffset)` 쌍당 **1회**만 돈다.

**판별력(실측).** `rewindsOnCheckpointRegression()` 분기를 제거하면
`ContestScoreboardStreamLifecycleTests`의 모드 격리 테스트가 실패하고 나머지는 통과한다.

### 14-2 offset 연속성 가정 제거

**결함.** 네 곳이 gap 없는 정수를 전제했다: Lua의 `streamOffset ~= currentOffset + 1`,
`ContestScoreboardStreamProcessor`의 `firstNew.offset() > startingOffset + 1L`,
`ContestScoreboardStreamLifecycle`의 `storedOffset + 1L`, 그리고 `InMemoryContestScoreboardApplier`.

**수정.** checkpoint는 **산술로 전진하지 않는다.** delivery가 실어온 offset으로만 움직이고, 확인하는
것은 단조 증가뿐이다. 재구독은 checkpoint **자신**을 포함해서 요청한다(`offsetValue(storedOffset)`).
Lua의 연속성 검사는 삭제하고 `ARGV[2]`를 **명시적 전진 정책 토큰**(`"continue"`/`"anchor"`)으로
바꿨다 — 전진 eligibility 판정이 Java gate로 옮겨갔으므로, 정책을 빠뜨린 호출자가 조용히 점프하는
대신 `error_reply`로 실패하게 만드는 것이다. 판정 규칙 표는 `ARCHITECTURE.md` §3.2.

**판별력(실측).** Lua의 gapless 검사를 복원하면 sparse offset 테스트가 실패한다.

### 14-3 [계획 외] 미적용 구간 — 두 번째 무음 유실

**계획에 없던 결함이다.** §14-6의 "batch 중간 실패" 실물 테스트를 쓰다가 드러났다.

**증상.** checkpoint가 아직 없는 상태(`-1`)에서 **실패한 batch 뒤에 도착한 delivery가 anchor로 그냥
채택**되어, 실패한 offset을 넘어서 checkpoint가 전진했다. 실패 기록만 남고 건너뛴 결과는 stream에도
standings에도 없었다.

**원인.** 리스너의 `clearAnchorVerified()`는 "다음 delivery를 질문으로 만든다"까지만 했고 **그 질문이
무엇에 대한 것인지**를 담는 상태가 없었다. 게다가 `resolveAdvance`에서 `checkpoint < 0` 채택 분기가
anchor 검증 여부보다 **먼저** 실행되므로, anchor를 지워도 그 경로는 그대로 통과했다.

**수정.** `ContestScoreboardStreamPosition`이 **미적용 구간**(`unappliedFrom`)을 든다. 실패한 batch는
자기가 멈춘 가장 낮은 offset을 기록하고(디코딩 실패면 batch의 첫 offset, 적용 실패면 applier가 처음
답하지 못한 요청의 offset = 그 delivery의 offset), gate는 그 구간 **위에서 시작하는** delivery를
① checkpoint가 없으면 **거부**(`contest.scoreboard.stream.unapplied.refusals`), ② checkpoint가 있으면
기존 retention-gap 질문으로 넘긴다. 구간은 **적용된 checkpoint가 그 구간에 도달했을 때만** 해제되고,
재구독은 의도적으로 해제하지 않는다.

**§15에서 정정된 부분.** ②의 "모드가 덮는다고 답할 때만 전진한다"는 그대로지만, 그때 모드에게 가는
구간의 **위쪽 끝**이 미적용 offset이 되면서 답이 달라진다 — 적용 시점에 기록되는 basis(`redis-seq`)는
**적용된 적 없는 offset을 찾을 수 없으므로 거부**하고, MySQL을 읽는 `full-replay`만 덮는다. 또 이
경우는 retention이 아니므로 `offset.gaps`를 올리지 않는다(`GapReason.UNAPPLIED`). 즉 이 절의 ②는
"질문으로 넘긴다"까지가 이 라운드의 내용이고, 질문의 **내용과 답**은 §15가 고정한다.

**판별력(실측).** 되돌려서 실측했다.

| 되돌린 구현 | 실패한 테스트 |
|---|---|
| gate(refusal 분기) | `aDeliveryAboveARangeAFailedBatchLeftUnappliedIsRefusedWhenThereIsNoCheckpoint`, `aDeliveryAboveTheRangeAsksTheModeWhenThereIsACheckpoint` |
| position의 구간 기록 | `aFailedApplyIsRecordedAsTheRangeTheCheckpointMayNotPass` |
| apply측 구간 해제 | `aDeliveryThatStartsAtTheUnappliedRangeIsAppliedAndReleasesIt` |
| listener측 실패 기록 | `ContestScoreboardStreamListenerTests` 2건 |

**정직하게 적어 둘 한계.** 게이트를 **단독으로** 되돌리면 실물 브로커 스위트는 **그대로
통과**했다(`BUILD SUCCESSFUL in 33s`). supervisor의 200ms 재구독이 poison과 후속 delivery를 한
batch로 다시 읽어버려 어느 쪽이든 적용이 없기 때문이다. 즉 **게이트의 통합 수준 판별력은
타이밍 의존**이고, 결정적으로 판별하는 것은 단위 테스트와 `offset-check-interval=1h`로 supervisor를
밀어둔 클래스의 refusal 테스트다. 이것을 판별력이 충분하다고 뭉개지 않고 그대로 적는다.

### 14-4 full replay 트랜잭션 경계

**결함.** `ContestSubmissionBatchExecutor.processBatchesOf`가 batch consumer 전체를 `REQUIRES_NEW`로
감쌌고, 그 consumer가 `scoreboardApplier.applyAll`(Redis Lua) + `appliedMarker.markApplied`
(Redis `HMGET` + JDBC)까지 실행했다. **Redis I/O가 DB 트랜잭션·connection 안에서** 수행됐다.
**Redis 쓰기는 롤백되지 않는다.** 반대로 redis-seq는 트랜잭션이 아예 없었다.

**수정.** `ContestScoreboardReplayApplication`이 두 replay 모드의 공통 3단계를 소유한다:
① chunk 조회(짧은 read-only) → ② **DB 트랜잭션 밖에서** `applyAll` → ③ 결과를 전부 검사한 뒤 marker를
**자기만의 짧은 트랜잭션**으로 기록. 순서가 핵심이다 — marker를 먼저 쓰면 MySQL이 scoreboard가 받지
않은 결과를 받았다고 주장하고, 그 marker를 믿는 pass가 그 위를 건너뛴다. marker 쓰기는 자체
bounds(3회, 50ms backoff)로 재시도하고, 최종 실패는 던지지 않고
`contest.scoreboard.recovery.marker.failed` + 로그로 남긴다.

**판별력(실측).** `REQUIRES_NEW`를 복원하면 `ContestScoreboardReplayTransactionBoundaryTests`가
실패한다(applier에서 `TransactionSynchronizationManager.isActualTransactionActive()`를 캡처해 false
단언).

### 14-5 설정 validation

**결함.** `RedisSequence`의 `duplicateCheckInterval`·`lostTailCheckInterval`·`retryBackoff`와
`ContestScoreboardStreamConsumerProperties`의 Duration 6개에 validation이 전혀 없었고,
`offsetCheckInterval`·`tailProbeInterval`은 clamp 없이 `addFixedDelayTask`로 전달됐다.

**수정.** Jakarta Bean Validation에 Duration용 제약이 없고 Spring Boot의 `@DurationMin`이 이
classpath에 없으므로 `@PositiveDuration` 제약을 직접 두고(`PositiveDuration` + Validator),
`ContestScoreboardStreamConsumerProperties`에 `@Validated`를 붙였다. 하한은 **1ms**다 —
`receive-timeout`·`tail-probe-quiet-period`의 기본값이 `50ms`이고 실물 테스트가
`retry-backoff=10ms`·`receive-timeout=20ms`를 쓰므로 초 단위 하한은 기존 테스트를 깨뜨린다.
`Math.max` 클램핑을 쓰지 않은 이유: check interval 0은 "그 주기로는 못 돈다"는 운영자의 요청이고,
다른 주기로 조용히 도는 것은 그것을 숨기는 일이다.

**판별력(실측).** `ContestScoreboardRecoveryPropertiesTests`의 Duration 거부 테스트가 위반을
**기동 단계에서** 잡는다.

### 14-6 batch 중간 실패 실물 테스트

**기존 테스트의 한계(정정).** 1라운드의 `ContestScoreboardStreamBatchFailureRabbitIntegrationTests`는
**decode 단계에서 먼저 실패**했다 — schema version이 맞지 않는 메시지를 넣었으므로 "첫 메시지 Redis
반영 성공, 두 번째 Redis 반영 실패"를 검증하지 못한다. 즉 "**중간** 실패"를 덮지 않았다.

**새 테스트가 덮는 범위.** `ContestScoreboardStreamPartialBatchFailureRabbitIntegrationTests` —
실물 Redis + 실물 RabbitMQ. 실패 주입은 mock이 아니라 **두 번째 메시지가 속한 대회의 scoreboard 키
하나를 다른 Redis 타입으로 미리 만들어 두는 것**이다. Lua의 `assertKeyType`이 `error_reply`를
반환하므로 **실물 Lua가 실물 Redis에서 거부**하고, 첫 write 이전에 실패하므로 checkpoint도 움직이지
않는다.

| offset | delivery | 남겨야 하는 것 |
|---|---|---|
| 0 | healthy | 적용됨, checkpoint가 여기로 이동 |
| 1 | healthy | 실패하는 batch 안에서 적용됨 |
| 2 | poison(타입 오염) | script가 거부, checkpoint는 그 아래에서 멈춤 |
| 3 | healthy | 시도되지 않음 — batch가 poison에서 멈춤 |
| 4 | healthy, 실패 **후** 발행 | 거부됨 — poison 위로 적용되지 않음 |

두 번째 메서드는 supervisor를 1시간으로 밀어두고 **checkpoint가 없는 상태**에서 그 거부를
결정적으로 재현한다(`contest.scoreboard.stream.unapplied.refusals`). 클래스에
`@DirtiesContext(AFTER_EACH_TEST_METHOD)`를 붙였다 — position의 미적용 구간과 meter 카운트가 둘 다
JVM-scoped이고 seeding으로 초기화되지 않으므로, 한 메서드가 남긴 상태가 다음 메서드의 측정 대상을
바꾼다.

### 14-7 [신규] 발행 순서는 confirm 없이는 보존되지 않는다

실물 브로커에서 **측정한** 성질이다. `convertAndSend`를 연속 호출하면 **offset 순서가 보존되지
않는다** — 다섯 건을 연속 발행했을 때 **첫 발행이 offset 0, 다섯 번째 발행이 offset 1, 두 번째·세
번째·네 번째가 2·3·4**를 받았다. 각 발행의 publisher confirm
(`CorrelationData.getFuture().get(...)`)을 기다리면 순서가 회복된다 — stream 큐의 confirm은
메시지가 log에 들어간 뒤 오므로, 다음 발행이 더 낮은 offset을 받을 수 없다.

이것이 테스트에 갖는 함의가 실질적이다: 순서를 전제하는 실물 테스트는 **confirm을 기다려야** 한다.
`ContestScoreboardStreamBatchFailureRabbitIntegrationTests`와 §14-6의 테스트를 그렇게 고쳤다. 그렇게
만든 성질(confirm을 기다리면 offset이 발행 순서대로 온다)은
`StreamPublishConfirmOrderRabbitIntegrationTests.aPublicationThatWaitsForItsConfirmIsGivenTheNextOffset`
이 고정한다. **confirm을 기다리지 않았을 때 순서가 깨진다는 성질 자체는 테스트로 고정하지
않았다** — 타이밍에 달린 관측이라 단언으로 만들면 간헐적으로 실패하는 테스트가 되기 때문이다. 그
관측은 이 보고서와 그 테스트의 javadoc에 기록으로 남긴다. 이 성질을 모르고 쓴 테스트는 간헐적으로
실패하며, 그 실패는 제품 결함처럼 보인다.

### 14-8 [신규] 판별력 실측 종합

| 대응 구현 | 그것을 되돌렸을 때 실패하는 테스트 | 수준 |
|---|---|---|
| `rewindsOnCheckpointRegression()` 분기 | lifecycle 모드 격리 테스트 | 단위 |
| Lua gapless 검사 | sparse offset 테스트 | 단위 |
| anchor gate(미적용 구간 refusal 분기) | processor 테스트 2건 | 단위(통합은 타이밍 의존, §14-3) |
| position의 미적용 구간 기록 | processor 테스트 1건 | 단위 |
| apply측 구간 해제 | processor 테스트 1건 | 단위 |
| listener측 실패 기록 | `ContestScoreboardStreamListenerTests` 2건 | 단위 |
| `REQUIRES_NEW` 래핑 | `ContestScoreboardReplayTransactionBoundaryTests` | 통합(컨텍스트) |
| `@PositiveDuration` | `ContestScoreboardRecoveryPropertiesTests` Duration 거부 | 단위 |
| pass gate | `ContestScoreboardRecoveryPassGateTests` | 단위 |
| 미채점 필터 | 실물 MySQL redis-seq 테스트 | 실물 |
| 구간 위쪽 끝 판정 `rebuiltAlready()` + `withinAppliedHistory()` (§15 지적 1·2) | `ContestScoreboardRecoveryStrategyTests` 4건 | 단위(실물 strategy) |
| `GapReason` 분류 (§15 지적 2) | `aDeliveryAboveTheRangeAsksTheModeWhenThereIsACheckpoint` | 단위 |
| 재개가 watermark를 쓰지 않음 (§15 지적 5) | `aRestartLeavesWhatThisProcessAppliedWhereItWas`, `theFirstRetainedOffsetIsUsedWhenTheCheckpointIsTheThingInDoubt` | 단위 |

되돌리기 실측은 각 항목을 **단독으로** 되돌려 해당 테스트만 실패하고 나머지가 통과하는지 확인하는
방식으로 했다. 유일하게 그 조건을 만족하지 못한 것이 §14-3에 적은 gate의 통합 수준 판별력이다.

**검토 지적에 대응해 추가한 3개 항목도 같은 방식으로 실측했다** (§15.1의 지적 1·2·5). 각각을 단독으로
되돌리고 `--tests "*ContestScoreboard*" --tests "*Recovery*"`(192건)를 돌린 결과다.

| 되돌린 구현 | 되돌린 내용 | 실패한 테스트 | 나머지 |
|---|---|---|---|
| 두 판정의 위쪽 끝 | `rebuiltAlready()` ← `rebuiltThrough >= firstLostOffset() - 1L`, `withinAppliedHistory()` ← `checkpointOffset < highestAppliedOffset` | 전략 테스트 4건 (요구된 "롤백 → 복구 → 두 번째 롤백" 포함) | 188/192 통과 |
| 재개가 watermark를 쓰지 않음 | `position.consumerRestarted()` ← `position.recordAppliedOffset(storedOffset)` | lifecycle 2건 | 190/192 통과 |
| `GapReason` 분류 | `UNAPPLIED` 분기 제거(미적용 구간이 `RETENTION`으로 분류되어 `offset.gaps`가 오른다) | processor 1건 | 191/192 통과 |

세 경우 모두 **되돌린 항목에 대응하는 테스트만** 실패했고, 실패 목록은 위 표의 것과 정확히
일치했다.

## 15. 2라운드 검토 (`60d98ec..7af681e`)

읽기 전용 검토자를 별도로 붙여 `60d98ec..HEAD`(`7af681e`)를 검토시켰다. 관점: 모드 격리, offset
비연속성, 다중 인스턴스 실행권(그리고 그 부재), 트랜잭션 경계, 테스트 판별력.

**검토자 판정: 다섯 관점 중 넷은 clean, 하나는 불완전.**
- 모드 격리 — clean. strategy가 주입되는 곳은 정확히 두 곳이고, 그 두 메서드가 유일한 모드 질의다.
  **[정정, 3라운드]** 이 판정은 **rollback 경로에 대해서만 참이다.** 검토 관점이 "모드가 자기 복구
  결정을 소유하는가"였고 그 질문은 supervisor·live delivery 두 지점에서 발생하므로, 검토자는 그
  두 지점만 보았다. **JVM cold start 경로는 이 판정의 범위 밖이었다** — 거기서는
  `SmartLifecycle.start()`가 `ApplicationRunner`보다 먼저 실행되어, 되감지 않는 두 모드에서도
  stream consumer가 모드의 기동 pass보다 먼저 읽기 시작했다(§16.3). 즉 cold start에서 모드 격리는
  **clean이 아니었다.**
- offset 비연속성 — clean. main 어디에도 `+1` 산술로 checkpoint를 전진시키는 곳이 없다.
- 다중 인스턴스 실행권과 그 부재 — clean. 문서·gate 해제(`finally`)·owner 기본값의 비대칭이 모두
  일관되며, 검증은 **안전한 방향으로만** 거부한다.
- 트랜잭션 경계 — clean. replay의 Redis 쓰기는 트랜잭션 밖이고, 남은 Redis 호출은 의도된 read다.
- **테스트 판별력 — 불완전.** 지적 5건.

### 15.1 지적과 판정

| # | 지적 | 심각도 | 판정 | 조치 |
|---|---|---|---|---|
| 1 | `rebuiltAlready()`가 **아래쪽 끝만 보고** "이미 재구성됨"을 결론낸다. `rebuiltThrough`는 단조 증가하고 `resumeAt`이 지우지 않으므로, stale watermark가 두 번째 롤백(또는 같은 RDB 스냅샷의 재적용)에서 replay를 **건너뛰게** 만든다 | high | **확인** | 두 판정이 모두 **구간의 위쪽 끝**을 읽도록 수정 (`rebuiltAlready()` = `rebuiltThrough >= lastLostOffset`, `withinAppliedHistory()` = `lastLostOffset <= highestAppliedOffset`) |
| 2 | checkpoint가 있는 미적용 구간 경로가 모드의 답에 전적으로 의존하고, 그 답은 checkpoint **아래** 구간에 대한 것이다. 모드가 true면 미적용 offset 41 위로 anchor되어 영구 미적용 + `offset.gaps` 오집계 | medium | **확인** | `GapReason`으로 이유를 분리(`UNAPPLIED`는 `offset.gaps`를 올리지 않는다). 위쪽 끝 판정과 합쳐져 `redis-seq`는 이제 그 구간을 **거부**한다 |
| 3 | 롤백 dedup 쌍이 `appliedOffset`이 커질 때마다 재무장되어, 실제 롤백 하나가 batch마다 로그·지표로 반복된다 | medium | **부분 인정** | 재무장 자체는 **더 깊은 롤백**에 대해 정당하다. "replay 없이 재구성됨"을 경고하던 **거짓 WARN은 지적 1의 결함**이며 거기서 고쳐졌다 |
| 4 | `LostRange` javadoc이 "두 호출자가 다른 `firstLostOffset`을 넘긴다"고 주장하지만 둘 다 `checkpoint + 1`을 넘긴다. 유지보수자가 processor를 "고치면" `rebuiltAlready()`의 임계값이 조용히 바뀐다. 또한 fallback이 존재한 적 없는 offset(`firstAvailableOffset - 1`)을 로깅한다 | medium | **확인** | 바닥을 `checkpointOffset + 1`로 **파생**시켜 우연을 구조로 바꾸고, fallback에는 구간의 실제 위쪽 끝을 넘기며, 로그는 실제 구간(`checkpoint+1` ~ `lastLostOffset`)을 찍는다 |
| 5 | `resumeAt`이 **consumer 위치와 적용 watermark를 혼동**한다. 모든 기동 경로가 이 메서드를 지나므로 non-rewinding 모드에서 watermark가 되감기고, 롤백이 잠시 가려지며 redis-seq의 `withinAppliedHistory()`가 뒤집히고 롤백이 retention gap으로 재분류된다 | low-medium | **부분 인정** | `resumeAt(long)`을 삭제하고 `consumerRestarted()`로 교체 — 재개는 watermark를 **쓰지 않는다**. 다만 실패 재구독 분기로는 도달하지 않는다(stored ≥ applied이고 그 쓰기는 watermark를 **올린다**). 실질 이득은 두 번째 Redis 복원 경우와, 필드가 문서화한 의미를 실제로 갖게 된 것이다 |

검토자가 지적 1의 판별을 위해 요구한 것은 "mock을 걷어내고 **실제** `FullReplayRecoveryStrategy`로
롤백 → 복구 → 두 번째 롤백을 구동하는 테스트 한 건"이었고, 그대로 추가했다(§15.2).

### 15.2 지적에 대응해 추가·수정한 테스트

- **`ContestScoreboardRecoveryStrategyTests` (신규, 8건)** — 세 전략을 **실물로** 구동한다. 기존
  lifecycle 테스트는 strategy를 mock하므로(지적 1·3이 구조적으로 보이지 않던 이유) 그 seam으로는
  판정의 **의미**를 물을 수 없다. 요구된 "롤백 → 복구 → 두 번째 롤백"(`replayAllContests()` 2회 단언),
  supervisor→live handoff(1회), 위쪽 끝이 적용 watermark를 넘는 구간을 `redis-seq`이 거부하는지,
  같은 구간을 MySQL basis는 덮는지, retention fallback이 실제 구간(5~12)을 받는지를 고정한다.
- **lifecycle** — supervisor가 넘기는 구간의 `lastLostOffset`을 단언하고, 새 테스트
  `aRestartLeavesWhatThisProcessAppliedWhereItWas`가 "재개 위치는 적용 watermark가 아니다"를 고정한다.
- **processor** — 다섯 테스트의 seed를 `resumeAt(N)`에서 `recordAppliedOffset(N)`으로 바꾸고(재개는
  적용이 아니다), 구간의 위쪽 끝을 단언한다. 미적용 구간 테스트는 `offset.gaps`가 **0**임을 함께
  단언한다.
- **실물 부분 실패 테스트** — await 조건을 `offset.gaps >= 1`에서 `failures >= 2`로 바꾸고,
  "retention 밖이었던 것이 없으므로 retention gap도 없다"(`offset.gaps == 0`)를 단언한다.

### 15.3 수정 후 재실행 결과

| tier | 총 | skipped | failed |
|---|---|---|---|
| `test` | 408 | 38 | 0 |
| `test -DredisIntegration=true -DredisPort=16379` | 408 | 13 | 0 |
| `test -DrabbitIntegration=true` | 408 | 31 | 0 |
| `test --tests "*ContestScoreboard*" --tests "*Recovery*"` | 192 (41 classes) | 29 | 0 |

§7의 399건에 신규 9건(전략 테스트 8 + lifecycle 테스트 1)이 더해져 408건이다.

### 15.4 검토가 확인하지 못한 것

검토자는 이번에도 **성능·복구 시간·비교 우위를 측정하지 않았고**, 이 보고서도 그 값을 추정하지
않는다. RDB 스냅샷 rollback 자체의 fault injection과 장시간 부하 테스트는 두 라운드 모두 범위 밖이다.
지적 1·2의 시나리오는 **단위 수준에서** 재현·고정했으며, 실물 브로커·실물 RDB 롤백으로 그 두 시나리오를
end-to-end로 구동한 증거는 없다 — 그 재현에는 RDB 복원 주입이 필요하고 그것은 범위 밖이다.

## 16. 3라운드 (`b815378..`) — 세 결함

### 16.0 왜 이 라운드가 필요했는가

앞의 두 라운드는 **rollback(Redis가 살아 있고 JVM도 살아 있는 상태에서의 회귀)** 을 다뤘다.
3라운드에서 고친 세 결함은 그 초점 밖에 있었다.

1. **owner 설정이 선언이었고 실행 경계가 아니었다** — 2라운드가 "설정·기동 검증·문서로 강제했다"고
   적은 그 설정은, 실제로는 검증기와 로그에만 반영되고 트리거 빈은 그대로 등록됐다(§13.1.1).
2. **busy recovery pass가 rollback 재시도를 잃었다** — 앞선 두 라운드가 추가한 재시도는
   "gate를 못 잡아 pass가 아예 돌지 않은 경우"를 "이미 답한 회귀"로 기록했고, 새 트래픽이 없으면
   다시 묻지 않았다.
3. **JVM cold start에서 모드가 격리되지 않았다** — 실패한 rollback 경로가 아니라 **기동 순서**의
   문제다. `SmartLifecycle.start()`가 `ApplicationRunner`보다 먼저 실행되므로, 되감지 않는 두
   모드에서도 stream consumer가 모드의 기동 pass보다 먼저 읽기 시작했다.

세 결함 모두 **코드로 고쳤고**, 각 수정은 그것이 없으면 실패하는 테스트를 갖는다(§16.5의 판별력
실측). 셋 다 이번 라운드의 작업 지시가 지목한 결함이며, 그 밖의 결함은 찾지 못했다(§16.8).

### 16.1 결함 1 — owner 설정을 실제 실행 gate로 만든다

**원인.** `contest.scoreboard.recovery.owner.enabled`는 2라운드에서 **검증기 입력**으로만 들어갔다.
`owner=false`여도 세 트리거 — `ContestScoreboardFullReplayStartupRunner`,
`ContestScoreboardRedisSequenceStartupCheck`, `ContestScoreboardRedisSequenceScheduler` — 는
`@ConditionalOnProperty(mode=...)`만 보고 빈으로 등록됐다. 그래서 web 역할이
`mode=full-replay`(또는 `redis-seq`)로 배포되면, 기동 로그와 기동 검증은 "이 인스턴스는 복구하지
않는다"고 말하는데 **실제로는 replay가 돌았다.** 즉 owner 설정은 "단순한 로그 선언"이었고, 2라운드
보고서의 "설정으로 단일 실행을 강제했다"는 서술은 그 지점에서 틀렸다.

**수정.** `ContestScoreboardRecoveryOwnerCondition`(package-private `Condition`, `owner.enabled`를
읽고 없으면 `true`)을 만들어 세 트리거에 `@Conditional`로 붙였다. 효과는 **빈 등록 자체가
사라지는 것**이다 — 로그 한 줄이 아니라 실행 경계다.

| 보장 | 근거 |
|---|---|
| `owner=false` ⇒ full replay 기동 runner 없음 | 빈 미등록(조건) |
| `owner=false` ⇒ redis-seq 기동 검사·주기 scheduler 없음 | 빈 미등록(조건). 모드의 service·metrics는 남는다 |
| `owner=true`인 지정 역할만 복구 트리거를 실행 | 세 트리거 모두 조건 통과 |
| 공용 full replay 서비스는 stream retention fallback에서 재사용 가능 | `ContestScoreboardFullReplayService`에 조건을 **붙이지 않았다.** 조건이 붙은 것은 트리거뿐이고, 서비스는 모든 모드에서 단일 빈으로 남는다 |

검증기는 "선언과 실제가 어긋나는 조합"을 계속 거부하되, 이제는 **실제 게이트와 같은 의미**로
정리했다. `rejectOwnerMismatch()`는 (a) consumer가 켜졌는데 owner=false, (b) owner=true인데
consumer가 꺼져 있고 `mode=stream-offset`(트리거가 될 것이 없는 owner) 두 방향을 거부한다.
cross-JVM 분산 lock은 이번에도 도입하지 않았다(범위 밖, §13.2).

**테스트.**

| 무엇을 고정하는가 | 테스트 |
|---|---|
| 세 모드 × owner true/false의 빈 등록 경계 | `ContestScoreboardRecoveryModeWiringTests.eachModeBringsUpItsTriggersOnlyOnTheInstanceThatOwnsRecovery` (6 context) |
| owner=false + redis-seq ⇒ 기동 검사·scheduler 없음, service는 남음 | `….anInstanceThatIsNotTheOwnerRegistersNoSequenceCheck` |
| owner=false + full-replay ⇒ 기동 replay 없음, service는 남음 | `….anInstanceThatIsNotTheOwnerDoesNotReplayAtStartup` |
| **배포되는 역할 파일 자체**로 같은 주장(web·judge는 트리거 0, batch는 있음) | `ContestScoreboardRecoveryRoleGateTests` 4건 — 실제 `SpringApplication` + `--spring.config.location=file:./src/main/resources/` |
| 모드만 바꾼 web·judge 역할도 트리거 0 | `….aRoleThatDoesNotOwnRecoveryRegistersNothingEvenInARecoveryMode` |
| owner 프로퍼티가 **없을 때** 조건의 기본값과 바인딩 기본값이 서로 같다(둘을 따로 읽으므로 어긋날 수 있다) | 조건 쪽: `….fullReplayModeIsTheOnlyOneThatReplaysAtStartup`(owner 프로퍼티 없이 runner 빈 존재). 레코드 쪽: `ContestScoreboardRecoverySummaryTests`의 `recovery-owner=true` 단언(owner 프로퍼티 없이) |

`ContestScoreboardRecoveryRoleGateTests`가 필요한 이유: 배포되는 파일이 주장의 대상이기 때문이다.
`multi-web`·`multi-judge`는 owner를 false로 선언하고, 그 파일을 편집해도 복구 pass가 조용히 뜨지
않아야 한다. 세 번째 역할(`multi-batch`)이 **양성 대조군**이다 — 같은 파일 집합·같은 빈으로 모드의
트리거가 실제로 올라오는 것을 확인하지 않으면, "트리거 없음"은 아무것도 올리지 않은 context에서도
통과한다.

**판별력 실측.** 조건을 제거(`@Conditional` 삭제)하면 위 경계 테스트들이 실패하고 나머지는
통과한다. 이 라운드에서 다시 확인했다.

### 16.2 결함 2 — busy recovery pass가 rollback 재시도를 잃는다

**원인.** supervisor pass는 회귀를 관측한 `(storedOffset, appliedOffset)` 쌍을 **"이미 답한
회귀"로 먼저 기록하고** 전략에 물었다. 그런데 `ContestScoreboardRecoveryPassGate`가 다른 pass에
잡혀 있으면 전략은 복구를 **하지 않고** 돌아왔고, 다음 supervisor 주기는 그 쌍을 "처리됨"으로 읽고
건너뛰었다. 새 stream 전달이 없다면 그 회귀는 **다시는 묻지 않았다** — 재구독을 하지 않는 두
모드에서는 checkpoint가 그 구간을 넘어가지 못하므로 쌍도 변하지 않는다. 결과적으로 full replay가
JVM 수명 동안 영원히 다시 돌지 않을 수 있었고, **gate 획득 실패와 실제 복구 실패가 같은 상태**가
됐다.

**수정 — 타입이 있는 결과.** `ContestScoreboardRecoveryStrategy.Outcome`을 도입했다.

| outcome | 뜻 | 호출자가 하는 일 |
|---|---|---|
| `COVERED` | 이 모드의 기준이 구간을 덮었다 | **rollback을 답한 것으로 기록**하고 `rebuiltThrough` 전진 |
| `BUSY_RETRY_LATER` | 다른 pass가 gate를 잡고 있어 **시도조차 하지 않았다** | 기록하지 않음. 다음 supervisor 주기에 재시도 |
| `RETRYABLE_FAILURE` | 시도했고 실패했다(창 소진, 예외, 부분 성공) | 기록하지 않음. 다음 주기에 재시도. `contest.scoreboard.stream.rollback.retry{outcome}` |
| `UNRECOVERABLE` | 이 기준으로는 원리적으로 못 찾는다 | 기억해 hot loop를 막되 **ERROR 로그 + `rollback.unrecoverable` 지표**로 요란하다 |

`recoverConsumption`은 `handleRollback`이 `true`(답했다)를 반환할 때만 쌍을 기록한다. 그런데
**`true`는 `COVERED`만이 아니다** — `true`가 나오는 경로는 셋이고, 셋의 "기록 이후"가 서로 다르다.

| 경로 | `true`인가 | 쌍 기록 | `rebuiltThrough` 전진 | 근거 |
|---|---|---|---|---|
| `COVERED` | 예 | 함 | **함**(`markRebuiltThrough(appliedOffset)`) | 기준이 구간을 덮었다. 답이 완결됐으므로 다시 묻지 않는다 |
| `UNRECOVERABLE` | **예** | 함 | **하지 않음** | 이 기준으로는 원리적으로 못 찾는다. 매 주기 같은 ERROR를 되풀이하는 hot loop를 막으려고 **답한 것으로만** 기억한다(ERROR 로그 + `rollback.unrecoverable`) |
| `BUSY_RETRY_LATER` / `RETRYABLE_FAILURE` | **아니오** | 하지 않음 | 하지 않음 | 시도조차 못 했거나(다른 pass가 gate 점유) 시도가 실패했다. 다음 주기에 다시 묻는다(`rollback.retry{outcome}`) |

되감는 모드(`stream-offset`)는 전략 분기에 들어가기 전에 재구독을 수행하고 `true`를 반환한다 —
재구독이 이 모드의 답이고 그것이 checkpoint를 움직이기 때문이다. `RETRYABLE_FAILURE`는 배치마다가
아니라 **다음 주기마다** 한 번이다(지표로 세어진다). 예외는 `RETRYABLE_FAILURE`이므로 **나중
재시도 가능성을 없애지 않는다.**

**"다음 주기에 실제로 다시 묻는가"의 근거**는 두 가지다. (a) 재시도 가능한 결과에서는 쌍이
기록되지 않으므로 `recoverConsumption`의 조기 반환(`storedOffset == answered… &&
appliedOffset == answered…`)이 성립하지 않는다. (b) 그 조기 반환은 컨테이너가 실제로 떠 있을
때만 도달하므로(`consuming` — 3라운드 당시의 필드명은 `running`이었다. 4라운드에서 대기 중인
lifecycle을 "시작됨"으로 보고하기 위해 이름과 의미가 갈렸다, §17.2) consumer가 살아 있는 한 주기마다
다시 묻는다. 새 stream 전달은 필요하지 않다 — 이 재시도는
**트래픽에 의존하지 않는 트리거**다.

**테스트.**

| 무엇을 고정하는가 | 테스트 |
|---|---|
| gate가 잡혀 있으면 `BUSY_RETRY_LATER`(복구된 것으로 보고하지 않음) | `ContestScoreboardRecoveryStrategyTests.aRangeAnotherPassIsAlreadyRebuildingIsNotReportedRebuilt` |
| 시도가 예외로 끝나면 `RETRYABLE_FAILURE`(기억하지 않음) | `….anAttemptThatThrewIsRetriedRatherThanRemembered` |
| 실패가 해소되면 **두 번째 시도가 실제로 돈다** | `….aRetriedPassCanRunOnceTheFailureHasCleared` |
| 답하지 못한 회귀는 다음 주기에 다시 묻는다 | `ContestScoreboardStreamLifecycleTests.aRollbackTheBasisCouldNotAnswerIsAskedAboutAgain` |
| 실패 재시도와 busy-gate skip이 **다른 지표**로 세어진다 | `….aFailedRebuildIsRetriedAndCountedApartFromABusyGate` |
| `UNRECOVERABLE`은 무한 replay하지 않고 error 상태로 남는다 | `….anUnrecoverableRollbackIsRememberedAndLeftAsAnError` |
| 재시도 상태가 두 모드(full-replay·redis-seq) 모두에서 전파 | 위 전략 테스트가 두 전략을 각각 구동한다 |
| `COVERED`가 아닌 outcome으로는 checkpoint를 전진시키지 않는다 | `ContestScoreboardStreamProcessor.anchorAfterRebuild`가 `!outcome.covers()`에서 던진다(+ processor 테스트) |

**판별력 실측.** `handleRollback`의 재시도 분기를 `return true`로 바꾸면(즉 재시도 가능한 결과를
답으로 기록하면) lifecycle 테스트 2건만 실패한다 — `aRollbackTheBasisCouldNotAnswerIsAskedAboutAgain`,
`aFailedRebuildIsRetriedAndCountedApartFromABusyGate`. 전략에서 `orElse(BUSY_RETRY_LATER)`를
`orElse(COVERED)`로 바꾸면 `aRangeAnotherPassIsAlreadyRebuildingIsNotReportedRebuilt` 1건만 실패한다.
예외 분기를 `COVERED`로 바꾸면 `anAttemptThatThrewIsRetriedRatherThanRemembered` 1건만 실패한다.

### 16.3 결함 3 — JVM cold start의 mode 격리 (결정 A + cutover 경계)

**결정: (A)를 택했다.** 즉 **지원하는 격리를 코드로 구현**하고, 지원하지 않는 구성은 기동에서
명시적으로 거부한다. "안전한 격리가 불가능하면 non-stream 모드 cold start를 지원하지 않는다고
선언"(B)하는 길은 택하지 않았다 — 격리가 실제로 구현 가능했기 때문이다.

**원인.** 세 모드를 가르는 축(§3.1 "모드는 복구 결정을 소유한다")은 **rollback 시점**의 질문에만
적용돼 있었다. cold start에는 축이 없었다. 그런데 기동 순서가 반대다 —
`SmartLifecycle.start()`(이 lifecycle은 `getPhase() = Integer.MAX_VALUE - 100`)는 context refresh
끝에 실행되고, 모드의 기동 pass는 `ApplicationRunner`라서 **그 뒤에** 실행된다. 그래서 JVM이 다시
뜨면 **되감지 않는 두 모드에서도** stream consumer가 먼저 저장 checkpoint에서 읽기 시작했고,
그 재소비가 두 모드의 기준(MySQL·seq)보다 먼저 역사를 메웠다. 모드 격리는 cold start에서
성립하지 않았다.

**수정 — 축을 하나 더 만들고, 그 축을 실제 경계로 만든다.**

1. `ContestScoreboardRecoveryStrategy.recoversHistoryBeforeConsuming()` — **역사 복구를 소비 전에
   자기 기준으로 수행하는 모드인가.** `stream-offset`만 `false`다: 이 모드의 역사 복구는 저장
   checkpoint에서 읽는 것 **그 자체**이므로 consumer를 붙잡으면 영원히 아무것도 소비하지 않는다.
   `full-replay`(MySQL)·`redis-seq`(seq)는 `true`.
2. `ContestScoreboardRecoveryCutover` — JVM 내부 경계. `whenCovered(action)`으로 대기하고
   `markCovered(coveredBy)`로 해제한다. lifecycle은 `true`인 모드에서 `start()`가 이 경계에
   대기하고, 모드의 기동 pass가 실제로 **완료됐을 때** 해제된다.
3. 해제를 보고하는 주체는 **그 모드의 기동 pass 자신**이다: full-replay는
   `ContestScoreboardFullReplayStartupRunner`의 replay가 돌아온 뒤, redis-seq는 scheduler의
   **끝까지 간** check(기동 check 포함, 매 trigger)가 끝난 뒤. gate에 잡혀 **돌지 않은** pass와
   예외로 **실패한** check는 해제하지 않는다 — 아무것도 복구되지 않았는데 consumer를 풀면
   되감지 않는 모드의 역사를 stream이 메우게 된다.

**cutover 경계의 계약(무엇을 잃지 않는가).** 대기는 **언제 소비를 시작하는가**를 옮길 뿐
**어디서 시작하는가**를 옮기지 않는다.

- consumer에 요청하는 offset은 **여전히 저장 checkpoint 자체(포함)** 다 — `next`도 브로커 tail도
  아니다. `startAtStoredOffset()`은 대기 전후에 같은 값을 읽는다.
- 기동 pass는 **stream offset을 쓰지 않는다**(`rebuild` 요청에 stream offset이 실리지 않는다).
  그래서 대기 중에 checkpoint가 앞으로 이동하지 않는다.
- 따라서 복구 중 발행된 결과와 기존 backlog는 재개 지점 **위**에 그대로 남고, broker가 그 구간을
  아직 retention에 갖고 있으면 재소비된다.
- 대기의 **정직한 비용**은 두 가지다: ① 기동 pass가 도는 시간만큼 retention 창이 줄어든다,
  ② 그 사이 stream에서 사라진 결과는 **평범한 retention gap**이 되어, live 경로가 그 모드의
  기준에 묻는다(§3.2). 새 메커니즘을 만들지 않았다.

**금지된 해법을 쓰지 않았다.** "데이터를 잃을 가능성이 있는 임의의 `next` 또는 broker tail
시작"은 어디에도 없다 — lifecycle의 시작 offset 계산은 이번 라운드에서 바뀌지 않았다.

**지원하지 않는 구성은 기동에서 거부한다.** 검증기의 `rejectAConsumerWithNoStartupRecovery()`는
**consumer가 켜져 있는데 그 모드의 대기를 해제할 수 있는 유일한 것이 꺼져 있으면 기동 실패**다 —
그 모드는 `full-replay` 하나다(`full-replay.startup-replay-enabled=false`). 이 모드에서 기동
runner는 경계를 보고하는 유일한 코드이고, retention-gap fallback은 **대기 중인 consumer를 통해야**
도달하며 운영자 rebuild endpoint는 다른 서비스를 쓰므로 경계를 보고하지 않는다. 즉 아무도 해제하지
않아 consumer가 영원히 멈춘다. 오류 메시지는 두 출구(기동 replay를 켜거나, 이 역할에서 consumer를
끄거나)를 함께 말한다.

`redis-seq`는 **같은 설정으로 거부하지 않는다.** 그 속성이 없애는 것은 첫 check이지 메커니즘이
아니다: scheduler는 `startup-check-enabled`와 무관하게 주기 task 둘을 등록하고(`configureTasks`에
그 속성을 읽는 곳이 없다), 대기는 consumer를 기다리게 할 뿐이므로 **한 주기 뒤 첫 check이 해제한다.**
`ContestScoreboardRedisSequenceStartupCheck`의 로그가 약속하는 것도 정확히 그것이다("the restored
scoreboard keeps whatever tail it lost until the next periodic check"). 거부하면 이 속성이
consumer를 켠 모든 역할에서 쓸 수 없게 되면서, scheduler 자신이 막고 있는 대체를 근거로 내세우게
된다. 초기 구현은 이 반쪽을 함께 거부했고 **독립 검토가 이를 medium으로 지적해 좁혔다**(§16.8).

**대기 해제는 "pass가 반환했다"가 아니라 "pass가 역사를 덮었다"로 판정한다.** 검토 지적을 받아
고친 두 번째 지점이다. redis-seq의 해제 조건은 `SequenceCheckReport.coveredTheWholeSet()`
(`!saturated && !unresolved`)이고, 이는 전략이 `COVERED`로 인정하는 기준과 **같은 기준**이다. 창
예산이 바닥나 tail을 끝까지 걷지 못했거나(`saturated`) 라운드를 다 썼는데 재생할 결과가 남은
(`unresolved`) pass가 대기를 풀면, 그 pass가 설명하지 못한 역사를 stream이 대신 메우게 된다 —
대기와 기동 check이 막으려던 바로 그 대체다. 그래서 consumer는 계속 기다리고 다음 주기가 다시
묻는다(라운드가 찾은 것을 재생하므로 다음 pass는 볼 것이 줄어든다). 그 대가도 정직하게 적는다:
**역사를 영원히 덮지 못하는 pass만 남으면 consumer는 해제되지 않고 인스턴스는 아무것도 소비하지
않는다**(ERROR로 남는다). 이는 이 모드가 이미 `UNRECOVERABLE`에 대해 내리는 판단과 같은 방향이다 —
메운 척하지 않고 요란하게 남는다.

`stream-offset`은 기동 pass가 없으므로 이 규칙의 대상이 아니다.

**테스트.**

| 무엇을 고정하는가 | 테스트 |
|---|---|
| `stream-offset` cold start는 저장 checkpoint에서 재소비한다 | `ContestScoreboardStreamLifecycleTests.consumptionResumesAtTheStoredCheckpointInclusive`, `theModeThatRecoversByReadingTheStreamIsNotHeld` |
| 되감지 않는 모드는 **pass 전에 아무것도 소비하지 않는다** | `….aModeThatRebuildsHistoryFromItsOwnBasisConsumesNothingUntilItsPassHasRun` |
| 해제 후 재개 지점은 대기가 시작된 checkpoint 그대로 | `….aHeldConsumerResumesAtTheCheckpointTheHoldBeganAt` |
| supervisor가 대기 중 consumer를 대신 시작하지 않는다 | `….theSupervisorDoesNotStartAHeldConsumer` |
| 종료 중 도착한 해제는 consumer를 시작하지 않는다 | `….aConsumerReleasedAfterShutdownIsNotStarted` |
| full-replay의 replay가 해제를 보고한다 / 돌지 않았으면 보고하지 않는다 | `ContestScoreboardFullReplayStartupRunnerTests` 2건 |
| redis-seq check가 역사를 **덮었을 때만** 해제한다(saturated도 unresolved도 아님) / skip된 trigger는 해제하지 않는다 | `ContestScoreboardRedisSequenceSchedulerTests` 3건(`onlyACheckThatCoveredTheHistoryReleasesTheHeldConsumer`, `aCheckThatDidNotCoverTheHistoryDoesNotReleaseTheHeldConsumer`, `aPassThatRanOutOfRoundsDoesNotReleaseTheHeldConsumer`) |
| 경계 자체의 순서·경합(등록 전 해제, 중복 해제, 64스레드 경합) | `ContestScoreboardRecoveryCutoverTests` 5건 |
| 대응하지 않는 구성은 기동에서 명확히 거부 | `ContestScoreboardRecoverySummaryTests.refusesAConsumerWhoseModesOnlyReleaseIsOff` + 양성 대조 3건(`allowsAConsumerWhoseRedisSeqStartupCheckIsOffBecauseThePeriodicChecksReleaseTheHold`, `doesNotRefuseASpellingTheConsumerConditionItselfTurnsDown`, `allowsAStartupPassTurnedOffOnARoleThatDoesNotConsume`) |
| **복구 중 발행분·기존 backlog가 조용히 유실되지 않는다** — 대기 중 checkpoint가 움직이지 않는다 | 재개 offset 고정: `….aHeldConsumerResumesAtTheCheckpointTheHoldBeganAt`(저장 checkpoint 포함 지점 그대로). 기동 pass가 offset을 쓰지 않음: `ContestScoreboardFullReplayServiceTests.replayContest_replaysStoredResultsWithoutResettingTheScoreboard`의 단언 `assertThat(requests).allMatch(request -> request.streamOffset() == null)` — "A rebuild request carries no offset, so a replay cannot move the stream checkpoint" |

**판별력 실측.** lifecycle의 대기 분기를 제거(`if (false)`)하면 새 테스트 4건만 실패한다
(`aModeThatRebuilds…`, `aHeldConsumerResumes…`, `aConsumerReleasedAfterShutdown…`,
`theSupervisorDoesNotStartAHeldConsumer`). startup runner의 해제를 조건 밖으로 빼면 1건, replay가
해제를 보고하지 않게 하면 1건이 실패한다. 검토 지적을 고친 뒤의 되돌림 실측은 §16.5의 아래쪽
네 줄이다(대기 해제 기준, 검증기 규칙 삭제, 검증기의 관대한 읽기, supervisor의 두 원인 독립).
`theModeThatRecoversByReadingTheStreamIsNotHeld`는 대기 제거에도 통과한다 — 그것은 **대기하지
않아야 하는 쪽**을 고정하는 대조군이다.

### 16.4 지원하는 장애 모델 (고정)

**지원하는 모델은 하나다: "애플리케이션 JVM은 살아 있고, Redis만 RDB 스냅샷 시점으로 되돌아간다."**

- 이 모델에서 세 모드는 서로 다른 기준으로 복구하고, supervisor pass가 rollback을 관측해
  모드별 pass를 트리거한다(§3.1). 재시도는 트래픽에 의존하지 않는다(§16.2).
- **JVM cold start는 이 모델에 포함되지 않는다.** 이번 라운드는 그중 **한 조각**만 다뤘다 —
  기동 순서 때문에 되감지 않는 모드가 stream 재소비로 복구되던 것을 막고, 그 격리가 **불가능한**
  구성(consumer on + full-replay의 기동 replay off)을 기동에서 거부하며, 대기 해제를 모드의
  coverage 기준과 일치시키는 것까지다(§16.3).
- **여러 인스턴스가 동시에 뜨는 경우도 포함되지 않는다.** 분산 실행권이 없고, 두 인스턴스가 모두
  올바르게 owner를 선언하면 둘 다 실행한다(§13.2). 배포 토폴로지(단일 `batch-role`)가 유일한
  방어라는 사실은 변하지 않았다.
- **Redis도 함께 되돌아간 cold start**는 검증되지 않았다. §16.7.

### 16.5 판별력 실측 종합 (3라운드)

새 테스트 각각에 대해 대응 구현을 되돌리고 **그 테스트만** 실패하는지 실측했다. 되돌린 뒤에는
원복하고 전체 DB-free 집합을 다시 통과시켰다(§16.6).

**위쪽 일곱 줄은 검토 전 구현에 대한 실측이고, 아래쪽 다섯 줄은 검토 지적을 고친 뒤 다시 실측한
것이다.** 아래쪽은 되돌린 상태와 원복한 상태를 각각 실행해, 실패가 **새 테스트에만** 국한되는지
확인했다.

| 되돌린 것 | 실패한 테스트 | 개수 |
|---|---|---|
| lifecycle의 대기 분기(`recoversHistoryBeforeConsuming()`) 제거 | lifecycle 4건 | 4 |
| startup runner가 무조건 해제 | runner `nothingIsReleasedWhenTheReplayDidNotRun` | 1 |
| startup runner가 해제를 보고하지 않음 | runner `theReplayReleasesTheHeldConsumerOnceItHasRun` | 1 |
| 검증기의 consumer 조기 반환 제거(과잉 거부) | summary `allowsAStartupPassTurnedOffOnARoleThatDoesNotConsume` | 1 |
| `handleRollback`의 재시도 분기(`BUSY_RETRY_LATER`·`RETRYABLE_FAILURE`)가 `true` 반환 | lifecycle 2건 | 2 |
| 전략의 `orElse(BUSY_RETRY_LATER)` → `orElse(COVERED)` | strategy `aRangeAnotherPassIsAlreadyRebuildingIsNotReportedRebuilt` | 1 |
| 전략의 예외 분기 → `COVERED` | strategy `anAttemptThatThrewIsRetriedRatherThanRemembered` | 1 |
| **대기 해제를 pass의 반환 여부로 되돌림**(`coveredTheWholeSet()` → `true`) | scheduler `aCheckThatDidNotCoverTheHistoryDoesNotReleaseTheHeldConsumer`, `aPassThatRanOutOfRoundsDoesNotReleaseTheHeldConsumer` | 2 |
| **redis-seq 거부를 되살림**(규칙을 검토 전 형태로) | summary `allowsAConsumerWhoseRedisSeqStartupCheckIsOffBecauseThePeriodicChecksReleaseTheHold` | 1 |
| **consumer 플래그를 관대하게 읽음**(`Boolean.class` 바인딩) | summary `doesNotRefuseASpellingTheConsumerConditionItselfTurnsDown` | 1 |
| **검증기의 규칙을 no-op으로**(`rejectAConsumerWithNoStartupRecovery` 비움) | summary `refusesAConsumerWhoseModesOnlyReleaseIsOff` | 1 |
| **answered rollback이 pass를 멈추게 되돌림**(두 원인을 다시 배타적으로) | lifecycle `aRollbackTheModeAnsweredStillLeavesTheFailedBatchToReRead` | 1 |
| **rewind가 재읽은 batch를 세지 않게 되돌림**(단락 제거) | lifecycle `aRewindThatAnsweredTheRollbackDoesNotRestartAgainForTheFailedBatch` | 1 |

판별력이 **없는** 테스트도 두 종류 있고, 그것을 숨기지 않는다. ① 대조군(위의
`theModeThatRecoversByReadingTheStreamIsNotHeld`, `allowsAStartupPassTurnedOffOnARoleThatDoesNotConsume`,
`allowsEveryModeToConsumeBehindItsOwnStartupPass`, 검토 후 추가된
`allowsAConsumerWhoseRedisSeqStartupCheckIsOffBecauseThePeriodicChecksReleaseTheHold`) — 이들은
"반대 방향으로 잘못 만들면 실패"를 고정하며, 위 표의 해당 줄들에서 그 절반이 확인됐다.
② `eachModeBringsUpItsOwnRecoveryStrategy`의 `rewindsOnCheckpointRegression` 축은 2라운드
자산이라 이 표에 없다(§14-8).

### 16.6 실행한 테스트와 결과 (3라운드)

**DB가 필요 없는 클래스만 명시적으로 나열해 실행했다.** MySQL·Rabbit·Redis가 필요한 tier는
실행하지 않았다(§16.7) — 이번 라운드의 변경은 전부 배선·생명주기·전략·검증 계층이고 그 계층은
DB 없이 전부 덮인다.

```
./gradlew test \
  --tests "…recovery.ContestScoreboardRecoveryModeWiringTests" \
  --tests "…recovery.ContestScoreboardRecoveryPassGateTests" \
  --tests "…recovery.ContestScoreboardRecoveryStrategyTests" \
  --tests "…recovery.ContestScoreboardRecoverySummaryTests" \
  --tests "…recovery.ContestScoreboardRecoveryPropertiesTests" \
  --tests "…recovery.ContestScoreboardRecoveryRoleGateTests" \
  --tests "…recovery.ContestScoreboardRecoveryCutoverTests" \
  --tests "…recovery.ContestScoreboardRedisSequenceRecoveryServiceTests" \
  --tests "…recovery.ContestScoreboardRedisSequenceSchedulerTests" \
  --tests "…recovery.ContestScoreboardReplayApplicationTests" \
  --tests "…recovery.ContestScoreboardReplayTransactionBoundaryTests" \
  --tests "…recovery.ContestScoreboardFullReplayServiceTests" \
  --tests "…recovery.ContestScoreboardFullReplayStartupRunnerTests" \
  --tests "…stream.ContestScoreboardStreamLifecycleTests" \
  --tests "…stream.ContestScoreboardStreamProcessorTests" \
  --tests "…stream.ContestScoreboardStreamListenerTests" \
  --tests "…stream.ContestScoreboardStreamRecoveryServiceTests" \
  --tests "my.oj.web.OperationalPropertiesBindingTests"
```

**결과: `BUILD SUCCESSFUL` — 18 classes, 143 tests, failures 0, errors 0.** 검토 지적을 고친 뒤의
재실행도 같은 결과다. 이 중 **39건이 이번 라운드에 새로 추가된 테스트**이고(8개 클래스: lifecycle 10,
strategy 6, cutover 5, role-gate 4, summary 5, scheduler 4, wiring 3, runner 2 — 나머지 변경은 기존
테스트의 수정·확장이며, `ContestScoreboardRecoveryPassGateTests`의 1건은 이름을 바꾸고 단언을 강화한
것이다). 라운드 자체의 15개 클래스(아래 표에서 마지막 3개를 뺀 것)가 **135건** = 143 − 8이고, 그중
role-gate·cutover 2개는 이번에 새로 만든 클래스다. **직전 라운드(`b815378`)에 이미 있던 13개 클래스는
96건이었고**(현재 126건), 차이 30건이 그 13개 클래스에 들어간 새 테스트다.

| 클래스 | tests | failed |
|---|---|---|
| `ContestScoreboardRecoveryModeWiringTests` | 11 | 0 |
| `ContestScoreboardRecoveryRoleGateTests` | 4 | 0 |
| `ContestScoreboardRecoveryCutoverTests` | 5 | 0 |
| `ContestScoreboardRecoveryStrategyTests` | 14 | 0 |
| `ContestScoreboardRecoverySummaryTests` | 15 | 0 |
| `ContestScoreboardRecoveryPropertiesTests` | 7 | 0 |
| `ContestScoreboardRecoveryPassGateTests` | 6 | 0 |
| `ContestScoreboardFullReplayStartupRunnerTests` | 6 | 0 |
| `ContestScoreboardFullReplayServiceTests` | 7 | 0 |
| `ContestScoreboardRedisSequenceSchedulerTests` | 7 | 0 |
| `ContestScoreboardRedisSequenceRecoveryServiceTests` | 10 | 0 |
| `ContestScoreboardReplayApplicationTests` | 4 | 0 |
| `ContestScoreboardReplayTransactionBoundaryTests` | 2 | 0 |
| `ContestScoreboardStreamLifecycleTests` | 24 | 0 |
| `ContestScoreboardStreamProcessorTests` | 13 | 0 |
| `ContestScoreboardStreamListenerTests` | 2 | 0 |
| `ContestScoreboardStreamRecoveryServiceTests` | 2 | 0 |
| `OperationalPropertiesBindingTests` | 4 | 0 |

아래 3개 클래스(Listener·RecoveryService·OperationalPropertiesBinding)는 이번 라운드의 변경된
API를 참조하지 않는 것을 grep으로 확인한 뒤 **추가로** 돌린 것이다(참조하는 클래스는 lifecycle·
processor 둘뿐이고 그 둘은 위 표에 있다).

`compileJava`·`compileTestJava`도 함께 통과한다(생성자 인자 변경 — lifecycle 8번째 인자, runner·
scheduler의 cutover — 이 모든 호출 지점에 반영됐는지는 컴파일이 확인한다).

### 16.7 실행하지 못한 테스트와 그 이유 — 보안상 실행 중단

**아래는 "skip"이 아니라 "실행 중단"이다.** 안전한 MySQL/Redis/Rabbit 환경을 준비할 수 없어
실행하지 않았고, 그 결과 이번 라운드의 변경이 그 tier에서 회귀를 만들지 않는다는 **실행 증거는
없다.** 대신 각 항목에 대해 **코드 수준 근거**를 적었다 — 근거가 있는 것과 실행한 것은 다르다.

| 실행하지 않은 것 | 왜 | 코드 수준 근거 |
|---|---|---|
| `./gradlew test` 전체(`test` profile, 실물 MySQL) | `oj-test-mysql`이 침해된 컨테이너라 쓰지 않기로 했다(§13.3). 안전한 새 테스트 인프라는 이번 범위 밖이다 | 아래 참조 |
| `-DredisIntegration=true` / `-DrabbitIntegration=true` tier | 로컬에 신뢰할 수 있는 Redis/RabbitMQ가 없고, 이번 변경은 broker를 요구하지 않는다 | stream-offset 모드에서는 대기가 걸리지 않으므로(`recoversHistoryBeforeConsuming()=false`) 그 tier의 소비 동작은 변하지 않는다 |
| `ContestScoreboardRecoveryModeStartupTests`(`@SpringBootTest`, MySQL) | 위와 같다. **이 클래스는 이번 라운드에서 수정했다** | 수정 내용: `owner.enabled=true`를 명시. 이 context는 `@ActiveProfiles("test")`이고 `application-test.properties:25`가 `consumer.enabled=false`이므로, 새 규칙 `rejectAConsumerWithNoStartupRecovery()`의 대상이 **아니다**(consumer off ⇒ 조기 반환). 같은 조합("consumer off + full-replay + 기동 replay off")이 허용되는지는 DB-free 테스트 `allowsAStartupPassTurnedOffOnARoleThatDoesNotConsume`가 실행으로 고정한다 |
| `ContestScoreboardRecoveryModeStartupRedisIntegrationTests`(실물 Redis) | 위와 같다. **이번 라운드에서 수정했다** | 수정 내용: `owner.enabled=true` 명시. 이 context는 `consumer.enabled=false`를 직접 선언하고 `startup-check-enabled=false`이므로, 새 규칙은 조기 반환한다. 빈 등록 주장 자체는 DB-free `ContestScoreboardRecoveryRoleGateTests`가 실행으로 고정한다 |

**실행 중단의 정확한 차단 요인.** ① 침해된 `oj-test-mysql`(localhost:3306, root/1234)을 계속 쓰는
것은 이번 지시가 금지한 사용이다. ② 대체 MySQL 컨테이너를 새로 띄우는 것도, 기존 컨테이너를
삭제·중지하는 것도 사용자 승인 없이는 하지 않는다. ③ 따라서 **MySQL·Redis·Rabbit tier 전체가
"실행 중단"** 이고, 이번 라운드의 증거는 DB-free 계층까지다.

**침해 컨테이너를 실제로 사용한 기록 (숨기지 않는다).** 이번 라운드 도중, 좁은 클래스 목록 대신
`--tests "my.oj.web.contest.scoreboard.recovery.*"`로 넓게 한 번 돌렸고, 그 실행이
`ContestScoreboardRecoveryModeStartupTests`와 `ContestScoreboardSequenceRecoveryMySqlIntegrationTests`
를 함께 가져가 **Hikari/Flyway 연결을 열었다**(JUnit XML의 datasource 로그로 확인). 4건 모두
통과했지만 그 증거는 침해된 서버에서 나온 것이므로 신뢰하지 않으며, **이후 모든 실행은 DB-free
클래스를 명시적으로 나열하는 방식으로 제한했다.** 위 §16.6의 결과는 그 제한 이후의 실행이고,
**검토 지적을 고친 뒤의 재실행과 판별력 실측(§16.5)도 전부 같은 제한 아래에서 이뤄졌다** — 즉
이번 라운드에 침해된 서버에 연결한 것은 그 한 번뿐이다. 검토자도 DB·Redis·Rabbit에 접속하지
않았고 읽기·grep·jshell만 사용했다고 보고했다.

### 16.8 독립 검토 (읽기 전용, 3라운드)

**검토 관점은 정확히 셋이었고, 그 밖의 관점은 요청하지 않았다:**

1. `owner.enabled=false`가 트리거를 **실제로 제거**하는가.
2. 수정 후에도 busy 상태가 rollback 재시도를 **잃는가**.
3. cold start에서 non-stream 모드가 Stream 복구와 **섞이는가**.

검토자는 커밋되지 않은 작업 트리(HEAD `b815378` 기준 diff + 미추적 4파일)를 읽기 전용으로
검토했고, 파일을 만들거나 고치지 않았다. 성능·복구 시간·전략 우위는 이번에도 요청하지 않았고
보고서도 그 값을 추정하지 않는다.

**세 질문에 대한 판정.**

| 질문 | 판정 | 근거 |
|---|---|---|
| ① `owner.enabled=false`가 트리거를 실제로 제거하는가 | **제거 자체는 clean** | 조건과 record가 같은 `Environment`를 같은 기본값(`true`)으로 읽어 **owner 축에는 표기 불일치가 없다**(`DefaultConversionService`·`ApplicationConversionService`가 `yes`/`on`/`1`을 모두 true로 변환하는 것을 jshell로 확인). 트리거 셋 모두에 조건이 붙어 있고, `src/main/java` 어디에도 세 클래스를 등록하는 두 번째 경로(`@Bean`·`@Import`·`@ComponentScan`)가 없다. **공용 full replay 서비스는 조건 없이 남아 있고**, retention-gap fallback(`ContestScoreboardStreamRecoveryService` → `replayAllContests()`)이 그대로 도달한다. owner=false + consumer on은 validator가 기동에서 거부하며, validator는 `SmartInitializingSingleton`이라 `finishRefresh`(lifecycle 시작) **이전**에 실패한다 — 즉 supervisor pass를 조건이 제거할 수 없다는 사실이 이 규칙 위에 서 있다 |
| ② busy 상태가 rollback 재시도를 잃는가 | **잃지 않는다(수정은 구조적으로 건전)** | 쌍은 `handleRollback`이 true를 반환할 때만 기록된다. true는 `COVERED`·`UNRECOVERABLE`·되감기 셋에서 나오므로, 재시도를 잃는지를 정하는 것은 **답하지 않는 쪽**이다 — true가 아닌 두 outcome(`BUSY_RETRY_LATER`·`RETRYABLE_FAILURE`)에서는 기록되지 않으며 그 쌍을 지우는 조기 반환이 없다. `UNRECOVERABLE`은 답으로 기억되지만 `rebuiltThrough`를 전진시키지 않으므로 checkpoint가 미적용 구간을 넘어가지 않고, 관측 쌍당 1회로만 기억돼 hot-loop이 되지 않는다(ERROR + `rollback.unrecoverable`). 전략 둘 다 `RuntimeException`을 `RETRYABLE_FAILURE`로 바꾸고, gate는 `finally`에서 해제된다. 재시도는 `offsetCheckInterval`(기본 1s)마다 트래픽 없이 다시 묻는다. gate 점유와 실제 실패는 서로 다른 tag(`busy-retry-later`/`retryable-failure`)로 구분된다 |
| ③ cold start에서 non-stream 모드가 Stream 복구와 섞이는가 | **대기 자체는 건전, 해제 기준에 결함(F4)** | 모든 시작 경로가 `startAt`을 지나고 `container.setAutoStartup(false)`이므로 Spring이 대신 시작하지 않는다. `markCovered`는 대기 목록을 monitor 밖에서 실행하고, 등록/해제 경합이 닫혀 있으며, lock 순서 역전이 없다. 종료 중 해제는 `stopping`으로 막힌다. 재개 offset은 저장 checkpoint 포함 지점 그대로(`next`/tail 아님)이고 pass는 offset을 쓰지 않는다 |

**지적 6건과 처리.**

| 지적 | 심각도 | 처리 |
|---|---|---|
| **F4** redis-seq 해제가 "check가 던지지 않았음"에 걸려 있었다 — `saturated`/`unresolved` pass도 대기를 푼다(전략은 같은 report를 `COVERED`로 보지 않는다). stream이 모드의 기준을 대신하게 된다 | medium | **고쳤다.** 해제 조건을 `coveredTheWholeSet()`으로 바꿔 전략의 coverage 기준과 일치시켰고, 덮지 못한 pass는 ERROR로 "consumer가 계속 대기 중"임을 남긴다 |
| **F1** 새 검증기 규칙이 `redis-seq` + `startup-check-enabled=false` + consumer on을 **안전한데도** 거부한다 — scheduler가 두 주기 task를 무조건 등록하므로 한 주기 뒤 첫 check이 해제하고, 아무것도 대체되지 않는다(거부 메시지의 주장이 거짓). 속성이 consumer를 켠 모든 역할에서 쓸 수 없게 된다 | medium | **고쳤다.** 규칙을 `full-replay` 반쪽만 남기고 좁혔다(그 반쪽은 검토자도 sound라고 확인). `startup-check-enabled` 상수는 이제 참조가 없어 제거 |
| **F3** rollback이 답해진 뒤에는 `failures <= handledFailures` 분기(실패한 batch의 유일한 재시도 경로)에 도달할 수 없고, 그 상태는 **자기 잠금**이다 — checkpoint가 미적용 구간을 지날 수 없어 `highestAppliedOffset`도 못 움직이므로 매 초 조기 반환만 반복한다. full-replay/redis-seq에서 JVM 수명 내내 standings가 짧은 채로 남는다. `if (rolledBack)/else if` 형태는 **기존 코드**지만, 이번에 쓴 새 javadoc이 두 분기 모두에 재시도 보장을 주장하게 됐다 | medium | **고쳤다.** 두 원인을 **독립적으로** 묻도록 바꿨고(rollback answer와 failed batch는 서로의 대안이 아니다), 되감는 모드에서는 되감기 자체가 checkpoint에서의 재시작이라 두 원인이 겹치므로 두 번 재시작하지 않는다 |
| **F2** 검증기가 consumer 플래그를 관대하게(`Boolean.class`) 읽는 반면 consumer 빈은 `@ConditionalOnProperty`로 **literal** 비교한다 — `consumer.enabled=yes`면 빈은 없는데 검증기는 거부한다(과잉 거부, 방향은 한쪽뿐) | low | **고쳤다.** 빈을 고르는 방식 그대로(`"true".equalsIgnoreCase`) 읽는다. 기존 `rejectOwnerMismatch`도 같은 헬퍼를 쓰게 해 두 곳을 일치시켰다 |
| **F5** `UNRECOVERABLE` ERROR가 "restart하면 다시 묻는다"를 말하지 않고, 재시작 없이는 동작하지 않는 처방("switch to full-replay")을 이름만 든다 | low | **고쳤다.** 두 ERROR(전략·lifecycle)에 "이 JVM에서만 기억하므로 restart가 다시 묻는다"와 "모드 변경에는 restart가 필요하다"를 명시 |
| **F6** full-replay runner의 gate 점유 분기가 ERROR만 남기고 해제를 보고하지 않아 consumer가 영원히 대기할 수 있다. 검토자가 full-replay에서 이 gate를 점유할 경로를 **찾지 못했다**(다른 호출자는 stream-offset 전용 fallback) | low | **고치지 않고 남긴다.** 도달 불가로 보고된 잔여 위험이며 §12에 적었다 |

**검토자가 명시적으로 "확인하지 못했다"고 밝힌 것.** live delivery가 멈춘 미적용 구간 위로
도착했을 때 failed batch로 기록되는지(`ContestScoreboardStreamListener.failBatch`/anchor 경로를
이번 검토에서 추적하지 않았다). F3의 failed-batch 변형은 그 추적에 의존하지 않고 성립한다.
**이번 보고서는 그 미확인 항목을 완료된 것으로 적지 않는다.**

**검토 지적을 고친 뒤의 검증.** 관련 DB-free 테스트를 재실행해 `BUILD SUCCESSFUL`(18 classes,
143 tests, failures 0)을 확인했고, 고친 4건 각각에 대해 **되돌림 실측**을 다시 했다(§16.5 아래쪽
다섯 줄 — 실패가 새 테스트에만 국한됨). 검토자가 확인해 준 항목(owner 축의 표기 일치, 서비스의
비조건성, retention-gap fallback 도달, 재시도 구조, 대기의 순서·경합)은 **그대로 유지**했고
그 근거 위에 이번 수정이 얹혀 있다.

### 16.9 commit과 최종 상태

**코드 커밋.** `ba25145` — `fix: make the recovery owner a real gate and stop losing rollback retries`
(기준 `b815378` + 1 commit). 28 files changed, `+2004 / −144`. 신규 4 files:
`ContestScoreboardRecoveryCutover.java`(119), `ContestScoreboardRecoveryOwnerCondition.java`(38),
`ContestScoreboardRecoveryCutoverTests.java`(110), `ContestScoreboardRecoveryRoleGateTests.java`(168)
— 합 435 lines. commit 본문에 세 결함의 원인과 수정, 그리고 검토 지적 F1~F4의 수정을 적었고, F6은
고치지 않고 잔여 위험으로 남긴다고 적었다(§12). 신규 의존성·스키마 변경 없음. Conventional Commits
형식이며 마지막 줄은 `Co-Authored-By: Claude Code <noreply@anthropic.com>`.

**문서 커밋.** 이 보고서와 `ARCHITECTURE.md`의 문구·수치를 고치는 `docs:` 커밋이 이 보고서를
저장소에 넣는다(이 commit 자신의 hash는 자기 내용에 적을 수 없으므로 §1의 "그 뒤" 행으로만
가리킨다). 그 전까지 이 보고서는 **미추적 파일**이었다 — §1.1이 1라운드에서 정정한 바로 그 상태가
3라운드에서도 반복되지 않도록, 이번 라운드의 수치·clean 상태는 이 commit 이후에 기록한다.

**최종 상태.** 문서 커밋 직후 `git status --short`는 **아무것도 출력하지 않는다**. 커밋 직전 작업
트리에 남아 있던 것은 이 두 문서뿐이었고(코드 28 files는 `ba25145`에 들어갔다), 임시·백업 파일은
없다 — 판별력 실측에 쓴 `*.bak` 사본은 저장소 밖에 두었고 전부 원복한 뒤 다시 초록임을 확인했다.

**이번 라운드가 하지 않은 것(§12와 같은 목록).** 성능·복구 시간·전략 우위는 측정하지 않았고
추정하지도 않았다. RDB 스냅샷 rollback 자체, stream replication/failover, 운영 프로파일 실배포
기동, 다중 인스턴스 실배포는 여전히 미검증이다. MySQL·Redis·Rabbit 실물 통합 테스트는 실행하지
않았으며 그 사유는 §16.7에 적었다.

## 17. 4라운드 (`54c990e..`) — cutover 해제와 held 상태의 두 결함

3라운드가 커밋된 뒤(`54c990e`) 같은 구현을 다시 읽어 찾은 **두 결함만** 고쳤다. 세 모드의 의미,
저장 checkpoint의 **포함** 재개 규칙, 이전 라운드의 결정은 건드리지 않았다. 성능 실험·분산 실행권·
F6 잔여 위험·무관한 리팩터는 이번 라운드에도 범위가 아니다.

### 17.1 결함 1 — 해제가 실패하면 consumer 시작이 영영 사라진다

**원인.** `ContestScoreboardRecoveryCutover.markCovered`가 "역사를 덮었다"와 "기다리던 동작을
실행했다"를 **한 상태로** 다뤘다: `covered = true`를 쓰면서 **같은 순간에 대기 목록을 비웠고**, 그
뒤에 콜백을 돌렸다. 그래서 콜백이 던지면 — `ContestScoreboardStreamLifecycle.startAfterHistoryRecovery`
의 `repairPending()`, checkpoint 읽기, `container.start()` 중 어느 것이 실패해도 — 그 동작은 **목록에
도 없고 실행되지도 않은** 상태가 된다. 이후의 모든 보고는 이미 켜진 coverage 플래그에서 조기
반환하므로, redis-seq의 주기 check이든 retention-gap fallback의 replay든 **다시 시도하지 않는다.**
consumer는 JVM 수명 동안 내려간 채 남고, 로그에는 "역사를 덮었다"만 남는다.

**수정 — 두 상태로 분리.** coverage는 한 번 쓰이는 사실이고 되돌려지지 않지만, 대기 목록에서
빠지는 조건은 **성공**이다.

- `release(actions, coveredBy)`가 monitor **밖에서** 실행된다(기존 동시성 보장 유지 — 콜백이 도는
  동안 다른 스레드의 등록/보고가 막히지 않는다). 성공한 것만 목록에서 빼고, 실패한 것은 남긴다.
  여러 개가 실패하면 첫 실패를 던지고 나머지는 `addSuppressed`로 묶는다 — **한 동작의 실패가 다른
  대기를 막지 않는다**(대기는 consumer 하나당 하나다).
- 실패는 **삼키지 않는다.** `markCovered`는 호출자(pass)에게 예외를 던진다. 삼키면 "역사를
  덮었다"고 보고하면서 아무것도 시작하지 못한 pass가 조용해진다.
- **재시도 트리거가 실재한다.** redis-seq에서는 역사를 덮는 **주기 check이 매번 경계를 보고**하므로
  일시적 실패 뒤 한 주기 뒤에 다시 시도된다. `ContestScoreboardRedisSequenceScheduler.runCheck`는
  release 실패를 catch해 ERROR로 남긴다 — fixed-delay task가 예외로 취소되면 그 모드의 check 자체가
  사라지기 때문이다. `ContestScoreboardFullReplayStartupRunner`는 반대로 예외를 그대로 던져 **기동을
  실패**시킨다(아무것도 소비하지 않으면서 복구했다고 기록된 JVM을 만들지 않는다).

**[정정 — 5라운드]** 위 "**재시도 트리거가 실재한다**"는 **트리거**에 대해서는 맞다 — 동작은
보존되고 보고도 다시 온다. 그러나 그 재시도가 **성공할 수 있다**는 뜻은 아니었다. 반쯤 실패한
start는 컨테이너의 running 플래그를 올린 채 consumer를 0건으로 남기고, 그 상태에서 다음
`container.start()`는 `isRunning()` 조기 반환으로 **아무것도 하지 않는다.** 즉 트리거는 실재했지만
재시도가 만난 컨테이너는 재시도할 수 있는 상태가 아니었다 — §18.1. 아래 §17.3·§17.4의 해당
테스트 2건도 5라운드에서 상태 기반으로 다시 쓰였다(§18.3).

### 17.2 결함 2 — held 상태와 Spring context 종료의 경합

**원인.** 비-stream 모드(`full-replay`·`redis-seq`)에서 `lifecycle.start()`는 consumer를 대기시키고
`running=false`로 **반환**했다. Spring 6.2.3 `DefaultLifecycleProcessor.doStop`은
`if (bean.isRunning())` **아래에서만** `smartLifecycle.stop(callback)`을 부르므로(소스 확인:
`stopBeans()`는 모든 `Lifecycle` 빈을 phase 그룹에 넣지만 stop은 그 조건에서만 부른다), 대기 중인
lifecycle은 **stop되지 않고** context가 닫힌다. 그 결과 `stopping` 플래그가 서지 않고, 늦게 끝난
복구 pass의 해제가 **닫히는 context에 `container.start()`를 호출**한다.

**수정 — 두 상태로 분리.**

| 필드 | 뜻 | 누가 읽는가 |
|---|---|---|
| `started` | Spring이 start했고 stop하지 않았다 | `isRunning()`. **종료 경로가 이 값을 본다** |
| `consuming` | listener container가 실제로 떠 있다 | supervisor guard(`consuming()`), 재시작 경로 |
| `stopping` | context가 내려가는 중이다 | 해제 경로(늦은 시작 차단) |

`stop()`/`stop(Runnable)`은 `started=false`를 **컨테이너를 만지기 전에, 그리고 consuming이 아니어도**
세운다 — 대기 중인 consumer야말로 이 플래그가 가장 필요한 대상이다. `stop(Runnable)`은 두 경로 모두
callback을 정확히 한 번 완료한다. 대기 중 시작이 실패하면 `consuming`은 false로 남아 cutover가 그
동작을 계속 보관한다. supervisor는 `isRunning()`이 아니라 `consuming()`을 본다 — 대기 중인 consumer에게
재구독할 것도 재읽을 batch도 없다(§16.8 검토 ③이 이미 지적한 방향이다).

### 17.3 추가·수정한 테스트

| 고정하는 것 | 테스트 |
|---|---|
| 실패한 해제가 다음 보고에서 **재시도되고 성공**한다 | `ContestScoreboardRecoveryCutoverTests.aDeferredActionThatFailedIsRetriedWhenTheBoundaryIsReportedAgain` |
| 실패한 동작이 대기 목록에서 **사라지지 않는다** | 같은 테스트의 `awaiting() == 1` 단언 |
| **성공한** 동작은 이후 보고에서 다시 돌지 않는다 | `….aDeferredActionThatSucceededIsNotRunAgainByALaterReport` |
| 한 동작의 실패가 다른 대기를 막지 않는다 | `….aFailedActionDoesNotKeepAnotherWaiting` |
| 콜백 실행 중 lock을 쥐고 있지 않다 | `….theBoundaryIsNotHeldWhileADeferredActionRuns`(다른 스레드에서 `isCovered()`가 5초 안에 답한다) |
| redis-seq 주기 check이 release 실패를 **재시도**한다 | `ContestScoreboardRedisSequenceSchedulerTests.aCoveringCheckWhoseConsumerCouldNotStartRetriesOnTheNextPeriod` |
| 기동 runner가 그 실패를 **삼키지 않고** 대기도 유지한다 | `ContestScoreboardFullReplayStartupRunnerTests.aConsumerThatCouldNotBeStartedFailsTheStartupAndStaysWaiting` |
| 종료 뒤 해제는 container를 **시작하지 않는다**(실제 Spring 경로) | `ContestScoreboardStreamLifecycleContextTests.aConsumerReleasedAfterTheContextHasClosedIsNotStarted` |
| 정상 해제는 **정확히 한 번** 시작하고 종료가 stop한다 | `….aConsumerReleasedBeforeTheCloseStartsOnceAndIsStoppedWithTheContext` |
| 대기 중 `isRunning()`은 true, `consuming()`은 false | `ContestScoreboardStreamLifecycleTests.aModeThatRebuildsHistoryFromItsOwnBasisConsumesNothingUntilItsPassHasRun`(§16.5 이후 갱신) |
| 대기 중 supervisor는 시작·재구독하지 않는다 | `….theSupervisorDoesNotStartAHeldConsumer`(같은 두 상태 단언 추가) |
| stop 뒤 재시작도 다시 대기한다 | `….aRestartAfterAStopIsHeldAgain`(신규) |
| `start()`가 돌아왔다고 consumer가 떴다고 보지 않는다 | `….aStartThatLeftNoConsumerIsNotTakenForAStartedConsumer`(신규, §17.6) |
| 재시작이 실패하면 그 batch를 다시 묻는다 | `….aFailedBatchIsAskedAboutAgainWhenTheResubscribeCouldNotStart`(신규, §17.6) |
| 동시에 온 두 번째 보고가 같은 동작을 다시 돌리지 않는다 | `….aSecondReportDoesNotRunWhatTheFirstIsAlreadyRunning`(신규, §17.6) |

**[정정 — 5라운드]** 위 표의 두 줄이 5라운드에서 바뀌었다(§18.3). `start()`가 돌아왔다고 consumer가
떴다고 보지 않는다 — `aStartThatLeftNoConsumerIsNotTakenForAStartedConsumer`는 **이름이 바뀌었고**
(→ `aStartThatLeftNoConsumerIsTakenBackOutAndTheRetryReallyStartsOne`), 재시작이 실패하면 그
batch를 다시 묻는다 — `aFailedBatchIsAskedAboutAgainWhenTheResubscribeCouldNotStart`는 **이름은
그대로**이나 mock의 `0 → 1` 순차 스텁 대신 상태 기반 double 위에서 돈다. 두 테스트 모두 "재시도가
일어났다"가 아니라 "정상화가 실제로 일어났고 그 다음 start가 실제로 시도됐다"를 단언한다.

`ContestScoreboardStreamLifecycleContextTests`는 **실제 `SpringApplication`** 을 띄우고 실제로
`context.close()`를 부른다 — 이 클래스가 `lifecycle.stop()`을 직접 부르면 결함 2를 되살려도 초록이
되므로, 그 방법을 쓰지 않았다(작업 지시가 금지한 바로 그 회피다). 컨테이너만 mock이고 그
`stop(Runnable)`은 실제 컨테이너처럼 callback을 완료한다.

### 17.4 실행 결과와 판별력 실측

```
.\gradlew.bat test --offline  --tests <명시적 19개 클래스>
  → BUILD SUCCESSFUL, 19 classes, 155 tests, failures 0, errors 0, skipped 0
```

3라운드와 같은 목록에 신규 `ContestScoreboardStreamLifecycleContextTests`(+2)를 더했고, cutover
+4 · scheduler +1 · runner +1 · lifecycle +1로 **143 → 152**(+9)다. 그 뒤 §17.6의 검토 수정이
세 건(cutover +1 · lifecycle +2)을 더해 **최종 155**다. 실행 뒤 JUnit XML 전체에
`HikariPool|jdbc:mysql|Flyway`가 없음을 확인했다 — **DB 활동 0건**(연결을 열지 않았다).

**수정 전 실측**(요구: 수정 전 실패, 수정 후 통과). 두 결함에 대응하는 테스트만 추려 실행해
**10건 중 3건 실패**를 확인했다 — `aDeferredActionThatFailedIsRetriedWhenTheBoundaryIsReportedAgain`
(`AtomicInteger(1)`을 기대값 2와 비교), `aFailedActionDoesNotKeepAnotherWaiting`(`["first"]`만 실행,
`"third"` 누락), `aConsumerReleasedAfterTheContextHasClosedIsNotStarted`(`NeverWantedButInvoked:
simpleMessageListenerContainer.start()` — **종료 뒤에 실제로 start가 호출됐다**).

**되돌림 실측**(각 수정이 정말 그 테스트를 잡고 있는지). 모두 **확정된 코드**에서 다시 측정했고,
매 회차는 5개 클래스 **54건**이다.

| 되돌린 것 | 실패한 테스트 | 개수 |
|---|---|---|
| `isRunning()`이 다시 "컨테이너가 떠 있는가"를 답하게 | context `aConsumerReleasedAfterTheContextHasClosedIsNotStarted` + lifecycle 두 상태 단언 2건 | 3 |
| 실패한 동작을 대기 목록에 **되돌려 놓지 않게** | cutover `aDeferredActionThatFailed…`, runner `aConsumerThatCouldNotBeStarted…`, scheduler `aCoveringCheckWhoseConsumerCouldNotStart…`, lifecycle `aStartThatLeftNoConsumer…` | 4 |
| `start()`가 돌아왔다는 이유로 consumer가 떴다고 보게(`getActiveConsumerCount()` 검사 제거) | lifecycle `aStartThatLeftNoConsumerIsNotTakenForAStartedConsumer`, `….aFailedBatchIsAskedAboutAgainWhenTheResubscribeCouldNotStart` | 2 |
| 재구독 재시작 **전에** batch를 handled로 기록하게 | lifecycle `aFailedBatchIsAskedAboutAgainWhenTheResubscribeCouldNotStart` | 1 |
| 대기 목록을 lock 안에서 **비우지 않게** | cutover `aSecondReportDoesNotRunWhatTheFirstIsAlreadyRunning` | 1 |

각 실측은 **그 수정에 대응하는 테스트에만** 국한됐고, 같은 실행에 포함된 나머지 52·50건은
통과했다. 되돌린 파일은 저장소 밖 사본(`AppData\Local\Temp\scoreboard-round4\backup2\`)에서
원복했고, 원복 뒤 19클래스 전체를 다시 초록으로 확인했다. 되돌림 패치는 모두 `MEASUREMENT ONLY`
표식을 달았고 작업 트리에는 남기지 않았다.

**[정정 — 5라운드]** 위 되돌림 표의 마지막 두 줄이 가리키는 테스트 2건은 5라운드에서 상태 기반으로
다시 쓰였고, 그중 하나는 **이름도 바뀌었다**(§18.3). 따라서 그 두 줄의 수치(2·1)는 **4라운드 코드와
4라운드 테스트 이름에 대한 측정**이며, 지금 그 이름으로는 재측정할 수 없다. 5라운드의 같은 자리
측정(수정 전 4건 실패 · 부분 되돌림 1/1/4)은 §18.4에 있다. 같은 이유로 위 실행 결과의
"19 classes, 155 tests"도 **4라운드 시점의 값**이고, 5라운드의 값은 159다.

### 17.5 실행하지 않은 검증

- **MySQL·Redis·Rabbit 실물 통합 테스트는 이번에도 실행하지 않았다.** 사유는 §16.7과 같다 —
  검증에 쓸 수 있는 안전한 MySQL 환경이 없다(`oj-test-mysql`은 침해된 컨테이너이고 `localhost:3306`
  도 같은 대상이다). **"보안상 실행 중단"** 이며 skip으로 세지 않았다. 따라서 실물 브로커로의 종료
  경합, 실물 Redis rollback 주입, 실물 batch 중간 실패는 **여전히 미검증**이다.
- 성능·복구 시간·전략 우위는 측정하지 않았고 추정하지 않았다. RDB 스냅샷 fault injection과 장시간
  부하 테스트도 범위 밖이다.
- [정정] **이번 라운드 중 한 번, 검증 제한을 위반한 실행이 있었다.** 넓은 wildcard 필터
  (`my.oj.web.contest.scoreboard.recovery.*` + `…stream.*`)로 돌린 회차에서
  `ContestScoreboardRecoveryModeStartupTests`(1건)와
  `ContestScoreboardSequenceRecoveryMySqlIntegrationTests`(4건)가 **침해된 `oj-test-mysql`에
  Hikari/Flyway 연결을 열었다**(5건 모두 통과, 11개 Redis/Rabbit 클래스는 skip되어 연결하지 않았다).
  즉시 명시적 19클래스 목록으로 전환하고 XML 검사로 DB 활동 0을 확인했지만, **그 5건의 통과는 이
  보고서의 어떤 근거로도 쓰지 않는다.** 이 문단이 그 실행의 유일한 기록이다.

### 17.6 독립 검토 결과 (읽기 전용)

두 결함을 고친 뒤 **별도 에이전트에게 이번 라운드의 diff를 읽기 전용으로 검토**시켰다. 지적을
그대로 받지 않고 spring-rabbit 3.2.3과 Spring 6.2.3 소스에서 먼저 확인한 뒤, **이번 라운드가 만든
것에 속하는 지적은 고치고 나머지는 보고만 했다**(작업 지시: 범위를 넓혀 수정하지 않는다).

**소스로 확인한 핵심 사실 — `start()`가 돌아왔다고 consume하는 것이 아니다.**
`AbstractMessageListenerContainer.start()`(3.2.3, 1397행)는 `isRunning()` 조기 반환 뒤 `doStart()`를
`catch (Exception)`으로 감싸 `convertRabbitAccessException`으로 던질 뿐 `setNotRunning()`을 부르지
않는다. `SimpleMessageListenerContainer.doStart()`(570행)는 `super.doStart()`로 `active=true;
running=true`를 **먼저** 세우고 그 뒤 `initializeConsumers()`(581행)와 `waitForConsumersToStart()`
(603행, `AmqpIllegalStateException("Fatal exception on listener startup")`)를 부른다. 따라서 **반쯤
실패한 start는 `isRunning()==true`인데 소비자는 0건**이다. 그래서 `consuming`은 `start()`가
예외 없이 돌아왔다는 사실이 아니라 **살아 있는 consumer 수**(`getActiveConsumerCount() <= 0`이면
실패로 취급)로 정한다. 이 검사는 "이미 떠 있는 컨테이너에 대한 start"도 정상으로 통과시키므로,
`DefaultLifecycleProcessor`가 context 재시작에서 stop했던 빈을 다시 start하는 경우
(`stoppedBeans`)에 잘못 걸리지 않는다.

**이번 라운드에서 고친 지적.**

| 지적 | 수정 |
|---|---|
| `start()` 반환 = 시작됨으로 오판 → 소비자 없는 "실행 중" 상태 | `startAt`이 `getActiveConsumerCount()`로 확인, 0이면 `IllegalStateException`으로 실패 처리 |
| 재구독 재시작 **전에** batch를 handled로 기록 → 재시도 1회 유실 | `handledFailures = failures`를 재시작 **성공 뒤로** 이동(`startAt`이 던지면 다음 주기가 다시 재시작) |
| 동시에 온 두 번째 보고가 같은 동작을 다시 실행 | 대기 목록을 lock 안에서 **한 번에 take & clear**(`release()`가 실패분만 되돌린다) |
| `Error`가 loop를 빠져나가면 미실행 동작이 사라지거나 성공분이 다시 실행됨 | 되돌림 장부를 `finally`로 이동 |
| 내가 쓴 잘못된 서술 — full-replay runner "나중 보고에도 재시도할 것이 남는다" | 실제대로 정정: 그 모드에서는 **이 runner가 유일한 보고자**이고 재시도는 기동 재실행이다(§17.1) |
| 테스트 위생 — latch 대기 테스트의 executor 누수, mock에 없는 `getActiveConsumerCount()` 스텁, context 테스트가 **재현하지 못하는 것**에 대한 서술 부재 | 각각 수정(executor는 `finally`에서 `shutdownNow()`, mock 스텁과 한계 서술을 주석·javadoc에 명시) |

**고치지 않고 보고만 하는 지적.**

1. **`consuming`은 창(window)에서 실제와 어긋난다.** 컨테이너가 실제로 떠 있는 순간과 이 필드가
   서는 순간, 그리고 stop 뒤 필드가 내려가는 순간 사이에 supervisor가 다른 스레드에서
   `consuming()==false`를 읽을 수 있다. "무엇이 소비 중인가"의 권위는 컨테이너의
   `getActiveConsumerCount()`이고 이 필드는 근사다. 이번 라운드의 트리거(해제는 `markCovered`가,
   대기는 `start()`가 같은 스레드에서 수행)로는 재현되지 않지만 **구조적으로 남아 있다.**
2. **scheduler는 `RuntimeException`만 catch한다.** release가 `Error`를 던지면 fixed-delay task가
   취소되어 그 모드의 주기 check 자체가 사라진다 — §17.1에서 일부러 `RuntimeException`만 잡은
   것과 같은 모양의 구멍이다. `Error`는 일시적 브로커 장애의 신호가 아니므로 의도적으로 그대로
   두었다.
3. **종료 순서 창(기존 동작).** spring-rabbit의 `stop(Runnable)`은 실제 컨테이너에서 대기를 task
   executor에 넘기고(`shutdownAndWaitOrCallback`) consumer가 내려가기 전에 돌아올 수 있다. 즉
   `consuming=false`가 브로커 연결 해제보다 먼저 서고, context close가 끝나도 consumer가 아직
   내려가는 중일 수 있다. **이번 라운드가 만든 것이 아니라 spring-rabbit의 기존 동작**이며 고치지
   않았다. `ContestScoreboardStreamLifecycleContextTests`의 javadoc이 mock이 이 순서를 재현하지
   않는다는 사실을 적어 둔다 — 그 테스트의 `verify(container).stop(...)`은 "종료가 이 lifecycle에
   도달했다"를 고정할 뿐 "실물 컨테이너가 내려갔다"를 고정하지 않는다.

## 18. 5라운드 (`aa979d2..`) — 반쯤 실패한 start는 재시도할 수 있는 상태가 아니었다

4라운드가 §17.6에서 `start()` 반환을 시작됨으로 오판하는 것을 고쳤지만, **그때 근거로 든 "다음
주기 재시도가 복구한다"는 반쯤 실패한 컨테이너에서는 성립하지 않았다.** 이번 라운드는 그 한 결함만
고쳤다. 성능 실험·분산 실행권·F6 잔여 위험·종료 순서 창·무관한 리팩터는 이번에도 범위가 아니다.

### 18.1 [정정] "다음 주기 재시도로 복구된다"는 성립하지 않았다

**정정 대상.** §17.1의 "**재시도 트리거가 실재한다.** redis-seq에서는 역사를 덮는 주기 check이
매번 경계를 보고하므로 일시적 실패 뒤 한 주기 뒤에 다시 시도된다", 그리고 같은 취지를 담은
`ARCHITECTURE.md` §3.3과 `ContestScoreboardRecoveryCutover`의 javadoc.

**무엇이 맞고 무엇이 틀렸는가.** 트리거는 실재했다 — 실패한 동작은 대기 목록에 남고, redis-seq의
주기 check은 매 주기 경계를 다시 보고하며, start 자체도 예외를 던져 실패를 알렸다. 틀린 것은
**그 재시도가 만나는 컨테이너가 재시도할 수 있는 상태라는 가정**이다. spring-rabbit 3.2.3
바이트코드(`javap -p -c`)로 확인한 사실:

- `AbstractMessageListenerContainer.start()`의 첫 두 줄이 `if (isRunning()) return;`이다. 예외는
  `catch (Exception) { throw convertRabbitAccessException(...) }`로 감싸 던질 뿐이고, `finally`는
  `lazyLoad=false`만 쓴다 — **`setNotRunning()`을 부르지 않는다.**
- `SimpleMessageListenerContainer.doStart()`는 `super.doStart()`로 `active=true; running=true`를
  **먼저** 세우고, 그 **뒤에** `initializeConsumers()`와 (실패 시
  `AmqpIllegalStateException("Fatal exception on listener startup")`를 던지는)
  `waitForConsumersToStart()`를 부른다.

즉 start가 반쯤 실패하면 컨테이너는 **running 플래그가 올라간 채 consumer는 0건**으로 남는다. 그
상태에서 다음 `container.start()`는 **첫 줄에서 반환하며 아무것도 시작하지 않는다.** consumer 수는
계속 0이므로 `startAt`은 같은 실패를 다시 내고, cutover는 동작을 다시 보관하며, 그 다음 주기도
같은 일이 반복된다. **재시도는 JVM 수명 동안 형식뿐이었다** — 컨테이너가 영영 올라오지 않는다.

### 18.2 수정 — 실패를 보고하기 **전에** 컨테이너를 되돌린다

실패한 start를 하나의 정상화 경로로 모았다.

- start가 `RuntimeException`을 던지든, 예외 없이 돌아왔는데 `getActiveConsumerCount() <= 0`이든
  **둘 다 같은 경로**를 탄다: `container.stop()`으로 실제 컨테이너의 running 플래그를 내린 **뒤에**
  그 실패를 호출자에게 보고한다. 보고가 정상화보다 먼저 오면 재시도는 정상화되지 않은 컨테이너를
  다시 만나므로 순서가 곧 결함이다.
- `container.stop()`(인자 없는 동기 변형)을 고른 근거는 **그 호출만이 확실히 되돌리기 때문**이다.
  `AbstractMessageListenerContainer.stop()`은 `doStop(); setNotRunning();`이고, 예외가 나면
  `setNotRunning()`을 부른 뒤 다시 던지는 catch-all 핸들러가 있으므로 **stop 자체가 던져도 플래그는
  내려간다.** 또
  `SimpleMessageListenerContainer.shutdownAndWaitOrCallback`은 `consumers == null`이면
  "Shutdown ignored - container is already stopped"를 남기고 돌아오므로 **플래그를 올린 적 없는
  컨테이너에 대한 stop도 안전**하다. `stop(Runnable)`이 아니라 `stop()`인 이유는 동기가 필요해서다 —
  재시도가 내려가는 중인 컨테이너와 경주하면 안 된다. **[정정 — 검토 A1]** start는 두 자리에서
  실패하고, **되돌리기가 필요한 것은 두 번째뿐**이다. 플래그가 올라가기 **전**의 실패 —
  `afterPropertiesSet()`과 그 주변 검사, 즉 브로커가 아직 안 떠 있을 때 실제로 실패하는 자리 — 는
  플래그를 내린 채 끝나므로 되돌릴 것이 없고, 이때 stop은 **no-op**이다(실제 컨테이너는 "Shutdown
  ignored - container is already stopped"를 남기고 돌아온다). 처음에 이 문서와 javadoc이 "start가
  실패하면 항상 플래그가 올라간 채 남는다"고 쓴 것은 **조건부 사실을 무조건으로 적은 것**이었다.
- **정리 과정의 예외는 최초 실패를 대체하지 않는다.** stop이 던지면 최초 예외에 `addSuppressed`로
  붙이고 WARN을 남긴다. start가 왜 실패했는지가 stop이 왜 실패했는지보다 중요하다.
- **정상 start는 건드리지 않는다.** 살아 있는 consumer가 있으면 stop을 부르지 않는다. 읽고 있는
  consumer를 내리는 것은 stream을 소유하지 않은 모드가 회수 경로에서 해서는 안 되는 일이다.
- **`consuming` 필드만 뒤집지 않는다.** 그 필드는 이 lifecycle의 근사일 뿐이고, 재시도를 막고 있던
  것은 컨테이너의 실제 `isRunning()`이다.

기존 의미는 그대로다: 실패 시 `consuming()`은 false, cutover 동작은 대기에 남고 다음 보고가 재시도,
full-replay startup에서는 실패가 `ApplicationRunner` 밖으로 전파되어 **기동 실패**, 저장 checkpoint
**포함** 재개 규칙 불변, failed batch는 재구독 **성공 뒤에만** handled, 모드별 복구 의미 불변.

**막혀 있던 경로는 해제 경로 하나였다.** 컨테이너를 멈추지 않고 start하는 곳은
`startAfterHistoryRecovery`(cutover가 부르는 경로)뿐이고, 되감기와 재구독 경로는 이미 `startAt`
**전에** `container.stop()`을 부르므로 플래그가 내려가 있다. 그래서 이 결함의 실제 발생 경로는
"해제가 실패한 뒤의 재시도"다.

### 18.3 상태 기반 테스트가 `0 → 1` 순차 스텁과 다른 점

4라운드의 두 테스트는 `when(container.getActiveConsumerCount()).thenReturn(0, 1)`로 "첫 start는 0,
재시도는 1"을 **호출 순번으로** 흉내 냈다. 그것으로는 이 결함을 볼 수 없다. 실제 컨테이너는 플래그가
올라가 있으면 **두 번째 start를 조기 반환으로 삼키므로**, 그 스텁은 **정상화하지 않은 구현도
통과시킨다** — 재시도가 실제로 시도됐는지 조기 반환으로 건너뛰었는지를 구분하지 못한다. 순번은
스텁이 원하는 대로 답하지만 컨테이너는 자기 상태로 답한다.

그래서 두 테스트(그리고 신규 4건)는 컨테이너를 **상태로** 모델링한 `StatefulListenerContainer` 위에서
돈다. `SimpleMessageListenerContainer`를 상속해 `start()`/`stop()`/`getActiveConsumerCount()`/
`setConsumerArguments()`를 오버라이드하고 다음 상태를 들고 있다.

| 상태 | 뜻 |
|---|---|
| running 플래그 | 실제 컨테이너가 `isRunning()`으로 답하는 그 플래그. `start()`가 **consumer를 세우기 전에** 올린다 |
| `consumers` | `getActiveConsumerCount()`가 답하는 값 |
| 다음 start의 결과 | consumer를 세운다 / 플래그만 올리고 consumer 0 / 플래그를 올린 뒤 던진다 |
| 다음 stop의 결과 | 정상 / 던진다(그래도 플래그는 내려간다) |

`start()`는 **호출 순번이 아니라 자기 상태**를 읽는다: 플래그가 올라가 있으면 조기 반환하고
`skippedStarts`를 센다. `stop()`은 던지도록 설정돼 있어도 플래그를 내린다(실제 컨테이너가 `finally`
에서 내리는 것과 같다). 그래서 `skippedStarts() == 0`은 "재시도가 실제로 **시도**됐다"는 진술이고,
`reportsItselfRunning() == false`는 "정상화가 실제로 일어났다"는 진술이다. 순번 기반 스텁으로는
앞의 것을 단언할 수 없다 — 스텁은 시작이 조기 반환으로 삼켜졌는지 알 방법이 없다.

**double이 재현하지 않는 것**(javadoc에 명시): 브로커, consumer 자신, 실제 `stop(Runnable)`의 순서
(실제 컨테이너는 대기를 task executor에 넘긴다), `waitForConsumersToStart()`의 대기와 그 타임아웃
(`consumerStartTimeout`, 이 라이브러리 기본 60초). 독립 검토(A2)가 지적한 두 구멍도 javadoc에 적었다:
double에는 `consumers` 필드가 없어 **`doStart()`가 `"A stopped container should not have consumers"`를
던지는 상태**(production javadoc이 stop의 근거 중 하나로 드는 위험)를 만들 수 없고,
`willFailToStop`은 던진 예외를 그대로 돌려주지만 실제 `stop()`은 비-AMQP 실패를
`convertRabbitAccessException`으로 감싸 `UncategorizedAmqpException`(cause만 든다)으로 바꾸므로
suppressed 예외의 **정확한 메시지 단언은 double의 성질**이다. 그리고 Spring의 `isRunning()`은
`public final`이고 private 필드를 읽으므로 하위 클래스가 그 값을 세울 수 없다 — double은 같은 플래그를
**자기 필드로** 복제하며 `isRunning()`이라는 이름을 쓰지 않는다(모든 인스턴스가 그 이름에는 false로
답한다). 서술할 때 `reportsItselfRunning()`을 `isRunning()`이라고 부르지 않는 이유가 이것이다.

### 18.4 실행 결과와 판별력 실측

```
.\gradlew.bat test --offline  --tests <4라운드와 같은 명시적 19개 클래스>
  → BUILD SUCCESSFUL, 19 classes, 159 tests, failures 0, errors 0, skipped 0
```

`ContestScoreboardStreamLifecycleTests`가 27 → 31(+4)이고 나머지 18클래스는 4라운드와 같은 수다
(155 → 159). 실행 뒤 JUnit XML 전체에 `HikariPool|jdbc:mysql|Flyway`가 없음을 확인했다 — DB 활동
0건. `git diff --check`는 통과했고, 작업 트리에는 소스 2 files만 있다.

**변경 파일(5라운드).** `src/main/java/my/oj/web/contest/scoreboard/stream/ContestScoreboardStreamLifecycle.java`,
`src/test/java/my/oj/web/contest/scoreboard/stream/ContestScoreboardStreamLifecycleTests.java`,
`docs/SCOREBOARD_RECOVERY_FINAL_REPORT.md`, `docs/ARCHITECTURE.md`. 신규 파일 0, 신규 의존성 0,
공개 API 변경 0.

**최종 작업 트리.** 두 commit(`04ab46b` code, `0001d8a` docs)을 만든 뒤 `git status --short`는
**아무것도 출력하지 않는다**(미추적 파일 없음). 이 문단을 넣는 commit까지 끝난 뒤 다시 확인했고,
그 시점에도 clean이다 — 이 보고서 자신이 커밋됐으므로 "clean"이 커밋된 파일만 세는 값이 아니라
작업 트리 전체의 값이다.

**수정 전 실측.** 새 상태 기반 테스트 4건을 **고치지 않은 구현**에 먼저 돌려 **31건 중 4건 실패**를
확인했다. 실패 메시지가 정확히 이 결함을 가리킨다 — `reportsItselfRunning()`이 true로 남아
(`Expecting value to be false but was true`) 있고, 정상화 stop이 없어 suppressed 예외가 비어 있다
(`Expected size: 1 but was: 0`). 고친 뒤 같은 31건이 초록이다. 신규 테스트는 4건이고 그중 셋이
여기서 걸린다 — `aConsumerThatCameUpIsNotStopped`는 **수정 전에도 통과한다**(그때도 정상 start를
멈추지 않았으므로). 그 테스트는 결함의 증거가 아니라 "수정된 코드가 무조건 stop으로 자라지 않게"
하는 guard이고, 위 4건에 세지 않았다(검토 A3도 같은 판정).

**부분 되돌림 실측.** 어느 수정이 어느 테스트를 잡는지 확인하려고 구현의 **일부만** 되돌려 측정했다
(매 회차 `ContestScoreboardStreamLifecycleTests` 31건).

| 되돌린 것 | 실패한 테스트 | 개수 |
|---|---|---|
| 던진 start를 정상화 없이 그대로 보고하게 | `aStartThatThrewIsStillRetriedAfterTheContainerIsTakenBackOut` | 1 |
| 정상화 중 실패한 stop이 최초 예외를 **대체**하게 | `aStopThatCouldNotTakeTheContainerBackOutDoesNotReplaceTheStartFailure` | 1 |
| 정상화를 **아예 하지 않게** | 위 둘 + `aStartThatLeftNoConsumerIsTakenBackOutAndTheRetryReallyStartsOne`, `aFailedBatchIsAskedAboutAgainWhenTheResubscribeCouldNotStart` | 4 |

각 회차에서 나머지 30·30·27건은 통과했다. 되돌린 파일은 저장소 밖 사본
(`AppData\Local\Temp\scoreboard-round5\backup\`)에서 원복했고 SHA-1이 커밋된 HEAD와 일치함을
확인했으며, 패치는 모두 `MEASUREMENT ONLY` 표식을 달았고 작업 트리에는 남기지 않았다
(`git grep -c 'MEASUREMENT ONLY' -- src/` = 0).

**되돌림이 판별하지 못하는 것(정직하게).** `aFailedBatchIsAskedAboutAgainWhenTheResubscribeCouldNotStart`
는 정상화가 없어도 **재시도 자체는 성공한다** — 재구독 경로가 `startAt` **전에** `container.stop()`을
부르므로 플래그가 이미 내려가기 때문이다(§18.2). 이 테스트가 잡는 것은 그 경로가 남기는 상태
(`reportsItselfRunning()` 단언)다. 반대로 **이번 수정이 실제로 푸는 것은 해제 경로 하나**이며, 그
경로를 고정하는 테스트가 `aStartThatLeftNoConsumerIsTakenBackOutAndTheRetryReallyStartsOne`다.

### 18.5 실행하지 않은 검증

§17.5와 같다. **MySQL·Redis·Rabbit 실물 통합 테스트는 이번에도 실행하지 않았다** — 검증에 쓸 수
있는 안전한 MySQL 환경이 없다(`oj-test-mysql`은 침해된 컨테이너이고 `localhost:3306`도 같은
대상이다). **"보안상 실행 중단"** 이며 skip으로 세지 않았다. 이번 라운드의 변경은 실물 브로커가
필요한 계층을 건드리지 않지만, 따라서 **반쯤 실패한 실물 컨테이너**(예: 실제로
`waitForConsumersToStart()`가 던지는 경우)에서 이 정상화가 동작하는지는 **미검증**이다 — 근거는
spring-rabbit 3.2.3 바이트코드와 그 상태를 모델링한 double뿐이다. 성능·복구 시간 비교도 이번에도
하지 않았고, RDB fault injection과 장시간 부하 테스트도 범위 밖이다.

### 18.6 독립 검토 결과 (읽기 전용)

수정을 마친 뒤 **별도 에이전트에게 이번 라운드의 diff(소스 2 files)를 읽기 전용으로
검토**시켰다 — 검토자가 본 것은 `+408/−34`이고, 지적을 반영한 최종 commit(`04ab46b`)은 `+441/−34`다
(차이는 검토 지적 반영분과 javadoc뿐이며 동작은 같다). 검토자는 spring-rabbit 3.2.3 / spring-amqp
3.2.3 jar를 `javap -p -c`로 직접 열어
모든 라이브러리 주장을 확인했고, 스스로 "추측에 그친 것"과 "확인한 것"을 구분해 보고했다. 이번
라운드는 **동작 결함을 만들지 않았고**, 지적은 전부 문서 수준 3건이었다(A1·A2·A4) — 셋 다 고쳤다.

**고친 지적.**

| 지적 | 수정 |
|---|---|
| **A1** — `normaliseAfterAFailedStart`의 javadoc이 "start에서 실패할 수 있는 모든 것은 플래그가 올라간 뒤에 온다"고 **무조건** 적었다. `afterPropertiesSet()`과 그 주변 검사는 플래그보다 **앞**에서 돌고 try/catch **밖**이라, 브로커가 안 떠 있을 때의 실패는 오히려 플래그가 내려간 채 끝난다 | 두 실패 자리를 구분하도록 다시 씀: 플래그 전 실패는 되돌릴 것이 없고 stop은 **no-op**, 되돌리기가 필요한 것은 `doStart()`가 super를 부른 뒤의 실패뿐. `startAfterHistoryRecovery`의 javadoc도 같은 조건부로 고침(§18.2 [정정 — 검토 A1]) |
| **A2** — double의 "재현하지 않는 것" 목록에 실제로는 없는 두 성질이 빠졌다: ① `consumers` 필드가 없어 `doStart()`가 `"A stopped container should not have consumers"`를 던지는 상태(production javadoc이 stop의 근거로 드는 위험)를 만들 수 없다 ② `willFailToStop`은 예외를 그대로 돌려주지만 실제 `stop()`은 비-AMQP 실패를 `convertRabbitAccessException` → `UncategorizedAmqpException`(cause만)으로 바꾸므로 **정확한 메시지 단언은 double의 성질**이다. 또 실제 stop은 cancellation loop 안에서 실패해 `consumers`를 null로 만들기 **전에** 빠져나갈 수 있다(= 실패한 stop 뒤 재시도가 성공한다는 단언이 그 경우를 증명하지 못한다) | 두 항목을 double javadoc에 추가하고, 해당 단언 옆에도 주석으로 명시. 실제로 실패 지점을 찾아내지는 못했으므로 **결함이 아니라 서술의 구멍**으로만 적었다(§18.3 갱신) |
| **A4** — 이 보고서 §18.3이 double javadoc이 "`waitForConsumersToStart()`의 60초 대기"를 언급한다고 적었는데 javadoc에는 없었다 | javadoc에 그 항목을 실제로 추가해 문서와 코드를 맞춤 |
| **A3**(정보) — `aConsumerThatCameUpIsNotStopped`는 수정 전에도 통과하므로 결함의 증거가 아니다 | 위 4건에 세지 않았음을 명시하고, 그 테스트의 javadoc에 "무조건 stop으로 자라지 않게 하는 guard"임을 적음 |

**확인된 것(검토자가 소스로 검증).** 조기 반환·플래그를 consumer보다 먼저 세우는 순서·`finally`의
`setNotRunning()`·플래그가 내려간 컨테이너에 대한 stop의 무해함은 **바이트코드로 확인**됐다. 그리고
**정상 consumer를 멈추지 않는다**는 이 수정의 핵심도 확인됐다: 정상 start는 각 consumer의 start
latch를 기다린 뒤에야 돌아오고 그 latch는 `BlockingQueueConsumer.start()`가 `activeObjectCounter`에
등록된 **뒤에** 세워지므로, 정상 반환은 `getActiveConsumerCount() >= 1`을 함의한다 — 즉 0건 판정은
"아무것도 소비하지 않는" 두 경우와 정확히 겹친다. 교착 없음(이 경로는 container worker 스레드에서
돌지 않는다), `stop()` 선택이 옳음(`stop(Runnable)`은 `consumers`가 아직 non-null인 채 돌아올 수
있다), 그리고 **재시도가 실제로 기능함**도 확인됐다 — stop이 `consumers`를 null로 만들고 다음
`initializeConsumers()`가 `cancellationLock.reset()` → `ActiveObjectCounter.reset()`으로 카운터를
다시 활성화하므로, 다음 start는 consumer를 정말 등록할 수 있다. 보존 의미 5가지(실패 시
`consuming` false, cutover 대기 유지와 재시도, full-replay 기동 실패 전파, 포함 재개 규칙, handled
기록 순서)도 각각 확인됐다. diff 위생(측정용 잔여 코드·TODO·미사용 import 없음)도 clean 판정.

**검토자가 판별력을 독립적으로 재도출했다.** 수정 전 코드에서 실패할 4건을 스스로 추론해(§18.4와
같은 4건) 일치함을 보고했다. 다만 "19 classes, 159 tests"는 **허용된 범위 밖 실행이 필요해 검증하지
않았고**, 실물 브로커에 대한 동작은 검토자도 **주장하지 않는다** — 근거는 바이트코드와 모델링된
상태까지다(§18.5와 같다).

**고치지 않고 보고만 하는 지적(기존 동작, §17.6과 같은 방침).** 검토자가 함께 보고한 것들은 이번
diff가 만든 것이 아니므로 **보고만 한다**: ① 실패한 재구독 뒤 `consuming`이 true로 남는 것(그것이
supervisor 재시도를 만든다) ② `recordFailureRestart()`가 start **시도 전**에 기록되는 것 ③ `startAt`
이 `repairPending()`·`consumerRestarted()`·`initializeOffset()`을 start 전에 부르는 것 ④ 0-consumer
모양에는 실제로 두 변종이 있고(`consumerStartTimeout` 만료, 동시 stop으로 consumer가 비워진 경우)
후자는 이 lifecycle에서 도달 불가 ⑤ `stop(Runnable)`이 callback 전에 돌아올 수 있는 창(그래서 동기
`stop()`을 고른 것이 옳다). 이들은 §16.7·§17.6과 같은 이유로 **이번 범위에서 고치지 않았다.**
