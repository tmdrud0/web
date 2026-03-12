---
title: "OFFSET 때문에 무너지는 around me 랭킹 조회를 다시 설계한 방법"
date: 2026-03-12 00:00:00 +0900
description: "깊은 페이지에서 느려지는 OFFSET 기반 랭킹 조회를 snapshot rank와 solved_count bucket 구조로 분해했다."
tags:
  - Ranking
  - SQL
  - Performance
  - MySQL
  - Optimization
---

랭킹 기능을 만들다 보면 꼭 나오는 요구가 있다.

- 현재 내 순위를 보고 싶다
- 내가 포함된 페이지로 바로 가고 싶다
- 앞쪽이 아니라 뒤쪽 페이지도 빠르게 열려야 한다

문제는 유저 수가 커질수록 이 요구가 `OFFSET`과 정면충돌한다는 점이다.

## 왜 기존 방식이 느린가

가장 단순한 구현은 아래와 같은 형태다.

```sql
SELECT ...
FROM user u
WHERE ...
ORDER BY score DESC, last_solved_date ASC, id ASC
LIMIT :offset, :limit
```

겉보기에는 단순하지만, 깊은 페이지에서는 앞 row를 계속 읽고 버려야 한다. 인덱스를 잘 타더라도 뒤쪽 페이지로 갈수록 비용이 커지고, 유저 수가 늘어날수록 거의 선형에 가깝게 나빠진다.

`around me`는 더 까다롭다.

1. 먼저 내 rank를 알아내야 하고
2. 그 rank가 포함된 페이지를 다시 읽어야 하기 때문이다

즉 단순 페이지 조회보다 내 위치 계산과 페이지 fetch라는 두 문제를 동시에 풀어야 한다.

## 목표

이번 작업의 목표는 명확했다.

- 1천만 유저 규모에서도 `around me`가 버틸 것
- naive한 offset 방식과 개선된 방식의 차이를 실제 시간과 실행계획으로 설명할 것

## 핵심 아이디어

`around me`에 정말 필요한 값은 두 가지뿐이었다.

1. 내 정확한 rank
2. 그 rank가 포함된 페이지 시작점

이 두 값만 빠르게 구할 수 있다면, 전체 정렬 결과를 다시 훑을 필요 없이 해당 페이지 row만 읽으면 된다.

이 아이디어를 랭킹 종류별로 다르게 풀었다.

## 1. current streak: rank를 미리 계산해 둔다

current streak는 하루 한 번 배치 시점에 `0이 아닌 유저만` 모아 `user_streak_rank_snapshot`을 만든다.

조회 시점에는 아래 순서만 수행한다.

1. snapshot에서 내 row를 찾는다
2. 거기에 이미 저장된 `snapshot_rank`를 읽는다
3. 그 rank로 page start를 계산한다
4. 필요한 rank 구간만 읽는다

즉 조회 시점에 rank를 계산하는 것이 아니라, 이미 계산된 rank를 읽는 구조다.

쿼리도 명확해진다.

```sql
SELECT ...
FROM user_streak_rank_snapshot s
JOIN user u ON u.id = s.user_id
WHERE s.snapshot_rank BETWEEN :startRank AND :endRank
ORDER BY s.snapshot_rank
```

뒤쪽 페이지에서도 인덱스 범위만 읽으면 되므로, `OFFSET`로 앞 row를 버리는 비용이 사라진다.

## 2. solved: 전체 문제를 bucket 내부 문제로 줄인다

solved 랭킹은 성질이 다르다. rank 전체를 매번 snapshot으로 만들기보다, `solved_count_bucket` 테이블에 아래 정보를 저장했다.

- solved count 값
- 해당 solved count를 가진 유저 수
- 자신보다 solved count가 큰 유저 수

이 정보를 이용하면:

1. 내 solved count를 읽고
2. bucket에서 나보다 solved count가 큰 유저 수를 읽고
3. 같은 solved count 그룹 안에서 내 위치를 계산하고
4. 이를 합쳐 내 rank를 구할 수 있다

즉 전체 순위 문제를 같은 solved count 그룹 내부 문제로 줄인 셈이다.

다만 이 방식은 데이터 분포에 영향을 크게 받는다. 예를 들어 `solved_count=1`처럼 tie group이 큰 구간에서는 결국 bucket 내부 offset이 다시 병목이 될 수 있다.

## 테스트 결과

비교는 각 랭크 타입마다 앞, 중간, 뒤 지점에서 수행했다.

- `current streak`: `101`, `49,601`, `99,301`
- `solved`: `101`, `4,000,501`, `8,000,901`

| Rank type | Front | Middle | Back |
|---|---:|---:|---:|
| `current streak` naive | `161 ms` | `8,890 ms` | `14,382 ms` |
| `current streak` optimized | `0.586 ms` | `0.443 ms` | `0.113 ms` |
| `solved` naive | `19.1 ms` | `217,842 ms` | `233,231 ms` |
| `solved` optimized | `24.2 ms` | `369 ms` | `47,747 ms` |

여기서 가장 인상적이었던 부분은 current streak였다.

- 뒤쪽 페이지 기준 `14,382 ms` -> `0.113 ms`
- 조회 시점 계산을 없애고, 필요한 rank 구간만 읽는 구조로 바꾼 효과가 그대로 드러났다

solved도 의미 있는 개선이 있었다.

- middle 구간 기준 `217,842 ms` -> `369 ms`

다만 back 구간에서는 여전히 `47,747 ms`가 걸렸다. 전체 순위를 바로 읽는 구조는 피했지만, tie group이 너무 큰 분포에서는 그 내부 offset 비용이 다시 커진다는 뜻이다.

## 실행계획으로 보면 더 명확하다

current streak optimized의 뒤쪽 페이지 실행계획은 거의 필요한 rank 범위만 스캔하는 모양이었다.

```text
-> Nested loop inner join  (actual time=0.151..0.221 rows=15 loops=1)
    -> Index range scan on s using PRIMARY
       over (99301 <= snapshot_rank <= 99400)
       (actual time=0.0816..0.0878 rows=15 loops=1)
    -> Single-row index lookup on u using PRIMARY
       (actual time=0.00829..0.00831 rows=1 loops=15)
```

반면 solved optimized는 전체 deep page fetch 대신 bucket 내부 offset으로 줄였지만, 그 내부에서 여전히 꽤 많은 row를 읽고 있었다.

```text
-> Limit/Offset: 100/23638 row(s)  (actual time=183..183 rows=100 loops=1)
    -> Index range scan on u using idx_user_ranking
       over (8 <= solved_count)
       with index condition: (u.solved_count <= 8)
       (actual time=0.346..183 rows=23738 loops=1)
```

즉 성능 병목이 사라진 것이 아니라, 더 작은 범위 안으로 밀려 들어간 것이다. 이 차이를 이해해야 다음 최적화 방향도 보인다.

## 한계와 다음 단계

현재 solved 랭킹은 시작점을 bucket으로 빨리 찾을 수 있지만, tie group 내부는 여전히 offset으로 읽는다.

그래서 다음 단계는 자연스럽게 두 가지 방향 중 하나다.

- tie group 내부 순서를 더 빨리 찾을 수 있는 보조 구조를 둔다
- solved 랭킹도 current streak처럼 더 적극적으로 snapshot화한다

어떤 쪽이 맞는지는 업데이트 비용과 조회 비용을 어디에 둘지에 달려 있다.

## 마무리

이번 작업에서 얻은 결론은 단순했다.

> 랭킹 조회 최적화의 핵심은 정렬 속도보다, 깊은 페이지에서 앞 row를 얼마나 덜 버리게 만들 수 있느냐에 있다.

current streak는 rank를 미리 계산하는 방식으로 문제를 거의 제거했고, solved는 bucket으로 범위를 줄여 큰 폭의 개선을 얻었다. 완전히 끝난 작업은 아니지만, 병목을 전체 데이터셋에서 tie group 내부로 줄였다는 점에서 다음 단계로 나아갈 기반은 충분히 만들었다.
