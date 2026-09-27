# 채점 작업 분배 실험 수치 요약

이 문서는 포트폴리오 2·3페이지를 작성할 때 사용할 수 있도록, MySQL claim/lease와 RabbitMQ 기반 채점 분배 실험의 핵심 수치를 한곳에 모은 근거표다.

- 실험일: 2026-09-20
- 원본 실험 워크트리: `C:\Users\Home\spring\web\web-mysql-judge-tradeoff`
- 공통 지연 프로파일: 95%는 50ms, 5%는 2,000ms
- 공통 채점기: 2개 노드, 노드당 worker 16개
- 별도 표기가 없으면 각 조건은 1회 실행이다. 작은 차이를 일반적인 성능 차이로 확대하지 않는다.
- `accepted = unique submissions = results = scoreboard applied`가 성립한 실행만 주요 근거로 사용한다.

## 포트폴리오에 우선 사용할 네 가지 결과

| 전달할 내용 | 조건 | 핵심 수치 | 슬라이드용 표현 |
|---|---|---|---|
| 너무 짧은 lease는 정상 작업도 다시 실행한다 | MySQL, MIF 16/node, 110 RPS, timeout 2.5s vs 1s | 실제 중복 채점 `0 → 354건`; 중복 실행률 `4.919%`; 계산상 추가 worker 비용 `+67.670%` | `전체 요청의 약 5%만 중복됐지만, 긴 작업이어서 worker 비용은 약 68% 증가했다.` |
| 중복 비용은 포화 근처에서 다른 요청까지 밀어낸다 | 같은 실험 | result RPS `106.867 → 81.900` (`-23.36%`); fast `L_total p95` `715ms → 16,358ms`; drain `1.930s → 18.145s` | `정합성은 유지됐지만 중복된 긴 작업이 worker를 점유해 정상 50ms 작업까지 대기시켰다.` |
| 장애 지연은 timeout만이 아니라 남은 처리 용량에 좌우됐다 | MySQL, MIF 64/node, judge 1대 SIGKILL, 15초 후 재시작 | 50 RPS에서 fault-down fast p95: `4s 877ms`, `10s 438ms`; 100 RPS에서는 `4s 10.474s`, `10s 10.056s` | `한 노드가 감당할 수 있는 부하에서는 영향이 작았지만, 유입량이 남은 용량을 넘자 timeout 차이보다 backlog가 지연을 지배했다.` |
| RabbitMQ의 이점은 압도적 처리량보다 ACK 재전달과 DB 경합 축소였다 | 1,000 starts/s, 10초 open burst, Rabbit prefetch 4 vs MySQL MIF 64 | result RPS `125.3 vs 96.0`; `L_total p95 17.997s vs 20.271s`; DB row-lock waits/s `0.8 vs 13.8`; 최종 무결성 양쪽 통과 | `RabbitMQ가 더 빠른 방향은 관측됐지만, 공통 진입부 503과 단일 실행 때문에 결정적인 처리량 우위로 해석하지 않았다.` |

## 1. MySQL max-in-flight 용량 탐색

### 측정 결과

| max-in-flight / node | 관측한 포화 중앙값 | 비고 |
|---:|---:|---|
| 16 | `116.296 RPS` | 정상상태 capacity ladder |
| 64 | `147.963 RPS` | MIF 16 대비 `+27.2%` |

### 해석

- MIF를 16에서 64로 네 배 늘려도 처리량은 `1.272배`만 증가했다.
- MIF는 worker 수를 늘리는 값이 아니다. 실행 worker는 노드당 16개로 같고, 추가분은 미리 claim해 둔 작업이다.
- 따라서 MIF 증가는 DB 왕복과 worker idle을 줄일 수 있지만, 너무 크면 장애 순간 한 노드가 소유한 미완료 작업의 상한과 로컬 대기를 키운다.
- 별도의 MIF 256, 200 RPS 실행에서도 처리 한계가 200 RPS까지 올라가지 않았다. result RPS는 `152.533`, `L_total p50/p95`는 `8.856s / 16.019s`, drain은 `18.758s`였고 backlog가 지속 증가했다.

### 사용 가능한 문장

> max-in-flight를 16에서 64로 늘려 관측 처리량을 116.3 RPS에서 148.0 RPS까지 높였지만, worker 수가 고정된 상태에서 MIF만 계속 키우는 방식은 200 RPS를 처리하지 못했다.

## 2. 정상상태 timeout 경계 탐색

### MIF 64, 100 RPS

| timeout | accepted | result RPS | durable duplicate claims | 실제 중복 채점 | `L_total p95` | drain |
|---:|---:|---:|---:|---:|---:|---:|
| 2s | 6,543 | 97.983 | 373 | 163 | 967.661ms | 4.175s |
| 4s | 6,539 | 98.067 | 0 | 0 | 594.680ms | 1.587s |
| 10s | 6,549 | 98.233 | 0 | 0 | 986.546ms | 1.643s |

### 해석

- MIF 64에서는 2초 timeout이 로컬 대기와 2초짜리 정상 작업을 stale로 오판해 중복 실행을 만들었다.
- 4초와 10초에서는 중복 claim과 중복 채점이 모두 0이었다.
- 4초와 10초의 처리량은 `98.067 vs 98.233 RPS`로 사실상 같았다.
- p95 차이는 timeout 효과로 설명할 수 없으며, 조건별 1회 실행에서 생긴 변동으로 취급한다.

### 사용 가능한 문장

> MIF 64에서는 2초 timeout이 정상 장기 작업을 재실행했지만, 4초와 10초는 모두 중복 0건과 약 98 RPS를 기록했다. 정상상태 비용만 보면 4초보다 timeout을 더 늘릴 근거는 없었다.

## 3. 포화 근처에서 짧은 timeout이 만든 중복 비용

### 조건

- MySQL direct judge
- MIF 16/node, worker 16/node
- 110 RPS, 60초 측정창
- timeout 2.5초와 1초를 각각 1회 실행
- warm-up은 별도 contest에서 수행한 뒤 완전히 drain

### 전체 결과

| 지표 | timeout 2.5s | timeout 1s | 변화 |
|---|---:|---:|---:|
| accepted | 7,195 | 7,197 | 사실상 동일 |
| result RPS | 106.867 | 81.900 | `-24.967 RPS`, `-23.36%` |
| backlog 시작 → 종료 | 28 → 45 | 244 → 1,723 | 1초 조건에서 지속 증가 |
| drain | 1.930s | 18.145s | `9.4배` |
| 전체 `L_total p50/p95/p99` | 407 / 861 / 2,482ms | 11,696 / 16,399 / 17,684ms | queueing 지배 |
| 최종 유실/불일치 | 0 / 0 | 0 / 0 | 정합성 유지 |

### 실제 중복 회계

| 지표 | timeout 2.5s | timeout 1s |
|---|---:|---:|
| durable duplicate claims | 0 | 708 |
| stale-token completions | 0 | 706 |
| stored-result republishes | 0 | 352 |
| 실제 중복 채점 실행 | 0 | 354 |
| 실제 judge invocations | 7,195 | 7,551 |

실제 중복 채점 354건은 다음 두 계산이 일치한다.

```text
stale completion - stored-result republish - failure
= 706 - 352 - 0
= 354

judge invocation - result - failure
= 7,551 - 7,197 - 0
= 354
```

`stale-token completion 706건`을 중복 채점 706건이라고 표현하면 안 된다.

### 왜 4.9%의 중복이 큰 비용이 됐는가

| 항목 | 값 |
|---|---:|
| slow 제출 비율 | `352 / 7,197 = 4.891%` |
| slow 작업의 기본 profile worker 비용 비율 | `67.288%` |
| 추가 실행 비율 | `354 / 7,197 = 4.919%` |
| 계산상 추가 worker 비용 | `708,000ms`, 기본 profile 비용의 `67.670%` |

- 중복은 모두 2초짜리 slow 작업에서 발생했고 fast 작업의 중복은 0건이었다.
- 그런데 fast `L_total p50/p95/p99`도 `399/715/807ms`에서 `11,656/16,358/16,823ms`로 증가했다.
- scoreboard 구간은 거의 변하지 않았기 때문에 지연 증가는 결과 처리 전의 worker queueing으로 해석하는 것이 가장 직접적이다.

### 사용 가능한 문장

> 1초 lease는 전체 제출의 약 5%를 중복 실행했지만, 대상이 모두 2초짜리 작업이어서 계산상 worker 비용은 67.7% 증가했다. 포화 근처에서는 result 처리량이 23.4% 감소하고, 중복되지 않은 50ms 작업의 p95도 0.7초에서 16.4초로 증가했다.

## 4. MySQL SIGKILL 장애 복구: timeout보다 capacity headroom

### 공통 조건

- MIF 64/node, worker 16/node
- judge-1 SIGKILL
- down window 약 15초 후 동일 설정으로 재시작
- timeout 4초와 10초 비교
- 각 조건 1회 실행

### 부하 100 RPS

| 지표 | timeout 4s | timeout 10s |
|---|---:|---:|
| accepted = result = scoreboard | 30,464 | 30,465 |
| 유실/최종 불일치 | 0 / 0 | 0 / 0 |
| 첫 stale reclaim | fault 후 6.238s | fault 후 9.702s |
| reclaimed submission 수 | 20 | 13 |
| reclaimed cohort `L_total p95` | 6.765s, n=20 | 13.009s, n=13 |
| fault-down fast `L_total p50/p95/p99` | 6.126 / 10.474 / 11.247s | 6.829 / 10.056 / 10.974s |
| combined backlog peak | 1,087 | 1,056 |
| throughput recovery, node-ready 이후 | 1.291s | 0.514s |

### 부하 50 RPS

| 지표 | timeout 4s | timeout 10s |
|---|---:|---:|
| accepted = result = scoreboard | 15,227 | 15,231 |
| 유실/최종 불일치 | 0 / 0 | 0 / 0 |
| reclaimed submission 수 | 4 | 4 |
| reclaimed cohort `L_total p95` | 6.431s, n=4 | 10.361s, n=4 |
| fault-down fast `L_total p50/p95/p99` | 0.356 / 0.877 / 1.205s | 0.304 / 0.438 / 0.853s |
| combined backlog peak | 63 | 56 |
| throughput recovery, node-ready 이후 | 0.439s | 0.434s |

### 해석

- timeout을 10초에서 4초로 줄이면 **재claim된 소수의 제출**은 더 빨리 완료됐다.
- 그러나 장애 동안 들어온 전체 fast 제출의 p95는 같은 부하에서 4초와 10초가 크게 다르지 않았다.
- 50 RPS에서는 fast p95가 모두 1초 미만이었다. 살아남은 한 노드가 유입량을 감당할 여지가 있었기 때문이다.
- 100 RPS에서는 fast p95가 두 조건 모두 약 10초였다. 살아남은 한 노드의 처리 여력을 넘어 backlog가 쌓인 영향이 timeout 차이보다 컸다.
- reclaimed cohort는 n=4~20으로 매우 작다. p95가 사실상 최대값에 가까우므로 일반적인 percentile로 확대하면 안 된다.

### 사용 가능한 문장

> 짧은 timeout은 장애 순간 선점돼 있던 소수 작업의 회수는 앞당겼다. 하지만 전체 사용자 지연은 남은 한 노드가 유입량을 감당할 수 있는지에 더 크게 좌우됐다. 50 RPS에서는 fast p95가 1초 미만이었지만, 100 RPS에서는 timeout과 무관하게 약 10초까지 증가했다.

## 5. RabbitMQ 정상 부하 관측

### prefetch 1, 2개 judge node

| offered rate | accepted/result/scoreboard | measurement `L_total p50/p95` | drain | backlog 지속 증가 판정 |
|---:|---:|---:|---:|---|
| 150 RPS | 9,788 / 9,788 / 9,788 | 371ms / 924ms | 1.557s | false |
| 200 RPS | 13,050 / 13,050 / 13,050 | 5.329s / 10.495s | 13.574s | false |

주의할 점:

- Rabbit worker의 `running/reserved` gauge를 수집하지 못해 analyzer의 정식 steady-state 판정은 두 실행 모두 `executorWithinConfiguredCaps=false`로 탈락했다.
- 따라서 `최대 처리량이 200 RPS다`라고 말할 수 없다.
- 150 RPS에서는 낮은 지연과 짧은 drain을 관측했고, 200 RPS에서는 queueing 지연이 크게 나타났다는 수준으로만 사용한다.

## 6. 1,000 starts/s, 10초 burst: RabbitMQ와 MySQL

### 조건

- 동일한 open-arrival 제출 시작률: Rabbit `1,000.6 starts/s`, MySQL `1,000.1 starts/s`
- 10초 측정창
- RabbitMQ prefetch 4
- MySQL MIF 64/node, claim timeout 30초
- Rabbit → MySQL 순서로 각각 1회 실행

### 측정 결과

| 지표 | RabbitMQ | MySQL |
|---|---:|---:|
| offered submissions | 10,006 | 10,001 |
| 측정창 accepted | 3,650 | 3,144 |
| 측정창 503 | 4,105 | 3,879 |
| result RPS | 125.3 | 96.0 |
| load stop 시 backlog | 2,840 | 3,078 |
| drain | 25.348s | 29.335s |
| `L_total p50` | 10.463s | 11.367s |
| `L_total p95` | 17.997s | 20.271s |
| `L_total p99` | 19.821s | 21.081s |
| DB row-lock waits/s | 0.8 | 13.8 |
| 최종 무결성 | 통과 | 통과 |

### 해석

- 같은 시작률을 실제로 공급한 상태에서 RabbitMQ가 result RPS, drain, `L_total p95` 및 DB row-lock wait에서 더 좋은 방향을 보였다.
- 하지만 두 조건 모두 공통 진입부에서 대량의 503이 발생했다. 따라서 이것은 순수한 judge dispatch 최대 처리량 비교가 아니다.
- Rabbit broker queue sample이 유실되어 ready/unacked backlog를 직접 확인하지 못했다.
- 실행 순서가 고정된 단일 표본이라 순서 효과와 변동성을 분리하지 못했다.
- MySQL 쪽이 contest 전체 accepted는 더 많았지만, 이는 측정창 밖 ramp와 in-flight 완료를 포함하므로 위의 측정창 result RPS와 섞어 우열을 말하면 안 된다.

### 사용 가능한 문장

> 1,000 starts/s burst에서는 RabbitMQ가 MySQL보다 높은 result RPS와 낮은 DB lock wait를 보였다. 다만 두 조건 모두 공통 진입부에서 503이 대량 발생했고 Rabbit queue 계측도 누락돼, 이를 RabbitMQ의 결정적인 최대 처리량 우위로 해석하지 않았다.

## 7. 슬라이드에 쓰지 말아야 할 표현

| 피할 표현 | 이유 | 대신 사용할 표현 |
|---|---|---|
| `stale completion 706건 = 중복 채점 706건` | republish와 fenced completion이 섞여 있다 | `실제 중복 채점 354건` |
| `MIF 64의 최대 처리량은 147.963 RPS` | ladder에서 관측한 포화 중앙값이며 환경 종속이다 | `이 실험 환경에서 관측한 포화 중앙값 148.0 RPS` |
| `RabbitMQ는 200 RPS를 안정 처리했다` | worker gauge 누락으로 정식 steady 판정이 불가능하다 | `200 RPS에서 최종 처리했지만 p95 10.5초의 queueing이 관측됐다` |
| `RabbitMQ가 MySQL보다 압도적으로 빠르다` | burst 단일 실행이고 공통 503 및 broker 계측 누락이 있다 | `RabbitMQ가 더 좋은 방향을 보였지만 결정적 우위로 보지 않았다` |
| `4초 timeout이 10초보다 전체 복구를 크게 개선했다` | 전체 fault-down 지연은 비슷하고 차이는 주로 소수 reclaimed cohort에 있다 | `4초는 선점된 소수 작업의 회수를 앞당겼다` |
| `장애 복구시간은 timeout으로 결정된다` | 50 RPS와 100 RPS 차이가 timeout 차이보다 컸다 | `전체 지연은 남은 처리 용량에 더 크게 좌우됐다` |

## 8. 원본 근거 파일

- `docs/MYSQL_JUDGE_MAX_IN_FLIGHT_CAPACITY.md`
- `docs/MYSQL_JUDGE_NORMAL_TIMEOUT_DUPLICATION.md`
- `docs/MYSQL_JUDGE_DUPLICATE_SATURATION_COST.md`
- `docs/MYSQL_JUDGE_FAULT_RECOVERY_COMPARISON.md`
- `results/mysql-judge-tradeoff/fault-recovery-comparison.md`
- `results/mysql-judge-tradeoff/fault-recovery-halfload-comparison.md`
- `results/mysql-judge-tradeoff/rabbit-capacity-rps150-prefetch1-20260920/summary.md`
- `results/mysql-judge-tradeoff/rabbit-capacity-rps200-prefetch1-20260920/summary.md`
- `results/mysql-judge-tradeoff/mysql-capacity-mif256-rps200-timeout40s-20260920/summary.md`
- `results/mysql-judge-tradeoff/burst-rps1000-10s-comparison-open1.json`
- `results/mysql-judge-tradeoff/burst-rps1000-10s-comparison-open1.csv`

위 상대 경로의 기준은 원본 실험 워크트리 `C:\Users\Home\spring\web\web-mysql-judge-tradeoff`다.
