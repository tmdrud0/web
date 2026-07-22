# Rank 조회 경로 최적화

## 무엇을 만들고 있었는가


- `around me` 기능은 유저가 자신의 랭킹 페이지로 이동하는 기능이다.

```sql
SELECT ...
FROM user u
WHERE ...
ORDER BY score DESC, last_solved_date ASC, id ASC
LIMIT :offset, :limit
```

 병목의 핵심은 정렬 자체보다 `OFFSET`이며, 유저 수가 커질수록 뒤쪽 페이지 비용이 거의 선형으로 증가한다.
한 번에 자기 위치로 이동하기 때문에 커서를 사용하기도 힘들다.

## 핵심 아이디어

### 1. solved count

`solved_count_bucket` 테이블에 아래 정보를 저장했다.
- 푼 문제 수 `n`
- 해당 solved count를 가진 유저 수
- 자신보다 solved count가 큰 유저 수


1. 내 solved count를 읽는다
2. bucket에서 `나보다 solved count가 큰 유저 수`를 읽는다
3. 같은 solved count를 가진 유저들 중에서 내가 몇 번째인지 계산한다
4. 둘을 더해서 내 rank를 구한다
5. 그 rank가 속한 page start를 계산한다
6. 해당 solved count 구간에서 page size만큼 읽는다

이 구조가 가능한 이유는 `solved_count`가 1씩 증가만 할 수 있기 때문이다.
그래서 전체 랭킹을 매번 다시 정렬하지 않고, 작은 bucket 테이블만 유지해도 내 rank를 계산할 수 있다.

다만 이 방식의 성능은 `전체 유저 수`만이 아니라 `값의 분포`에 크게 좌우된다.

### 2. current streak

현재 스트릭은 하루 한 번 배치 시점에 `0이 아닌 유저만` 모은 `user_streak_rank_snapshot`을 생성한다.

1. snapshot에서 내 row를 바로 찾는다
2. 거기에 이미 저장된 `snapshot_rank`를 읽는다
3. 그 rank로 page start를 계산한다
4. `snapshot_rank BETWEEN start AND end`로 해당 page만 읽는다

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

## 한계

현재 `solved`는 bucket으로 시작점을 빠르게 찾지만, tie group 내부를 여전히 offset으로 읽는다.  
그래서 `solved_count=1`처럼 유저가 많은 구간에서는 뒤쪽 페이지 비용이 크다.
반면 snapshot 방식은 빨랐지만 정렬기준이 자주 변경되지 않는 경우에만 정확하게 계산할 수 있다.
