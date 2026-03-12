# 대회 제출 Insert 경로 최적화

## 무엇을 만들고 있었는가

이 프로젝트는 온라인 저지 서비스다.  
사용자가 대회 중 문제를 제출하면 서버는 먼저 대회 제출 정보를 저장하고, 이후 채점과 스코어보드 반영이 이어진다.

내가 최적화한 대상은 이 중에서도 가장 먼저 호출되는 `대회 제출 저장 경로`였다.

- 사용자는 제출 버튼을 누른 뒤 즉시 응답을 체감한다
- 대회 시간에는 같은 경로로 트래픽이 집중된다
- 이 경로가 느리면 전체 서비스 품질이 바로 떨어진다

## 목표

목표는 단순했다.

- 대회 제출 저장 경로의 처리량을 높인다
- 고부하에서도 안정적으로 버티게 만든다
- 왜 빨라졌는지 설명 가능한 수준까지 원인을 검증한다

## 핵심 아이디어

핵심 아이디어는 MySQL의 batch rewrite를 실제로 활용하는 것이었다.

JDBC batch와 `rewriteBatchedStatements=true`가 제대로 동작하면 여러 insert가 아래처럼 하나의 multi-values insert로 합쳐질 수 있다.

```sql
insert into contest_submission (...) values (...), (...), (...);
```

이 방식이 제대로 동작하면 다음 이점이 생긴다.

- 요청당 DB round-trip 수 감소
- insert 처리량 증가
- 커넥션 사용 효율 개선

## 어떻게 구현했는가

구현은 두 단계였다.

### 1. 요청을 큐에 모으는 writer

여러 요청을 같은 트랜잭션, 같은 `EntityManager` 안에서 처리해야 JPA batch와 MySQL rewrite가 실제로 의미 있게 동작한다.  
그래서 요청을 바로 insert하지 않고 잠시 큐에 모은 뒤 chunk 단위로 flush하도록 구성했다.

### 2. chunk 단위로 실제 insert를 수행하는 processor

큐에서 꺼낸 요청을 하나의 트랜잭션 안에서 처리하고, Hibernate batch와 MySQL rewrite가 적용되도록 만들었다.

구현 포인트는 두 가지였다.

- ID를 DB auto increment가 아니라 애플리케이션에서 먼저 할당했다
- `saveAll(...)` 대신 `entityManager.persist(...)`를 사용했다

초기에는 `saveAll(...)`을 사용하고 있었는데, 엔티티 ID를 애플리케이션에서 먼저 할당하는 구조와 만나면서 JPA가 `merge` 경로를 타고 row마다 `select ... where id = ?`를 추가로 발생시키고 있었다. 이 부분은 `entityManager.persist(...)` 기반으로 바꾸면서 제거했다.

## 테스트 방식

성능 검증은 Gatling step-load로 진행했다.

- 각 단계에서 목표 RPS를 일정 시간 유지
- 단계별로 RPS를 올리면서 어느 구간에서 처음 실패가 발생하는지 확인
- 실패 유형은 Gatling 리포트와 서버 로그를 함께 확인

이번 문서에서는 다음 네 가지 비교만 남겼다.

1. `insert 1건`, `Hikari pool=100`
2. `insert 3건`, `Hikari pool=100`
3. `insert 1건`, `Hikari pool=10`
4. `insert 3건`, `Hikari pool=10`

`insert 3건` 시나리오는 제출 1건당 메인 insert 1회와 보조 insert 2회를 추가로 수행하는 `perf-triple` 프로필로 검증했다.

## 결과

| 시나리오 | immediate |             bulk |
|---|---:|-----------------:|
| insert 1건, pool 100 | 첫 실패 4000 RPS |    첫 실패 6000 RPS |
| insert 3건, pool 100 | 첫 실패 4000 RPS |    첫 실패 5000 RPS |
| insert 1건, pool 10 | 첫 실패 2000 RPS |    첫 실패 4000 RPS |
| insert 3건, pool 10 | 첫 실패 2000 RPS | 4000 RPS까지 실패 없음 |

### 해석

- 커넥션 풀이 충분한 `pool=100` 환경에서도 bulk가 더 높은 RPS 구간까지 버텼다
- 커넥션 풀이 작은 `pool=10` 환경에서는 차이가 더 크게 벌어졌다
- 특히 `insert 3건 + pool=10`에서는 immediate가 `2000 RPS`에서 무너진 반면, bulk는 `4000 RPS`까지 통과했다

## 시각 자료

### insert 1건, Hikari pool=100

- immediate
  - [HTML Report](C:/Users/Home/spring/web/web/gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260308140653644/index.html)
  - [stats.json](C:/Users/Home/spring/web/web/gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260308140653644/js/stats.json)
- bulk
  - [HTML Report](C:/Users/Home/spring/web/web/gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260308141440663/index.html)
  - [stats.json](C:/Users/Home/spring/web/web/gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260308141440663/js/stats.json)

### insert 3건, Hikari pool=100

- immediate
  - [HTML Report](C:/Users/Home/spring/web/web/gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260308142441341/index.html)
  - [stats.json](C:/Users/Home/spring/web/web/gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260308142441341/js/stats.json)
- bulk
  - [HTML Report](C:/Users/Home/spring/web/web/gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260308142835802/index.html)
  - [stats.json](C:/Users/Home/spring/web/web/gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260308142835802/js/stats.json)

### insert 1건, Hikari pool=10

- immediate
  - [HTML Report](C:/Users/Home/spring/web/web/gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260308144724662/index.html)
  - [stats.json](C:/Users/Home/spring/web/web/gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260308144724662/js/stats.json)
- bulk
  - [HTML Report](C:/Users/Home/spring/web/web/gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260308145102905/index.html)
  - [stats.json](C:/Users/Home/spring/web/web/gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260308145102905/js/stats.json)

### insert 3건, Hikari pool=10

- immediate
  - [HTML Report](C:/Users/Home/spring/web/web/gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260308145638352/index.html)
  - [stats.json](C:/Users/Home/spring/web/web/gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260308145638352/js/stats.json)
- bulk
  - [HTML Report](C:/Users/Home/spring/web/web/gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260308145959684/index.html)
  - [stats.json](C:/Users/Home/spring/web/web/gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260308145959684/js/stats.json)

## 실패했던 부분

1. 저장 전 조회가 섞여 있어서 큐에 넣기 전부터 트랜잭션과 커넥션을 오래 점유하고 있었다.
2. lazy 로딩 때문에 worker 안에서 예상하지 못한 `SELECT`가 추가로 나가고 있었다.
3. OSIV가 켜져 있으면 이런 lazy 접근이 더 늦은 시점까지 살아남아 커넥션 사용 구간이 퍼졌다.
4. `saveAll(...)` 때문에 신규 insert인데도 `merge` 경로를 타면서 row마다 `select ... where id = ?`가 붙고 있었다.

## 결론

- DB 작업이 무겁고 커넥션 풀 같은 DB 자원이 제한적일수록 bulk insert의 효과가 커진다.
- 큐 적재 이전 단계가 다른 작업과 같은 트랜잭션으로 묶이면, 대기 시간 동안 커넥션과 애플리케이션 자원을 함께 점유해 오히려 비효율적일 수 있다.
- 유저 요청이 저장 완료를 직접 기다려야 하면 큐 대기 비용까지 응답시간에 포함되므로, batch 이득이 충분히 크지 않으면 손해를 볼 수 있다.
- 반대로 즉시 반환 가능하거나 백그라운드 작업이라면 서블릿 스레드, HTTP in-flight, TCP 연결 같은 앞단 자원을 오래 점유하지 않아 더 잘 맞는다.
