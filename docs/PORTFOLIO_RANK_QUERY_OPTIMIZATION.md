# Rank 조회 경로 최적화

## 무엇을 만들고 있었는가

사용자는 랭킹 페이지에서 현재 위치를 확인하고, 자신이 포함된 페이지로 바로 이동할 수 있어야 한다.

문제는 유저 수가 커질 때였다.

- 단순 `ORDER BY ... LIMIT offset, size` 방식은 뒤쪽 페이지로 갈수록 느려진다
- `around me` 기능은 내 랭크를 먼저 알아낸 뒤 해당 페이지를 읽어야 한다

```sql
SELECT ...
FROM user u
WHERE ...
ORDER BY score DESC, last_solved_date ASC, id ASC
LIMIT :offset, :limit
```

이 쿼리는 정렬 인덱스를 잘 타더라도 깊은 페이지에서는 앞 row를 계속 읽고 버려야 한다.  
즉 병목의 핵심은 정렬 자체보다 `OFFSET`이며, 유저 수가 커질수록 뒤쪽 페이지 비용이 거의 선형으로 증가한다.

## 목표

- 1천만 유저 규모에서도 `around me` 페이지 조회가 버티는지 확인한다
- 단순 offset 방식과 개선된 방식의 차이를 실행계획과 실제 시간으로 비교한다

## 핵심 아이디어

`around me`가 필요로 하는 정보는 사실 두 가지뿐이다.

1. 내 정확한 rank
2. 그 rank가 포함된 페이지의 시작 rank

이 두 값만 빠르게 구할 수 있으면, 나머지는 그 페이지에 해당하는 row만 읽으면 된다.

### 1. solved count

`solved_count_bucket` 테이블에 아래 정보를 저장했다.
- 푼 문제 수 `n`
- 해당 solved count를 가진 유저 수
- 자신보다 solved count가 큰 유저 수

`around me`는 이렇게 계산한다.

1. 내 solved count를 읽는다
2. bucket에서 `나보다 solved count가 큰 유저 수`를 읽는다
3. 같은 solved count를 가진 유저들 중에서 내가 몇 번째인지 계산한다
4. 둘을 더해서 내 rank를 구한다
5. 그 rank가 속한 page start를 계산한다
6. 해당 solved count 구간에서 page size만큼 읽는다

이 구조가 가능한 이유는 `solved_count`가 1씩 증가만 할 수 있기 때문이다.
그래서 전체 랭킹을 매번 다시 정렬하지 않고, 작은 bucket 테이블만 유지해도 내 rank를 계산할 수 있다.

다만 이 방식의 성능은 `전체 유저 수`만이 아니라 `값의 분포`에 크게 좌우된다.

이번 검증에서는 오픈 1년, 문제 100개를 가정했고 solved count 분포는 
낮은 solved count 구간에 유저가 많이 몰리는 온라인 저지형 분포를 가정했다.  
이 가정 때문에 `solved_count=1` 같은 큰 tie group에서는 bucket 방식이 불리해진다.

### 2. current streak

현재 스트릭은 하루 한 번 배치 시점에 `0이 아닌 유저만` 모은 `user_streak_rank_snapshot`을 생성한다.

`around me`는 solved와 방식이 다르다.

1. snapshot에서 내 row를 바로 찾는다
2. 거기에 이미 저장된 `snapshot_rank`를 읽는다
3. 그 rank로 page start를 계산한다
4. `snapshot_rank BETWEEN start AND end`로 해당 page만 읽는다

- 자정이 지나면 전날 활동 데이터를 바탕으로 streak를 갱신한다
- 갱신이 끝난 뒤 그날의 current streak 순위를 snapshot으로 만든다
- 조회 시점에는 이미 정렬이 끝난 결과를 읽기만 하면 된다

즉 current streak는 조회 시점에 `내 rank를 계산`하는 것이 아니라, 이미 계산된 rank를 읽는 구조다.

## 테스트 결과
비교는 각 랭크 타입마다 앞/중간/뒤 지점 한 페이지씩 총 3지점에서 수행했다.

- `current streak`: `101`, `49,601`, `99,301`
- `solved`: `101`, `4,000,501`, `8,000,901`


| Rank type | Front | Middle | Back |
|---|---:|---:|---:|
| `current streak` naive | `161 ms` | `8,890 ms` | `14,382 ms` |
| `current streak` optimized | `0.586 ms` | `0.443 ms` | `0.113 ms` |
| `solved` naive | `19.1 ms` | `217,842 ms` | `233,231 ms` |
| `solved` optimized | `24.2 ms` | `369 ms` | `47,747 ms` |


bucket 방식은 규모가 더 작을 때는 충분히 실용적이었다.  
10만 유저 데이터셋에서 같은 solved bucket 방식의 deep page fetch는 약 `0.46 ms` 수준이었다.  

## 실행계획

### current streak

optimized는 snapshot에서 필요한 rank 구간만 읽는다.

```sql
SELECT ...
FROM user_streak_rank_snapshot s
JOIN user u ON u.id = s.user_id
WHERE s.snapshot_rank BETWEEN :startRank AND :endRank
ORDER BY s.snapshot_rank
```

`rank 99,301`이 포함된 뒤쪽 페이지 기준 실행계획은 이랬다.

```text
-> Nested loop inner join  (actual time=0.151..0.221 rows=15 loops=1)
    -> Index range scan on s using PRIMARY
       over (99301 <= snapshot_rank <= 99400)
       (actual time=0.0816..0.0878 rows=15 loops=1)
    -> Single-row index lookup on u using PRIMARY
       (actual time=0.00829..0.00831 rows=1 loops=15)
```

즉 current streak는 `내 rank 계산`을 미리 끝내 두고, 조회 시점에는 필요한 page만 읽는 구조로 바뀌었다.

### solved

optimized는 먼저 bucket으로 `solved_count` 구간을 찾은 뒤 해당 범위만 읽는다.

```sql
SELECT ...
FROM user u FORCE INDEX (idx_user_ranking)
WHERE u.solved_count <= :sc
ORDER BY u.solved_count DESC, u.streak_last_solved_date ASC, u.id ASC
LIMIT :limit OFFSET :offsetInBucket
```

중간 지점인 `rank 4,000,501`에서 bucket이 가리킨 값은 `solved_count=8`, bucket 내부 offset은 `23,638`이었다.

```text
-> Limit/Offset: 100/23638 row(s)  (actual time=183..183 rows=100 loops=1)
    -> Index range scan on u using idx_user_ranking
       over (8 <= solved_count)
       with index condition: (u.solved_count <= 8)
       (actual time=0.346..183 rows=23738 loops=1)
```

즉 solved는 `전체 순위 문제`를 `같은 solved count 그룹 내부 문제`로 줄이는 데는 성공했지만, tie group이 큰 분포에서는 그 내부 offset이 다시 병목이 된다.

## 한계

현재 `solved`는 bucket으로 시작점을 빠르게 찾지만, tie group 내부를 여전히 offset으로 읽는다.  
그래서 `solved_count=1`처럼 유저가 많은 구간에서는 뒤쪽 페이지 비용이 크다.
