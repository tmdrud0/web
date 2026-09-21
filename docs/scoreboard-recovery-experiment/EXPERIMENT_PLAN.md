# Redis 스코어보드 복구 3방식 pilot 실험 계획

이 문서는 **측정 전에 고정한 계획**이다. 실행 명령과 안전 규칙은 [`README.md`](README.md), 실측 결과는
`PILOT_REPORT.md`(측정 후 작성), 요약 수치는 `results/summary.csv`를 본다.

**이 문서에는 측정 결과가 없다.** 여기 적힌 값은 전부 설정값이거나 설계상 기대이며, 실측과 구분해서
읽어야 한다. 실측으로 확인되지 않은 항목은 `PILOT_REPORT.md`에서 `unmeasured` 또는 `unavailable`로
표시한다.

관련 문서:

- 구현 내역과 5라운드 감사 기록: [`../SCOREBOARD_RECOVERY_FINAL_REPORT.md`](../SCOREBOARD_RECOVERY_FINAL_REPORT.md)
- 세 모드의 설계 경계: [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §3–§5

---

## 1. 목적

`codex/scoreboard-recovery-tradeoff` 브랜치에 구현된 Redis 스코어보드 복구 3방식은 **아직 부하
조건에서 비교된 적이 없다**. `SCOREBOARD_RECOVERY_FINAL_REPORT.md` §10은 성능·복구 시간을 측정하지
않았다고 스스로 밝히고 있고, 기존 `gatling/run-scoreboard-rdb-recovery.ps1`은 outbox vs stream 전송
방식 비교용이며 **rollback 전에 부하를 멈춘다**. 즉 "복구 중에도 신규 유입이 계속되는" 조건의 측정은
존재하지 않는다.

이 실험의 목적은 **어느 모드가 항상 우월한지를 정하는 것이 아니다**. 세 모드를 동일 조건에서 실행해
(1) 복구 SLO, (2) 복구 중 신규 유입 지연, (3) MySQL·Redis·RabbitMQ 비용을 각각 재고, 그 셋으로
모드를 고르는 기준을 만드는 것이다.

### 1.1 가설 (실측으로 검증할 대상)

| # | 가설 | 근거 | 반증 조건 |
|---|---|---|---|
| H1 | 세 모드의 **감지 지연은 구분되지 않는다** | 세 모드 모두 `ContestScoreboardStreamLifecycle.recoverConsumption()`의 fixed-delay 1s 주기가 `storedOffset < appliedOffset`를 비교해 감지한다 (설계상 기대) | 한 모드의 감지 지연이 다른 모드의 poll 간격을 넘어설 만큼 크면 반증 |
| H2 | 복구 비용의 차이는 **감지가 아니라 수리**에서 나온다 | `stream-offset`은 consumer를 멈추고 checkpoint에서 재구독(브로커 재소비), `full-replay`는 contest 전체 결과를 MySQL에서 replay, `redis-seq`는 중복 seq + allocator 초과 tail만 replay | 세 모드의 `repairDurationMs`가 구분되지 않으면 반증 |
| H3 | `full-replay`의 비용은 **유실 tail 크기가 아니라 contest 전체 결과 수**에 비례한다 | replay 대상이 rollback으로 사라진 구간이 아니라 contest 전체다 | tail을 키워도 `full-replay`의 MySQL 비용이 그대로면 반증 |
| H4 | `stream-offset`은 복구 중 **consumer를 멈추므로** backlog가 가장 깊게 쌓인다 | `rewindsOnCheckpointRegression=true`, consumer stop 후 재구독 | `minStreamQueueConsumers`가 0으로 떨어지지 않으면 반증 |
| H5 | 정합성 장애 시간(`consistencyOutageMs`)은 **유실 수와 무관하게** 한 모드 안에서 안정적이다 | 유실 집합 전체를 한 번에 복구하는 경로이므로 | 회차 간 편차가 유실 수에 비례하면 반증 |

H1–H5는 전부 **설계상 기대**이며 이 실험 전에는 실측 근거가 없다.

---

## 2. 실패 시나리오 (고정)

측정 대상으로 삼는 상황은 하나다.

- 실제 contest가 **진행 중**이다. 제출과 채점 결과가 계속 흘러 들어온다.
- JVM, MySQL, RabbitMQ Stream은 **살아 있다**. 죽지 않는다.
- **Redis 스코어보드만** 과거 RDB snapshot과 동등한 과거 상태로 되돌아간다.
- rollback **이후에도 신규 결과 유입은 멈추지 않는다.**

**cold start(JVM 재시작) 시나리오는 이 실험의 범위가 아니다.** `SCOREBOARD_RECOVERY_FINAL_REPORT.md`
§16.0이 밝힌 대로 cold start 경로는 별개의 검증 대상이며, 여기서 측정하는 것은 "Redis만 되돌아간"
경우다.

---

## 3. 고정 변수 / 독립 변수 / 종속 변수

### 3.1 독립 변수

`contest.scoreboard.recovery.mode` 하나다. 값은 `full-replay`, `redis-seq`, `stream-offset` 세 가지이며,
batch-1 컨테이너의 환경변수 `CONTEST_SCOREBOARD_RECOVERY_MODE`로 주입한다. 주입한 값은 **컨테이너
자체의 환경에서 다시 읽어 검증**한다(`Assert-BatchRecoveryMode`) — 파일이 아니라 컨테이너가 실제로 그
모드로 떴는지가 기준이다.

### 3.2 고정 변수 (세 모드에 동일하게 적용)

| 항목 | 값 | 고정 방법 |
|---|---|---|
| MySQL 데이터 | 동일 | run마다 같은 seed에서 시작. 결정론적 판정기 + 결정론적 payload로 9회의 데이터가 같아진다 |
| contest 규모 | `-UserCount`, `-ProblemCount` | suite 파라미터, 9회 동일 |
| rollback 지점 | tail은 판정 결과 개수 기준 `-TailResults`, baseline 창은 `-BaselineResults` 하한 + 고정 `-BaselineWindowSeconds` | tail은 **적용된 결과 수**로 정확히 고정한다. baseline은 결과 수 하한에 도달한 뒤 고정 길이 창을 재는 방식이라 **창의 길이는 고정되지만 K 시점의 결과 수는 유입 순서에 따라 달라진다** → K의 결과 수를 매 run 실측 기록하고, 회차 간 차이가 있으면 그대로 보고한다 |
| Redis snapshot 상태 | K 캡처 시점 | batch-1 pause 중 캡처 → checkpoint와 스코어보드 내용이 자기정합 |
| rollback으로 사라지는 결과 집합 | `lostCount` | 매 run 실측 기록. 회차 간 차이가 있으면 **그대로 보고**한다 |
| 신규 결과 이벤트 순서 | 동일 | payload가 `(userName, submissionIndex)`의 순수 함수 |
| 신규 유입률 | `-TargetRps` | closed model: `ceil(rps * interval / 1000)` 세션이 `interval` 간격으로 pacing |
| RabbitMQ retention / stream 내용 | 동일 | run 시작 전 `contest.judge.result.stream` 큐만 삭제·재선언 |
| worker / consumer 동시성 | 동일 | 문서화된 tier web×2 / judge×2 / batch×1 |
| JVM / MySQL / Redis / RabbitMQ 자원 | 동일 | 컨테이너 자원 제한·이미지 고정 |
| warm-up | `-RampSeconds` | 모든 run 동일 |
| 측정 시간 | `-HoldSeconds` | 모든 run 동일 |
| 정합성 판정기 | 동일 oracle | 아래 §5 |

### 3.3 종속 변수

`T_fault`, `T_detected`, `T_consistent`, `T_backlog_drained`와 그 파생값, 신규 유입 지연·처리량·실패,
정합성 판정, 자원 사용량. §6에 전부 열거한다.

---

## 4. 용어 — 네 시각과 파생값

| 이름 | 정의 | 측정 방법 |
|---|---|---|
| `T_fault` | rollback이 완료되어 스코어보드가 과거 상태가 된 시각 | 주입기가 RESTORE 검증을 통과한 직후. MySQL 시계로도 함께 기록 |
| `T_detected` | batch-1이 offset 회귀를 **로그로 보고한** 시각 | batch-1 컨테이너 로그에서 제품의 `Redis scoreboard offset rolled back from A to B` 최초 발생(harness가 두 분기를 `detected-nonrewinding` / `detected-rewinding`으로 이름 붙인다). 교차확인 `contest_scoreboard_stream_rollback_observed_total` 증분은 **rewind하지 않는 두 모드에만 유효하다** — `stream-offset`은 rewind 분기로 가고 제품이 그 카운터를 rewind 분기에서 기록하지 않으므로 **0이 설계다(감지 실패 아님)**. 로그에 없으면 `unavailable` (0으로 적지 않는다) |
| `T_consistent` | 스코어보드가 MySQL과 **처음 일치한** 시각 | 제품 API digest == oracle digest 가 처음 성립한 poll. **복구 메서드의 반환값이나 로그만으로 완료를 판정하지 않는다** |
| `T_backlog_drained` | 파이프라인이 처음 조용해진 시각 | judge outbox 미발행 0 + `scoreboard_applied_at IS NULL` 0 + rabbit live/dead ready·unacked 0 + pending events 0 이 처음 동시에 성립한 poll |

파생값:

- `detectionLatencyMs` = `T_detected − T_fault`
- `consistencyOutageMs` = `T_consistent − T_fault` (스코어보드가 틀린 채로 있던 시간)
- `backlogDrainMs` = `T_backlog_drained − T_fault`
- `repairDurationMs` = `T_backlog_drained − T_consistent` (정합해진 뒤 남은 수리 비용)
- `fullRecoveryMs` = 둘 중 늦은 시각 − `T_fault`
- `drainedBeforeConsistent` — 큐가 먼저 조용해지고 digest가 나중에 맞는 경우를 구분하기 위한 boolean

`T_consistent`와 `T_backlog_drained`는 **다른 질문**이라 따로 잰다. `full-replay`는 큐가 비어도
digest가 아직 안 맞을 수 있고, `stream-offset`은 반대(consumer가 멈춰 큐는 비었는데 소비는 안 한 상태)를
지나간다.

---

## 5. 정합성 oracle

MySQL을 기준으로 기대 순위를 **제품 코드와 독립적으로** 계산한다.

- 대상 행: `contest_submission_result` 중 `coalesce(final_result, provisional_result) <> 'PENDING'`
  **이면서** `scoreboard_applied_at IS NOT NULL`인 행. 스코어보드가 실제로 받은 결과만 센다.
- 사용자별: `solved` = 문제별 정답 여부 합, `penalty` = 문제별 `acceptedMinutes + 5 * wrongBefore`.
- 정렬: `solved DESC, penalty ASC, user_id ASC` (= score `solved*1e9 − penalty*1e3 − userId` 내림차순과 동일).
- 비교: 제품 API `GET /api/contests/{id}/scoreboard`의 **전체 페이지**를 읽어 `(rank, userId, solved,
  penalty)` 정규화 문자열의 SHA-256을 oracle digest와 비교한다.

### 5.1 oracle이 스스로 검사하는 전제 (하나라도 깨지면 run을 실패로 기록하고 수치를 만들지 않는다)

| 전제 | 깨졌을 때 생기는 일 |
|---|---|
| 결과가 저장된 contest가 이 실험의 contest 하나뿐 | `full-replay`만 다른 contest를 replay해 세 모드가 다른 일을 한다 |
| 참가자 user id 폭 < 1000 | 두 참가자가 ZSET score에서 동점이 되어 순위가 member 문자열에 의존한다 |
| submission id 자릿수가 전부 같음 | 스코어보드의 문자열 tie-break와 oracle의 수치 tie-break가 갈린다 |
| `scoreboard_applied_at IS NOT NULL`인 PENDING 결과 0건 | 그 행의 기여를 oracle이 예측할 수 없다 |
| 부하 시작 전 digest가 이미 일치 | 불일치는 측정 한계가 아니라 harness 결함 신호다 |

### 5.2 부하 시작 전 검증

load를 걸기 **전에** digest가 일치해야 한다. 이 검증 없이는 이후의 불일치가 fault 때문인지 harness
때문인지 구분할 수 없다.

---

## 6. 수집 지표 (수집원 명시)

**수집할 수 없는 항목은 0으로 적지 않고 `unavailable`로 표시한다.** 단위 테스트 통과는 물리적 복구
성공이 아니다.

### 6.1 시간

§4의 네 시각과 파생값. `T_detected`는 product 로그 + rollback observed counter 교차확인.

### 6.2 신규 유입 영향

| 지표 | 수집원 |
|---|---|
| 반영 지연 p50 / p95 / p99 / max (baseline / recovery / after 3구간) | MySQL 직접: `scoreboard_applied_at − coalesce(final_judged_at, provisional_judged_at)` |
| 처리량, 실패·timeout | Gatling `global_stats.json` (`numberOfRequests.total/ok/ko`), `perf.assert.*` |
| ingress 자체 p95 / max | Gatling `percentiles3.total`, `maxResponseTime.total` |
| consumer lag / 최대 backlog | `contest_scoreboard_pending_events`, `contest_scoreboard_oldest_ready_seconds`, stream queue ready/unacked |
| backlog 해소 시간 | `T_backlog_drained − T_fault` |
| 정상 대비 악화 | recovery p95 − baseline p95 (`lagP95IncreaseMs`) |
| consumer held/stopped 시간 | `minStreamQueueConsumers`, `pollsWithoutConsumer`, `rollbackRestartsTotal` |

### 6.3 정합성

digest 일치 시각, 누락 결과 수, 중복 적용, 잘못된 score·penalty, 순위가 다른 사용자 수, 복구 중 신규
결과 누락, 최종 수렴 여부, **checkpoint가 실제 적용 상태보다 앞서는지**.

`duplicate applications`는 별도 카운터가 없으므로 `sequenceDuplicatesTotal`(redis-seq 전용)과
`processedCardinality` 대비 `oracleAppliedResults` 초과분으로 판단한다.

**`lostReapplied` / `lostComplete`의 의미(오독 주의).** 이 값은 rollback으로 사라진 제출 id가 제품의
`processed` set에 **다시 들어왔는지**를 센다. 제품은 `processed`를 결과가 순위를 움직이는지 판정하는
guard **바깥에서** 쓰므로, 아직 `PENDING`인 전달도 제출을 processed로 표시하고 순위는 건드리지 않는다.
따라서 `lostComplete = true`는 "사라진 제출이 전부 다시 전달되었다"는 뜻이지 "스코어보드가 맞다"는 뜻이
아니다. 실행 harness의 `T_consistent` 판정 predicate는 `digestMatches` **와** `lostComplete`의 논리곱이라
거짓 일치가 생기지 않지만, 이 카운터 하나만 인용해서는 복구 성공으로 읽으면 안 된다.

### 6.4 자원

| 지표 | 수집원 |
|---|---|
| MySQL 질의·읽은 행·슬로우 질의 | `SHOW GLOBAL STATUS`(Questions, Innodb_rows_read, Slow_queries) delta, `mysql_global_status_*` |
| pool active/pending | `hikaricp_connections_active`, `hikaricp_connections_pending` |
| DB 스레드 | `Threads_connected`, `Threads_running` |
| Redis 명령·CPU·Lua 오류·pipeline | `INFO`(total_commands_processed, used_cpu_*, evicted_keys), `contest_scoreboard_redis_lua_errors_total`, `contest_scoreboard_redis_pipeline_seconds_*` |
| Redis 명령 종류별 | `INFO commandstats` (eval / restore / del) — `RESTORE`·`DEL`은 주입기 자체 footprint이므로 세 모드 모두에 동일하게 들어간다 |
| RabbitMQ 처리량 | `rabbitmq_detailed_queue_messages_delivered_ack_total`, `..._exchange_messages_published_total` |
| 앱 CPU/메모리/throttle/OOM | `process_cpu_usage`, `jvm_memory_used_bytes`, `cgroup_cpu_usage_seconds_total`, `cgroup_cpu_throttled_periods_total`, `cgroup_memory_oom_kills_total` |
| redis-seq round / window / candidate | `contest_scoreboard_redis_sequence_{rounds,duplicates,replayed,failed,windows_saturated,unresolved}_total`, `..._mapping_size` |

### 6.5 수집할 수 없는 항목 (의도적으로 `unavailable`)

`replayOfferedCount`, `replayFoundAlreadyAppliedCount` — `ContestScoreboardReplayApplication.apply(...)`는
`void`를 반환하고 marker 실패만 기록하므로 **제안/적용/무시 건수를 나누는 카운터가 제품에 없다.**
0으로 적지 않고 `unavailable`로 표시하며, 사용 가능한 대리 지표인 `appliedDeltaDuringRecovery`(복구
구간의 `contest_scoreboard_applied_total` 증분 — live 적용과 replay 적용이 섞여 있어 분리 불가)를 함께
기록한다.

---

## 7. 결정론적 입력 (재현성)

| 항목 | 방법 | 기본값 |
|---|---|---|
| 채점 결과 | `contest.submission.judge.deterministic.enabled=true`일 때만 활성. `code` 문자열 해시로 `ACCEPTED`/`WRONG_ANSWER`를 결정하고 정답률은 `accept-permille`로 고정 | **false → 제품 동작 무변화** |
| payload | `perf.deterministic=true`일 때 problemId·code를 `(userName, submissionIndex)`의 순수 함수로 생성 (`DeterministicPayload`) | **unset → 기존 시나리오 동작 그대로** |
| rollback 깊이 | 적용된 결과 수 기준 | `-TailResults` |

같은 `(user, n번째 제출)` → 항상 같은 문제·같은 code → 항상 같은 판정. 9회의 MySQL 데이터와 Redis
상태가 같아지고, 회차가 바꾸는 것은 **각 제출의 도착 시각**뿐이다.

### 7.1 baseline 동일성

9회의 K 시점 oracle digest(`kOracleDigest`)가 모두 같아야 한다. 다르면 그 사실과 편차를 **그대로**
리포트에 적는다(숨기지 않는다). 다만 §3.2의 고정 방식상 **K의 결과 수가 회차마다 몇 건 달라질 수 있다**:
tail은 `-TailResults`로 정확히 고정되지만 K는 `-BaselineResults` 하한 뒤 **고정 길이 창**(`-BaselineWindowSeconds`)이
끝나는 시점이라, 창 안에 도착한 판정 수가 유입 순서에 따라 달라진다. 그러므로 `kOracleDigest`가 갈리는 것은
곧 버그가 아니라 **고정 변수의 한계**이며, 어느 쪽이든 `kOracleDigest`·`kAppliedResults`·`kProcessedCount`를
매 run 기록해 편차의 크기를 숫자로 남긴다. rollback 목표 깊이는 `-TailResults`로 고정이고, 실제 `lostCount`는
poll 간격 때문에 몇 건 달라질 수 있으므로 매 run 실측값을 기록하고 편차를 보고한다.

---

## 8. 실험 절차 (매 run 동일 · 11단계)

`gatling/run-recovery-pilot.ps1`이 이 순서를 그대로 수행한다.

1. **reset** — Redis·RabbitMQ 서비스 기동 확인 → 앱 tier 정지 → `contest.judge.result.stream` 큐만 삭제 →
   전용 Redis(`oj-loadtest-redis`) 초기화 → 이전 시도의 잔여 행 회수 → 잔여 행 비간섭 assert
2. **seed** — `sbrec_<runId>_` prefix로 contest / problems / users 생성, Flyway 18 assert
3. **기동** — `CONTEST_SCOREBOARD_RECOVERY_MODE`와 함께 앱 tier 기동 → 15개 컨테이너 healthy →
   컨테이너 환경에서 모드 재확인 + DB 이름 일치 확인
4. **pre-run 검증** — pipeline quiescent → oracle 전제 → clock frame → digest 일치 (불일치면 run 실패)
5. **부하 시작** — Gatling을 백그라운드로 기동. **이 시점부터 run 종료까지 유입이 계속된다**
6. **baseline 구간** — 적용 결과 수가 `-BaselineResults`에 도달할 때까지 대기 → `-BaselineWindowSeconds`
   동안 정상 상태 지연 분포 측정
7. **K 캡처** — batch-1 pause → 키별 `TYPE`/`DUMP`/`PTTL` 캡처 + checkpoint + oracle digest +
   적용 결과 수 → digest 일치 확인 → unpause
8. **tail → fault 주입** — 적용 결과 수가 K 대비 `-TailResults` 이상이 되는 순간 batch-1 pause →
   `contest:scoreboard:*` 전체 `DEL` → 캡처 payload `RESTORE ... REPLACE` → **키 집합·payload 바이트
   단위 검증** → `T_fault` 기록 → unpause
9. **관측** — `-PollIntervalSeconds`마다 poll. digest 일치 + 유실 집합 전부 재적용 → `T_consistent`,
   pipeline quiescent → `T_backlog_drained`
10. **종료·검증** — Gatling 종료 대기 → 잔여 부하 정지 → 최종 digest / seed 상태 / clock frame 재검증
11. **정리** — 범위 한정 DELETE, 테이블별 삭제 건수 기록, 비간섭 assert

### 8.1 fault 주입 방식과 한계

스코어보드 키 범위 `DEL` + `RESTORE`를 쓴다. 전체 RDB 파일 교체가 **아니다**.

- 이유: 세션(`spring:session:*`)·dedup·rate-limit 키를 건드리지 않으므로 **주입기 자체가 가용성을
  교란하지 않는다.** 전체 인스턴스 RDB 교체는 컨테이너 kill로 수 초의 Redis 무응답을 만들고, 그것이
  "신규 유입 실패"에 섞인다.
- `RESTORE`는 K 시점 키의 직렬화 바이트를 그대로 되돌리므로 "과거 RDB snapshot과 동등한 상태"다.
- **한계(문서화 대상)**: RDB 로드 경로 자체와 세션·dedup 상태의 rollback은 재현하지 않는다. **세 모드에
  동일하게 적용**되므로 모드 간 비교는 성립하지만, "RDB에서 로드했을 때의 복구 시간"으로 일반화할 수
  없다.
- `docker pause` 구간(수백 ms)은 주입기 자체 footprint로 별도 기록한다(`redisRestoreCallsDelta`,
  `redisDelCallsDelta`는 세 모드 모두에 동일하게 들어간다).

---

## 9. Calibration (측정 전 1회)

`실험계획`에 고정 변수로 기록할 값을 **실제로 재서** 정한다. 데이터 크기와 유입률을 임의로 "운영 규모"라고
부르지 않는다.

**현재 환경에서 안정적으로 반복 가능한 값을 먼저 calibration하고 그 값을 기록한다.**

기준:

- (a) 세 모드 모두 정해진 timeout 안에 수렴한다
- (b) OOM kill 0
- (c) p95 제출 지연이 SLO 버킷 안
- (d) `full-replay`의 수리 비용이 tail 크기보다 유의하게 크다 (H3 검증 가능성)

`-Phase calibration`으로 모드당 1회씩 짧게 돌려 정하고, 확정값을 `results/calibration.md`에 기록한다.
Calibration run은 `-RunIndex 0`을 쓰므로 pilot 9회와 run id가 섞이지 않는다.

---

## 10. 완료 기준

1. 세 모드가 동일 조건에서 실행 가능하다
2. 복구 중에도 신규 유입이 계속된다
3. Redis rollback이 재현 가능하다 (바이트 단위 검증 통과)
4. `T_consistent`가 MySQL oracle로 자동 판정된다
5. 감지·복구·backlog 해소 시간이 분리되어 기록된다
6. 신규 결과 지연과 실패율이 기록된다
7. 최소 pilot이 모드당 3회 실행된다
8. raw 결과와 요약 결과가 저장된다
9. 다른 사람이 run 명령만으로 재현할 수 있다
10. 실측 / 설계상 가정 / 미측정이 명확히 구분된다
11. 기존 DB에서 실험이 생성·수정·삭제한 범위가 보고된다
12. 최종 보고에 commit hash, 테스트 결과, pilot 결과, 미검증 항목, git status가 포함된다

### 10.1 run의 완결성 판정

run은 다음이 **전부** 성립할 때 `complete=true`다.

- 최종 digest가 oracle과 일치
- 복구가 load의 hold 안에 끝남
- batch-1이 감지 로그를 남김
- Gatling assertion 통과 (최소 요청 수, 성공률 99%, p95는 측정용으로 관대하게 설정)
- 최종 pipeline quiescent
- OOM kill 0

완결성 판정은 **모드의 성질이 아니라 측정의 성질**이다. 기준을 못 채운 run은 별표를 달아 보고하지 않고
`complete=false`로 기록하며, 그 이유를 `incompleteReasons`에 남긴다. **수치는 버리지 않는다** — 모드가
ingress SLO를 깨는 것 자체가 결과이기 때문이다.

---

## 11. 결과 해석 규칙

- 설정값을 측정 결과로 쓰지 않는다
- 단위 테스트 통과를 물리적 복구 성공으로 쓰지 않는다
- 1회 run을 일반 성능으로 주장하지 않는다
- 서로 다른 데이터 크기·fault 조건의 숫자로 개선율을 계산하지 않는다
- 모든 수치에 전체 데이터 수·유실 수·유입률·환경을 함께 적는다
- 미측정은 `unmeasured`, 수집 불가는 `unavailable`
- 설계상 기대와 실측을 구분한다 (§1.1의 H1–H5는 실측 전까지 가설이다)
- 결론은 "어느 기술이 항상 우월"이 아니라 **복구 SLO · 신규 유입 지연 · DB/Redis/Rabbit 비용에 따른
  선택 기준**으로 쓴다
- 3회 반복은 분포가 아니다. 중앙값과 범위를 쓰고, 평균으로 두 run 사이의 값을 만들어내지 않는다

---

## 12. 독립 검토

측정이 끝난 뒤, 구현과 분리된 **읽기 전용 검토**를 한 번 수행한다. 검토 범위:

1. **공정성** — 세 모드가 정말 같은 조건에서 측정됐는가 (고정 변수가 실제로 고정됐는가)
2. **oracle 의미** — 정합성 판정이 제품 코드와 독립적인가, 무엇을 놓칠 수 있는가
3. **지표 의미** — 각 수치가 주장하는 것을 실제로 재고 있는가, 파생값의 정의가 타당한가

검토 지적 중 이번 실험이 만든 결함만 반영하고, 반영 내역과 반영하지 않은 이유를 남긴다.
