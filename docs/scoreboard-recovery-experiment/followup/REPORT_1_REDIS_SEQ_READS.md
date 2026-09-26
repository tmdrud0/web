# 보고서 1: redis-seq는 왜 수천 건만 다시 보냈는데 MySQL 수천만 행을 읽었나

## 질문
라이브 영향 실험(`docs/scoreboard-recovery-experiment/live-impact/`)의 redis-seq run은 두 수치가 맞지 않았다.
- 실제로 다시 적용한 결과: 3,532건(기존 run) / 4,496건(이번 run)
- `Innodb_rows_read`: fault부터 부하 종료까지만 약 4,500만 행

이 보고서는 두 가지를 확인한다.
1. 어느 쿼리가 이 차이를 만들었나?
2. 그 원인은 redis-seq 설계상 피할 수 없는 비용인가, 고칠 수 있는 구현 비효율인가?

## 결론 요약
- fault 이후 읽은 행의 **90.4%가 쿼리 하나(`findSequencedRowsDescending`, tail walk)**에서 나왔다.
- 원인은 실행계획이다. 1,000행을 요청한 호출이 테이블 전체(286,557행)를 조인·정렬한 뒤에 LIMIT을 적용했다. 고칠 수 있는 **구현 비효율**이다.
- 설계상 불가피한 비용(중복 seq 그룹 확인)은 **5% 미만**이었다.

## 맥락: 기존 라이브 영향 실험 결과
모든 run은 단일 run이다.

| | full-replay(현재) | redis-seq | stream-offset | full-replay 개선판(Run B) |
|---|---|---|---|---|
| 신규 반영 정지 | 138s | 15s | 3s | 1s |
| 복구 중 반영 지연 p50/p95 | 68s/132s | 9.4s/16s | 0.21s/3.7s | 3.2s/4.4s |
| tail 복귀 | 83s | 6.7s | 2.7s | 10.4s |
| Innodb_rows_read(fault→부하 종료) | 72.9M | 45.1M | 1.78M | 37.5M |
| replayRows / passesAfterRollback | 167,085 / 2 | 3,532 / 13 | – / 0 | 105,308 / 2 |

- 기존 run은 이미 끝난 뒤라 digest를 뒤늦게 켤 수 없었다.
- 그래서 이 보고서를 위해 `performance_schema` 계측을 켠 상태로 redis-seq run을 **새로** 1회 실행했다: `liredisseq_r1_20260927010033`

## 조건
- **작업 위치:** `web-live-impact`, 브랜치 `codex/scoreboard-recovery-live-impact`, `gitHead e1ffe6d` (현재 코드)
  - `run.gitDirty=True`는 untracked 작업 파일 때문이다.
- **runner 명령:** `run-recovery-live-impact.ps1 -Mode redis-seq -Phase run -StackMySql -ResetMySqlVolume -TargetRps 500 -JudgedRatePerSecond 457.733 -SubmitIntervalMillis 5000 -SkipCleanup`
- **DB:** 스택의 `oj-loadtest-mysql` / `oj_loadtest`, 전용 볼륨 `oj-loadtest-mysql-live-impact-data`
- **digest 수집 방식**
  - seed 시작 직전에 `performance_schema.events_statements_summary_by_digest`를 TRUNCATE했다. 그 뒤 15초 간격으로 스냅샷을 뜨고, T_fault와 부하 종료 전후의 차이로 구간을 나눴다.
  - 그래서 구간 경계에는 ±15초 오차가 있다.
- **시행착오**
  - 첫 시도: web-1/web-2가 Flyway V18 체크섬 불일치로 죽어 있었고, 그 때문에 nginx가 unhealthy였다.
  - 원인: 다른 worktree(`web-full-replay-bg`)가 같은 compose 이미지 태그를 덮어써서, runner의 migration 단계가 그 이미지로 먼저 실행됐다. runner의 `-Build`는 migration보다 늦게 실행된다.
  - 해결: 이 worktree에서 `docker compose build`로 이미지를 먼저 만들고, `-ResetMySqlVolume`로 전용 볼륨만 초기화한 뒤 성공했다. 실패한 시도의 잔여 데이터는 볼륨 초기화로 지워졌다.

## 이번 run 결과
| 지표 | 기존 run(`…153528`) | 이번 run(`…010033`) |
|---|---|---|
| replayRows | 3,532 | 4,496 |
| replayChunks | 8 | 9 |
| passesAfterRollback | 13 | 13 |
| 신규 반영 정지(초) | 15 | 18 |
| tail 복귀(ms) | 6,667 | 8,621 |
| backlog 해소(ms) | 27,462 | 32,090 |
| Innodb_rows_read(fault→부하 종료) | 45.1M | 45,756,501 |
| Com_select(전체 run) | 433,400 | 439,940 |
| 판정 | C | C |

독립된 두 run에서 다시 적용한 행은 27% 늘었는데 읽은 행 수(약 4,500만)와 pass 수(13)는 거의 같았다. 읽은 행이 **다시 적용한 행 수가 아니라 다른 무언가에 비례한다**는 첫 증거다.

## digest 상위 쿼리 (SUM_ROWS_EXAMINED 기준)
원본: `followup/report1/digest-delta-*.csv`, `digest-final-top15.tsv`

### fault 이후 구간 (스냅샷 fault 8ms 전 → 부하 종료 9초 후, 전체 48,743,842행)
| 쿼리 | 호출 수 | rows_examined | 비중 |
|---|---:|---:|---:|
| `findSequencedRowsDescending` (역순 tail walk) | 124 | 44,062,916 | **90.4%** |
| `findDuplicateAppliedSequences` (중복 그룹 탐지) | 13 | 2,419,173 | 4.96% |
| `COUNT(*) ... scoreboard_applied_at IS NULL` (staleness 점검) | 2 | 558,653 | 1.15% |
| judge outbox claim SELECT (judge 파이프라인, 무관) | 1,036 | 405,538 | 0.83% |
| 최종 digest 카운트 쿼리 | 1 | 220,217 | 0.45% |
| `UPDATE contest_submission_result ... scoreboard_applied_*` (실제 적용 경로) | 187,636 | 187,636 | 0.38% |
| 그 외(judge 파이프라인 등) | – | – | 약 2.1% |

- **fault 이전 구간(seed, baseline, ramp; 7,172,776행)도 순서가 같다.** `findSequencedRowsDescending` 78.4%, `findDuplicateAppliedSequences` 4.0%였다. 즉 이 쿼리들은 **fault가 없어도** 주기적으로 이만큼 읽는다. fault는 비용의 종류를 바꾸지 않고, pass가 걸리는 시간을 늘릴 뿐이다.
- `Innodb_rows_read`(스토리지 엔진 카운터)와 digest 합계는 약 6.5% 다르다. 계측 지점이 다르고 구간 경계가 ±15초 근사이기 때문이다. 방향과 자릿수는 일치한다.

## EXPLAIN 요약
전체 출력: `followup/report1/explain-outputs.txt`. run이 남긴 실제 데이터(286,557행)를 정리하기 전에 떴다.

### `findSequencedRowsDescending` (afterSequence=NULL, LIMIT 1000, EXPLAIN ANALYZE)
```
-> Limit: 1000 row(s)  (actual time=685..685 rows=1000 loops=1)
    -> Sort: csr1_0.scoreboard_applied_seq DESC, limit input to 1000 row(s) per chunk
        -> Stream results  (actual time=245..636 rows=286557 loops=1)
            -> Nested loop inner join  (actual time=245..535 rows=286557 loops=1)
                -> Inner hash join (no condition)  (actual time=245..278 rows=286557 loops=1)
                    -> Table scan on c1_0  (rows=1)
                    -> Hash
                        -> Index range scan on csr1_0 using idx_csr_scoreboard_applied_seq (rows=286557)
                -> Filter: (s1_0.contest_id = c1_0.id)
                    -> Single-row index lookup on s1_0 using PRIMARY (loops=286557)
```
- 옵티마이저가 `contest`와의 해시 조인을 먼저 골랐다. 그 결과 인덱스가 주는 DESC 정렬을 버리고, **테이블 전체를 조인하고 filesort한 뒤에야 LIMIT 1000을 적용**했다.
- 인덱스 범위 스캔 자체는 정상이었다(222 ms). 문제는 조인 순서다. "필요한 1,000행만 읽고 멈춘다"가 불가능해졌다.
- 같은 쿼리를 빈 테이블에서 뜨면 역방향 인덱스 스캔과 중첩 루프로 조기 종료하는 계획이 나왔다. 즉 이 나쁜 계획은 SQL 모양의 고정된 결함이 아니다. 테이블이 10만 행대 이상으로 커지면 옵티마이저가 반복해서 고르는 선택이다.

### `findDuplicateAppliedSequences` (afterSequence=NULL)
조인 없이 커버링 인덱스를 스캔하고 스트리밍으로 group aggregate한다. 계획 자체는 비효율적이지 않다. 다만 `HAVING COUNT(*) > 1`은 중복이 "없다"는 것을 증명하려면 끝까지 읽어야 해서, 비용이 테이블 크기에 비례한다.

## pass 표 (이번 run, `trace/recovery-trace.csv`)
| 순번 | 스레드 | 소요(ms) | 결과 |
|---|---|---:|---|
| 1 | main | 1,711 | true (fault 전, 시작 직후) |
| 2 | scheduling-2 | 1,300 | false |
| 3 | scheduling-2 | 1,713 | false |
| 4 | scheduling-3 | 3,102 | false |
| 5 | scheduling-2 | 3,081 | false |
| **6** | scheduling-4 | **12,227** | **RETRYABLE_FAILURE (fault, 4,496행 재적용, 9청크)** |
| 7 | scheduling-3 | 4,085 | false |
| 8 | scheduling-2 | 4,258 | false |
| 9 | scheduling-3 | 4,457 | false |
| 10 | scheduling-3 | 4,992 | false |
| 11 | scheduling-3 | 5,597 | false |
| 12 | scheduling-3 | 5,797 | false |
| 13 | scheduling-3 | 6,908 | false |
| 14 | scheduling-3 | 6,921 | false |
| 15 | scheduling-2 | 7,467 | false |
| 16 | scheduling-3 | 7,807 | false |
| 17 | scheduling-1 | 6,657 | false |

- fault 이후의 "이상 없음"(false) pass 12개는 소요 시간이 4.1초에서 7.8초로 **계속 늘어났다.**
- 기존 run(`…153528`, `followup/report1/recovery-trace-liredisseq_r1_20260926153528-reference.csv`)도 같은 모양이다. fault 전 1.2~2.7초, fault 9.8초, 이후 3.3초에서 13.1초까지 늘었다.
- 테이블에 쌓인 행이 많아질수록 **"잃은 게 없다"를 확인하는 비용도 커진다.** tail walk가 매 라운드 처음부터(afterSequence=null) 최대 10창을 걷고, 실행계획은 그 LIMIT을 지키지 못하기 때문이다.

## 판단: 구조적 원인 vs 구현 비효율
### 구조적 원인 (redis-seq가 반드시 치르는 비용)
- 매 라운드 "잃은 게 있는지" 다시 확인해야 한다(폴링·재확인 설계).
- `findDuplicateAppliedSequences`의 `HAVING COUNT(*) > 1`은 중복이 없다는 것을 증명하려면 전체 범위를 읽어야 한다. **fault 이후 비중 4.96%**다.
- MySQL의 `scoreboard_applied_seq`는 Redis 쓰기보다 항상 늦다. 그래서 재확인 자체를 없앨 수 없다.

### 구현 비효율 (고칠 수 있음, 이번에는 코드를 고치지 않음)
- **tail walk의 조인 순서와 실행계획이 전체 비용의 90.4%다.** 코드는 "많아야 checkWindowSize × maxWindowsPerPass = 10,000행"을 의도했지만, 실행 단계에서 이 상한이 지켜지지 않았다.
- **매 라운드 afterSequence=null부터 다시 시작한다.** 이미 확인한 구간을 매번 다시 읽는다.
- **10,000행 상한이 이 규모에서는 항상 소진된다.**
  - tail walk 호출은 181번 / 18라운드로, 라운드당 약 10회다. 거의 매번 saturated였다는 뜻이다.
  - 이번 run에서는 잃은 tail이 범위 맨 위에 있어서 결과에 문제가 드러나지 않았다.
  - 하지만 "전체를 봤다"는 이 모드의 자체 완전성 신호(`coveredTheWholeSet`)는 이 규모에서 사실상 매번 거짓이다.
- 복구가 끝난 뒤에도 약 30초 주기로 재확인 pass가 계속 돈다. 12번 중 실제로 무언가를 찾은 pass는 없었다.

### 비율 (fault 이후, 48,743,842행 기준)
| 구분 | 비중 |
|---|---:|
| tail walk (구현 비효율) | 90.4% |
| 중복 확인 (구조적) | 4.96% |
| 무관한 쿼리 (judge 파이프라인, 적용 경로, 점검) | 4.6% |

## 개선안 (제안만)
1. **tail walk를 두 단계로 나눈다.** 먼저 `contest_submission_result` 하나만으로 seq 인덱스를 따라 LIMIT을 적용해 후보 id만 뽑고, 그 1,000개에 대해서만 조인한다. 또는 `STRAIGHT_JOIN`이나 옵티마이저 힌트로 csr을 드라이빙 테이블로 고정한다.
2. **재확인 시작점을 기억한다.** "마지막으로 이상 없다고 확인한 상한"을 남겨 두면, 비용이 테이블 크기가 아니라 라운드 사이 증분에 비례한다. 다만 롤백으로 allocator가 거꾸로 가는 경우에 워터마크를 되돌리는 신호가 필요하므로, 정합성 조건을 따로 검토해야 한다.
3. **재확인 주기에 백오프를 둔다.** 연속으로 이상이 없으면 간격을 늘린다.
4. 이 개선들을 해도 중복 확인 비용은 테이블 크기에 비례해서 남는다(현재 5% 미만).

## 한계
- 두 run 모두 단일 run이고, 서로 다른 날 실행했다. 배수나 개선율은 계산하지 않았다.
- digest를 초기화한 시점은 fault가 아니라 seed 시작 직전이다. fault 전후 구분은 15초 간격 스냅샷으로 근사했으므로 ±15초 오차가 있다.
- `Innodb_rows_read`와 SUM_ROWS_EXAMINED는 서로 다른 계측이라 약 6.5% 차이가 난다. 교차 확인 용도로만 썼다.
- EXPLAIN은 정리 전 짧은 시간에 한 번만 떴다. afterSequence 값별 계획을 전수 조사하지는 않았다.
- 개선안은 구현하거나 검증하지 않았다.

## 포트폴리오에 쓸 수 있는 문장 (조건 포함)
> redis-seq 모드로 두 번 실험했다(초당 제출 500 / 채점 약 458, 대회 1개 약 10만~11만 건, 각 단일 run). 다시 적용한 결과는 3,532~4,496건뿐이었는데, fault 이후 InnoDB가 읽은 행은 약 4,500만이었다. `performance_schema` digest로 추적해 보니 그중 90.4%가 tail 확인 쿼리 하나에서 나왔다. 3-way 조인과 `ORDER BY ... LIMIT`이 만나자 옵티마이저가 인덱스 정렬을 버리고, 매 호출마다 테이블 전체(286,557행)를 조인·정렬한 뒤에야 LIMIT을 적용했다(EXPLAIN ANALYZE로 확인). 설계상 불가피한 중복 확인 비용은 5% 미만이었다.

이 문장은 조건(단일 run 두 개, ±15초 경계, 계측기 간 6.5% 차이)을 함께 밝힐 때만 쓴다.
