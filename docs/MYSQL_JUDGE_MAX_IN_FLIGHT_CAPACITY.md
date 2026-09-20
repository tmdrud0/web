# MySQL claim 기반 judge의 max-in-flight별 정상상태 용량

## 목적과 범위

`docs/MYSQL_JUDGE_TRADEOFF_EXPERIMENT.md`는 fault 주입 복구 축(timeout, prefetch)을,
`gatling/MAX_RPS.md`는 단일 RPS의 지속 가능 상한을 다뤘다. 이 문서는 **fault 없는 정상상태
용량** 축만 다룬다. 질문은 하나다.

> 노드당 `max-in-flight`를 worker 수(16)보다 작게, 같게, 훨씬 크게 두면 처리량과 대기가 어떻게
> 달라지는가?

결과를 미리 정하지 않는다. 특히 `max-in-flight=64`가 더 높은 처리량을 낸다고 가정하지 않는다.

## 메커니즘: max-in-flight가 동시에 제어하는 세 가지

`MysqlContestJudgeDispatcher`에서 이 값은 서로 다른 세 가지의 상한이다.

- `ThreadPoolExecutor(workers=16, 16, queueCapacity = maxInFlight)` — 로컬 handoff 큐 용량.
- `capacity = maxInFlight - reserved.get()` — poll 1회가 claim할 수 있는 양의 상한.
- `reserved` — claim했지만 아직 끝나지 않은 작업 수.

따라서 `max-in-flight=8`이면 worker 16개 중 8개까지만 동시에 돌 수 있고, `=16`이면 16개가 모두
돌 수 있으며, `=64`면 최대 48개가 로컬 큐에 대기한다. poll 간격 100ms에 claim batch 16이므로
`=16`은 poll 사이에 worker가 굶을 수 있고 `=64`는 큐를 채워 굶지 않을 수 있다. **어느 쪽이 실제로
처리량이 높은지는 코드에서 결정되지 않는다.** 이 실험이 그 지점을 실측한다.

세 상한은 run 전체(측정 창 밖 tick 포함) 노드별 최댓값에서 그대로 확인된다. mif=8은
`running` 최대 8 / `reserved` 최대 8 / `localWaiting` 최대 0, mif=16은 16 / 16 / 0,
mif=64는 `running` 최대 16(= worker 수) / `reserved` 최대 64(= mif) / `localWaiting` 최대
48(= 64 − 16)이다. mif=64의 두 값이 코드가 정한 상한에 **정확히** 닿고 그 위로는 한 번도
올라가지 않았으므로, 이 조건에서 로컬 큐와 reserved 카운터가 모두 포화됐다는 것은 산술이 아니라
관측이다.

## 고정 파라미터

| 항목 | 값 |
|---|---|
| dispatch mode | `mysql` |
| judge 노드 | 2 (`judge-1`, `judge-2`) |
| 노드당 worker | 16 |
| claim batch size | 16 |
| **claim timeout** | **30s** |
| poll interval | 100ms |
| fault | 없음 |
| latency seed | `20260920` |
| synthetic judge latency | 95% 50ms, 5% 2000ms (`key-source=code`) |
| user count | 1000 |
| drain timeout | 600s |

synthetic latency의 평균은 147.5ms다. 두 노드가 모두 saturate되면 이론적 상한은
`max-in-flight=8`에서 약 108 RPS, `max-in-flight>=16`에서 약 217 RPS다. **이론값은 차이를 설명하기
위한 참조이며 측정을 여기에 맞추지 않는다.**

## workload

모든 stage는 같은 stack/JVM 수명 안에서 완료된다. run 사이에 재시작이 없다.

| 순서 | 구간 | 목표 RPS | 길이 | 집계 |
|---|---|---|---|---|
| 1 | 초기 ramp | 1 → 155 user | 5s | 제외 |
| 2 | warm-up hold | 50 | 30s | **제외** |
| 3 | 전환 | 155 → 155 (`constantConcurrentUsers`) | 5s | 제외 |
| 4 | stage-1 | 50 | 30s | 측정 |
| 5 | 전환 | 155 → 310 | 5s | 제외 |
| 6 | stage-2 | 100 | 30s | 측정 |
| 7 | 전환 | 310 → 465 | 5s | 제외 |
| 8 | stage-3 | 150 | 30s | 측정 |
| 9 | 전환 | 465 → 620 | 5s | 제외 |
| 10 | stage-4 | 200 | 30s | 측정 |
| 11 | 전환 | 620 → 713 | 5s | 제외 |
| 12 | stage-5 | 230 | 30s | 측정 |

`maxDuration`은 210초다. 이후 backlog가 0이 될 때까지 drain한다(조기 종료). user 수는
`ceil(230 × 3.1) = 713`이므로 1000으로 둔다.

각 hold 안에서 앞 `steadyGuardSeconds`(3초)는 측정 창에서 제외한다. 전환 구간은 Gatling이
`stage-trace.csv`에 예측 기록하고, harness가 그 경계를 읽어 stage를 라벨링하므로 측정에서 분리된다.
trace는 시뮬레이션 생성 시점에 한 번에 기록되며 anchor 시각과 함께 남는다.

## 정확한 실행 명령

```powershell
# max-in-flight 16 → 8 → 64 순서로 각 1회
.\scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1 -DispatchMode mysql -Staircase `
  -MySqlMaxInFlight 16 -MySqlClaimBatchSize 16 -MySqlClaimTimeout 30s -WorkerCount 16 `
  -LatencySeed 20260920 -UserCount 1000 -DrainTimeoutSeconds 600 -RunId capacity-mif16-20260920

.\scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1 -DispatchMode mysql -Staircase `
  -MySqlMaxInFlight 8 -MySqlClaimBatchSize 16 -MySqlClaimTimeout 30s -WorkerCount 16 `
  -LatencySeed 20260920 -UserCount 1000 -DrainTimeoutSeconds 600 -RunId capacity-mif8-20260920

.\scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1 -DispatchMode mysql -Staircase `
  -MySqlMaxInFlight 64 -MySqlClaimBatchSize 16 -MySqlClaimTimeout 30s -WorkerCount 16 `
  -LatencySeed 20260920 -UserCount 1000 -DrainTimeoutSeconds 600 -RunId capacity-mif64-20260920
```

분석과 비교:

```powershell
.\scripts\mysql-judge-tradeoff\Analyze-TradeoffRun.ps1 -RunDirectory .\results\mysql-judge-tradeoff\capacity-mif16-20260920
.\scripts\mysql-judge-tradeoff\Analyze-TradeoffRun.ps1 -RunDirectory .\results\mysql-judge-tradeoff\capacity-mif8-20260920
.\scripts\mysql-judge-tradeoff\Analyze-TradeoffRun.ps1 -RunDirectory .\results\mysql-judge-tradeoff\capacity-mif64-20260920
.\scripts\mysql-judge-tradeoff\Compare-TradeoffRuns.ps1 -RunDirectory @(
  '.\results\mysql-judge-tradeoff\capacity-mif16-20260920',
  '.\results\mysql-judge-tradeoff\capacity-mif8-20260920',
  '.\results\mysql-judge-tradeoff\capacity-mif64-20260920')
```

`-Staircase`는 `-TargetRps`, `-DurationSeconds`, `-FaultEnabled`를 쓰지 않는다. Gatling exit code `0`은
통과, `2`는 assertion 실패이며 리포트가 완전하면 `events.gatlingAssertionFailed=true`로 기록하고 run을
계속한다. `1`이거나 리포트가 없으면 실패다.

## 산출물

`results/mysql-judge-tradeoff/<run-id>/` (Git에서 무시됨)에 남는다.

- `timeseries.csv` — 1초 tick. 시각, phase, stage, 목표 RPS, 누적 accepted/result/scoreboard,
  contest·global unfinished outbox, unapplied scoreboard, 노드별 running/queued/reserved,
  `Threads_connected`/`Threads_running`, `Innodb_row_lock_current_waits`/`Innodb_row_lock_waits`,
  `Questions`. 실제 tick 주기(`sampleIntervalMs`)와 수집 소요(`sampleElapsedMs`)를 함께 남긴다.
- `stages.json` — trace 정렬 판정, stage별 hold 창과 측정 창, warm-up/측정 경계, drain 시각.
- `http-1s.csv` — Gatling `simulation.log`의 REQUEST 줄에서 초 단위 offered/OK/429/503/500/기타.
- `latency.csv`, `stale-reclaims.csv`, `claim-attempts.tsv`, `metrics/*.prom`, `gatling-simulation.log`.
- `parameters.json`, `events.json` — 실행 파라미터와 Git commit, warm-up/측정/drain 경계, Gatling exit code.

## 실행 결과 (3 run, 각 1회, 2026-09-20)

세 run 모두 integrity를 통과했다. count는 accepted / unique / results / scoreboard가 모두 같고,
`staleReclaims`는 세 run 모두 0이다. 429/503/500은 어느 stage에서도 0이며
`apiRateLimitSuspected`는 세 run 모두 `no`다. 따라서 아래 수치는 rate limiter가 아니라 judge
경로의 값이다.

| run | mif | accepted=unique=results=scoreboard | completedHttp | drain s | knee (측정) | 429 |
|---|---:|---|---:|---:|---|---|
| `capacity-mif16-20260920` | 16 | 26644 | 26595 | 60.354 | (100, 150] | 0 |
| `capacity-mif8-20260920` | 8 | 26645 | 26615 | 203.287 | (50, 100] | 0 |
| `capacity-mif64-20260920` | 64 | 26668 | 26618 | 25.542 | (100, 150] | 0 |

`accepted`와 `completedHttp`의 차이(30~50건)는 `maxDuration` 종료 시점에 이미 서버에 저장됐지만
클라이언트 로그에 완료로 남지 않은 요청이다. `unavailable`에 이유가 기록되어 있고 integrity 실패가
아니다.

### stage별 측정값

backlog는 judge outbox(`unfinishedOutbox`)와 scoreboard 미적용 합이다. `growth`는 측정 창의
끝-시작 차이를 창 길이로 나눈 값(row/s)이며 판정 규칙은 이것 하나다. `LS`는 같은 창의 최소제곱
기울기로, 판정에는 쓰지 않고 판정이 표본 잡음에 흔들리는지 보기 위한 참조값이다.

**mif=16**

| stage | 목표 RPS | 판정 | result RPS | growth | LS | p95 L_total ms | running avg /32 | queued avg | reserved avg | occ ms | timed ms | untimed ms | claims/s | rows/claim |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| stage-1 | 50 | steady | 48.111 | 0.2693 | 0.1176 | 439.104 | 6.407 | 0 | 6.407 | 133.171 | 136.348 | -3.177 | 20.185 | 2.670 |
| stage-2 | 100 | steady | 95.333 | 0.6156 | 0.0273 | 541.543 | 16.481 | 0 | 16.481 | 172.878 | 134.666 | 38.212 | 19.111 | 5.740 |
| stage-3 | 150 | overloaded | 113.519 | 32.2469 | 32.4766 | 9124.193 | 24.704 | 0 | 24.704 | 217.620 | 148.873 | 68.747 | 16.148 | 7.959 |
| stage-4 | 200 | overloaded | 119.111 | 75.7716 | 73.7322 | 28486.586 | 24.407 | 0 | 24.407 | 204.910 | 141.363 | 63.547 | 15.259 | 8.791 |
| stage-5 | 230 | overloaded | 116.296 | 107.2981 | 107.5688 | 56463.953 | 23.296 | 0 | 23.296 | 200.316 | 147.788 | 52.528 | 15.148 | 8.726 |

**mif=8**

| stage | 목표 RPS | 판정 | result RPS | growth | LS | p95 L_total ms | running avg /32 | queued avg | reserved avg | occ ms | timed ms | untimed ms | claims/s | rows/claim |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| stage-1 | 50 | steady | 47.444 | 0.5772 | -0.2283 | 657.208 | 8.370 | 0 | 8.259 | 174.079 | 139.202 | 34.877 | 19.741 | 2.752 |
| stage-2 | 100 | overloaded | 71.481 | 25.4980 | 25.3035 | 12123.755 | 13.778 | 0 | 13.778 | 192.751 | 135.371 | 57.380 | 17.481 | 4.612 |
| stage-3 | 150 | overloaded | 64.037 | 82.5317 | 84.3111 | 55084.932 | 12.815 | 0 | 12.815 | 200.119 | 147.557 | 52.562 | 17.037 | 4.352 |
| stage-4 | 200 | overloaded | 65.889 | 130.3428 | 131.5378 | 115210.249 | 13.222 | 0 | 13.222 | 200.671 | 146.013 | 54.658 | 16.074 | 4.505 |
| stage-5 | 230 | overloaded | 61.481 | 165.6218 | 166.1412 | 197893.955 | 12.037 | 0 | 12.037 | 195.784 | 153.787 | 41.997 | 15.630 | 4.405 |

**mif=64**

| stage | 목표 RPS | 판정 | result RPS | growth | LS | p95 L_total ms | running avg /32 | queued avg | reserved avg | occ ms | timed ms | untimed ms | claims/s | rows/claim |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| stage-1 | 50 | steady | 47.852 | -0.1539 | 0.0983 | 469.011 | 8.963 | 0.037 | 9.000 | 188.080 | 137.487 | 50.593 | 20.370 | 2.678 |
| stage-2 | 100 | steady | 95.963 | 0.1923 | -0.0513 | 485.092 | 14.593 | 0.444 | 15.037 | 156.696 | 135.666 | 21.030 | 19.852 | 5.513 |
| stage-3 | 150 | overloaded | 143.148 | 1.1155 | 0.9651 | 2286.137 | 29.556 | 37.444 | 67.000 | 468.047 | 155.257 | 312.790 | 18.778 | 8.641 |
| stage-4 | 200 | overloaded | 159.481 | 34.6101 | 34.5480 | 7569.539 | 32.000 | 77.407 | 109.407 | 686.019 | 138.429 | 547.590 | 16.370 | 11.072 |
| stage-5 | 230 | overloaded | 147.963 | 75.2308 | 75.3639 | 23858.510 | 32.000 | 76.074 | 108.074 | 730.412 | 153.607 | 576.805 | 15.370 | 10.730 |

`occ`는 Little's law로 구한 점유 시간(`reserved 평균 / result 처리율`)이다. `reserved`는 claim했지만
끝나지 않은 작업이므로 mif가 worker 수보다 크면 여기에 로컬 큐 대기가 포함된다. `timed`는 JVM이
실제로 측정한 judge 실행 시간이고 `untimed`는 그 차이다. mif=16 stage-1의 `untimed`가 -3.177ms로
음수인 것은 두 값이 서로 다른 창(점유는 1초 gauge 평균, timed는 경계 스냅샷 사이 delta)에서
계산되기 때문이며, 0 근처라는 뜻으로만 읽는다.

### capacity knee 비교

| run | knee | 아래 끝 판정 | 위 끝 판정 | knee 아래 stage의 성장 | knee 위 stage의 성장 |
|---|---|---:|---:|---|---|
| mif=16 | (100, 150] RPS | 흔들림 (여유 0.3844 row/s) | 견고함 (여유 812.013 row) | net 16 row, 최대 tick swing 37 row | net 838 row |
| mif=8 | (50, 100] RPS | 흔들림 (여유 0.4228 row/s) | 견고함 (여유 636.998 row) | net 15 row, 최대 tick swing 13 row | net 663 row |
| mif=64 | (100, 150] RPS | 견고함 (여유 0.8077 row/s) | **흔들림** (여유 3.003 row) | net 5 row, 최대 tick swing 27 row | net 29 row, 최대 tick swing 55 row |

세 run 모두 knee의 한쪽 끝은 임계값 1.0 row/s 근처에서 결정된다. mif=16과 mif=8은 **아래** 끝이,
mif=64는 **위** 끝이 그렇다. mif=64 stage-3의 순증가는 29 row인데 같은 창에서 한 tick이 최대
55 row를 움직였으므로, "150 RPS에서 처음 무너졌다"는 판정은 표본 하나에 좌우된다. 임계값을 두 배로
하면 steady, 마지막 표본을 빼면 steady가 된다. 따라서 이 사다리(50 RPS 간격)가 말할 수 있는 것은
"mif=16과 mif=64의 용량 경계는 100과 150 사이 어딘가"까지이고, 그보다 정밀한 위치는 이 실험의
해상도 밖이다. mif=8만 경계가 한 단계 아래에 있다.

### overload 구간의 포화 처리량 (용량 비교용)

| run | 포화 result RPS (overloaded stage 중앙값) | 단일 최고 초 result RPS | 최고 steady rung |
|---|---:|---:|---:|
| mif=16 | 116.296 (3 stage) | 119.111 | 95.333 |
| mif=8 | 64.963 (4 stage) | 71.481 | 47.444 |
| mif=64 | 147.963 (3 stage) | 159.481 | 95.963 |

비율: mif16/mif8 = 1.790, mif64/mif16 = 1.272 (포화 중앙값 기준). mif=8의 포화값은 부하가 커질수록
71.481 → 64.037 → 65.889 → 61.481로 **떨어진다**(혼잡 붕괴 형태). mif=16과 mif=64는 평평하다.
`최고 steady rung` 비교(mif64/mif16 = 1.007)는 사다리 해상도에 묶여 있어 용량 추정으로 쓸 수 없다.

drain도 함께 본다: mif=64 25.542s < mif=16 60.354s < mif=8 203.287s. run 끝의 잔여 backlog를
비우는 시간이며 같은 순서다.

### 이론값과의 격차 (기제)

147.5ms 평균 기준 이론 상한은 mif=8에서 약 108 RPS, mif≥16에서 약 217 RPS다. 측정된 포화
중앙값은 mif=8 64.963, mif=16 116.296, mif=64 147.963이다. 즉 mif=8은 이론의 60%,
mif=16은 54%, mif=64는 68%다. 격차의 원인은 위 표의 `timed` / `running` / `queued`로 설명된다.

- mif=16: `running` 평균이 32개 중 23.3이고 `queued`는 전 구간 0이다. 로컬 큐가 비어 있으므로
  worker가 노는 것이며, poll 100ms마다 claim 상한이 `mif - reserved`로 묶여 있어 큐가 채워지지
  않는다. timed 실행 시간(147.788ms) 자체는 정상이다.
- mif=64: `running`이 32/32에 도달하고 `queued`가 76~77, `reserved`가 108~109다. worker는
  포화됐고 처리량이 mif=16보다 1.272배 높다. `rows/claim`도 8.7 → 10.7로 커지는데, batch 16에서
  로컬 큐가 차 있으면 claim 결과가 전부 소비되지 않기 때문이다.
- mif=8: `running` 최대가 노드당 8(= worker 16 중 8)에서 잘리고 평균 12.037/32다. worker 16개 중
  8개만 동시에 돌 수 있다는 코드 예측과 일치한다. 같은 run에서 `reserved` 최대도 노드당 8이고
  `localWaiting` 최대는 0이므로, 이 제한은 로컬 큐 용량이 아니라 poll 1회의 claim 상한
  `capacity = mif − reserved`가 건 것이다. 큐 용량(mif)만 8이었다면 reserved가 8을 넘어
  16개 worker가 돌 수도 있었다.
- `untimed`(occ − timed)는 mif=16 stage-1의 −3.177ms를 빼면 21.030~576.805ms다. 그 한 값이 0
  근처인 이유는 바로 위에 적었고, 나머지 stage에서는 claim/poll/결과 저장 등 timed 구간 밖의
  시간이 존재하며 mif가 커질수록 그 값도 커진다(로컬 큐 대기 포함).

`rows/claim`과 `claims/s`는 표에만 두고 해석하지 않는다. claim은 batch 16으로 최대 16건을
가져오지만 실제 소비량은 큐 상태에 따라 달라지므로, 이 둘로 judge 비용을 환산하지 않는다.

### 지연을 정상 서비스 지연으로 읽을 수 있는 stage

`reliableAsSteadyStateLatency`는 (a) 판정이 steady이고, (b) 그 판정이 임계값 절반/두 배 및 끝
표본 제거에 흔들리지 않고, (c) 429/503/500/연결 거부가 임계값 미만이며, (d) 창 안 완료 표본이
10건 이상일 때만 true다.

| run | 측정 stage 중 신뢰 가능한 stage | `measurement-steady` cohort |
|---|---|---|
| mif=16 | stage-1만 (50 RPS) | 1347건, p50 288.936 / p95 439.104 / p99 2265.390 ms |
| mif=8 | **없음** | unavailable (신뢰 가능한 창이 없음) |
| mif=64 | stage-1, stage-2 (50·100 RPS) | 4040건, p50 310.668 / p95 484.461 / p99 2310.246 ms |

run 전체 cohort(`all`)의 p95/p99는 warm-up, 전환 구간, overload stage, drain을 모두 포함하므로
서비스 지연이 아니다: mif=16 p95 53957.290ms, mif=8 p95 188703.131ms, mif=64 p95 22515.694ms.
이 값들은 부하 대기열의 크기를 말할 뿐이며 max-in-flight 우열의 근거로 쓰지 않는다.
`measurement-steady`만이 같은 세 지연을 서비스 시간으로 제한한 cohort이고, mif=8에서는 그 cohort가
아예 성립하지 않는다.

### 가설별 판정

1. **mif가 worker 수보다 작으면 동시 실행 worker가 제한된다 — 지지됨.**
   mif=8에서 노드당 `running` 최대 8, 평균 12.037/32. mif=16/64는 노드당 최대 16에 도달한다.
2. **mif = worker 수가 모든 worker를 돌리는 최소값이다 — 지지됨.** mif=8만 노드당 8에서
   잘리고, mif=16에서 16에 도달한다. 다만 16에서도 평균 점유는 23.296/32(73%)이라 "모든 worker를
   돌린다"는 상한이지 "계속 돌린다"는 뜻이 아니다.
3. **mif를 worker 수 훨씬 위로 올려도 처리량 상한이 크게 오르지 않는다 — 이 사다리에서는
   반박됨.** 포화 중앙값이 116.296 → 147.963으로 1.272배 올랐고 drain은 60.354s → 25.542s로
   줄었다. 다만 그 대가는 `queued` 76~77 / `reserved` 108~109의 로컬 대기이며, 217 RPS 이론값에는
   여전히 못 미친다(68%).
4. **큰 mif는 burst를 흡수하지만 더 많은 대기와 긴 tail, 긴 drain을 남긴다 — 대기와 reserved는
   지지, tail과 drain은 반박.** mif=64의 `queued`/`reserved`는 확실히 크다(위 표). 그러나 overload
   stage의 p95는 mif=16 56463.953ms 대 mif=64 23858.510ms로 mif=64가 **짧고**, drain도
   mif=64가 짧다. 대기가 로컬 큐로 흡수되어 backlog 적체가 줄어든 결과로 보이며, 이 관측은
   "큰 mif = 긴 tail"이라는 예상을 지지하지 않는다.
5. **30s claim timeout에서 정상상태 reclaim은 거의 없다 — 지지됨.** 세 run 모두
   `staleReclaimRowsInWindow` 0, `claim_stale` delta 0, stale-token completion 0, duplicate
   judge 상·하한 0. 모든 stage에서 `attempts > 1` 행이 없다.

### 알려진 한계

- **사다리 해상도.** 50 RPS 간격이라 knee의 양 끝 중 한쪽은 항상 임계값 근처에서 결정된다.
  mif=16·mif=8은 아래 끝, mif=64는 위 끝이 그렇다. 더 좁은 간격의 재실행 없이 knee를 이보다
  정밀하게 말할 수 없다.
- **판정 규칙 자체의 잡음.** 세 run 모두 최저 rung에서 순증가가 한 tick swing과 같은 크기다
  (mif=16 stage-1 net 7 / swing 21, mif=8 stage-1 net 15 / swing 13, mif=64 stage-1 net -4 /
  swing 18). "가장 낮은 stage는 유지됐다"는 판정은 이 실험에서 잡음 수준이다.
- **boundary 스냅샷 지연.** stage 경계 prometheus 스냅샷이 경계 후 최대 890~912ms(mif=8),
  216~468ms(mif=16·64)에 찍혔다. 스냅샷은 초 단위 tick에서 찍히므로 이 값은 실제 어긋남의
  **하한**이고, per-stage prometheus delta는 그만큼(최소 그만큼) 창이 밀린다.
  1초 시계열 기반 판정과 backlog 수치에는 영향이 없고, `claim`/`timed` delta에만 해당한다.
- **gauge 두 개의 표본 시점 차.** mif=8 stage-1에서 `running` 평균(8.370)이 `reserved`
  평균(8.259)보다 큰데, 이는 그 stage의 27 tick 중 1개에서만 나타난다. 두 gauge는 같은 scrape
  안에서도 서로 다른 시점에 읽히므로 순간적으로 `running` > `reserved`가 될 수 있다. 이 run의
  동시성 해석은 평균이 아니라 run 전체 노드별 최댓값(`running` 8 / `reserved` 8 /
  `localWaiting` 0)에 기대므로 이 값의 영향을 받지 않는다.
- **sampler 자체 부하.** `load` phase 전체(측정 창 밖 전환 구간 포함)에서 tick 평균 주기는
  994.763~1004.260ms, 최대 1907.2~2429.6ms, 수집 소요 평균은 375.054~400.118ms다. drain phase는
  poll 주기가 느려 평균 1352.763~1386.964ms다. 반면 **측정 창 안에서는** 1500ms를 넘긴 tick이
  0건이므로, stage 판정과 backlog 수치에 쓰인 표본은 모두 정상 주기다. 수집 비용은 같은
  호스트의 docker exec이므로 부하에 포함된다.
- **run 사이 seed 공유.** 세 run이 같은 latency seed를 쓰므로 판정용 synthetic 작업 구성이
  동일하다. 좋은 점은 비교 가능성이고, 한계는 반복 간 우연 변동을 분리할 수 없다는 점이다.
  각 조건 1회 실행은 계획된 제약이다.
- **`completedHttp` ≠ accepted.** maxDuration 종료 시점의 in-flight 요청 때문이며 위에 적었다.
- **trace 정렬은 전체 창만 본다.** `stageWindowAlignment=ok`는 계획 종료와 마지막 요청의 차이만
  본다. stage별 offered/target 비율은 분석기가 따로 내며, 세 run 모두 99.41~100.10%다.
- **batch-1 pod.** `compose.loadtest.yaml`의 batch-1은 매 스냅샷에서 scrape되지만 판정용 합계에
  포함되지 않는다(judge-1/judge-2만 합산). scoreboard는 batch-1이 처리하므로 scoreboard
  backlog 증가율은 별도 열로만 본다.
- **이론값은 참조다.** 108/217 RPS는 147.5ms 평균만 쓴 계산이고, 측정 평균은
  146.84~146.89ms로 이론 입력과 사실상 같다. 따라서 격차는 상수 차이가 아니라 위 기제에서 온다.

### 다음 timeout 실험에 권장하는 mif와 부하 구간

측정값만으로 정한다.

- **mif는 16과 64 사이에서 고른다.** mif=8은 동시 실행이 노드당 8로 잘려 포화 처리량이
  mif=16의 56%이고 부하가 커질수록 오히려 떨어진다(혼잡 붕괴). timeout 실험에서 mif를 8로 두면
  lease 실패가 아니라 처리량 부족을 측정하게 된다.
- **timeout 실험의 부하 구간은 100~150 RPS로 잡는다.** knee가 세 run 모두 이 근처이고, 150 RPS는
  mif=16에서 여유 812 row로 확실히 overload, mif=64에서는 여유 3 row로 경계다. 즉 100 RPS는
  "정상 유지", 150 RPS는 "적체 시작"이 보장되는 두 기준점이다. mif=64에서 150 RPS를 쓰면 경계
  판정이 표본 하나에 좌우되므로, mif=64를 쓸 경우 130~150 사이를 추가로 나누거나 150 대신
  140을 쓰는 편이 낫다.
- **timeout 효과는 복구 시간과 대기 비용으로 본다.** 30s에서 stale reclaim이 0이었으므로 더
  짧은 timeout의 비용(`duplicateJudgementMillisLower/UpperBound`, `claim_stale` delta)과 이득
  (drain 단축, backlog 정상화 시간)이 이 실험의 종속 변수다. 정상상태 처리량은 timeout이 아니라
  mif가 결정했다.
- **overload stage의 p95/p99를 timeout 우열의 근거로 쓰지 않는다.** overload 구간의 백분위는
  대기열 크기이고, 이 실험에서 그것은 mif가 정했다.

이 문서가 남긴 "timeout을 짧게 줄이면 정상상태에서 실제로 무엇이 일어나는가"는
`docs/MYSQL_JUDGE_NORMAL_TIMEOUT_DUPLICATION.md`에서 mif 16과 64의 timeout별 중복 claim·중복
채점으로 측정했다. 그 라운드는 위에서 권한 100~150 RPS가 아니라 포화의 약 70%(80/100 RPS)에서
돌았으므로, 두 문서의 부하 구간은 다르다.

