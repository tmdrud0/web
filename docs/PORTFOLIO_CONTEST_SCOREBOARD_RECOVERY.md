# Redis Scoreboard Recovery
## 무엇을 해결했는가

대회 스코어보드를 Redis에 두면 조회는 빠르다.  
대신 Redis가 장애 후 예전 스냅샷으로 복구되면, DB에는 남아 있지만 Redis에는 없는 반영 결과가 생길 수 있다.

문제는 Redis를 다시 띄우는 것이 아니라, **무엇이 유실됐는지 어떻게 다시 찾아서 반영할 것인가**였다.

## 문제

복구 기준으로 `contest_submission_outbox.id`만 쓰는 방법은 단순하지만 아쉬움이 있었다.

- `outbox.id`는 DB insert 순서이지 Redis 적용 순서가 아니다.
- 실패와 재시도로 인해 일부 id만 비어 있을 수 있다.
- Redis가 예전 스냅샷으로 복구돼도 `outbox.id` 자체는 되돌아가지 않는다.

즉 `outbox.id`만으로는 Redis 입장에서 유실을 자연스럽게 감지하기 어렵다.

## 선택

`outbox.id`와 별도로 Redis가 발급하는 `redis_seq`를 두었다.

- `outbox.id`
  - 멱등 적용 키
- `redis_seq`
  - 복구 감지 키

`redis_seq`는 Redis가 `contestSubmissionId` 기준으로 발급한다.

- 이미 본 제출이면 기존 seq 반환
- 처음 보는 제출이면 새 seq 발급

이 값을 outbox에도 저장한다.

Redis가 예전 스냅샷으로 복구되면:

- seq counter
- `contestSubmissionId -> redis_seq` 매핑

도 같이 과거로 돌아간다.  
그 뒤 새 결과가 들어오면 예전에 쓰던 `redis_seq`가 다시 발급될 수 있고, Spring 서버는 outbox에서 이 중복 seq를 찾아 replay할 수 있다.

즉 **duplicate `redis_seq`를 복구 신호로 사용했다.**

## 비교

### 대안: DB gapless seq

또 다른 방법은 Redis가 아니라 DB가 `gapless seq`를 직접 발급하는 구조였다.

- outbox insert 시점에 DB가 별도 seq를 부여한다
- Redis는 이 seq를 그대로 받아 scoreboard 반영과 복구 기준으로 사용한다
- 복구 시에는 Redis가 마지막으로 연속 반영한 seq를 보내고, Spring batch가 그 다음 seq부터 다시 밀어 넣는다 (go back n)

장점:

- seq 자체가 DB에 영속되므로 Redis snapshot rollback과 무관하다
- 트래픽이 복구에 필요하지 않다.

단점 :

- gapless를 유지하려면 seq 발급 구간을 직렬화해야 한다
- 위의 방식과는 다르게 회복을 여러 서버에서 돌리기 쉽기 않다.


### 명시적 복구

- Redis의 현재 max seq 또는 체크포인트를 읽는다
- 그보다 큰 outbox를 다시 보낸다

장점:

- 복구 범위가 명확하다
- 새 이벤트가 없어도 바로 복구할 수 있다

단점:

- 별도 복구 절차가 필요하다
- 장애 감지와 복구 시작 시점을 운영에서 관리해야 한다

### 자연 복구

- 평소처럼 outbox를 계속 처리한다
- Redis가 과거 스냅샷으로 돌아오면 duplicate `redis_seq`가 발생한다
- batch 서버가 이 중복 seq를 주기적으로 찾아 replay한다

장점:

- 별도 복구 모드가 필요 없다
- 운영 흐름을 끊지 않는다

단점:

- 복구 속도가 새 이벤트 유입에 영향을 받는다
- 트래픽이 적으면 회복이 늦어진다

## 최종 구조

두 방식을 같이 썼다.

- 평소
  - duplicate `redis_seq` 기반 자연 복구
- 대회 종료 시
  - Redis `max seq` 기준 catch-up replay

즉 운영 중에는 절차를 단순하게 두고, 종료 시점에는 한 번 더 정합성을 맞추는 구조다.

## 역할 분리

- judge 서버
  - `contest_submission_result`, `contest_submission_outbox` 저장
- batch 서버
  - outbox를 Redis scoreboard에 반영
  - duplicate `redis_seq` replay
  - 종료 시 catch-up replay
- Redis scoreboard
  - 빠른 조회용 파생 상태
  - `outbox.id` 기준 멱등 적용

