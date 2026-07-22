# 대회 제출 Insert 경로 최적화


## 핵심 아이디어

핵심 아이디어는 MySQL의 batch rewrite를 실제로 활용하는 것이었다.

JDBC batch와 `rewriteBatchedStatements=true`가 제대로 동작하면 여러 insert가 아래처럼 하나의 multi-values insert로 합쳐질 수 있다.

```sql
insert into contest_submission (...) values (...), (...), (...);
```

## 어떻게 구현했는가
 
요청을 바로 insert하지 않고 잠시 큐에 모은 뒤 chunk 단위로 flush하도록 구성했다.

큐에서 꺼낸 요청을 하나의 트랜잭션, `EntityManager` 안에서 처리하여, Hibernate batch와 MySQL rewrite가 적용되도록 만들었다.

- ID를 DB auto increment가 아니라 애플리케이션에서 먼저 할당했다
- `saveAll(...)`을 사용하면, JPA가 `merge` 경로를 타고 row마다 `select ... where id = ?`를 발생시킨다. 
  `entityManager.persist(...)` 기반으로 바꾸면서 제거했다.

## 테스트 방식

성능 검증은 Gatling step-load로 진행했다.

- 각 단계에서 목표 RPS를 일정 시간 유지
- 단계별로 RPS를 올리면서 어느 구간에서 처음 실패가 발생하는지 확인
- 실패 유형은 Gatling 리포트와 서버 로그를 함께 확인

1. `insert 1건`, `Hikari pool=100`
2. `insert 3건`, `Hikari pool=100`
3. `insert 1건`, `Hikari pool=10`
4. `insert 3건`, `Hikari pool=10`

## 결과

| 시나리오 | immediate |          bulk |
|---|---:|--------------:|
| insert 1건, pool 100 | 첫 실패 4000 RPS | 첫 실패 6000 RPS |
| insert 3건, pool 100 | 첫 실패 4000 RPS | 첫 실패 5000 RPS |
| insert 1건, pool 10 | 첫 실패 2000 RPS | 첫 실패 4000 RPS |
| insert 3건, pool 10 | 첫 실패 2000 RPS | 첫 실패 4000 RPS |

## 실패했던 부분

1. lazy 로딩 때문에 worker 안에서 예상하지 못한 `SELECT`가 추가로 나가고 있었다.
2. OSIV가 켜져 있으면 큐에서도 커넥션을 잡고 있어서 커넥션이 고갈됐다.
3. `saveAll(...)` 때문에 신규 insert인데도 `merge` 경로를 타면서 row마다 `select ... where id = ?`가 붙고 있었다.

## 결론

- DB 작업이 무겁고 커넥션 풀 같은 DB 자원이 제한적일수록 bulk insert의 효과가 커진다.
- 큐 적재 이전 단계가 다른 작업과 같은 트랜잭션으로 묶이면, 대기 시간 동안 커넥션과 애플리케이션 자원을 함께 점유해 오히려 비효율적일 수 있다.
- 유저 요청이 저장 완료를 직접 기다려야 하면 큐 대기 비용까지 응답시간에 포함되므로, batch 이득이 충분히 크지 않으면 손해를 볼 수 있다.
- 반대로 즉시 반환 가능하거나 백그라운드 작업이라면 서블릿 스레드, HTTP in-flight, TCP 연결 같은 앞단 자원을 오래 점유하지 않아 더 잘 맞는다.
