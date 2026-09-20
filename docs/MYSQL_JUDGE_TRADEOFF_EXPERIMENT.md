# MySQL claim 기반 judge 분배 실험

## 목적과 가설

이 실험은 결과/scoreboard 경로를 고정하고 judge 작업의 분배만 RabbitMQ work queue와
`contest_judge_outbox` 직접 claim 사이에서 바꾼다. 운영 기본값은 `rabbit`이다. MySQL 모드의
짧은 lease는 장애 복구를 앞당기지만 정상 장기 실행이나 로컬 대기를 stale로 오인하여 중복
채점 비용을 늘릴 수 있다. 큰 reserved window는 claim 호출을 줄이는 대신 head-of-line 대기와
노드 사망 시 stranded 작업 수를 늘릴 수 있다.

RabbitMQ 비교축은 raw prefetch가 아니라 `worker-count × prefetch`, 즉 노드당
reserved-but-unfinished 상한이다. MySQL의 대응 축은 `max-in-flight`다.

fault 없이 `max-in-flight` 자체가 정상상태 처리 용량을 어떻게 바꾸는지는 별도 문서
`docs/MYSQL_JUDGE_MAX_IN_FLIGHT_CAPACITY.md`에서 다룬다. 그 문서가 남긴 "timeout을 짧게 줄이면
정상상태에서 실제로 무엇이 일어나는가"는 `docs/MYSQL_JUDGE_NORMAL_TIMEOUT_DUPLICATION.md`에서
`max-in-flight` 16과 64의 timeout별 중복 claim·중복 채점으로 측정한다.

## 지연과 cohort 정의

- `L_result`: `contest_submission.submitted_time`부터
  `contest_submission_result.result_saved_at`까지.
- `L_scoreboard`: `result_saved_at`부터 `scoreboard_applied_at`까지.
- `L_total`: `submitted_time`부터 `scoreboard_applied_at`까지.
- `all`: 해당 contest의 모든 제출.
- `pre-fault-normal`: fault 5초 전보다 먼저 도착한 제출. fault가 없으면 전 구간.
- `fault-window`: fault 5초 전부터 노드 재시작 5초 후까지 도착한 제출.
- `killed-node-claimed`: kill 직전 killed node가 claim한 제출. 현재 schema에 claim owner가 없으면
  모든 active claim의 보수적 snapshot이며 summary에 `unavailable`로 명시된다.
- `post-fault-arrivals`: fault timestamp 이후 도착한 제출.

각 cohort에 대해 세 지연의 p50/p95/p99/max를 별도로 계산한다. 장애 사용자가 1% 미만이면
전체 p99가 영향을 숨길 수 있으므로 `all`만으로 장애를 판정하지 않는다.

## 파라미터와 결과

`Run-TradeoffExperiment.ps1`은 dispatch mode, target RPS, ramp/hold duration, 노드당 worker,
claim batch, max in-flight, claim timeout, poll interval, Rabbit prefetch, deterministic seed,
fault 시각, killed node, down duration을 받는다. loadtest judge는 base 50 ms, 5% 확률의 2 s
작업을 안정적인 생성 코드와 seed로 결정한다. 일반 loadtest 외 실행은 기본적으로 submission ID를
사용하므로 기존 동작은 유지된다. Gatling의 jitter, problem 선택, 코드와 사용자 prefix도 같은 seed로
고정되어 Rabbit/MySQL 실행이 같은 논리 workload를 사용한다.

결과는 Git에서 무시되는 `results/mysql-judge-tradeoff/<run-id>/`에 기록된다.

- `parameters.json`, `events.json`, `compose-config.yaml`: commit, 모든 파라미터와 시각.
- `latency.csv`: 제출별 세 지연, attempts, cohort.
- `metrics/*.prom`, `metrics/*-mysql-status.tsv`: JVM counter/gauge와 MySQL connection/lock snapshot.
- `killed-node-claims.csv`, `claim-attempts.tsv`, `stale-reclaims.csv`, `backlog.csv`: claim/recovery 증거.
- `capacity.csv`: 실행 중 노드별 running/local-waiting/reserved 1초 시계열.
- `db-verification.json`: completed HTTP/accepted/unique/result/scoreboard 수, 유실/불일치, 비용 지표.
- `summary.json`, `summary.md`: 기계/사람이 읽는 cohort 분포와 recovery 결과.

측정할 수 없는 값은 0으로 만들지 않고 `unavailable` 배열에 이유를 쓴다. 기본 MySQL
컨테이너는 CPU exporter를 제공하지 않으므로 connection과 InnoDB lock counter만 저장한다.
Gatling `maxDuration` 종료 시 진행 중 요청은 서버에 저장된 뒤 client log에 완료로 남지 않을 수
있으므로 총 HTTP 시도 수는 unavailable이며, `completedHttpRequests`와 DB accepted를 분리해 기록한다.
첫 stale 회수 시각과 backlog는 장애 후 약 1초 간격으로 관찰하며 probe 실행 시간만큼 추가 오차가
생길 수 있다. `faultScheduledAt`, 실제 `faultInjectedAt`, `faultTimingErrorSeconds`를 함께 저장한다.
down duration은 `faultInjectedAt`부터 `restartRequestedAt`까지이며 Docker start 소요와 애플리케이션
readiness는 각각 `nodeRestartedAt`, `nodeReadyAt`으로 분리해 기록한다.
현재 schema에 claim owner가 없으면 `killed-node-claimed` 분포는 비어 있는 값이 아니라 명시적인
`available=false`로 출력하고, kill 시점의 전체 active claim 수만 upper bound로 보존한다.
Rabbit의 노드별 실제 running/local-waiting/reserved gauge는 현재 노출되지 않으므로 capacity CSV에서
빈 값이며 `worker-count × prefetch`는 관측값이 아니라 설정된 정규화 상한으로만 보고한다.
중복 judge 시간은 lease 특성상 평균 시간 배분을 쓰지 않고 deterministic profile의 50ms~2000ms
범위와 중복 invocation 추정 수를 곱한 하한/상한으로 보고한다.
SIGKILL 실행은 마지막 pre-fault scrape 뒤의 killed JVM counter 증가분을 잃을 수 있으므로 invocation과
completion counter를 하한으로 표시하며, 이 실행의 중복 invocation/time은 unavailable로 둔다. 반면
outbox `attempts` 기반 duplicate claim 수는 durable DB 값이다.

## 실행 전 확인과 pilot

PowerShell에서 저장소 루트 기준으로 실행한다. `-DryRun`은 컨테이너를 기동하지 않고 파라미터
검증과 `docker compose config`만 수행한다.

```powershell
.\scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1 -DispatchMode rabbit -DryRun

# Pilot 1: Rabbit, reserved/node = 16 × 1
.\scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1 -RunId pilot-rabbit-p1 -DispatchMode rabbit -TargetRps 20 -DurationSeconds 30 -WorkerCount 16 -RabbitPrefetch 1

# Pilot 2: MySQL, short lease + SIGKILL equivalent (`docker compose kill`)
.\scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1 -RunId pilot-mysql-short -DispatchMode mysql -TargetRps 20 -DurationSeconds 45 -WorkerCount 16 -MySqlMaxInFlight 16 -MySqlClaimBatchSize 16 -MySqlClaimTimeout 5s -FaultEnabled -FaultAtSeconds 20 -KilledNode judge-1 -DownDurationSeconds 10

# Pilot 3: 같은 workload/seed, 긴 lease
.\scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1 -RunId pilot-mysql-long -DispatchMode mysql -TargetRps 20 -DurationSeconds 45 -WorkerCount 16 -MySqlMaxInFlight 16 -MySqlClaimBatchSize 16 -MySqlClaimTimeout 30s -FaultEnabled -FaultAtSeconds 20 -KilledNode judge-1 -DownDurationSeconds 10

# Pilot 4: 큰 local waiting/reserved window
.\scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1 -RunId pilot-mysql-wide -DispatchMode mysql -TargetRps 80 -DurationSeconds 45 -WorkerCount 16 -MySqlMaxInFlight 1024 -MySqlClaimBatchSize 256 -MySqlClaimTimeout 30s
```

Pilot에서는 먼저 `integrity.passed`, metric 이름, cohort sample 수, backlog drain을 확인한다.
장시간 전체 matrix는 pilot이 통과하기 전 실행하지 않는다.

## 전체 matrix 명령

아래 예시는 순서를 한 번 뒤집어 반복하고 같은 seed, worker 수, RPS, fault 시각을 유지한다.

```powershell
# A. timeout 비교 (reserved = worker = 16)
foreach ($repeat in 1,2) {
  $timeouts = if ($repeat -eq 1) { '3s','10s','30s','60s' } else { '60s','30s','10s','3s' }
  foreach ($timeout in $timeouts) {
    .\scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1 -RunId "timeout-r$repeat-$timeout" -DispatchMode mysql -TargetRps 100 -DurationSeconds 180 -RampSeconds 30 -WorkerCount 16 -MySqlMaxInFlight 16 -MySqlClaimBatchSize 16 -MySqlClaimTimeout $timeout -LatencySeed 20260919 -FaultEnabled -FaultAtSeconds 90 -KilledNode judge-1 -DownDurationSeconds 20
  }
}

# B. reserved window; 비포화(100 RPS)와 용량 근처 조건(측정된 pilot RPS로 180을 교체)
foreach ($rps in 100,180) {
  foreach ($reserved in 16,64,1024) {
    .\scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1 -RunId "mysql-rps$rps-reserved$reserved" -DispatchMode mysql -TargetRps $rps -DurationSeconds 180 -RampSeconds 30 -WorkerCount 16 -MySqlMaxInFlight $reserved -MySqlClaimBatchSize $reserved -MySqlClaimTimeout 30s -LatencySeed 20260919
  }
  foreach ($prefetch in 1,4,64) {
    .\scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1 -RunId "rabbit-rps$rps-prefetch$prefetch" -DispatchMode rabbit -TargetRps $rps -DurationSeconds 180 -RampSeconds 30 -WorkerCount 16 -RabbitPrefetch $prefetch -LatencySeed 20260919
  }
}
```

컨테이너 CPU/memory, warm-up, seed, 장애 시각을 고정한다. 첫 순서와 역순을 모두 실행하여
JIT/cache warm-up 편향을 드러낸다.

## 결정 규칙

1. 유실, 미완료, 최종 결과 불일치가 있으면 해당 설정은 탈락한다.
2. 정상 cohort의 tail latency와 장애 cohort의 복구/최대 지연을 분리해 비교한다.
3. stale reclaim 시간과 backlog 정상화 시간뿐 아니라 attempts, judge invocation, duplicate judge
   time, claim 횟수/batch, 노드별 running/local-waiting/reserved, DB lock/connection 비용을 함께 본다.
   Rabbit outbox `attempts`는 publish claim, MySQL outbox `attempts`는 direct judge claim이므로 서로
   같은 실행 횟수로 해석하지 않고 실제 judge invocation을 공통 비용 축으로 사용한다.
4. 중복 채점은 결과 정합성 오류가 아니라 자원 비용이다. 비포화에서 지연이 늘지 않았어도
   비용이 없다고 일반화하지 않는다.
5. 차이가 반복 간 오차 범위이면 승자를 만들지 않고 운영 복잡성, 설정 민감도, 장애 범위만
   사실로 보고한다. 이 문서는 최종 timeout이나 claim 크기를 선택하지 않는다.

## 알려진 한계와 후속 실험

- deterministic latency는 실제 sandbox 실행 성능이 아닌 분배/복구용 synthetic sleep이다.
- static `claimed_at` lease는 running과 local-waiting을 구분하지 않는다. timeout을 넘는 정상 작업도
  재claim될 수 있다.
- owner가 영속화되지 않은 schema에서는 killed-node cohort를 정확히 귀속할 수 없다. 이를 위해
  운영 status/schema를 대규모 migration하는 것은 이번 실험 범위가 아니다.
- `PUBLISHING/PUBLISHED` status는 MySQL direct judge에서 각각 claimed/complete 의미로 재사용되어
  이름이 어색하다. 실험 해석에만 반영하고 운영 migration은 별도 과제로 둔다.
- 다음 실험에서는 heartbeat로 running claim만 연장하고 local-waiting은 claim하지 않는 설계를
  static lease와 비교한다.
- scoreboard stream offset 연속성은 고정된 외부 경로의 별도 과제이며 이 실험이 해결하지 않는다.
