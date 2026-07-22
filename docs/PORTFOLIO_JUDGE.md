# JUDGE 서버 설계 과정
## 배경

채점서버는 rest api로 채점을 지원한다.

최초에 대회 제출 후 처리는 event를 발행하여 asny로 건별로 api를 호출후 
결과로 `contest_submission_result`, `contest_submission_outbox`를 생성했다.

문제는 대회제출의 부하가 그대로 체점서버로 옮겨가고 spring 서버가 죽었을 때 복구가 안된다는 점이다.

## 1. 한 트랜잭션 구조

1. DB에서 `PENDING` 제출을  `SELECT ... FOR UPDATE SKIP LOCKED`로 여러 건 조회한다
2. 같은 트랜잭션 안에서 judge를 병렬 실행한다
3. judge가 모두 끝나면 `contest_submission_result`, `contest_submission_outbox`를 쓴다
4. 마지막에 `contest_submission.judge_status = DONE`으로 갱신하고 commit 한다

- contest submission때와는 달리 insert ignore이 필요해서 jdbc batch를 이용했다.
- 중간에 프로세스가 죽으면 트랜잭션 rollback으로 정리된다
- 채점서버 api 병렬 호출 수준을 조절 가능.

채점서버 가정 및 한계 :
- 병렬로 10개씩 처리 가능하고 대회시에는 보통 제출이 10ms걸린다고 생각했다.(대회시 부분채점)
- 하지만 간단한 검증을 가지고 있어도 코드를 잘못짤 수도 있기에 1000건당 하나만 2초정도 걸린다고 가정.
- 지금 구조에서는 트랜잭션 하나에서 다른 judge의 완료를 기다려야 하기에 100건이 다 10ms면 100ms예상.
- 하나라도 2초가 걸리는 게 있으면 그동안 judge의 병렬 처리를 전혀 이용하지 못한다.


## 2. 현재 구조

### 1. claim
DB에서 `PENDING` 제출을 짧은 트랜잭션으로 선점한다.

- `judge_status = 'PROCESSING'`
- `judge_claim_token = ?`  : 다시 조회를 위해 필요
- `judge_claimed_at = now` : 회복을 위해 필요

선점 후 queue에 넣는다.

### 2. judge
- worker가 queue에서 제출을 꺼내 채점서버 api 호출한다.

응답을 queue에 넣는다. 

### 3. write

queue에서 일정 건씩 꺼내 batch로 한 번에 처리한다.

1. `contest_submission_result` insert
2. `contest_submission_outbox` insert
3. `contest_submission.judge_status = DONE` update

### 4. recovery

- `PROCESSING`인데 `judge_claimed_at`이 일정 시간보다 오래되면 다시 `PENDING`으로 복구한다
채점 서버는 멱등성이 보장된다는 가정이다.

## 3. 한계

- recovery timeout
현재 채점 서버 api 호출시간이 최대 2초로 가정하고 있는데 
이것을 준수해도 100건을 읽는다고 했을 때 어느 입력에서 100건이 모두 2초가 걸리는 것 같은 최악의 상황까지 가정하면
넉넉하게 잡아야하는데 그럴수록 회복이 늦어질 수 있다.

- 메모리 queue 구간은 durable하지 않다
각 과정의 병목에 서로 영향을 받지 않기위해 queue를 2개 두어서 분리했다.
이것은 중간 설계의 문제를 해결하지만 그만큼 durable하지 않은 구간을 늘린다.

둘 다 정합성보다 중복 처리 비용 문제에 가깝다.
