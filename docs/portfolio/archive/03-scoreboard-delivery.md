# 3. 스코어보드 전달 경로 — DB Outbox vs RabbitMQ Stream

> 실시간 순위를 Redis에 두면 조회는 빨라집니다.
> 문제는 **Redis가 죽었다 과거 스냅샷으로 살아났을 때 순위를 어떻게 되돌리느냐**입니다.
> 이 장은 두 가지 전달·복구 구조를 실제로 만들어 비교하고, 무엇을 근거로 선택했는지에 대한 기록입니다.

---

## 3.1 배경 — 스코어보드는 파생 상태다

[1.3절](01-project-overview.md)에서 정한 원칙에서 출발합니다.

```text
MySQL  = 원본        (유실되면 복구 불가)
Redis  = 파생 상태   (MySQL 결과에서 언제든 재구성 가능해야 함)
```

이 원칙이 있으면 "Redis가 죽으면 어떡하지"라는 질문이
**"어디서부터 다시 흘려보낼 것인가"**라는 훨씬 좁은 질문으로 바뀝니다.
이 장의 전체가 그 재시작 지점(checkpoint)을 어디에 둘 것인가에 대한 이야기입니다.

### 먼저 해결해야 했던 것 — 채점 완료 순서는 제출 순서와 다르다

복구를 논하기 전에, **재적용이 안전해야 한다**는 전제가 필요했습니다.

기존 스코어보드는 `(user, problem)`마다 누적 상태만 저장했습니다.

```text
accepted       = 정답 처리 여부
wrongAttempts  = 정답 전까지 도착한 오답 수
```

ACCEPTED가 오면 그 시점의 `wrongAttempts`로 penalty를 확정하고, 이후 도착한 이벤트는 버렸습니다.
**이벤트가 제출 순서대로 도착한다는 숨은 가정**이 있었던 것입니다.

[2.1절](02-submission-pipeline.md)의 긴 꼬리 가정 때문에 이 가정은 실제로 깨졌습니다.

```text
t=10분   WRONG 제출      → 채점에 2초
t=12분   ACCEPTED 제출   → 채점에 10ms

도착 순서: ACCEPTED → WRONG
```

| 경로 | 계산 | 결과 |
|---|---|---:|
| 실시간 Redis | ACCEPTED 먼저 적용, 늦게 온 WRONG은 버림 | `12 + 0 × 5` = **12** |
| DB 재구성 | 제출 시각 순으로 재생 | `12 + 1 × 5` = **17** |

**대회 중 참가자가 본 순위와 최종 순위가 달라질 수 있었습니다.**

#### 해결: 누적하지 않고 제출 집합에서 다시 계산

누적을 버리고, 지금까지 본 제출 사실 자체를 Redis hash에 보존한 뒤
이벤트가 올 때마다 그 문제의 기여도를 처음부터 다시 계산하도록 바꿨습니다.

| 필드 | 의미 |
|---|---|
| `a:min` | 가장 이른 ACCEPTED의 contest minute |
| `a:sid` | 그 ACCEPTED의 submission ID (같은 분 tie-break) |
| `w:<submissionId>` | 각 WRONG 제출의 contest minute |
| `c:solved` / `c:penalty` | 이 문제가 summary에 기여 중인 기존 값 |

```text
새 기여 penalty = 가장 이른 ACCEPTED 시각 + (그보다 이른 WRONG 개수 × 5)
summary 반영     = 새 기여도 − 기존 c:* 기여도   (차이만 HINCRBY)
```

- 같은 WRONG이 중복 전달돼도 `w:<submissionId>`가 같은 필드를 덮어쓰므로 결과가 변하지 않습니다.
- 더 이른 ACCEPTED가 나중에 도착해도 `(분, submissionId)`를 비교해 교체하고 다시 계산합니다.

> **구현 함정 하나:** Lua의 number는 double이라 53비트를 넘는 Snowflake ID를 정확히 표현하지 못합니다.
> Lua script에서는 submission ID를 숫자가 아니라 **길이와 사전순을 이용한 십진 문자열로 비교**합니다.

이렇게 해서 **적용 순서와 중복 횟수에 무관하게 같은 결과로 수렴(commutative)**하게 되었고,
비로소 "그냥 다시 흘려보내면 된다"는 복구 전략이 가능해졌습니다.

**비용도 있습니다.** 오답을 저장하므로 문제별 상태가 커집니다.

| 문제별 상태 | 측정 메모리 |
|---|---:|
| 정답 + wrong 0건 | 112 bytes |
| 정답 + wrong 20건 | 696 bytes |
| 정답 + wrong 200건 | 5,176 bytes |

오답 하나당 약 25 bytes가 추가됩니다. 정확성을 위해 메모리를 지불한 선택입니다.

---

## 3.2 문제 — checkpoint를 어디에 둘 것인가

![스코어보드 전달 경로 비교](diagrams/scoreboard-paths.svg)

### `outbox.id`를 checkpoint로 쓸 수 없는 이유

가장 단순한 발상은 "마지막으로 적용한 `outbox.id`를 저장해두자"입니다. 하지만:

- DB auto increment ID에는 **gap이 있을 수 있습니다.**
- **DB insert 순서와 Redis 적용 순서가 같지 않습니다.**
- 실패한 작은 ID가 재시도되는 동안 더 큰 ID가 먼저 적용될 수 있습니다.

따라서 `max(outbox.id)` 하나만 저장하면 **중간 누락을 정확히 판단할 수 없습니다.**

### 근본 원인: 단조 증가 키를 누가 소유하는가

문제를 다시 정의하면 이렇습니다.
Redis 상태와 checkpoint가 **같은 시점으로 함께 되감기지 않으면** 복구가 어려워집니다.

```text
Redis 상태  → Redis RDB 스냅샷 시점으로 롤백
checkpoint  → DB에 있으므로 롤백되지 않음
              ↳ 둘의 시점이 어긋남 → 어긋난 구간을 "찾아내야" 함
```

---

## 3.3 후보 비교

### 후보 A — `redis_seq` 자연 복구 (기존 구현)

Redis Lua가 이벤트 처리마다 전역 sequence를 발급하고, 그 값을 DB outbox에 저장합니다.
Redis가 RDB로 롤백되면 counter도 낮아지므로 다음 신호가 생깁니다.

- Redis counter보다 큰 DB `redis_seq`가 존재 → **lost tail**
- 롤백 이후 새 이벤트가 과거 sequence를 재사용 → **DB에 duplicate `redis_seq`**

복구 worker가 이 신호를 주기적으로 스캔해서 해당 outbox를 `PENDING`으로 되돌립니다.

| | |
|---|---|
| **장점** | 별도 복구 모드가 필요 없어 운영 흐름을 끊지 않음 |
| **단점** | 복구 속도가 새 이벤트 유입과 스캔 주기에 영향받음. **lost-tail 스캔, duplicate group requeue, pagination**을 정합성 경로에 계속 유지해야 함 |

### 후보 B — DB gapless sequence

DB가 gapless sequence를 직접 발급하고, Redis는 그 값을 받아 복구 기준으로 씁니다.
복구 시 Redis가 성공하지 못한 가장 작은 seq를 알려주고 그 지점부터 다시 밀어넣습니다 (go-back-N).

| | |
|---|---|
| **장점** | 복구에 트래픽이 필요 없음 |
| **단점** | gapless를 보장하려면 **seq 발급 구간을 직렬화**해야 함. 복구를 여러 서버에서 돌리기 어려움 |

### 후보 C — RabbitMQ Stream offset ✅

**키의 소유권을 뒤집는 방식입니다.**

```text
기존: Redis INCR(redis_seq)  →  DB outbox에 checkpoint
변경: RabbitMQ가 offset 발급  →  Redis Lua 안에 checkpoint
```

핵심은 checkpoint를 **Redis 안에** 두는 것입니다.
그러면 스코어보드 상태와 checkpoint가 **같은 RDB 스냅샷 경계를 공유**합니다.

Lua script 하나가 다음 세 가지를 원자적으로 함께 실행합니다.

1. commutative 스코어보드 갱신 (3.1절)
2. `contest:scoreboard:stream:offset` 저장
3. `contest:scoreboard:stream:db-pending`에 submission ID 기록

```text
정상          stored offset N → request N+1 → Lua(상태 + offset N+1) → JDBC applied_at → ACK
Redis 롤백    상태 + offset가 K로 함께 복귀 → consumer 재시작 → K+1부터 replay
retention gap 첫 delivery > K+1 → 전체 DB rebuild → 첫 보존 offset을 Lua에서 연결
```

**lost-tail 탐지도, duplicate sequence 그룹도, pagination도 필요 없어집니다.**
Redis와 함께 되감긴 offset 자체가 재시작점이기 때문입니다.

> **Redis 적용과 MySQL 쓰기 사이의 틈은 별도로 처리했습니다.**
> 두 저장소가 다르므로 `scoreboard_applied_at` 컬럼은 checkpoint로 쓸 수 없습니다.
> 대신 Lua가 `db-pending` set을 offset과 함께 기록하고, consumer가 Redis 적용을 마친 뒤
> JDBC batch로 완료 처리한 다음 set에서 제거합니다.
> 그 사이에 죽으면 재전달이나 다음 기동의 `repairPending()`이 같은 batch update를 반복합니다.
> SQL은 `COALESCE(scoreboard_applied_at, CURRENT_TIMESTAMP(6))`라서 최초 완료 시각을 보존합니다.

---

## 3.4 검증 — 두 구현을 실제로 만들어 비교

**전환 계획을 A/B/C 세 단계로 나눴습니다.**

| 단계 | 내용 | 목적 |
|---|---|---|
| **A** | 기존 outbox를 유지한 채 judge 결과를 stream에 **병행 발행** | payload가 같은지 먼저 확인 (소비 경로는 그대로) |
| **B** | 스코어보드 consumer와 checkpoint를 stream으로 전환 | 실제 구현 |
| **C** | 두 구현을 같은 조건에서 비교 측정 | 채택 판단 |

한 번에 갈아엎지 않고 A단계에서 payload 동등성부터 확인한 것이,
문제가 생겼을 때 원인 범위를 좁히는 데 도움이 되었습니다.

### 측정 조건

```text
outbox 후보: 751b774  (master)
stream 후보: 925f9f3  (A + B단계 포함)

동일 조건: compose.yaml + loadtest + observability, 깨끗한 volume,
          submit-100, user 10,000 / problem 5, 같은 자원 상한
```

각 실행 전에 Prometheus 12/12 target, `oj-app` 5/5, batch-1의 지표 시계열을
**두 scrape 연속으로 확인**한 뒤에야 부하를 시작하도록 했습니다.

> **이 preflight를 넣게 된 이유:** 초기 outbox 실행 5개는
> Redis reset 뒤 batch JVM이 Prometheus에 다시 나타나기 전에 부하가 시작된 실행이었습니다.
> judge outbox가 3,700~5,300건까지 쌓이고 result p95가 34~48초가 되어 **전부 폐기**했습니다.
> 관측 준비가 안 된 상태의 수치를 결론에 쓰지 않기 위해 harness에 게이트를 추가했습니다.

### 3.4.1 성능 비교 — 결론을 내리지 않기로 한 이유

`submit-100` 관찰값입니다. `/`는 1회차 / 2회차입니다.

| 지표 | outbox | stream |
|---|---:|---:|
| scoreboard-applied p50 (ms) | 403.3 / 416.7 | **326.0 / 321.4** |
| scoreboard-applied p95 (ms) | 616.6 / 779.5 | **542.7 / 546.8** |
| scoreboard apply segment p95 (ms) | 303.1 / 298.9 | **167.5 / 156.6** |
| Redis pipeline p95 (ms) | **7.90 / 8.62** | 9.26 / 9.34 |
| scoreboard applied total | 19,465 / 19,273 | 19,419 / 19,419 |
| Redis Lua errors | 0 / 0 | 0 / 0 |

수치만 보면 stream이 유리해 보입니다. 하지만 **반복 집합 게이트를 확인했습니다.**

| 후보 | result p95 IQR/중앙값 | scoreboard p95 IQR/중앙값 | 판정 |
|---|---:|---:|---|
| outbox | 22.005% | 11.668% | **FAIL** (`≤ 5%` 불충족) |
| stream | 1.395% | 0.378% | PASS |

원래 계약은 후보별 최소 5회였지만 반복 시간 문제로 2회로 제한된 상태였습니다.
**outbox 집합의 실행 간 분산이 너무 커서 성능 비교의 근거로 쓸 수 없다고 판단**했고,
`end-to-end 성능의 승자를 정하지 않는다`고 문서에 명시했습니다.

### 3.4.2 고부하에서는 오히려 stream이 불리했다

`submit-200`(30초 ramp → 200 RPS 120초 유지, 제출 약 26,600건) 단일 관찰입니다.

| 구간 / 분위 | outbox | stream | stream 대 outbox |
|---|---:|---:|---:|
| result-queryable p95 | 644.4ms | 1,917.4ms | **+197.5%** |
| scoreboard-applied p95 | 1,082.6ms | 2,520.8ms | **+132.8%** |
| scoreboard apply segment **p50** | 206.9ms | **136.8ms** | **−33.9%** |
| scoreboard apply segment p95 | 475.4ms | 947.7ms | **+99.3%** |

**낮은 부하에서 보였던 중앙 경로의 이점이 높은 부하의 tail에서는 사라졌습니다.**

원인을 한 구간에 귀속시키려 했지만 실패했습니다.

- 스코어보드나 Redis가 관여하기 **전인** result-queryable p95부터 stream이 커졌으므로 Redis 탓이 아닙니다.
- `batch-1`에는 judge outbox relay와 stream consumer가 **함께** 있어, stream 실행의 높은 CPU throttling(19.52% vs 11.83%)이 relay에도 퍼졌을 수 있습니다.
- stream은 같은 결과 수에 Redis pipeline을 **2.22배 자주** 호출했습니다 (더 작고 잦은 consumer batch).
- Redis 컨테이너 CPU peak는 오히려 stream이 낮아(13.31% vs 15.48%) Redis 서버 포화로도 설명되지 않습니다.

confirm 전용 지표가 없어 기여도를 분리할 수 없었으므로,
**"이 요인들이 함께 움직였다"는 범위까지만 결론 냈습니다.**

정합성은 두 구현 모두 유지했습니다 — 제출 수 = 결과 수 = 반영 수, 최종 pending 0, DLQ 0, Lua error 0.

### 3.4.3 복구 실험 — 실제로 Redis를 죽였습니다

이것이 **채택을 결정한 측정**입니다.

복구 harness의 절차는 다음과 같습니다.

```text
1. batch consumer를 잠시 멈추고 SAVE로 스냅샷 시점 K를 보존
2. 새 제출 tail을 완전히 적용한 시점 N의 스코어보드 SHA-256 digest를 기록
3. Redis를 SIGKILL하고 K의 RDB로 되돌림
4. checkpoint와 전체 스코어보드 digest가 N으로 돌아올 때까지 500ms마다 확인
```

일부 key만 지우는 시험이 아니라 **스코어보드와 checkpoint가 함께 되감기는 일관된 RDB 롤백**입니다.

![복구 흐름 비교](diagrams/recovery-timeline.svg)

| 후보 | 롤백된 tail | 자동 수렴 시간 | 비고 |
|---|---:|---:|---|
| **stream 1** | 91건 | **2.533s 이내** | digest/pipeline 수렴 |
| **stream 2** | 90건 | **2.038s 이내** | applied +90, rollback counter +1 |
| **outbox 1** | 91건 | **30.954s** | 5초 주기 스캔 → DB requeue → Lua 재적용 |

제어 흐름의 차이가 그대로 드러납니다.

```text
outbox:  Redis K → 5초 스캔 → lost-tail/duplicate 탐지 → DB requeue → Lua 재적용
stream:  Redis 상태+offset K → 롤백 감지 → K+1부터 stream 직접 replay
```

> **이 시간 차이를 성능 배수로 인용하면 안 됩니다.**
> outbox 복구는 시간 절약을 위해 축소된 기준점(30명, 73 results)을 썼고,
> 이는 **outbox에 유리한 조건**입니다.
> 또한 stream 두 실행은 의미 상태가 수렴한 뒤 Docker health가 회복되기 전에 검사한
> harness 문제로 최종 게이트가 FAIL 처리되어, 정식 PASS 실행의 대표값이 아닌
> **수렴 상한 관찰값**으로 취급합니다.
>
> 드러나는 것은 시간이 아니라 **제어 흐름의 복잡도 차이**입니다.
> outbox는 Redis와 DB 사이의 어긋남을 *찾아야* 하고 tail보다 많은 작업을 만들 수 있는 반면,
> stream은 되감긴 offset 자체가 재시작점입니다.

---

## 3.5 판단 — 성능이 아니라 복구 불변식을 근거로

**RabbitMQ Stream 방식을 조건부 채택했습니다.**

채택 근거를 명확히 구분했습니다.

| 근거로 쓴 것 | 근거로 쓰지 않은 것 |
|---|---|
| ✅ 복구 불변식이 단순해짐 — lost-tail 스캔, duplicate `redis_seq` 그룹, pagination, requeue를 **운영 정합성 경로에서 제거** | ❌ end-to-end 성능 (n=2 제한 + outbox 집합 FAIL) |
| ✅ 실제 RDB 롤백에서 offset부터 직접 replay해 같은 스코어보드로 수렴한 관찰 | ❌ 고부하 처리량 (submit-200에서 오히려 tail 악화) |

**성능이 좋아서 고른 것이 아닙니다.**
오히려 200 RPS에서는 tail 위험을 추가로 관찰했고, 그 사실을 결론과 함께 남겼습니다.
고른 이유는 **운영 중 정합성 경로에서 지워야 할 코드가 줄어든다**는 것입니다.

## 3.6 남은 한계와 production 전환 게이트

조건부 채택이므로 무엇을 더 검증해야 production에 올릴 수 있는지 명시했습니다.

| # | 남은 검증 | 이유 |
|---|---|---|
| 1 | judge의 stream confirm 대기시간과 outbox head lag를 **분리 계측** | 3.4.2의 앞단 tail 원인을 confirm 비용과 relay 경합으로 나누지 못함 |
| 2 | consumer prefetch와 적용 batch 크기 조정 후 **CPU throttling 없는 예산에서 재비교** | Redis pipeline 호출 수를 outbox 수준으로 맞춰야 공정한 비교 |
| 3 | 후보별 **최소 5회** 기준 복원 | 성능을 채택 논거로 쓰려면 필요 |
| 4 | **다중 노드 stream replication / leader failover** 검증 | 현재 Compose는 단일 RabbitMQ 노드. HA 검증 완료로 해석하면 안 됨 |

### 구조적으로 남는 한계

- **AMQP 0.9.1에는 stream single-active-consumer 조정이 없습니다.**
  native stream protocol(5552) 기능이므로, 현재 설계에서는 스코어보드 consumer를 **`batch-1` 한 인스턴스로 제한**했습니다.
  Lua가 offset 불연속 시 실패하므로 잘못된 다중 소비가 조용히 offset을 건너뛰지는 않습니다.
- **poison 이벤트를 건너뛰지 않습니다.** Redis pipeline은 앞 명령이 실패해도 뒤 `EVAL`을 실행할 수 있어
  checkpoint가 poison 뒤로 점프할 수 있습니다. 그래서 live batch는 **offset 순서로 fail-fast** 실행하고,
  batch 전체를 requeue합니다. 스킵보다 정지를 택한 선택입니다.
- **선택적 key 손상은 자동 복구되지 않습니다.** 일관된 RDB 롤백이 아니라
  운영자가 일부 key만 지우거나 type이 손상된 경우에는 롤백 신호가 나타나지 않을 수 있습니다.
  이 경우 contest 단위 전체 rebuild(`POST /actuator/contestscoreboard?contestId={id}`)가 필요합니다.

### 검증을 고정한 회귀 테스트

- `InMemoryContestScoreboardCommutativityTests` — 무작위 순서·중복 적용에도 같은 결과
- `RedisContestScoreboardApplierRedisIntegrationTests` — 실제 Redis에서 Lua 계약 검증
- `ContestScoreboardLiveVersusRebuildRedisIntegrationTests` — **실시간 경로와 DB 재구성 경로의 결과 일치**

마지막 테스트가 3.1절에서 발견한 문제(실시간 순위 ≠ 최종 순위)가 재발하지 않는다는 보증입니다.

---

## 3.7 이 장에서 얻은 판단

- **복구를 쉽게 만들려면 상태와 checkpoint가 같은 실패 경계를 공유해야 합니다.** 둘이 다른 저장소에 있으면 "어긋남을 찾는" 코드가 영원히 필요합니다.
- **재적용이 안전해야 복구 전략을 단순하게 만들 수 있습니다.** commutative 갱신(3.1절)이 먼저 있었기 때문에 "그냥 다시 흘려보낸다"가 가능했습니다.
- **측정했는데 결론을 못 내리는 경우가 있습니다.** 그때는 수치를 근거로 포장하지 않고, 무엇을 근거로 골랐는지와 무엇이 미검증인지 함께 남기는 편이 낫다고 판단했습니다.
