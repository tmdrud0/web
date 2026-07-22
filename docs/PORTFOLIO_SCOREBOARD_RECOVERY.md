# Redis Scoreboard Recovery
## 무엇을 해결했는가

대회 스코어보드를 Redis에 두고 redis가 죽었을 때 효율적인 회복법을 구현했다.

## score board 구조

judge 서버 : outbox 생성
batch 서버 : outbox 주기적으로 redis로 전송
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