# 2. 대회 제출 파이프라인 — 저장부터 채점 분배까지

> 제출 요청 하나가 DB에 저장되고 채점 서버로 전달되기까지의 경로를,
> 개선 사례별로 나열하지 않고 **요청이 지나가는 순서대로** 정리했습니다.

---

## 2.1 배경 — 만족해야 했던 조건

대회 제출 경로가 지켜야 하는 조건을 먼저 고정했습니다.

| 조건 | 의미 |
|---|---|
| **유실 금지** | HTTP 성공을 반환했다면 제출 원본은 DB에 commit되어 있어야 한다 |
| **중복 저장 금지** | `(contest, problem, user, codeHash)`가 같은 제출은 한 건만 저장한다 |
| **격리** | 채점이 늦어져도 다른 제출의 처리가 같이 막히지 않아야 한다 |
| **복구 가능** | 프로세스나 broker 장애 후 미완료 작업을 다시 처리할 수 있어야 한다 |

부하 모델은 다음과 같이 가정했습니다.

```text
최대 유입      약 1,000 TPS
채점 평균      약 10ms   (대회용 부분 채점)
채점 긴 꼬리   약 1,000건 중 1건이 최대 2초
```

**이 "1,000건 중 1건"이 설계 전체를 결정한 조건입니다.**
평균만 보면 아무 구조나 통과하지만, 2초짜리 한 건이 나머지를 막느냐 아니냐에서 구조가 갈립니다.

## 2.2 초기 구조와 한계

![채점 구조의 변화](diagrams/judge-evolution.svg)

### V0 — Spring event + 비동기 listener

제출을 저장한 뒤 애플리케이션 이벤트를 발행하고, 비동기 listener가 건별로 채점 API를 호출했습니다.

**한계:** 이벤트가 JVM 메모리에만 있습니다.
DB commit 후 listener 실행 전에 프로세스가 죽으면 **그 제출은 영원히 채점되지 않습니다.**
복구 지점이 어디에도 없었습니다.

### V2 — 한 트랜잭션 안에서 여러 건 채점

`SELECT ... FOR UPDATE SKIP LOCKED`로 미채점 제출을 여러 건 가져와,
같은 트랜잭션 안에서 채점 API를 병렬 호출하고 결과를 batch로 저장했습니다.
중간에 죽으면 rollback으로 정리된다는 점이 매력적이었습니다.

**한계:** 외부 API 호출 동안 DB 트랜잭션과 커넥션이 계속 열려 있습니다.
그리고 100건을 묶었을 때 **99건이 10ms에 끝나도 1건이 2초면 트랜잭션 전체가 2초입니다.**
2.1절에서 가정한 긴 꼬리가 정확히 이 지점을 때립니다.

### V3 — claim / 채점 / 저장 분리

트랜잭션을 짧게 만들기 위해 단계를 나눴습니다.

```text
1. 짧은 트랜잭션으로 DB에서 제출을 claim (PROCESSING, claim_token, claimed_at)
2. 메모리 큐와 worker pool에서 채점
3. 완료 결과를 모아 DB batch로 저장
4. 오래된 claim은 sweeper가 회수
```

외부 API 호출을 트랜잭션 밖으로 꺼냈고 head-of-line blocking도 사라졌습니다.
하지만 **claim timeout, heartbeat, lease, sweeper, JVM in-flight를 전부 직접 구현하고 관찰**해야 했습니다.
timeout이 짧으면 정상적으로 오래 걸린 작업을 중복 채점하고, 길면 실제 장애 복구가 늦어집니다.
이 값을 정할 근거가 없다는 것이 다음 단계로 넘어간 이유입니다.

---

## 2.3 저장 경로 — JVM 배치 큐와 batch rewrite

### 문제: 설정은 batch인데 실제 SQL은 batch가 아니었다

제출마다 즉시 insert하지 않고 JVM 큐에 모아 chunk 단위로 쓰도록 바꿨습니다.
Hibernate batch size와 `rewriteBatchedStatements=true`를 켰는데도 처리량이 오르지 않았습니다.

원인은 애플리케이션이 ID를 먼저 발급한 엔티티를 `saveAll()`에 넘긴 것이었습니다.
Spring Data JPA는 **ID가 이미 있는 엔티티를 신규 row가 아니라고 판단해 `persist`가 아닌 `merge` 경로**를 택합니다.
그 결과 신규 제출인데도 row마다 존재 확인 쿼리가 붙었습니다.

```sql
select ... from contest_submission where id = ?;   -- row마다 반복
```

INSERT 앞에 SELECT가 반복되면 batch 설정을 아무리 켜도 요청당 DB 왕복이 줄지 않습니다.
여기에 OSIV가 켜져 있어 커넥션 점유 시간까지 길어졌습니다.

### 해결

1. DB `AUTO_INCREMENT`를 기다리지 않도록 애플리케이션에서 **Snowflake ID를 먼저 발급**
2. `saveAll()`의 `merge` 대신 `EntityManager.persist()`로 신규 insert 경로를 명확히 함
3. 이후 행별 사후 판별이 필요해지면서 임계 경로를 **JDBC batch로 단일화**
4. **설정값을 믿지 않고** MySQL general log와 드라이버 호출로 실제 multi-values rewrite를 확인

```sql
INSERT IGNORE INTO contest_submission (...)
VALUES (...), (...), (...);   -- 실제로 이 모양이 되는지 로그로 검증
```

### 측정 — 저장 경로만 격리했을 때의 첫 실패 지점

| 시나리오 | Immediate | Bulk |
|---|---:|---:|
| insert 1건, pool 100 | 4,000 RPS | **6,000 RPS** |
| insert 3건, pool 100 | 4,000 RPS | **5,000 RPS** |
| insert 1건, pool 10 | 2,000 RPS | **4,000 RPS** |
| insert 3건, pool 10 | 2,000 RPS | **4,000 RPS** |

> **이 수치를 end-to-end 향상률로 읽으면 안 됩니다.**
> 제출 저장 경로만 격리한 값이고, HTTP·RabbitMQ·채점·스코어보드는 포함되지 않았습니다.
> 커넥션 풀이 작을수록(pool 10) 이득이 큰 것은 batch가 커넥션 점유 시간을 줄이기 때문입니다.
>
> 트레이드오프도 있습니다. HTTP 응답이 batch commit을 기다리므로,
> 부분 chunk는 flush 주기만큼 추가로 대기합니다.

---

## 2.4 `INSERT IGNORE` — 숨은 누락을 찾아내기

![INSERT IGNORE 사후 판별](diagrams/insert-ignore.svg)

### 문제 1: 중복 한 건이 정상 99건을 함께 rollback시켰다

batch는 최대 100건을 한 트랜잭션으로 묶습니다.
그런데 이미 저장된 코드를 다시 제출한 요청 하나가 unique key를 위반하면,
**같은 트랜잭션의 정상 제출 99건까지 전부 rollback**됩니다.

일반 `INSERT`를 `INSERT IGNORE`로 바꿔 중복 row만 무시하도록 했습니다.

### 문제 2: `INSERT IGNORE`는 중복만 무시하는 게 아니었다

`INSERT IGNORE`는 unique 충돌뿐 아니라 **FK 위반 같은 오류도 경고와 함께 조용히 무시**합니다.
user 사전 SELECT를 제거하고 `getReferenceById()`로 바꾼 뒤로는
존재하지 않는 user가 FK 위반으로 드러나는데, 이것이 "정상 중복"과 구분되지 않았습니다.

게다가 Connector/J가 batch를 rewrite하면 JDBC update count가 `SUCCESS_NO_INFO(-2)`를 반환합니다.
**어떤 행이 삽입되고 어떤 행이 무시됐는지 update count만으로는 알 수 없습니다.**

### 해결: INSERT 직후 같은 트랜잭션에서 다시 조회해 분류

| 조회 결과 | 판정 | 처리 |
|---|---|---|
| 예약 ID에 요청과 같은 key의 row가 존재 | **새 삽입** | 예약 ID 반환, judge outbox 생성 |
| 예약 ID는 없고 같은 dedup key의 row가 존재 | **정상 중복** | 기존 submission ID 반환, outbox 미생성 |
| 예약 ID와 dedup key 모두 없음 | **설명할 수 없는 누락** (FK 위반 등) | 정합성 예외 → rollback |
| 예약 ID가 다른 key의 row를 가리킴 | **Snowflake ID 충돌 가능성** | fail-fast → rollback |

이 판별 덕분에 **MySQL이 중복의 유일한 권위**가 되었고, 앞단의 Redis 중복 캐시가 필요 없어졌습니다.

### 문제 3: 그래도 불량 1건이 정상 99건을 실패시켰다

누락을 발견해도 예외가 발생하면 chunk 전체가 rollback됩니다.
처음에는 그 예외를 chunk 전체의 Future에 그대로 전달해,
**존재하지 않는 user 한 명 때문에 정상 제출 99건도 함께 실패**했습니다.

정합성 예외에 문제가 된 예약 ID 목록을 담고, chunk를 두 집합으로 나눴습니다.

```text
offenders  = 정합성 예외가 지목한 요청 + 같은 dedup key를 공유하는 요청
survivors  = 같은 트랜잭션 때문에 함께 rollback됐을 뿐 정상인 요청
```

offenders의 Future만 실패시키고, survivors는 **이미 예약한 동일 ID로 한 번만 다시 batch insert**합니다.
첫 시도가 rollback으로 완전히 취소됐고 요청별 예약 ID가 고정되어 있으므로 재실행이 안전합니다.
재실행에서는 다시 격리하지 않아 **chunk당 DB 시도 횟수를 최대 2회로 제한**했습니다.

---

## 2.5 채점 전달 — DB claim에서 outbox + RabbitMQ로

2.2절 V3의 claim 구조는 동작했지만, timeout·heartbeat·sweeper를 직접 관리해야 했습니다.
이 책임을 검증된 broker에 넘기기로 하고 후보를 비교했습니다.

### 후보 비교

| 후보 | 장점 | 이 프로젝트에서의 문제 |
|---|---|---|
| **Kafka classic consumer** | 높은 순차 처리량, replay, partition 확장 | 한 번의 `poll()`로 받은 record를 worker pool에 넘기면 **완료 순서가 offset 순서와 달라짐**. offset 10이 느리고 11~20이 먼저 끝나도 20까지 commit할 수 없어, 연속 구간을 직접 추적해야 함 — claim 코드와 다른 형태의 복잡성이 되돌아옴 |
| **Kafka Share** | record 단위 accept/release, work queue에 가까움 | broker가 acquired 상태를 강하게 영속화하지 않아 중복 전달은 여전. 운영·개념 복잡도가 큰데 **replay 장점을 이 프로젝트가 쓰지 않음** |
| **Redis Streams** | 이미 Redis를 쓰므로 인프라 추가 없음 | pending reclaim, trimming, AOF/RDB durability를 직접 관리. **대량 backlog를 스코어보드와 같은 Redis 메모리에 두는 것이 불리** |
| **RabbitMQ quorum queue** ✅ | 메시지 하나를 consumer 하나에게 주는 **work queue 의미가 명확**. `prefetch`로 consumer별 in-flight 제한. 연속 offset 추적 불필요. retry/DLQ 의미가 작업 처리와 맞음 | 긴 backlog는 Kafka보다 비쌈. replay와 다중 consumer group이 자연스럽지 않음 |

**선택 근거:** 이 프로젝트의 본질은 replay 가능한 이벤트 로그가 아니라
**각 제출을 한 worker가 가져가 완료하는 work queue**입니다.
Kafka가 구조적으로 나쁜 게 아니라, Kafka의 강점(replay·audit·다중 consumer group)을 쓰지 않는 문제였습니다.

### 왜 RabbitMQ만 쓰지 않았나 (DB outbox를 유지한 이유)

RabbitMQ-only도 검토했지만 다음이 남습니다.

- 제출 코드와 조회 가능한 상태는 **결국 DB에 저장해야 함**
- `(contest, problem, user, codeHash)` unique 제약은 **DB가 가장 명확하게 보장**
- HTTP 성공 후 DB 저장이 실패하면 사용자가 본 성공과 실제 조회 상태가 달라짐

그래서 HTTP 요청에서 **제출 원본과 `contest_judge_outbox`를 같은 트랜잭션에 commit**합니다.
DB와 RabbitMQ 사이에 2PC를 쓰지 않고, outbox와 멱등성으로 at-least-once를 만듭니다.

```text
contest_judge_outbox claim (FOR UPDATE SKIP LOCKED, 최대 500건)
  → persistent message 발행
  → Rabbit publisher confirm
  → outbox PUBLISHED 완료
```

> **주의한 점:** outbox가 있다고 해서 Rabbit durability를 낮출 수 있는 게 아닙니다.
> 현재 relay는 confirm을 받으면 outbox를 `PUBLISHED`로 바꾸고 **그 이후는 추적하지 않습니다.**
> 따라서 confirm 이후 유실되면 DB가 자동으로 재발행하지 않으므로,
> `quorum queue + persistent message + publisher confirm`을 모두 유지해야 합니다.

### ACK 순서를 고정했습니다

채점 결과의 완료 순서는 반드시 다음과 같습니다.

```text
contest_submission_result commit
  → result stream publisher confirm
  → judge work queue ACK
```

이 순서를 바꾸면 복구 연결이 끊깁니다.
publish를 commit 앞에 두거나 confirm을 기다리지 않고 listener를 반환하면,
**"DB commit 후 publish 전 장애"를 work queue 재전달로 복구할 수 없게 됩니다.**

재전달됐을 때는 먼저 `contest_submission_result`를 조회해서,
행이 있으면 **채점 API를 다시 호출하지 않고 저장된 결과만 재발행**합니다.

---

## 2.6 관측 지표로 병목을 다시 찾기

구조를 바꾼 뒤 실제 병목이 어디인지 지표로 확인했습니다.
**세 번 모두 예상과 달랐습니다.**

### 사례 1 — relay가 33건/초밖에 처리하지 못했다

Rabbit 큐는 계속 비어 있는데 outbox가 쌓였습니다.
broker가 아니라 **메시지마다 `markPublished()`를 개별 UPDATE하는 DB 왕복**이 병목이었습니다.

```text
개선 전:  publish → confirm 대기 → UPDATE  (건별 반복)
개선 후:  전부 publish → confirm Future 모아서 대기 → JDBC batch UPDATE 1회
```

여기서 **relay worker 수를 먼저 늘리지 않은 것**이 판단의 핵심입니다.
worker를 늘리면 같은 비효율을 병렬화하면서 DB 커넥션과 lock 경합만 늘렸을 것입니다.

### 사례 2 — Redis가 느린 게 아니라 스레드가 기다리고 있었다

1,000 TPS 진단 실행에서 응답 p95가 16초까지 올라갔습니다. Redis를 의심했습니다.

| 측정 대상 | 값 |
|---|---:|
| Redis 서버의 `HGET` 자체 실행시간 | **0.72 µs** |
| Redis 서버의 `HSET` 자체 실행시간 | **6.62 µs** |

Redis는 느리지 않았습니다. JFR로 Java 스레드의 park 시간을 봤습니다.

| 위치 | 표본 수 | 평균 park | 최대 park |
|---|---:|---:|---:|
| 요청 전 dedup `HGET` | 4,641 | **141.3ms** | 1,240ms |
| commit 후 dedup `HSET` | 2,235 | 61.2ms | 1,130ms |
| TTL 등 추가 Redis 명령 | 2,207 | 67.3ms | 1,140ms |

병목은 Redis의 자료구조 연산이 아니라,
**CPU가 포화된 상태에서 Lettuce event loop가 스케줄링을 기다리는 동안
동기 호출을 수행한 Tomcat·completion 스레드가 함께 park된 것**이었습니다.

**해결은 Redis 튜닝이 아니라 Redis 제거였습니다.**
Redis 선조회는 batch 실패 가능성을 낮출 뿐 DB unique 제약을 대체하지 못합니다.
2.4절의 사후 판별이 그 역할을 이미 하고 있었으므로,
요청 전 `HGET`과 commit 후 `HSET + EXPIRE`를 **제출 임계 경로에서 모두 제거**했습니다.

### 사례 3 — 큐는 쌓이지 않았다

같은 진단 실행의 DB bulk 지표입니다.

| Web | 제출 수 | chunk 평균 | max pending | completion 큐 평균 대기 |
|---|---:|---:|---:|---:|
| Web 1 | 7,503 | 36.1ms | 101 | 898ms |
| Web 2 | 7,500 | 36.3ms | 90 | 932ms |

MySQL batch insert 큐는 크게 쌓이지 않았고, RabbitMQ·judge·스코어보드도 최종적으로 모두 drain됐습니다.
**"어딘가 쌓였겠지"라는 추측이 틀렸다는 것을 지표가 알려줬습니다.**
GC도 최대 194ms pause가 있었지만 초 단위 지연의 주원인이 아니었습니다.

> 이 진단 실행은 JFR과 스레드 덤프를 켜고 같은 장비에서 부하를 준 것이라
> **용량 수치로는 쓰지 않고 대기 원인 분석에만 사용**했습니다.

---

## 2.7 과부하는 어디에 쌓이는가

"큐 하나가 모든 과부하를 흡수한다"는 그림은 이 시스템에 맞지 않습니다.
단계마다 다른 곳에, 다른 영속성으로 쌓입니다.

![과부하가 쌓이는 위치와 영속성 경계](diagrams/backpressure.svg)

| 단계 | 대기 위치 | 영속성 | 포화 동작 |
|---|---|---|---|
| DB 저장 전 | bulk writer 큐 + semaphore | ❌ | `max-in-flight` 초과 시 **HTTP 503 + Retry-After** |
| DB 저장 후 응답 전 | completion executor (4 threads, queue 64) | ❌ | 큐가 차면 CallerRuns가 bulk worker를 막음 |
| Rabbit publish 전 | `contest_judge_outbox` PENDING rows | ✅ MySQL | DB 용량과 relay drain rate만큼 축적 |
| Judge 대기 | Rabbit live queue ready messages | ✅ quorum queue | broker disk 사용 증가 |
| Judge 처리 중 | 최대 약 32건 unacked | ✅ Rabbit 관리 | `concurrency=16×2`, `prefetch=1` |
| 스코어보드 대기 | stream tail offset − Redis 적용 offset | ✅ stream retention | pending offset 증가 |
| 최종 실패 | Dead-letter queue | ✅ quorum queue | 운영자가 재처리할 때까지 유지 |

**의도적으로 비영속 구간을 남겼습니다.** DB commit 전 요청은 유실될 수 있습니다.
대신 admission semaphore로 **유실 가능 범위의 상한을 설정값으로 고정**했습니다.
`max-in-flight=256` 기준선 측정에서 정확히 상한에서 멈췄고,
거부된 제출 수와 HTTP 503 수가 일치하는 것을 확인했습니다.

`prefetch=1`을 선택한 이유도 같습니다.
consumer 하나가 unacked 메시지를 하나만 갖게 하면,
**2초짜리 채점 한 건이 다른 consumer의 진행을 막지 못합니다.** (2.1절의 긴 꼬리 가정)

---

## 2.8 결과와 남은 한계

### 확인한 것

2026-08-09 `submit-100` 실행(제출 19,386건) 기준:

| 검증 항목 | 결과 |
|---|---|
| 제출 수 = 결과 수 = 스코어보드 반영 수 | **19,386으로 전부 일치** |
| 최종 미반영(unapplied) | **0** |
| unacked 상태의 consumer connection을 강제 종료 | 기존 결과 1행 유지, stream entry만 1건 증가, **채점 API 호출 0회** |

마지막 항목이 2.5절의 ACK 순서 설계가 실제로 동작한다는 증거입니다.

### 현재 보장하는 것과 보장하지 않는 것

```text
보장:      HTTP 성공 → 제출 원본 DB commit 완료
보장 안 함: DB commit → HTTP 성공 응답
```

DB commit 후 응답 전에 프로세스가 죽으면 사용자는 실패를 보지만 제출은 존재합니다.
분산 시스템에서 일반적인 결과이며, **클라이언트 재시도 + 멱등 처리로 흡수**합니다.
이때 2.4절의 사후 판별이 기존 submission ID를 돌려주는 것이 중요합니다.

### 남은 한계

| 한계 | 내용 |
|---|---|
| **채점 서버가 stub** | 현재 채점은 즉시 결과를 반환하는 stub입니다. 지금까지의 부하 테스트는 RabbitMQ와 DB 흐름을 검증했지만, **2.1절에서 가정한 p999 2초를 실제로 재현한 것은 아닙니다.** |
| **Snowflake worker ID** | 인스턴스별 유일성을 배포 검증으로 강제하지 못하고 있습니다. 현재는 Compose 설정으로 고정하고, 충돌 시 batch 정합성 조회가 fail-fast합니다. |
| **DLQ 운영** | retry를 소진한 메시지는 DLQ로 가지만, 자동 replay 정책은 없습니다. |
| **exactly-once 아님** | outbox → Rabbit 경로는 at-least-once입니다. 이를 억지로 exactly-once로 만들지 않고 submission ID 기반 멱등 처리로 중복을 흡수합니다. |

### 이 장에서 얻은 판단

- **캐시 상태를 정확성의 전제로 두지 않는다.** Redis dedup은 성능 최적화였을 뿐 정합성 장치가 아니었고, 결국 임계 경로에서 제거했습니다.
- **설정을 켰다고 동작한다고 믿지 않는다.** batch 설정과 실제 SQL은 달랐고, 로그로 확인해야 알 수 있었습니다.
- **병목을 늘리기 전에 없앤다.** relay worker를 늘리는 대신 건별 DB 왕복을 제거했습니다.
