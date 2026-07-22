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

## 테스트 결과

| 시나리오 | immediate |          bulk |
|---|---:|--------------:|
| insert 1건, pool 100 | 첫 실패 4000 RPS | 첫 실패 6000 RPS |
| insert 3건, pool 100 | 첫 실패 4000 RPS | 첫 실패 5000 RPS |
| insert 1건, pool 10 | 첫 실패 2000 RPS | 첫 실패 4000 RPS |
| insert 3건, pool 10 | 첫 실패 2000 RPS | 첫 실패 4000 RPS |

## 실패했던 부분

1. OSIV가 켜져 있으면 lazy로 `SELECT`가 숨어있기 쉽고 큐에서도 커넥션을 잡고 있어서 커넥션이 고갈됐다.
2. `saveAll(...)` 때문에 신규 insert인데도 `merge` 경로를 타면서 row마다 `select ... where id = ?`가 붙고 있었다.
