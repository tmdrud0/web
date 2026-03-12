---
title: "대회 제출 저장 경로를 Bulk Insert로 바꿔 처리량을 끌어올린 과정"
date: 2026-03-12 00:00:00 +0900
description: "대회 중 가장 먼저 맞는 쓰기 경로를 큐 + batch rewrite 구조로 바꿔, 첫 실패 구간을 최대 2배까지 뒤로 밀었다."
tags:
  - SpringBoot
  - MySQL
  - JPA
  - Gatling
  - Performance
---

온라인 저지에서 대회 제출 경로는 생각보다 민감하다. 사용자는 제출 버튼을 누른 직후의 반응을 바로 체감하고, 대회 시간에는 같은 저장 경로로 트래픽이 한꺼번에 몰린다. 그래서 이 경로가 느려지면 채점이나 스코어보드보다 먼저 서비스 품질이 흔들린다.

이번 글은 대회 제출 저장 경로를 최적화하면서 무엇이 병목이었고, 왜 bulk insert가 실제로 효과를 냈는지 정리한 기록이다.

## 문제 상황

최적화 대상은 대회 제출을 저장하는 가장 앞단의 insert 경로였다.

처음에는 "insert는 어차피 단순하니 DB만 버티면 된다"라고 보기 쉬운데, 실제로는 그렇지 않았다.

- 제출 직전 단계의 조회가 같은 트랜잭션에 섞여 있었다.
- worker 안에서 lazy loading 때문에 예상하지 못한 `SELECT`가 추가로 나가고 있었다.
- OSIV가 켜진 상태에서는 커넥션 점유 구간이 더 길어졌다.
- `saveAll(...)`이 신규 저장에서도 `merge` 경로를 타면서 row마다 `select ... where id = ?`를 유발하고 있었다.

즉 insert 자체보다도, insert 주변에서 커넥션을 오래 잡고 있는 구조가 더 큰 문제였다.

## 목표

이번 최적화의 목표는 세 가지였다.

- 대회 제출 저장 경로의 처리량을 높일 것
- 커넥션 풀이 작은 환경에서도 덜 무너지게 만들 것
- 왜 빨라졌는지 설명 가능한 수준까지 원인을 검증할 것

## 핵심 아이디어

핵심은 MySQL의 batch rewrite를 실제로 먹히게 만드는 것이었다.

JDBC batch와 `rewriteBatchedStatements=true`가 제대로 동작하면 여러 insert가 하나의 multi-values insert로 합쳐진다.

```sql
insert into contest_submission (...) values (...), (...), (...);
```

이렇게 되면 요청당 DB round-trip 수를 줄일 수 있고, 같은 커넥션으로 더 많은 작업을 처리할 수 있다.

문제는 "설정만 켜면 자동으로 빨라지는가"였는데, 실제로는 아니었다. 같은 트랜잭션, 같은 `EntityManager` 안에서 chunk 단위로 모아 처리해야 JPA batch와 MySQL rewrite가 의미 있게 동작했다.

## 구현 방식

구현은 두 단계로 나눴다.

### 1. 요청을 큐에 모으는 writer

요청을 들어오는 즉시 바로 insert하지 않고, 잠시 큐에 모아 chunk 단위로 flush하도록 바꿨다.

이 단계의 목적은 단순하다.

- 여러 요청을 같은 처리 단위로 묶고
- 같은 영속성 컨텍스트 안에서 처리해서
- 실제 batch rewrite가 일어날 조건을 만드는 것

### 2. chunk 단위 insert를 수행하는 processor

큐에서 꺼낸 요청은 하나의 트랜잭션 안에서 처리했다. 이때 구현 포인트는 두 가지였다.

- ID를 DB auto increment 대신 애플리케이션에서 먼저 할당
- `saveAll(...)` 대신 `entityManager.persist(...)` 사용

초기 구현에서는 ID를 애플리케이션에서 미리 할당하고 있었는데도 `saveAll(...)`을 사용하고 있었다. 이 조합 때문에 JPA가 신규 엔티티를 단순 insert가 아니라 `merge` 경로로 판단했고, row마다 추가 조회를 발생시키고 있었다.

이 부분을 `persist(...)` 기반으로 바꾸자, 숨겨져 있던 불필요한 `SELECT`를 제거할 수 있었다.

## 어떻게 검증했나

성능 검증은 Gatling step-load로 진행했다.

- 일정 시간 동안 목표 RPS를 유지
- 단계별로 RPS를 올리면서
- 어느 지점에서 처음 실패가 나는지 비교

비교한 시나리오는 아래 네 가지다.

1. `insert 1건`, `Hikari pool=100`
2. `insert 3건`, `Hikari pool=100`
3. `insert 1건`, `Hikari pool=10`
4. `insert 3건`, `Hikari pool=10`

여기서 `insert 3건`은 제출 1건당 메인 insert 1회와 보조 insert 2회가 추가되는 더 무거운 쓰기 시나리오다.

## 결과

아래 그래프는 각 시나리오에서 immediate 방식과 bulk 방식이 처음 실패를 보인 RPS를 비교한 것이다.

![First-failure RPS comparison]({{ '/assets/images/posts/contest-insert-first-failure-rps.svg' | relative_url }})

시나리오 표기:

- `1x / p100`: insert 1건, Hikari pool 100
- `3x / p100`: insert 3건, Hikari pool 100
- `1x / p10`: insert 1건, Hikari pool 10
- `3x / p10`: insert 3건, Hikari pool 10

결과를 정리하면 이렇다.

- `pool=100`처럼 커넥션 풀이 넉넉한 환경에서도 bulk가 더 높은 RPS까지 버텼다.
- `pool=10`처럼 커넥션 풀이 작은 환경에서는 차이가 더 크게 벌어졌다.
- 특히 `insert 3건 + pool=10`에서는 immediate가 `2000 RPS`에서 무너진 반면, bulk는 `4000 RPS`까지 실패 없이 통과했다.

즉 "DB 한 번 쓰는 작업을 조금 모아서 넣는 것"이 아니라, 같은 커넥션과 같은 트랜잭션을 더 짧고 효율적으로 쓰게 만든 것이 핵심 효과였다.

## 왜 빨라졌나

정리하면 bulk insert의 효과는 세 군데에서 나왔다.

### 1. DB round-trip 감소

여러 insert를 하나의 multi-values insert로 합치면서 요청당 DB 왕복 횟수가 줄었다.

### 2. 커넥션 사용 효율 개선

커넥션 풀은 쓰기 경로에서 가장 먼저 바닥나는 자원 중 하나다. 특히 pool 크기가 작을수록, 요청을 길게 붙잡는 구조는 바로 병목으로 드러난다. bulk 구조는 같은 커넥션으로 더 많은 insert를 처리할 수 있게 만들었다.

### 3. 숨겨진 조회 제거

이번 최적화에서 생각보다 컸던 부분은 batch 자체보다도, `merge`와 lazy loading 때문에 붙어 있던 불필요한 조회를 제거한 것이다. 실제로는 insert 최적화이면서 동시에 insert 경로 정리이기도 했다.

## 적용하면서 배운 점

bulk insert는 항상 이득이 되는 만능 패턴은 아니었다.

- 사용자가 저장 완료를 동기적으로 기다려야 하면, 큐 대기 시간도 응답시간에 포함된다.
- 큐에 넣기 전 단계가 무거우면, batch 이득보다 앞단 점유 비용이 더 커질 수 있다.
- 반대로 즉시 반환이 가능하거나 백그라운드 처리로 넘길 수 있으면 훨씬 잘 맞는다.

즉 batch의 성패는 DB가 빨라졌는가보다 어디에서 얼마나 오래 자원을 붙잡는가에 더 가깝다.

## 마무리

이번 작업에서 얻은 가장 큰 교훈은 이거였다.

> 쓰기 경로 최적화는 SQL 한 줄만 보는 작업이 아니라, 트랜잭션 경계와 커넥션 점유 구간을 다시 설계하는 작업에 가깝다.

대회 같은 순간 트래픽에서는 이 차이가 더 크게 드러난다. 겉으로는 같은 insert여도, 어떤 경로를 타게 하느냐에 따라 시스템이 버티는 상한이 꽤 달라질 수 있었다.
