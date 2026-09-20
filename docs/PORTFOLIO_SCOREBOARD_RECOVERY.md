# Redis Scoreboard Recovery

> **이 문서는 폐기된 설계의 기록이다.** `contest_submission_outbox`를 복구 기준으로 삼는
> `outbox.id`/`redis_seq` 방식은 더 이상 쓰이지 않는다. §폐기된 설계와 달라진 점이 그 이유와,
> 지금 코드가 대신 무엇을 하는지를 정리한다. 현재 구현은
> [`ARCHITECTURE.md` §3 복구 경계](ARCHITECTURE.md#3-복구-경계)를 본다.

## 무엇을 해결했는가

대회 스코어보드를 Redis에 두고 redis가 죽었을 때 효율적인 회복법을 구현했다.

## score board 구조

당시 구성:

judge 서버 : outbox 생성
batch 서버 : outbox 주기적으로 redis로 전송
redis score board : score board 관리

지금 구성:

judge 서버 : 채점 결과 저장 후 result stream 발행
batch 서버 : stream 소비 → 단일 Lua EVAL로 scoreboard + offset 반영
redis score board : score board 관리

## 문제

복구할때 `contest_submission_outbox.id`는 아쉬움이 있다.

- `outbox.id`는 DB insert 순서이지 Redis 적용 순서가 아니다.
- 실패와 재시도로 인해 일부 id만 비어 있을 수 있다.

 `outbox.id`만으로는 Redis 회복이 어렵다.

## 비교

### 자연 복구

- Redis에서 하나의 처리를 완료할 때마다 `redis_seq`를 발행하고 이를 outbox에 저장한다. 
- 만약 같은 값이 들어오면 같은 `redis_seq`를 반환한다.
- Redis가 죽었다가 RDB 방식으로 스냅샷을 복구하면 `redis_seq`도 낮아져서 outbox에서 duplicate `redis_seq`가 발생한다
- batch 서버가 이 중복 seq를 주기적으로 찾아 replay한다

장점: 별도 복구 모드가 필요 없어서 운영 흐름을 끊지 않는다

단점: 복구 속도가 트래픽에 영향을 받는다

### 대안 DB gapless seq

DB가 `gapless seq`를 직접 발급한다.

- outbox insert 시점에 DB가 별도 seq를 부여한다
- Redis는 이 seq를 그대로 받아 scoreboard 반영과 복구 기준으로 사용한다
- 복구 시에는 Redis가 성공하지 못한 가장 작은 seq를 보내고, Spring에서 그 seq부터 이미 처리됐어야할 것들을 다시 밀어 넣는다 (go back n)

장점: 트래픽이 복구에 필요하지 않다.

단점 :
- gapless를 보장하려면 seq 발급 구간을 직렬화해야 한다
- 위의 방식과는 다르게 회복을 여러 서버에서 돌리기 쉽기 않다.

### 선택
자연복구 방식을 사용하고 대회가 끝나고 나서 혹시 있을지도 모르는 미처리 중복을 처리해준다.

## 트래픽 없이도 복구하기 (lost tail)

자연 복구의 단점을 메우기 위해 두 번째 장치를 뒀다. 중복 seq는 **새 트래픽이 유실된 구간을 다시 밟아야** 생기므로, 유실 직후 트래픽이 없으면 아무것도 감지되지 않는다. 대회 종료 직후 유실이 딱 이 경우다.

- outbox에 기록된 seq 중 상위 N개를 가져온다
- Redis 할당자의 현재 값을 읽는다
- 할당자보다 큰 seq를 가진 행을 replay 대상으로 본다

### 읽는 순서가 정확성의 전부다

seq는 **할당자가 발급한 뒤에야** outbox에 기록된다. 따라서 DB에서 보이는 행은 그 시점에 이미 할당자가 커버하던 값이다.

- **DB 먼저 → Redis 나중**: 그 사이 워커가 진행하면 할당자만 올라간다. `seq > 할당자`가 성립하면 그건 진짜 Redis 퇴행이다.
- **Redis 먼저 → DB 나중** (처음 구현): 기준선이 낡는다. 읽은 뒤 워커가 완료한 정상 행들이 전부 유실로 보여 불필요하게 requeue된다. 부하가 높을수록 오탐이 늘어난다.

재적용은 멱등하므로 오탐이 스코어보드를 깨지는 않았지만, 상시 재처리 낭비였다. 순서를 뒤집어 오탐을 구조적으로 제거했다.

이 순서 규율은 지금도 그대로 유효하다. 바뀐 것은 **무엇을 읽는가**뿐이다 — outbox 행이 아니라
`contest_submission_result.scoreboard_applied_seq`를 읽고, outbox 할당자가 아니라 Redis 할당자
`contest:scoreboard:seq`를 읽는다.

## 폐기된 설계와 달라진 점

이 문서가 서술하는 설계는 outbox를 복구 기준으로 삼았다. 그 전제가 무너져 아래와 같이 바뀌었다.

| 폐기된 설계 | 현재 구현 | 이유 |
|---|---|---|
| `contest_submission_outbox.id`가 진행 순서 | RabbitMQ Stream offset / Redis seq | `outbox.id`는 DB insert 순서이지 Redis 적용 순서가 아니고, 실패·재시도로 비는 구간이 생긴다 |
| seq를 `contest_submission_outbox.redis_seq`에 저장 | `contest_submission_result.scoreboard_applied_seq` (V18) | outbox는 V1부터 `ON DELETE CASCADE`이고 `ContestFinalizationService`가 대회 제출을 purge하므로 **최종화 시 seq 기록이 사라진다.** 컬럼명도 레거시 `redis_seq`와의 혼동을 피해 바꿨다 |
| outbox를 relay가 주기적으로 Redis로 전송 | 결과가 RabbitMQ Stream으로 발행되고 batch가 소비 → 단일 Lua EVAL | 적용 경로가 하나여서 세 복구 방식이 **같은 적용 로직**을 공유한다 |
| 별도 복구 모드 없이 자연 복구 하나 | `contest.scoreboard.recovery.mode`로 셋 중 하나 선택 | 세 방식을 같은 조건에서 비교하려면 각 방식이 실제로 기동·동작해야 한다 |
| DB가 gapless seq를 발급하는 대안 | Redis가 seq를 발급 | gapless를 보장하려면 발급 구간을 직렬화해야 한다(대안이 스스로 밝힌 단점). 할당자와 매핑이 같은 Redis에 있으면 RDB 롤백 시 스코어보드와 함께 되감긴다 |
| 상위 N건만 읽어 `seq > 할당자`를 판정 | `check-window-size` × `max-windows-per-pass`까지 내림차순 keyset으로 이어 읽음 | 상위 N건만 읽으면 **할당자가 N보다 깊이 퇴행했을 때 누락을 놓친다** |

### 지금의 세 방식

셋은 전송도 채점도 바꾸지 않는다. **체크포인트와 복구 기전**만 다르다.

| 모드 | 복구 기준 | 체크포인트를 움직이는가 |
|---|---|---|
| `stream-offset`(기본) | scoreboard와 같은 Lua EVAL에서 저장된 stream offset | 움직인다 |
| `full-replay` | MySQL `contest_submission_result` 전량 | 움직이지 않는다 |
| `redis-seq` | Redis가 발급해 DB에 남긴 `scoreboard_applied_seq` | 움직이지 않는다 |

`full-replay`는 **Redis를 초기화하지 않는다.** 이전의 rebuild 경로는 첫 줄에서 `reset`을 호출해
RDB에서 복원된 standings를 지웠고, retention gap fallback이 그 경로를 타서 모든 대회의 Redis 상태를
날렸다. 지금은 reset 없이 전량 replay하고 이미 반영된 행은 `processed` set이 흡수한다.

세 방식 모두 **미채점(PENDING) 행을 재생에서 제외**한다. Lua는 PENDING일 때 standings 변형만
건너뛰고 `processed` set 등록은 무조건 실행하므로, PENDING을 한 번 적용하면 그 제출의 실제 채점
결과가 영구히 무시된다.

### 자연 복구에서 실제로 유지된 것과, 유지되지 않은 것

- **유지**: "중복 seq를 주기적으로 찾아 replay한다"는 골격, 그리고 §읽는 순서의 DB 먼저 → Redis 나중
  순서. `redis-seq` 모드가 이 둘을 그대로 쓴다.
- **유지**: "별도 복구 모드가 필요 없어 운영 흐름을 끊지 않는다"는 자연 복구의 장점은 `stream-offset`
  기본값으로 남아 있다. 트래픽이 복구를 돕는다는 단점도 그대로이며, 그래서 트래픽 없이도 도는
  lost-tail 검사를 별도로 둔다.
- **유지되지 않음**: "같은 값이 들어오면 같은 `redis_seq`를 반환한다"는 규칙은 **반환값으로는 남지
  않았다.** 지금 Lua는 이미 처리된 제출을 `sismember`에서 먼저 반환하고 응답은 stream offset
  그대로다. 남은 것은 그 규칙의 의도뿐이다 — 재전달된 이벤트에는 새 seq가 발급되지 않고 매핑도
  그대로다(호출자는 발급된 seq를 `KEYS[8]`에서 되읽는다).
- **유지되지 않음**: 그 의도는 **모든 구성원이 이미 처리된 중복 그룹을 고치지는 못한다.** 재생이
  그 행들에서 아무것도 바꾸지 않으므로 seq가 그대로여서 중복이 해소되지 않는다. 현재 구현은 이
  상태를 **찾을 수는 있으나 고칠 수 없는 것**으로 분류해 `contest.scoreboard.redis.sequence.unresolved`로
  보고한다. 이전 문서는 이 경우를 "대회가 끝나고 미처리 중복을 처리해준다"고 적었는데, 그 처리가
  실제로는 성립하지 않는다.