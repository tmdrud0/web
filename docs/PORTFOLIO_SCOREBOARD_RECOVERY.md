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