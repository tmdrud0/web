# MySQL claim 기반 judge의 poll 간격별 정상상태 용량 (100ms vs 20ms)

## 목적과 범위

`docs/MYSQL_JUDGE_MAX_IN_FLIGHT_CAPACITY.md`는 poll 간격 100ms에서 MySQL claim 방식이 이론 용량
(약 217 RPS = 노드 2 × worker 16 / 평균 채점 147.5ms)의 54~68%만 낸다고 측정했다. 원인 후보 중 하나는
poll 간격 사이에 worker가 노는 것이다. 이 문서는 그 후보 하나만 검증한다.

> poll 간격을 20ms로 줄이면 MySQL 방식의 포화 처리량이 얼마나 오르고, 그 대가로 DB 부하가 얼마나
> 늘어나는가?

RabbitMQ와의 피크 부하 비교에 앞서 "MySQL을 충분히 튜닝하지 않았다"는 반론에 답하는 것이 목적이다.
결과를 미리 정하지 않았다. 사전 예상은 "MIF 16은 크게 오르고 MIF 64는 덜 오른다"였다.

## 메커니즘: poll 간격이 건드리는 것과 건드리지 않는 것

`MysqlContestJudgeDispatcher.poll()`은 `@Scheduled(fixedDelayString = poll-interval)`이다. 한 poll이
끝난 뒤(claim 트랜잭션 포함) 간격만큼 쉬고 다시 claim한다. claim 크기는
`min(batch 16, maxInFlight − reserved)`이고, `reserved`는 worker가 `judge()`를 끝낸 **뒤** 줄어든다.

- 줄이는 것: worker가 일을 끝낸 뒤 다음 claim이 올 때까지의 공백(poll 대기 + claim 왕복).
- 줄이지 않는 것: worker 스레드 안에서 채점(timed) 이후에 일어나는 결과 저장과 outbox 완료 처리
  (`completeAll`). 이 시간은 `running` gauge에 포함된다.
- 대가: 일이 없어도 노드마다 초당 `1 / (poll + claim 시간)`번 claim 쿼리를 보낸다.

## 고정 파라미터

기존 문서와 같다. 바뀐 것은 poll 간격 하나다.

| 항목 | 값 |
|---|---|
| dispatch mode | `mysql` |
| judge 노드 / 노드당 worker | 2 / 16 |
| claim batch / claim timeout | 16 / 30s |
| max-in-flight | 16, 64 |
| **poll interval** | **100ms (기준선), 20ms (실험)** |
| latency seed / synthetic latency | `20260920` / 95% 50ms, 5% 2000ms (`key-source=code`) |
| user count / drain timeout | 1000 / 600s |
| 사다리 | warm-up 50 → 50, 100, 150, 200, 230 RPS, hold 30s, guard 3s |

## 하네스 변경 (실험 전에 별도 커밋)

| 커밋 | 내용 | 이유 |
|---|---|---|
| `9f85414` | `ContainerCpuSampler.ps1` 추가, `-ResetMySqlVolume`, `-IdleBaselineSeconds`, 부하 전 outbox 행 수와 judge 컨테이너 환경 기록 | 이 실험의 핵심 대가 지표인 MySQL CPU가 없었다. DB 상태 초기화와 유휴 비용 측정이 필요했다 |
| `4988c5f` | 분석기에 stage별 컨테이너 CPU·DB 비용 표와 idle baseline, 비교기에 poll 간격 축 추가 | poll 간격을 축으로 한 비교 표를 만들기 위해 |
| `a5b01ed` | `-Staircase` 회귀 수정 | `68821e3`(fault-recovery 추가)에서 normal-timeout 전용 분기의 조건을 `$NormalTimeout`에서 `$stagedLoad`로 바꿨다. `$stagedLoad`에는 staircase도 들어가므로 staircase 실행은 빈 prefix로 별도 warm-up contest를 만들려다 부하 전에 죽었다. 해당 분기 여섯 곳을 `$phasedLoad`로 되돌렸다 |
| `307fa7d` | MySQL 조회를 실행당 mysql 세션 하나로 통일, CPU 수집기를 run 내 분석 전에 종료 | 아래 "Docker Desktop 크래시" 참고. 1초 sampler가 매초 `docker compose exec`를 열던 것을 없앴다 |

### poll 간격이 실제로 적용됐다는 증거

- `parameters.json`: `"mysqlPollInterval": "20ms"` / `"100ms"`.
- `judge-config-evidence.json`(컨테이너 환경): judge-1·judge-2 모두 `CONTEST_JUDGE_MYSQL_POLL_INTERVAL=20ms`
  (100ms 실행은 `100ms`). `compose-config.yaml`에도 같은 값이 렌더링되어 있다.
- 행동 증거: 부하 없는 30초 동안 노드별 claim 호출이 20ms에서 47.3회/s, 100ms에서 9.8회/s다
  (`summary.json`의 `idleBaseline.claimCallsPerSecondByNode`). fixedDelay이므로 1/(0.020 + claim ≈ 1ms) ≈ 47,
  1/(0.100 + 1ms) ≈ 9.9와 맞는다. dispatcher는 poll 간격을 로그로 남기지 않으므로 이 두 가지가 증거다.

### DB 상태

- 이번 네 실행은 모두 `-ResetMySqlVolume`으로 MySQL 볼륨을 지우고 새로 migrate한 스키마에서 시작했다.
  부하 직전 `contest_judge_outbox`는 0행, `contest_submission`은 0행이다(`events.json`의
  `outboxRowsBeforeLoad`, `contestSubmissionRowsBeforeLoad`).
- 2026-09-20의 poll 100ms 실행은 볼륨을 유지한 채 돌았다. 같은 prefix(`tradeoff_seed_20260920`)로
  seeding하면 이전 capacity 실행의 행은 cascade로 지워지므로, 남아 있던 것은 다른 prefix
  (`tradeoff_seed_20260919`)의 마지막 pilot 실행 행(약 114건, `pilot-head-final-v2`의 accepted)으로
  추정한다. claim 인덱스 `(status, claimed_at, id)`에서 PUBLISHED 행은 claim 범위 밖이므로 영향은
  작다고 보지만, 측정한 값은 아니다.

### 컨테이너 CPU 수집 방식

`alpine` helper 컨테이너 하나가 `--network none --cgroupns=host -v /sys/fs/cgroup:/cg:ro`로 떠서, 1초마다
각 컨테이너의 cgroup v2 `cpu.stat`(`usage_usec`, `throttled_usec`, `nr_throttled`)을 읽어 stdout에 쓴다.
실행 중 Docker API 호출은 없다(시작 시 `docker inspect`·`docker run` 각 1회, 종료 시 `docker logs`·`docker rm`
각 1회). 코어 수는 실행 후에 두 판독 사이의 실제 간격으로 나눠 계산한다. helper 자신의 CPU도 같은
파일에 `cpu-sampler`로 남는다. 네 실행 모두 평균 0.0022~0.0025 코어다.

## Docker Desktop 크래시와 무효 처리된 실행

- 크래시 시점: 2026-09-25 03:31~03:32 UTC. 첫 번째 큐(poll 20ms MIF 16/64, poll 100ms MIF 64가 끝난 뒤)의
  네 번째 실행 `capacity-mif16-poll100-20260925`이 `docker compose down` → `docker volume rm
  oj-loadtest-mysql-data` → `docker compose up -d --build`의 이미지 빌드 단계에 있을 때였다
  (`failure.txt`: `target web-1: failed to receive status: rpc error ... EOF`). 이 시점에는 CPU helper가
  떠 있지 않았고(이전 실행 종료 시 제거됨) 부하도 없었다.
- 당시 수집 방식: CPU는 위 helper(실행 중 API 호출 없음)였다. 반면 기존 1초 sampler와 drain 루프,
  boundary 스냅샷은 SQL 한 번마다 `docker compose exec mysql ...`을 새로 열었다. 부하와 drain 동안 초당
  약 1회(스냅샷 경계에서는 더 자주), 호출마다 Docker API를 여러 번 부른다. docker CLI를 동시에 부른
  프로세스는 하네스 하나뿐이었다.
- 조치: `307fa7d`에서 모든 SQL을 실행당 `docker compose exec` 한 번으로 연 mysql 세션에 stdin으로 보내도록
  바꿨다(실행당 세션 1회 오픈, 301~332문장). sampler의 수집 소요 평균은 약 520ms에서 105~128ms로
  줄었다. CPU helper는 금지 조건(매초 `docker stats`/`exec`/`inspect`, 여러 프로세스의 동시 CLI 호출)에
  해당하지 않아 유지했다. `docker stats` 스트림보다도 daemon 부하가 적다.
- 스모크(`smoke-session-cpu-20260925`, 20/20/40 RPS × 15초)로 수집을 확인한 뒤 본 실행 네 번을 다시 돌렸다.
  재실행 동안과 이후 Docker는 정상이었다.
- 무효: 크래시 전의 `*-20260925-precrash` 네 디렉터리(세 개는 완료, 하나는 빌드 중 실패)와
  staircase 회귀로 부하 전에 죽은 `capacity-mif16-poll20-20260925-harnessbug`. 이 문서의 수치에는 쓰지 않는다.

## 정확한 실행 명령

각 1회, 이 순서로. 네 실행 모두 같은 커밋(`307fa7d`)이다.

```powershell
.\scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1 -DispatchMode mysql -Staircase `
  -MySqlMaxInFlight 16 -MySqlClaimBatchSize 16 -MySqlClaimTimeout 30s -WorkerCount 16 `
  -MySqlPollInterval 20ms -LatencySeed 20260920 -UserCount 1000 -DrainTimeoutSeconds 600 `
  -ResetMySqlVolume -IdleBaselineSeconds 30 -RunId capacity-mif16-poll20-20260925

.\scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1 -DispatchMode mysql -Staircase `
  -MySqlMaxInFlight 64 -MySqlClaimBatchSize 16 -MySqlClaimTimeout 30s -WorkerCount 16 `
  -MySqlPollInterval 20ms -LatencySeed 20260920 -UserCount 1000 -DrainTimeoutSeconds 600 `
  -ResetMySqlVolume -IdleBaselineSeconds 30 -RunId capacity-mif64-poll20-20260925

# 같은 하네스의 poll 100ms 기준선 (CPU와 idle 비용을 같은 조건에서 얻기 위해)
.\scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1 -DispatchMode mysql -Staircase `
  -MySqlMaxInFlight 64 -MySqlClaimBatchSize 16 -MySqlClaimTimeout 30s -WorkerCount 16 `
  -MySqlPollInterval 100ms -LatencySeed 20260920 -UserCount 1000 -DrainTimeoutSeconds 600 `
  -ResetMySqlVolume -IdleBaselineSeconds 30 -RunId capacity-mif64-poll100-20260925

.\scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1 -DispatchMode mysql -Staircase `
  -MySqlMaxInFlight 16 -MySqlClaimBatchSize 16 -MySqlClaimTimeout 30s -WorkerCount 16 `
  -MySqlPollInterval 100ms -LatencySeed 20260920 -UserCount 1000 -DrainTimeoutSeconds 600 `
  -ResetMySqlVolume -IdleBaselineSeconds 30 -RunId capacity-mif16-poll100-20260925
```

`-IdleBaselineSeconds 30`은 seeding 후 부하 전에 30초 동안 아무것도 보내지 않고 두 스냅샷으로 감싼다.
부하 schedule 자체는 기존과 같다.

poll 100ms 기준선을 MIF 16까지 다시 돌린 이유: 하네스가 바뀌었다(sampler 수집 비용, 커밋 `dce90f7` 이후
Gatling step simulation 변경, DB 초기 상태). 아래 표에서 보듯 이 차이만으로 MIF 16의 포화값이 6% 움직였고,
이는 poll 효과보다 크다. 같은 하네스의 기준선 없이는 poll 효과를 분리할 수 없다.

분석과 비교:

```powershell
foreach ($r in 'capacity-mif16-poll20-20260925','capacity-mif64-poll20-20260925',
               'capacity-mif64-poll100-20260925','capacity-mif16-poll100-20260925') {
  .\scripts\mysql-judge-tradeoff\Analyze-TradeoffRun.ps1 -RunDirectory .\results\mysql-judge-tradeoff\$r
}
# 09-20 실행은 원본 summary를 보존하려고 사본을 현재 분석기로 다시 분석했다(수치는 원 문서와 같다).
.\scripts\mysql-judge-tradeoff\Compare-TradeoffRuns.ps1 `
  -OutputDirectory .\results\mysql-judge-tradeoff\poll-interval-comparison-20260925 -RunDirectory @(
  '.\results\mysql-judge-tradeoff\capacity-mif16-poll100-20260925',
  '.\results\mysql-judge-tradeoff\capacity-mif64-poll100-20260925',
  '.\results\mysql-judge-tradeoff\capacity-mif16-poll20-20260925',
  '.\results\mysql-judge-tradeoff\capacity-mif64-poll20-20260925',
  '.\results\mysql-judge-tradeoff\poll-interval-comparison-20260925\reanalyzed-capacity-mif16-20260920',
  '.\results\mysql-judge-tradeoff\poll-interval-comparison-20260925\reanalyzed-capacity-mif64-20260920')
```

## 산출물

`results/mysql-judge-tradeoff/` 아래(Git에서 무시됨):

- `capacity-mif16-poll20-20260925/`, `capacity-mif64-poll20-20260925/`,
  `capacity-mif64-poll100-20260925/`, `capacity-mif16-poll100-20260925/`
- `poll-interval-comparison-20260925/comparison.{md,json}` — 아래 두 표의 원본
- 실행마다 기존 산출물에 더해 `container-cpu-1s.csv`, `container-cpu-meta.json`, `container-cpu-raw.txt`,
  `judge-config-evidence.json`, `metrics/idle-{start,end}-*`가 있다.

## 실행 결과

### 유효성

네 실행 모두 integrity 통과(accepted = unique = results = scoreboard: 26636 / 26645 / 26629 / 26632),
`staleReclaims` 0, stale-token completion 0, 모든 stage에서 429/503/500 0, `apiRateLimitSuspected` no,
Gatling exit 0, trace 정렬 ok, 측정 창 안에서 1500ms를 넘긴 tick 0건(run 전체 최대 tick 간격은
1346~1507ms로 측정 창 밖이다). 따라서 네 실행 모두 근거로 쓴다.

### 포화 처리량 (poll × MIF)

이론 217 RPS는 참조값이다. 부하 생성기와 서버가 같은 물리 머신이므로 절대 RPS가 아니라 같은 조건 간
비율로만 읽는다.

| run | poll | MIF | 포화 result RPS (overloaded stage 중앙값) | 이론 대비 % | knee | drain s |
|---|---|---:|---:|---:|---|---:|
| `capacity-mif16-20260920` (기존) | 100ms | 16 | 116.296 (3 stage) | 53.6 | (100, 150] | 60.354 |
| `capacity-mif64-20260920` (기존) | 100ms | 64 | 147.963 (3 stage) | 68.2 | (100, 150] | 25.542 |
| `capacity-mif16-poll100-20260925` | 100ms | 16 | 123.704 (3 stage) | 57.0 | (100, 150], 아래 끝 흔들림 | 52.285 |
| `capacity-mif64-poll100-20260925` | 100ms | 64 | **149.296 (stage 3~5)** / 분석기 값 144.518 (4 stage) | 68.8 / 66.6 | 분석기: ≤ 50, 흔들림 → 실질 (100, 150] | 24.505 |
| `capacity-mif16-poll20-20260925` | 20ms | 16 | 124.963 (3 stage) | 57.6 | (100, 150], 양 끝 견고 | 50.306 |
| `capacity-mif64-poll20-20260925` | 20ms | 64 | 149.074 (3 stage) | 68.7 | (100, 150], 양 끝 견고 | 25.292 |

같은 하네스 안의 비율: **MIF 16: 20ms / 100ms = 1.010**, **MIF 64: 20ms / 100ms = 0.999**
(stage 3~5 기준. 분석기 값 144.518을 쓰면 1.032).

`capacity-mif64-poll100-20260925`의 knee 주석: 50 RPS stage-1이 성장률 1.0051 row/s로 임계값 1.0을
0.005 넘어 overloaded로 판정됐다. 순증가 26 row 대 임계 초과선 25.867 row이고 한 tick이 19 row를
움직였으며, 임계값 두 배나 끝 표본 하나 제거로 steady가 된다. 같은 run의 100 RPS stage-2는 −0.23 row/s로
steady다. 따라서 이 판정은 잡음이고, 분석기의 포화 중앙값은 50 RPS stage(47.704)를 포함한 4개의 중앙값이다.
비교에는 나머지 run과 같은 stage 3~5의 중앙값 149.296을 쓰고 두 값을 모두 적는다.
MIF 16 poll 100ms도 knee 아래 끝(100 RPS, 여유 0.3031 row/s)이 흔들린다. 기존 09-20 MIF 16과 같은 양상이다.

하네스 차이의 크기: 같은 poll 100ms에서 MIF 16이 116.296(09-20) → 123.704(09-25)로 1.064배다. 이번
poll 효과(1.010)보다 크다. MIF 64는 147.963 → 149.296(1.009)로 거의 같다. 09-20 실행과 이번 20ms 실행을
직접 비교하면 MIF 16이 "7% 올랐다"로 잘못 읽힌다.

### stage별 측정값과 DB 비용

`running`/`queued`는 두 노드 합의 측정 창 평균이고, `claims/s`·`rows/claim`은 stage 경계 Prometheus
스냅샷 delta, `Questions/s`·`row-lock waits/s`는 1초 시계열의 측정 창 delta, `MySQL CPU`는 cgroup 1초
판독의 측정 창 시간가중 평균(코어, 한도 2.0)이다. `idle`은 부하 전 30초이며 1초 sampler가 돌지 않는 구간이다
(나머지 stage의 Questions/s에는 sampler 자신의 초당 1문장이 들어 있다).

| run | stage | result RPS | running avg /32 | queued avg | claims/s | rows/claim | Questions/s | row-lock waits/s | MySQL CPU |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| mif16-poll100 | idle | 0 | 0 | 0 | 19.579 | 0 | 79.0 | 0 | 0.030 |
| mif16-poll100 | stage-1 (50) | 47.741 | 10.556 | 0 | 20.407 | 2.697 | 991.4 | 3.04 | 0.223 |
| mif16-poll100 | stage-2 (100) | 95.037 | 17.778 | 0 | 19.407 | 5.672 | 1779.1 | 8.81 | 0.361 |
| mif16-poll100 | stage-3 (150) | 123.704 | 26.852 | 0.185 | 17.037 | 8.180 | 2351.3 | 4.44 | 0.513 |
| mif16-poll100 | stage-4 (200) | 127.556 | 25.111 | 0 | 16.000 | 9.174 | 2705.7 | 3.96 | 0.621 |
| mif16-poll100 | stage-5 (230) | 117.593 | 23.556 | 0.259 | 15.704 | 8.623 | 2804.6 | 3.81 | 0.684 |
| mif16-poll20 | idle | 0 | 0 | 0 | 94.612 | 0 | 379.1 | 0 | 0.065 |
| mif16-poll20 | stage-1 (50) | 47.481 | 10.148 | 0 | 95.778 | 0.577 | 1251.9 | 3.44 | 0.248 |
| mif16-poll20 | stage-2 (100) | 95.407 | 11.963 | 0 | 75.667 | 1.450 | 2032.8 | 27.59 | 0.401 |
| mif16-poll20 | stage-3 (150) | 124.963 | 27.333 | 0 | 45.630 | 3.145 | 2680.0 | 61.22 | 0.696 |
| mif16-poll20 | stage-4 (200) | 128.296 | 25.556 | 0 | 41.889 | 3.546 | 3011.0 | 39.22 | 0.880 |
| mif16-poll20 | stage-5 (230) | 120.148 | 25.259 | 0 | 40.407 | 3.447 | 3048.9 | 20.59 | 0.988 |
| mif64-poll100 | idle | 0 | 0 | 0 | 19.643 | 0 | 79.0 | 0 | 0.028 |
| mif64-poll100 | stage-1 (50) | 47.704 | 7.259 | 0 | 20.444 | 2.710 | 990.4 | 3.26 | 0.230 |
| mif64-poll100 | stage-2 (100) | 95.481 | 16.926 | 1.185 | 19.815 | 5.546 | 1801.4 | 8.04 | 0.366 |
| mif64-poll100 | stage-3 (150) | 139.741 | 30.370 | 39.333 | 18.926 | 8.644 | 2709.5 | 23.74 | 0.520 |
| mif64-poll100 | stage-4 (200) | 160.667 | 32.000 | 79.667 | 16.926 | 10.972 | 3223.9 | 32.19 | 0.734 |
| mif64-poll100 | stage-5 (230) | 149.296 | 32.000 | 78.963 | 16.148 | 10.697 | 3289.9 | 22.70 | 0.818 |
| mif64-poll20 | idle | 0 | 0 | 0 | 94.727 | 0 | 379.6 | 0 | 0.065 |
| mif64-poll20 | stage-1 (50) | 47.667 | 6.148 | 0 | 94.296 | 0.580 | 1281.8 | 8.19 | 0.259 |
| mif64-poll20 | stage-2 (100) | 95.704 | 17.333 | 1.074 | 84.556 | 1.309 | 2114.1 | 32.37 | 0.422 |
| mif64-poll20 | stage-3 (150) | 140.185 | 30.704 | 36.556 | 74.000 | 2.234 | 2986.3 | 49.56 | 0.569 |
| mif64-poll20 | stage-4 (200) | 159.852 | 32.000 | 87.481 | 44.037 | 4.177 | 3389.1 | 59.30 | 0.972 |
| mif64-poll20 | stage-5 (230) | 149.074 | 32.000 | 85.815 | 39.667 | 4.309 | 3383.8 | 55.63 | 1.094 |

기존 09-20 poll 100ms 실행의 같은 표(MySQL CPU 없음)는 `poll-interval-comparison-20260925/comparison.md`의
"Database cost per stage, all runs"에 있다. 비교 가능한 지표는 이번 poll 100ms 기준선과 같은 범위다
(예: MIF 16 stage-5 Questions/s 2793.8 대 2804.6, row-lock waits/s 8.07 대 3.81).

MySQL은 네 실행의 어느 stage에서도 CFS throttling이 0이다(초당 최대 1.23 코어, 한도 2). judge 컨테이너는
포화 stage에서 0.10~0.19 코어(한도 0.75)다. 두 컨테이너 모두 CPU 한도에 닿지 않았다.

### 유휴 비용

| poll | 노드별 claim/s | Questions/s | Com_select/s | MySQL CPU | judge CPU (노드별) |
|---|---:|---:|---:|---:|---:|
| 100ms | 9.8 | 79.0 | 20.0 | 0.028~0.030 | 0.029~0.033 |
| 20ms | 47.3 | 379.1~379.6 | 95.1 | 0.065 | 0.056~0.058 |

빈 poll 하나가 SELECT 1개와 트랜잭션 제어문을 합쳐 Questions 약 4개다((379 − 79) / (94.7 − 19.6) ≈ 4.0).
20ms는 일이 전혀 없을 때 MySQL CPU를 코어의 약 0.035(한도 2.0의 1.8%) 더 쓴다. 노드 수에 비례해 늘어난다.

### 저부하에서의 지연

poll 대기가 줄어드는 효과는 처리량이 아니라 저부하 지연에서 보인다. stage-1(50 RPS, 네 실행 모두 steady)의
p95 `L_total`은 MIF 16 448.705 → 394.819ms, MIF 64 446.518 → 400.878ms다. run 전체의
`measurement-steady` cohort p50 `L_result`는 218.8/234.3ms(100ms) 대 173.1/173.4ms(20ms)다. 다만 이
cohort는 run마다 포함된 stage가 다르므로(MIF 16 poll 100ms는 stage-1만, MIF 64 poll 100ms는 stage-2만,
20ms 두 실행은 stage-1·2) 방향만 읽는다.

### 포화 구간의 기제: claim 시간과 worker 안의 비-채점 시간

stage 경계 스냅샷에서 구한 claim 한 번의 평균 시간(`contest_judge_claim_latency`):

| run | stage-1 | stage-2 | stage-3 | stage-4 | stage-5 |
|---|---:|---:|---:|---:|---:|
| mif16-poll100 | 7.9ms | 11.1 | 21.5 | 26.5 | 29.9 |
| mif16-poll20 | 2.8 | 5.9 | 21.1 | 27.3 | 30.1 |
| mif64-poll100 | 8.0 | 11.2 | 16.6 | 30.5 | 36.8 |
| mif64-poll20 | 3.3 | 6.0 | 9.5 | 27.6 | 32.9 |

- 포화 구간에서 claim 한 번이 30ms 안팎이다. fixedDelay라 poll 주기는 `간격 + claim`이므로 100ms는 약
  130ms, 20ms는 약 50ms가 된다. 노드별 claim/s가 20ms에서 약 20회로 무부하 때(47회)보다 적은 이유다.
- MIF 64는 두 poll 간격 모두 `running`이 32/32에 붙어 있고 로컬 큐(`queued`)가 79~87이다. worker는
  한 번도 굶지 않았다. 그런데도 처리량은 약 149/s이므로 worker 한 칸이 일 하나에 쓰는 시간은
  32 / 149 ≈ 215ms이고, JVM이 잰 채점 시간(`timed`, 137~158ms)보다 약 60ms 길다. 이 60ms는 `judge()`
  안에서 채점 뒤에 일어나는 결과 저장·outbox 완료 처리로 보이며, poll 간격과 무관한 구간이다.
  poll 20ms가 MIF 64에서 아무것도 바꾸지 못한 것은 이 때문이다.
- MIF 16은 `queued`가 0이고 `running`이 포화 stage에서 25.1~26.9/32(100ms) 대 25.3~27.3/32(20ms)다.
  worker가 여전히 7개쯤 논다. poll 대기는 줄었지만(최대 100ms → 20ms), claim 자체가 30ms 걸리고
  `reserved`는 `judge()`가 완전히 끝난 뒤에야 줄어 다음 claim의 용량이 된다. 그래서 공백이 poll 간격만큼
  줄지 않는다.

## 해석

1. **MIF 16의 `running` 평균이 얼마나 올랐는가.** 같은 하네스의 poll 100ms 대비 포화 stage에서
   stage-3 26.852 → 27.333, stage-4 25.111 → 25.556, stage-5 23.556 → 25.259다(+0.4~+1.7 / 32).
   기존 문서의 23.3/32(09-20)와 비교하면 25.3이지만, 그 차이의 대부분은 하네스 차이다(같은 하네스
   poll 100ms가 이미 23.6~26.9). poll 간격이 worker 유휴의 **일부** 원인이라는 방향은 맞지만, 크기는
   32개 중 1~2개 수준이다. 가설의 강한 형태("유휴는 poll 간격 때문")는 지지되지 않는다.
2. **MIF 64는 얼마나 올랐는가.** 오르지 않았다. 포화값 149.296 → 149.074(0.999배), `running`은 두 조건 모두
   32/32다. 로컬 큐가 이미 poll 공백을 가리고 있었다는 예상과 맞는다.
3. **오른 처리량 대비 DB 비용.** 포화 stage 기준으로 MIF 16은 처리량 +1%에 대해 MySQL CPU 0.684 → 0.988
   코어(+44%, stage-4 +42%), Questions/s +9%, row-lock waits/s 3.8 → 20.6(5.4배)이다. MIF 64는 처리량
   ±0%에 MySQL CPU 0.818 → 1.094(+34%, stage-4 +32%), Questions/s +3%, row-lock waits/s 22.7 → 55.6(2.4배)다.
   결과 하나당 MySQL CPU는 MIF 16 5.8 → 8.2 ms·core, MIF 64 5.5 → 7.3 ms·core로 늘었다. 무부하에서는
   claim이 초당 19.6 → 94.6회, Questions가 79 → 379/s, MySQL CPU가 0.030 → 0.065 코어로 약 두 배다.
   저부하(50 RPS)에서는 Questions +26~29%, MySQL CPU +11~13%다.
4. **판정: "poll을 줄여도 격차가 남는다."** 20ms에서도 포화값은 이론의 57.6%(MIF 16), 68.7%(MIF 64)로
   100ms와 같은 자리다. 오히려 DB는 더 쓴다. 남는 격차의 기제는 claim 대기가 아니라 worker 스레드 안의
   비-채점 시간(약 60ms/건)과, MIF 16에서는 `reserved` 반환 시점과 30ms claim 왕복이다. 단, 각 조건 1회
   실행이고 MIF 16의 1%는 run 간 잡음(같은 poll 100ms에서 하네스 차이만으로 6%) 안에 있다. "20ms가
   처리량을 바꾸지 않는다"까지만 말할 수 있고 "1% 올린다"고 일반화하지 않는다.
5. **피크 부하 실험의 MySQL poll 간격 권고: 100ms를 유지하고, 필요하면 20ms를 민감도 조건으로 1회 둔다.**
   근거: (a) 포화 처리량이 20ms와 100ms에서 구분되지 않는다(0.999~1.010). (b) 20ms는 같은 처리량에서
   MySQL CPU를 30~45% 더 쓰고 row-lock wait을 2.4~5.4배 늘린다. MySQL과 RabbitMQ를 비교하는 실험에서
   MySQL 쪽 DB 부하를 불필요하게 키우면 오히려 MySQL에 불리한 조건이 된다. (c) "튜닝 부족" 반론에는 이 문서가
   답한다: poll을 5배 줄여도 용량은 그대로이고 병목은 worker 안의 완료 처리다. MySQL 쪽 용량을 더 끌어올릴
   손잡이는 poll이 아니라 MIF(16 → 64에서 1.2배)와 완료 경로다. 피크 실험은 MIF 64를 쓰는 편이 이 결과와
   일관된다. 저부하 지연을 비교 항목으로 넣는다면 20ms가 p95를 약 45~55ms 줄인다는 점은 함께 적는다.

## 알려진 한계

- **각 조건 1회.** 같은 poll 100ms에서 하네스 차이만으로 MIF 16 포화값이 6% 움직였다. 이번 차이(1%, 0%)는
  그보다 작으므로 "차이 없음"으로만 읽는다.
- **knee 판정 잡음.** MIF 64 poll 100ms의 50 RPS overloaded 판정과 MIF 16 poll 100ms의 100 RPS steady
  판정은 임계값 근처다(위 주석). 20ms 두 실행은 양 끝이 견고하지만 그 역시 1회 표본이다.
- **Questions/s에는 측정 도구가 섞인다.** 부하 stage에는 1초 sampler의 문장(세션 1개, 초당 1문장)과 경계
  스냅샷이 들어 있다. 네 실행이 같으므로 비교에는 영향이 없다. idle 구간에는 sampler가 없다.
- **sampler의 COUNT(*) 비용.** sampler는 매초 outbox·submission 테이블을 COUNT한다. 행이 쌓일수록 비싸지므로
  stage-5의 MySQL CPU 일부는 측정 쿼리다. 네 실행이 같은 행 수를 쌓으므로 조건 간 차이에는 들어가지 않지만,
  절대 CPU 값을 MySQL 한도 여유로 해석하지 않는다.
- **CPU 시계.** helper의 시각은 Docker VM 시계(uptime을 epoch에 맞춘 값)다. Docker Desktop이 호스트와
  동기화하므로 창 경계 오차는 수십 ms 수준으로 보지만 측정하지 않았다. 30초 창에서 1초 판독 1개 분량 이하다.
- **"약 60ms의 비-채점 시간"은 추론이다.** MIF 64의 `running` 포화와 처리량으로 계산한 값이며, 그 안의 구성
  (결과 저장, outbox 완료, 커넥션 대기)은 이 실험에서 나누어 재지 않았다. MySQL CPU·judge CPU 모두 한도에
  닿지 않았으므로 CPU 포화는 아니다.
- **같은 물리 머신.** 부하 생성기와 서버가 CPU를 나눠 쓴다. 절대 RPS는 용량 수치가 아니다.
- **`stale-token completion`은 0이다.** 중복 채점 수로 쓰지 않는다는 규칙과 무관하게, 이번 네 실행에는 재claim
  자체가 없다.

## 이후 실험에서 재사용할 것

- **컨테이너 CPU:** `Run-TradeoffExperiment.ps1`은 기본으로 모든 compose 컨테이너의 CPU를 수집한다
  (`-SkipContainerCpu`로 끔). 다른 스크립트에서는:

  ```powershell
  . .\scripts\mysql-judge-tradeoff\ContainerCpuSampler.ps1
  $s = Start-ContainerCpuSampler -OutputDirectory $dir -Containers @('oj-loadtest-mysql','oj-loadtest-rabbitmq') -SamplerName 'my-cpu-sampler'
  # ... 부하 ...
  Stop-ContainerCpuSampler -Sampler $s        # container-cpu-1s.csv, container-cpu-meta.json
  $rows = Import-Csv (Join-Path $dir 'container-cpu-1s.csv')
  Get-ContainerCpuWindow -Rows $rows -StartMillis $a -EndMillis $b   # 컨테이너별 평균/최대 코어, throttling
  ```

  실행 중 Docker API를 부르지 않는다. 컨테이너가 재시작되면 해당 행에 `counterReset=1`이 찍힌다.
- **분석기:** `summary.md`의 "Database cost per stage" 표와 `summary.json`의 `stages[].mysql.containerCpu`,
  `idleBaseline`.
- **비교기:** `Compare-TradeoffRuns.ps1`의 "Poll interval comparison"과 "Database cost per stage, all runs".
  max-in-flight 비율은 첫 run과 같은 poll 간격의 run만으로 계산한다.
- **하네스 옵션:** `-ResetMySqlVolume`(깨끗한 스키마), `-IdleBaselineSeconds N`(유휴 비용).
- **MySQL 세션:** 하네스의 SQL은 이제 실행당 mysql 세션 하나로 간다. 새 측정 코드도 `Invoke-SqlRows`를 쓰면
  `docker compose exec`를 반복 호출하지 않는다.
