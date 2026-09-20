# MySQL judge short-timeout 중복 비용의 포화 근처 실험

## 1. 목적과 반증 가능한 가설

이 실험은 `max-in-flight=16`의 이전 관측 포화 처리량 116.296 RPS에 가까운 110 RPS에서
claim timeout만 바꿨다. 미리 정한 가설은 다음과 같다.

1. 95% 50ms / 5% 2000ms 분포에서 slow 5%가 기본 judge worker 시간의 약 68%를 쓴다.
2. 1s timeout은 slow 작업을 선택적으로 stale reclaim하여 실제 중복 채점을 만든다.
3. 중복 실행 수는 전체 요청의 약 5%여도 추가 worker 시간은 기본 비용에 비해 크다.
4. 80 RPS에서 지연으로만 보였던 비용은 110 RPS에서 backlog, result throughput, drain,
   fast cohort 지연 중 하나 이상으로 나타난다.
5. 2500ms에서는 같은 비용이 없거나 현저히 작다.

가설과 다른 결과가 나오더라도 조건을 바꾸거나 run을 추가하지 않기로 하고, 두 조건을 각각
한 번만 실행했다.

## 2. 110 RPS 선택 근거

110 RPS는 이전 MIF16 포화 처리량 116.296 RPS의 94.6%다. 2500ms에는 작은 여유를 남기면서,
1s의 중복 worker 비용이 실제 처리 한계를 넘기는지를 볼 수 있는 입력이다. 두 조건이 모두
overload이거나 모두 여유롭더라도 사후에 RPS를 바꾸지 않는다는 규칙을 적용했다.

## 3. 실행 조건

| 항목 | 값 |
|---|---|
| dispatch | MySQL direct judge |
| judge node | 2 |
| worker / max-in-flight / claim batch | 노드당 16 / 16 / 16 |
| poll interval | 100ms |
| target | 110 RPS |
| latency | 95% 50ms / 5% 2000ms |
| seed / key | `20260920` / `code` |
| fault | 없음 |
| warm-up | 별도 contest, 30s hold 뒤 완전 quiescence |
| measurement | 5s ramp + 3s guard + 60s window |
| drain timeout | 600s |
| 실행 횟수 | 조건별 1회 |

실행 명령은 다음 한 개이며, 이 driver에는 두 조건만 고정돼 있다.

```powershell
.\scripts\mysql-judge-tradeoff\Invoke-DuplicateSaturationExperiment.ps1
```

내부 실행 순서는 다음과 같다.

1. `saturation-mif16-timeout2500ms-rps110-20260920`
2. `saturation-mif16-timeout1s-rps110-20260920`

실행 전 검증:

```powershell
.\gradlew.bat test :gatling:compileGatlingScala --rerun-tasks
.\scripts\mysql-judge-tradeoff\Invoke-DuplicateSaturationExperiment.ps1 -DryRun
```

전체 테스트는 289개 실행, 실패 0, 오류 0, skip 17이었고 Gatling Scala 컴파일과 두 조건의
dry-run이 통과했다.

## 4. Provenance

| 항목 | 값 |
|---|---|
| 실행 커밋 | `23ea864a1475508fdaeced3e7721b2222bb9fac0` |
| git tree dirty | 두 run 모두 `false` |
| harness tree dirty | 두 run 모두 `false` |
| harness SHA-256 | `81497AC625F4CBD671B56AB187B3280201D5D7ABD92C6A8637CE4E9AEF46EE7A` |
| 실행 시 analyzer SHA-256 | `382713BEEDE542239F9E44492507A2B3853B00AD7778BA53094EAFB7A9253E56` |

실행 뒤 분석 표시를 두 군데 수정했다. 원시 데이터와 실행 코드는 바꾸지 않았고 측정도 다시
실행하지 않았다.

- attempts=1만 있는 run에서 `rowsWithAttemptsAboveOne`을 `null`이 아니라 0으로 표시했다.
- 보존된 노드별 Prometheus snapshot에서 claim/invocation counter를 summary에 추가했다.

따라서 위 analyzer hash는 실제 run 직후 사용된 버전이며, 이 문서의 최종 파생 표는 같은 원시
파일을 수정 후 analyzer로 다시 계산한 값이다.

## 5. 유효성과 무결성

| 조건 | accepted / unique / result / scoreboard | lost / mismatch | warm-up quiesced | warm-up growth | cap | HTTP 429/500/503 | 유효성 |
|---|---|---|---|---:|---|---|---|
| 2500ms | 7195 / 7195 / 7195 / 7195 | 0 / 0 | yes | 0 | running 32/32, reserved 32/32 | 0 / 0 / 0 | valid, steady |
| 1s | 7197 / 7197 / 7197 / 7197 | 0 / 0 | yes | 0 | running 32/32, reserved 32/32 | 0 / 0 / 0 | valid, overloaded |

1s run이 steady 조건을 통과하지 못한 이유는 무결성 문제가 아니라
`backlogNotPersistentlyGrowing=false`다. overload 자체가 이 실험이 찾으려던 결과이므로 run은
비교 가능한 유효 측정이다. API backpressure는 두 조건 모두 관측되지 않았다.

## 6. 전체 비교

60초 측정창의 target request는 6,600건이다. `window HTTP`는 그 창에서 Gatling이 완료 상태를
기록한 요청이고, `completed HTTP`는 ramp와 guard를 포함한 measurement contest 전체 client
log 수다. DB accepted는 client log가 닫힌 뒤 저장된 in-flight 요청도 포함할 수 있다.

| 지표 | 2500ms | 1s | 1s - 2500ms |
|---|---:|---:|---:|
| target requests | 6,600 | 6,600 | 0 |
| window HTTP completed | 6,587 | 6,585 | -2 |
| measurement-contest completed HTTP | 7,178 | 7,186 | +8 |
| accepted | 7,195 | 7,197 | +2 |
| accepted RPS | 107.017 | 106.833 | -0.184 (-0.17%) |
| result RPS | 106.867 | 81.900 | **-24.967 (-23.36%)** |
| scoreboard-applied RPS | 106.733 | 82.050 | **-24.683** |
| backlog start / end / peak | 28 / 45 / 94 | 244 / 1,723 / 1,723 | end +1,678 |
| backlog growth, whole / second half | +0.2905 / -1.2758 rows/s | **+25.2950 / +26.9664 rows/s** | 지속 증가 |
| drain | 1.930s | **18.145s** | +16.215s, 9.4x |
| overall L_total p50 / p95 / p99 | 407 / 861 / 2,482ms | **11,696 / 16,399 / 17,684ms** | queueing 지배 |

2500ms의 끝점 증가는 17 row뿐이고 tick 표준편차 17.81, 최대 한 tick 변동 42 row보다 작다.
최소제곱 기울기는 +0.0176 rows/s이며 임계값을 절반/두 배로 바꾸거나 첫/끝 tick을 빼도 steady다.
반면 1s는 1,479 row 순증가, 최소제곱 +25.7542 rows/s, 최대 한 tick 변동 88 row다. 모든 강건성
변형에서 overload로 남았다.

## 7. Fast/slow cohort end-to-end 지연

아래 sample은 ramp와 3초 guard를 제외한 동일한 60초 창이다.

| timeout | class | n | L_result p50 / p95 / p99 / max ms | L_scoreboard p50 / p95 / p99 / max ms | L_total p50 / p95 / p99 / max ms |
|---|---|---:|---|---|---|
| 2500ms | fast | 6,268 | 324 / 627 / 712 / 847 | 71 / 127 / 176 / 223 | 399 / 715 / 807 / 920 |
| 1s | fast | 6,266 | **11,548 / 16,286 / 16,751 / 17,172** | 77 / 128 / 156 / 222 | **11,656 / 16,358 / 16,823 / 17,260** |
| 2500ms | slow | 319 | 2,267 / 2,562 / 2,665 / 2,751 | 80 / 133 / 173 / 270 | 2,353 / 2,641 / 2,747 / 2,835 |
| 1s | slow | 319 | **12,635 / 18,213 / 18,720 / 19,089** | 69 / 107 / 128 / 144 | **12,715 / 18,281 / 18,778 / 19,156** |

1s에서 fast L_total은 p50 29.2x, p95 22.9x, p99 20.8x다. scoreboard 구간은 거의 평평하고
증가는 L_result에 집중됐다. 따라서 긴 작업의 중복이 관련 없는 50ms 작업의 judge 대기를 늘렸다는
가설을 강하게 지지한다. 다만 1s 창은 overload 상태이므로 이 percentile은 안정 상태 service
latency가 아니라 실제로 누적된 queueing latency다.

## 8. Latency class별 invocation과 worker 시간

### 8.1 Measurement contest 전체: 정확한 중복 회계 범위

이 표는 warm-up quiescence 뒤 baseline부터 drain 뒤 end snapshot까지다. 이 범위에서는 모든
measurement contest submission과 그 invocation이 닫히므로 class별 `invocations - unique`를
중복 실행 수로 사용할 수 있다.

| timeout | class | unique | invocations | duplicate executions | unique expected ms (계산) | actual invocation ms (실측 timer) | duplicate ms (profile 계산) |
|---|---|---:|---:|---:|---:|---:|---:|
| 2500ms | fast | 6,843 | 6,843 | 0 | 342,150 | 342,794 | 0 |
| 2500ms | slow | 352 | 352 | 0 | 704,000 | 704,036 | 0 |
| 2500ms | total | 7,195 | 7,195 | 0 | 1,046,150 | 1,046,830 | 0 |
| 1s | fast | 6,845 | 6,845 | 0 | 342,250 | 342,902 | 0 |
| 1s | slow | 352 | **706** | **354** | 704,000 | **1,412,062** | **708,000** |
| 1s | total | 7,197 | **7,551** | **354** | 1,046,250 | **1,754,964** | **708,000** |

1s의 slow submission 비율은 352 / 7,197 = **4.891%**다. 그런데 중복이 없을 때의 profile 비용
중 slow가 차지하는 비율은 704,000 / 1,046,250 = **67.288%**다. 가설의 “약 68%”를 지지한다.

추가 실행은 354 / 7,197 = **4.919%**이지만, profile로 계산한 추가 worker 비용은
708,000 / 1,046,250 = **67.670%**다. 실제 timer 총량과 unique profile 비용의 차이는
708,714ms로 같은 크기다. 다만 개별 invocation의 first/duplicate tag는 없으므로 708,000ms는
`354 × 2000ms` 계산값이며 “실측 duplicate duration”으로 부르지 않는다.

### 8.2 정확한 60초 측정창 worker-seconds

guard 이후 별도 Prometheus snapshot과 hold-end snapshot 사이에서 측정했다. 두 노드의 가용량은
`2 × 16 × 60 = 1,920 worker-seconds`다.

| timeout | fast invocation seconds | slow invocation seconds | total judge-seconds | 가용 worker-seconds 대비 |
|---|---:|---:|---:|---:|
| 2500ms | 313.936 | 634.033 | 947.969 | 49.37% |
| 1s | 238.300 | 1,040.046 | 1,278.346 | **66.58%** |

1s에서는 result 처리량이 떨어졌기 때문에 창 안의 fast invocation 수 자체는 줄었지만, slow
worker-seconds는 406.013초 증가했다.

## 9. Duplicate claim / 실제 중복 채점 / republish

| timeout | durable duplicate claims | rows attempts>1 | attempts histogram | actual duplicate judge | stored-result republish | stale token completion | failed |
|---|---:|---:|---|---:|---:|---:|---:|
| 2500ms | 0 | 0 | `1:7195` | 0 | 0 | 0 | 0 |
| 1s | 708 | 352 | `1:6845, 3:348, 4:4` | **354** | 352 | 706 | 0 |

중복 채점의 두 독립 계산 경로는 일치했다.

```text
stale - republish - failures = 706 - 352 - 0 = 354
invocations - results - failures = 7551 - 7197 - 0 = 354
```

요구된 회계 항등식도 residual 0이다.

```text
invocations + republishes = 7551 + 352 = 7903
results + stale completions + failures = 7197 + 706 + 0 = 7903
```

stale token completion 706을 actual duplicate judgement로 부르지 않는다. class metric은 중복
실행 354건이 전부 slow이고 fast 중복은 0임을 직접 보여준다.

## 10. Executor와 claim

running/reserved/queued는 60초 측정창의 tick 평균/최대다. claim counter는 기존 분석 정의와
동일하게 63초 hold boundary snapshot 범위다.

| timeout | node | running avg/max | reserved avg/max | queued avg/max | claim calls | claimed rows | stale claims | invocations | rejected |
|---|---|---|---|---|---:|---:|---:|---:|---:|
| 2500ms | judge-1 | 9.717 / 16 | 9.717 / 16 | 0 / 0 | 552 | 3,408 | 0 | 3,419 | 0 |
| 2500ms | judge-2 | 10.833 / 16 | 10.833 / 16 | 0 / 0 | 555 | 3,437 | 0 | 3,437 | 0 |
| 1s | judge-1 | 12.667 / 16 | 12.667 / 16 | 0 / 0 | 446 | 2,713 | 269 | 2,591 | 0 |
| 1s | judge-2 | 12.083 / 16 | 12.083 / 16 | 0 / 0 | 445 | 3,017 | 273 | 2,881 | 0 |

MIF가 worker 수와 같으므로 local queue는 두 조건 모두 0이다. 1s는 두 노드 모두에서 reclaim과
중복이 발생했고, executor rejection 없이 worker 점유만 늘었다.

## 11. Backlog 시계열

측정창 시작을 0초로 한 약 10초 간격 표다. backlog는 unfinished judge outbox와 scoreboard
unapplied row의 합이다.

| 초 | 2500ms accepted/result/backlog | 1s accepted/result/backlog |
|---:|---|---|
| 0 | 713 / 685 / 28 | 702 / 475 / 244 |
| 10 | 1,756 / 1,702 / 64 | 1,733 / 1,287 / 468 |
| 20 | 2,839 / 2,806 / 33 | 2,827 / 2,087 / 755 |
| 30 | 3,947 / 3,883 / 82 | 3,924 / 3,012 / 941 |
| 40 | 5,037 / 5,009 / 28 | 5,018 / 3,800 / 1,218 |
| 50 | 6,146 / 6,107 / 54 | 6,126 / 4,580 / 1,564 |
| 59 | 7,134 / 7,097 / 45 | 7,112 / 5,389 / 1,723 |

2500ms는 tick 변동 안에서 오르내리고 끝점이 시작과 가깝다. 1s는 전반과 후반 모두 backlog가
커졌고, 끝점·최소제곱·후반 기울기가 같은 방향이다. 단순히 기울기 부호만 보고 내린 판정이 아니다.

## 12. 질문별 답

1. **중복 실행은 어디에 집중됐나?** 354건 모두 slow, fast 0건이다.
2. **요청 수 기준 추가 실행은?** 4.919%다.
3. **기본 worker 시간 기준 추가 비용은?** profile 계산 67.670%다.
4. **fast p50/p95/p99도 증가했나?** L_total 399/715/807ms에서
   11,656/16,358/16,823ms로 모두 증가했다.
5. **다른 요청의 queueing 증가 가설을 지지하나?** 지지한다. 증가는 L_result에 집중됐고
   scoreboard 지연은 평평하며 fast 중복 실행은 0이다.
6. **accepted/result RPS 차이는?** accepted -0.184 RPS로 사실상 같지만 result는 -24.967 RPS다.
7. **1s backlog는 지속 증가했나?** 그렇다. 전체 +25.295, 후반 +26.966 rows/s다.
8. **drain은 증가했나?** 1.930s에서 18.145s로 9.4배다.
9. **무결성은 유지됐나?** 두 조건 모두 유지됐다.
10. **80 RPS에서 숨겨진 비용이 110 RPS에서 손실로 바뀌었나?** 이 표본에서는 그렇다.
    이전 80 RPS run은 처리량이 약 78 RPS로 평평하고 1s 비용이 주로 지연으로 보였지만,
    이번 110 RPS에서는 result throughput -23.36%와 지속 backlog가 함께 나타났다.

## 13. 결론의 증거 등급

### 측정이 직접 지지하는 결론

- slow 4.891%가 기본 profile worker 비용의 67.288%를 차지했다.
- 1s에서 actual duplicate judge 354건이 발생했고 모두 slow였다. 2500ms는 0건이었다.
- 1s의 추가 실행 4.919%는 기본 profile worker 비용의 67.670%에 해당했다.
- offered/accepted 처리량은 거의 같았지만 result와 scoreboard 처리량은 약 25 RPS 낮아졌다.
- 1s backlog는 강건하게 증가했고 fast/slow 모두 L_result queueing이 커졌으며 drain이 9.4배였다.
- 두 조건 모두 최종 결과와 scoreboard 무결성을 유지했다.

### 코드와 측정으로부터의 추론

- fast duplicate가 0인데 fast L_result가 약 11~16초 늘고 scoreboard 지연은 거의 그대로이므로,
  slow 중복이 동일 worker pool을 점유해 fast 작업 대기를 늘렸다고 해석하는 것이 가장 직접적이다.
- 80 RPS에서 처리량 차이가 거의 없었던 비용이 이번 110 RPS에서는 result capacity 부족으로
  바뀌었다. 이는 두 실험의 조건과 mechanism이 일관된다는 추론이며 반복 실험의 통계적 결론은 아니다.

### 이번 실행으로 판단할 수 없는 사항

- 작은 accepted RPS 차이(-0.184)가 재현 가능한 차이인지 알 수 없다.
- 신뢰구간과 run-order 효과를 알 수 없다. 각 조건을 한 번만, 2500ms 뒤 1s 순서로 실행했다.
- 실제 sandbox judge의 CPU·메모리 비용이나 sleep 이외의 workload 분포에는 일반화할 수 없다.
- 개별 duplicate invocation의 실측 duration은 tag하지 않았다. 실제 class별 전체 timer와
  deterministic profile 기반 duplicate millis만 구분해 보고했다.

## 14. 알려진 한계

- synthetic sleep은 실제 채점 sandbox가 아니다.
- 단일 run이라 작은 percentile/RPS 차이는 일반화하지 않는다.
- 60초 창 시작 snapshot lag는 2500ms 820ms, 1s 574ms다. worker-second 비율에는 이 경계
  오차가 남는다.
- MySQL CPU는 stock image에서 측정하지 못했다. connection과 InnoDB lock counter만 보존했다.
- 총 HTTP attempt는 client maxDuration의 in-flight 요청 때문에 unavailable이다. 완료 요청과
  DB accepted를 분리했다.
- 1s percentile은 overload queueing을 포함하므로 steady service-time percentile이 아니다.
- run-scope actual judge-seconds의 분모에는 서로 다른 drain 길이가 포함되므로 조건 간 점유율 비교는
  정확한 60초 창의 49.37%와 66.58%를 사용했다.

## 15. 다음 장애 복구 실험에 미치는 영향

static lease의 timeout을 실제 slow 작업보다 짧게 두면 정상상태에서도 복구 기능이 선택적
재실행기로 변하고, 포화 근처에서는 정합성을 지키면서도 처리 용량을 잃는다. 다음 복구 실험은
다음을 분리해야 한다.

- running claim만 heartbeat로 연장하고 local-waiting claim은 만들지 않는 방식
- node failure 때 heartbeat가 끊긴 작업의 recovery time
- 동일한 reserved 상한에서 static lease와 heartbeat의 duplicate judge-seconds
- fault가 없는 정상상태에서 fast cohort latency와 result throughput이 유지되는지

이번 결과는 2500ms를 최종 운영값으로 확정하지 않는다. 다만 이 workload와 MIF16에서 1s를
정상상태 후보로 유지할 근거는 반증됐다.

## 16. 산출물

- `results/mysql-judge-tradeoff/saturation-mif16-timeout2500ms-rps110-20260920/`
- `results/mysql-judge-tradeoff/saturation-mif16-timeout1s-rps110-20260920/`
- `results/mysql-judge-tradeoff/duplicate-saturation-comparison.json`
- `results/mysql-judge-tradeoff/duplicate-saturation-comparison.md`

각 run에는 `parameters.json`, `events.json`, `timeseries.csv`, `latency.csv`, `capacity.csv`,
Prometheus/MySQL snapshot, Gatling raw log, `claim-attempts.tsv`, `stale-reclaims.csv`,
`db-verification.json`, `summary.json`, `summary.md`가 보존돼 있다.
