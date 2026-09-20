# 최종 보고 — Redis 스코어보드 복구 3방식 (full-replay / redis-seq / stream-offset)

이 문서는 작업 완료 시점의 보고서다. 설계·경계는 [`ARCHITECTURE.md`](ARCHITECTURE.md) §3–§5,
폐기된 seq 설계와의 차이는 [`PORTFOLIO_SCOREBOARD_RECOVERY.md`](PORTFOLIO_SCOREBOARD_RECOVERY.md)를
본다.

**이 문서는 두 라운드로 이루어진다.** 1라운드(`0d36f26..60d98ec`)에서 세 모드를 구현했고, 그 결과에
대한 독립 검토(§9.1)가 **"세 모드가 실제로 분리되어 있지 않다"**는 지적을 포함해 14건을 냈다.
2라운드(`60d98ec..HEAD`, §14)에서 그 지적과 함께 나온 다섯 결함을 고쳤다. **1라운드 시점의 서술과
2라운드에서 정정된 서술을 구분해서 읽어야 한다** — 정정 대상은 각 절에 `[정정]`으로 표시했다.

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
| **최종 HEAD** | **`655838e`** (기준 + 22 commit, 102 files, +10725 / −283) |
| 작업 트리 | **clean (미추적 파일 없음)** — 최종 HEAD에서 측정. §1.1 |

마지막 한 commit(`655838e`)은 이 절을 측정값으로 채우는 commit이다 — 즉 위 수치는 그 commit 자신을
포함하고, `b98c83f..655838e`의 차이는 정확히 그 commit의 `+67 / −21`이다. 이것이 "clean 상태와 변경
파일 수는 보고서를 커밋한 다음에만 다시 기록한다"를 만족시키는 방식이다.
| 원본 checkout | 건드리지 않음. `reset`/`clean`/강제 checkout 사용 안 함 |

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
+5236/−476, 전체는 102 files +10725/−283이 된다(위 §1의 표가 그 값이다). 세 수치 모두 **보고서
자신을 포함한다** — 그것이 1라운드의 "clean"이 틀렸던 바로 그 지점이다.

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

### 5.2 [정정] 2라운드 (`60d98ec..HEAD`) — 60 files, +5190 / −476

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

### 8.2 2라운드 (`60d98ec` → `b98c83f`)

```
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
