# fault 없는 정상상태에서 claim timeout이 만드는 중복 claim과 중복 채점

## 1. 목적과 가설

`docs/MYSQL_JUDGE_TRADEOFF_EXPERIMENT.md`는 fault 주입 복구 축에서 짧은 lease의 이득과 비용을
다뤘고, `docs/MYSQL_JUDGE_MAX_IN_FLIGHT_CAPACITY.md`는 fault 없는 정상상태에서 `max-in-flight`가
처리 용량을 어떻게 바꾸는지 다뤘다. 두 문서가 남긴 질문이 이 실험이다.

- 용량 문서의 결론: 정상상태 처리량은 timeout이 아니라 `max-in-flight`가 결정했다. 30s에서
  stale reclaim이 0이었다. 그러면 **timeout을 짧게 줄여도 정상상태에서는 아무 일도 일어나지
  않는가?**
- tradeoff 문서의 결론: MySQL 모드의 짧은 lease는 장애 복구를 앞당기지만 로컬 대기를 stale로
  오인할 수 있다. 그러면 **얼마나 짧아야 그 오인이 실제로 일어나며, 일어나면 무엇을 잃는가?**

이 실험은 fault를 주입하지 않는다. 정상상태만 본다. timeout을 짧게 줄였을 때

1. outbox row가 실제로 두 번 claim되는가(durable duplicate claim),
2. 그 재claim이 실제로 **두 번 채점**까지 가는가(actual duplicate judge execution),
3. 그 비용이 처리량·적체·지연에 나타나는가,
4. 어느 지점부터 timeout을 더 늘려도 달라지지 않는가

를 측정한다. **가설을 반증 가능한 형태로 둔다.** "짧은 timeout은 중복 채점을 만든다"는 참일
수도 거짓일 수도 있고, "timeout을 늘리면 그 중복이 사라진다"도 마찬가지다. 결과를 미리 정하지
않고 측정값만 보고한다.

## 2. timeout 상한의 유도

파라미터를 임의로 고르지 않기 위해 상한을 먼저 계산한다. claim이 회수되는 조건은 row의
`claimed_at`이 `CURRENT_TIMESTAMP(6) - INTERVAL timeout MICROSECOND`보다 오래된 것이다. 즉
timeout은 **"한 번의 채점이 끝나기까지 걸릴 수 있는 시간"보다 길어야** 정상상태에서 회수가
일어나지 않는다.

한 번의 채점 시간 상한은 두 부분이다.

- worker가 그 claim을 실제로 잡고 있는 시간: 노드당 동시 실행은 `max-in-flight`로 제한되므로
  reserved된 claim은 최대 `max-in-flight / worker-count` 번의 worker 교대를 기다린다.
- 한 번의 채점 자체 시간: 이 workload의 결정론적 프로파일에서 최대 2000ms다.

```
T_configured >= ceil(max-in-flight / worker-count) x 최대 채점시간
```

- `max-in-flight = 16`, worker 16/노드: `ceil(16/16) = 1` → 상한 2s
- `max-in-flight = 64`, worker 16/노드: `ceil(64/16) = 4` → 상한 8s

이 상한은 **보수적**이다. reserved가 항상 꽉 차 있지 않고, 한 claim의 채점이 2000ms인 경우는
5%뿐이며, 실제로는 회수가 일어나도 그 시점에 이전 시도가 이미 결과를 저장했으면 재채점 없이
저장된 결과를 재발행한다. 그래서 상한 근처와 상한 위를 모두 측정한다.

## 3. max-in-flight 16의 timeout 선택 근거

상한 2s. 세 지점을 잡는다.

| timeout | 상한 대비 | 의도 |
|---|---|---|
| 1s | 50% | **양성 대조군**. 상한의 절반이므로 회수가 일어나야 한다면 여기서 일어난다. 여기서도 0이면 "짧은 timeout이 중복을 만든다"는 가설 자체가 이 workload에서 지지되지 않는다. |
| 2s | 100% | **경계**. 유도한 상한과 정확히 같다. DB 왕복, poll 간격 100ms, 결과 저장 시간이 이론값 위에 더해지므로 이 지점이 실제 경계인지 확인한다. |
| 2.5s | 125% | 상한 +25%. 2s에서 중복이 보이면 여기서 사라지는지 본다. 사라지면 경계는 (2s, 2.5s] 사이고, 그 구간의 폭이 곧 "이론값에 얹힌 실제 오버헤드"다. |

**8s는 이번 라운드에서 제외한다.** max-in-flight 16에서 8s는 상한의 4배라 정보를 주지 않는다.

## 4. max-in-flight 64의 timeout 선택 근거

상한 8s. 세 지점을 잡는다.

| timeout | 상한 대비 | 의도 |
|---|---|---|
| 2s | 25% | **적극적 양성 대조군**. 상한의 1/4이다. mif=64는 노드당 48개가 로컬 큐에 대기하므로 큐 대기만으로도 2s를 넘길 수 있다. 여기서 중복이 안 보이면 큐 대기 가설이 이 workload에서 지지되지 않는다. |
| 4s | 50% | 상한의 절반. 2s에서 중복이 보이면 여기서 줄어드는지, 10s와 같은지 본다. |
| 10s | 125% | 상한 +25%의 안전값. 4s와 같으면 4s로 충분하다는 근거가 된다. |

**8s는 이번 라운드에서 실행하지 않는다.** 4s에서 중복이 남고 10s에서 사라지는 경우에만
후속으로 제안한다(13절). 자동으로 추가 실행하지 않는다.

## 5. warm-up 격리: 왜 별도 contest인가

한 run 안에서 stack과 서버 JVM 수명을 하나로 유지하려면 warm-up을 같은 프로세스 안에서
해야 한다. 그러면 warm-up이 남긴 작업이 측정 창의 카운터 delta에 섞일 수 있다. 필터로
걸러내는 대신 **구조적으로 분리**한다.

- contest 두 개를 부하 시작 **전에** seed한다. warm-up은 `norm_warm_<seed>`, 측정은
  `norm_meas_<seed>`에 쓴다. 측정 contest로 스코프한 어떤 쿼리도 warm-up row를 볼 수 없다.
- 두 contest를 미리 만드는 이유: phase 사이에 seed를 하면 그 DB 작업이 run 안에 들어와
  부하를 밀어낸다.
- 중복 등록부(`contest_submission_duplicate_registry`)는 `(contestId, problemId, userId,
  codeHash)`로 키가 잡히므로 같은 workload를 두 contest에서 재생해도 측정 phase가 dedup으로
  사라지지 않는다.
- warm-up이 **quiescence에 도달한 뒤에** baseline을 뜬다. quiescence 조건은 다음과 같다.
  미완료 outbox 전역 0, 두 judge 노드의 `reserved` 0, `results == accepted`,
  scoreboard 적용 `== accepted`, 그리고 **judge invocation 총량이 안정 창 동안 변하지 않음**.
  마지막 조건이 없으면 worker가 아직 채점 중인 상태를 baseline으로 잡을 수 있다.
- 그 뒤 **검사**한다. baseline 이후 warm-up contest의 제출 수가 늘었으면(즉 warm-up 작업이
  baseline 뒤에 실행됐으면) run을 버린다. warm-up 오염 여부를 주장하지 않고 확인한다.

warm-up 결과는 중복·지연·처리량 집계에서 제외된다. `timeseries.csv`에는 `phase=warmup`으로
남아 근거가 보존되고, `stages.json`의 `warmupPhase`와 `db-verification.json`의 `warmup`
블록에 격리 기록이 남는다.

## 6. 고정 조건과 정확한 실행 명령

고정: dispatch `mysql`, judge 2 노드, worker 16/노드, claim batch 16, poll interval 100ms,
fault 없음, latency seed `20260920`(95% 50ms / 5% 2000ms, `key-source=code`), user 1000,
drain timeout 600s, ramp 5s, warm-up hold 30s, 측정 hold 63s(= 측정 창 60s + guard 3s).
측정 불가 값(예: MySQL CPU)은 0이 아니라 `null`/`unavailable`과 이유를 기록한다.

부하: mif=16은 80 RPS, mif=64는 100 RPS. 용량 문서가 측정한 포화 처리량 116.296 / 147.963 RPS의
약 70%다. 정상상태에 머물면서 timeout 효과만 보기 위한 선택이며, 적체가 지속적으로 증가하면
그 run은 정상상태 결과로 쓰지 않는다.

여섯 run을 아래 순서로 **각 1회** 실행한다. 각 run은 `Run-TradeoffExperiment.ps1`의
`-NormalTimeout` 모드 한 번이다. 여섯을 순서대로 한 번에 돌리는 드라이버가
`scripts/mysql-judge-tradeoff/Invoke-NormalTimeoutMatrix.ps1`이고, 같은 명령을 하나씩 실행해도
같은 run이 된다.

```powershell
$h = "scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1"
$common = @("-DispatchMode","mysql","-NormalTimeout","-UserCount",1000,
            "-DrainTimeoutSeconds",600,"-WarmupSeconds",30,"-MeasurementSeconds",60,
            "-RampSeconds",5,"-SteadyGuardSeconds",3,"-LatencySeed",20260920)

# 1) mif 16, 2.5s   (80 RPS)
& $h @common -TargetRps 80  -MySqlMaxInFlight 16 -MySqlClaimTimeout 2500ms -RunId normal-mif16-timeout2500ms-20260920-retry
# 2) mif 16, 1s     (80 RPS)
& $h @common -TargetRps 80  -MySqlMaxInFlight 16 -MySqlClaimTimeout 1s     -RunId normal-mif16-timeout1s-20260920
# 3) mif 16, 2s     (80 RPS)
& $h @common -TargetRps 80  -MySqlMaxInFlight 16 -MySqlClaimTimeout 2s     -RunId normal-mif16-timeout2s-20260920
# 4) mif 64, 10s    (100 RPS)
& $h @common -TargetRps 100 -MySqlMaxInFlight 64 -MySqlClaimTimeout 10s    -RunId normal-mif64-timeout10s-20260920
# 5) mif 64, 2s     (100 RPS)
& $h @common -TargetRps 100 -MySqlMaxInFlight 64 -MySqlClaimTimeout 2s     -RunId normal-mif64-timeout2s-20260920
# 6) mif 64, 4s     (100 RPS)
& $h @common -TargetRps 100 -MySqlMaxInFlight 64 -MySqlClaimTimeout 4s     -RunId normal-mif64-timeout4s-20260920
```

`2.5s`는 그대로 쓸 수 없다. Spring Boot가 이 프로퍼티를 바인딩하는 `DurationStyle`의 단순 형식은
`^([+-]?\d+)([a-zA-Z]{0,2})$`라 소수점을 받지 않으므로, 하네스가 소수 초를 정수 밀리초로 바꿔
전달한다(`2.5s` → `2500ms`, 같은 Duration). `parameters.json`에는 요청값과 실제 전달값이 함께
남는다.

다만 **측정된 여섯 run은 위 명령대로 `2500ms`를 직접 넘겼으므로 그들의 `parameters.json`은
요청값과 전달값이 모두 `2500ms`로 같다.** 즉 비교표의 `2500ms` 행 자체는 이 변환이 일어났다는
증거가 아니다. 변환을 실제로 태운 것은 실패한 run 1이고, 그 `parameters.json`에 `2.5s` →
`2500ms` 쌍이 남아 있다. 하네스는 소수 초를 거부하지 않으므로(`2.5s`를 주면 `2500ms`로 바꿔
실행한다) 둘은 같은 설정이다.

run 1은 `normal-mif16-timeout2500ms-20260920`으로 한 번 실행되었고 **하네스 결함으로 실패했다**.
그 실패 run의 디렉터리는 원인 기록과 함께 그대로 남아 있고, 코드를 고친 재실행이므로 새 RunId
(`...-retry`)를 쓴다. 실패 원인과 수정은 7절에 적는다.

```powershell
# 두 run 사이에 stack을 내리지 않는다. 한 run 안에서 stack과 서버 JVM 수명이 하나다.
& "scripts\mysql-judge-tradeoff\Analyze-TradeoffRun.ps1" -RunDirectory "results\mysql-judge-tradeoff\<RunId>"
& "scripts\mysql-judge-tradeoff\Compare-NormalTimeoutRuns.ps1" -RunDirectory @(
    "results\mysql-judge-tradeoff\normal-mif16-timeout2500ms-20260920-retry",
    "results\mysql-judge-tradeoff\normal-mif16-timeout1s-20260920",
    "results\mysql-judge-tradeoff\normal-mif16-timeout2s-20260920",
    "results\mysql-judge-tradeoff\normal-mif64-timeout10s-20260920",
    "results\mysql-judge-tradeoff\normal-mif64-timeout2s-20260920",
    "results\mysql-judge-tradeoff\normal-mif64-timeout4s-20260920") `
  -OutputDirectory "results\mysql-judge-tradeoff\normal-timeout-comparison-20260920"
```

## 7. 하네스 결함 하나와 그것이 만든 오해

run 1은 시스템이 아니라 하네스 때문에 죽었고, 그 실패가 남긴 증상은 시스템 결함처럼 읽혔다.
두 가지를 분리해서 적는다.

- **결함.** warm-up phase의 JVM 인자에서 `-Dperf.stageTraceFile`가 `-cp <classpath>
  io.gatling.app.Gatling` **뒤에** 붙었다. JVM은 시스템 프로퍼티를 클래스 이름 앞까지만 읽으므로
  이 값은 프로그램 인자가 되었고, Gatling은 `Unknown option -Dperf.stageTraceFile=...` 경고를 내고
  trace 파일을 아예 쓰지 않았다. 하네스는 trace가 없으면 warm-up 창을 배치할 수 없어 run을
  중단한다. 측정 phase의 인자 순서는 처음부터 맞았기 때문에 이 결함은 normal-timeout 모드에서만
  드러났다.
- **오해.** 중단되면서 하네스가 stack을 내렸고, 그 시점에 warm-up Gatling JVM은 아직 살아 있었다.
  죽은 주소를 향해 계속 요청을 보낸 그 JVM의 `simulation.log`(실패 run의 결과 트리 밖,
  `gatling/build/reports/gatling/contestsubmissionsteploadsimulation-20260920010358037/`)에는
  `j.i.IOException: Premature close` KO가 **정확히 881건**, 그 외 다른 오류 종류 없이 남았다.
  구성은 `api-login-once` 857건 + `api-contest-submit` 24건이고, 폭이 0.63초인 한 구간에 몰려
  있다. 그 구간은 run 시작 3.53초 뒤에 시작하는데, **실패 run의 `runEndedAt`보다 291ms 뒤다.**
  즉 로드는 run이 이미 끝난 뒤에야 무너졌다 — teardown이 살아 있는 JVM과 경쟁한 것이지 앱이
  로그인을 거부한 것이 아니다. 로그인 실패 → `exitHereIfFailed` → closed model이 사용자를 즉시
  대체 → feeder(1000개, 비순환) 소진 → 엔진 중단 순서로 읽히지만, **그 마지막 두 단계
  (`Feeder in-memory is now empty, stopping engine`과 Gatling의 `Unknown option
  -Dperf.stageTraceFile=...` 경고)는 그때 콘솔에서 관측했을 뿐 보존되지 않았다.** 실패 run의
  디렉터리에는 Gatling 콘솔 로그가 없고 `simulation.log`만 남았으므로, 아카이브로 확인되는 것은
  881건의 teardown 경쟁과 그 시각까지다.

수정은 둘이다. 인자 순서를 측정 phase와 같게 맞추고(`-D` 프로퍼티를 `-cp` 앞으로), 실패 경로의
`finally`에서 아직 살아 있는 Gatling 프로세스를 먼저 종료한 뒤 stack을 내린다. 뒤의 수정이 없으면
다음 실패도 같은 방식으로 오해를 만든다.

### 이 수정이 남긴 provenance 공백

수정은 **커밋되지 않은 채** 작업 트리에 있었고, 그 상태로 여섯 run이 모두 실행됐다. 그래서
여섯 run의 `parameters.json`이 기록한 `gitCommit`은 `a518ed2`인데, **그 커밋의 하네스에는 위
인자 순서 결함이 그대로 있어 6절의 명령을 그 커밋에서 다시 실행하면 같은 지점에서 다시 중단된다.**
즉 기록된 커밋만으로는 측정을 재현할 수 없고, 아티팩트 어디에도 그 사실이 드러나 있지 않았다
(수정 전 `parameters.json`에는 dirty 표시도 스크립트 해시도 없었다).

이 개정은 이제 `b520df3`으로 커밋됐다. 그 커밋에는 실행된 하네스에 더해 **실행 후에 추가한 두
가지**(0 이하 lease 거부, provenance 필드)가 함께 들어 있으므로, 여섯 run이 쓴 정확한 개정은
`b520df3`에서 그 둘을 뺀 내용이다. **이 진술 자체는 아티팩트로 증명되지 않는다** — 여섯 run의
`parameters.json`에는 그 필드가 없기 때문이다. 재발을 막기 위해 하네스는 이제
`harnessScriptSha256`(실행된 스크립트의 SHA-256)과 `harnessTreeDirty`(그 트리에 미커밋 변경이
있었는지)를 `parameters.json`에 기록한다.

## 8. 여섯 run 결과

**일곱 번 시도했고 하나는 실패했다.** run 1(`normal-mif16-timeout2500ms-20260920`)은 하네스
결함으로 warm-up 창을 배치하지 못해 중단됐고 측정값이 없다(7절). 그것은 코드를 고친 뒤 새
RunId(`...-retry`)로 다시 실행한 여섯 run과 함께 비교표에 들어가며, 비교 산출물의 `excluded`
목록에 실패 사유와 함께 명시적으로 표시된다 — 표에 아예 없는 것이 아니라 "실패해서 제외됨"으로
보인다.

여섯 run 모두 integrity를 통과했고(`accepted == uniqueSubmissions == results == scoreboardApplied`,
`lostOrIncomplete = 0`, `finalResultMismatch = 0`), 모두 `steady`로 분류됐으며, executor는 설정
상한 안에 있었고, 429/500/503은 0이었다. 즉 **측정된 여섯 run 중 어느 것도 오염이나 적체로
실격되지 않았다.**

`duplicate claims`는 `SUM(GREATEST(attempts-1,0))`, `duplicate judgements`는 중복 채점 수
(9절에서 정의), `stale`은 fence에 걸린 completion 수다.

| MIF | target RPS | timeout | accepted | result RPS | backlog growth | duplicate claims | duplicate claims/10k | judge invocations | duplicate judgements | stale token completions | p95 total | p99 total | drain |
|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 16 | 80 | 2500ms | 5221 | 78.417 | +0.0678 | 0 | 0 | 5221 | 0 | 0 | 2087.419 | 2319.962 | 1.585 |
| 16 | 80 | 1s | 5222 | 78.783 | -0.1356 | 560 | 1072.386 | 5510 | 288 | 558 | 2288.879 | 3381.611 | 1.653 |
| 16 | 80 | 2s | 5220 | 78.583 | +0.0509 | 90 | 172.414 | 5264 | 44 | 90 | 2089.975 | 2343.092 | 2.940 |
| 64 | 100 | 10s | 6549 | 98.233 | -0.0509 | 0 | 0 | 6549 | 0 | 0 | 986.546 | 2342.494 | 1.643 |
| 64 | 100 | 2s | 6543 | 97.983 | -0.2034 | 373 | 570.075 | 6706 | 163 | 373 | 967.661 | 2360.489 | 4.175 |
| 64 | 100 | 4s | 6539 | 98.067 | +0.1186 | 0 | 0 | 6539 | 0 | 0 | 594.680 | 2331.265 | 1.587 |

회계(9절)는 여섯 run 모두 잔차 0으로 닫혔다. attempts 분포:

| run | histogram | rows(attempts>1) |
|---|---|---|
| 16 / 2500ms | `1:5221` | 0 |
| 16 / 1s | `1:4952, 3:250, 4:20` | 270 |
| 16 / 2s | `1:5148, 2:54, 3:18` | 72 |
| 64 / 10s | `1:6549` | 0 |
| 64 / 2s | `1:6284, 2:145, 3:114` | 259 |
| 64 / 4s | `1:6539` | 0 |

warm-up은 모든 run에서 baseline 전에 quiescence에 도달했고(`quiescedBeforeBaseline = true`),
baseline 이후 warm-up contest의 제출 수 증가는 여섯 run 모두 0이었다. 즉 **warm-up 작업이 측정
창에 섞인 run은 없다.** warm-up contest 자체의 중복 claim은 2 / 296 / 129 / 0 / 252 / 0건이었다.

## 9. durable duplicate claim과 actual duplicate judgement는 다른 양이다

이 실험의 중심 구분이다. 세 값을 섞으면 결론이 뒤집힌다.

**durable duplicate claim** = `SUM(GREATEST(attempts-1,0))` = outbox row가 첫 배분 이후 몇 번 더
배분됐는가. lease가 만료되어 회수된 사건의 수다. attempts는 claim마다 쓰이고 production 경로가
읽지 않으므로 durable한 기록이다.

**actual duplicate judgement** = 한 제출이 **두 번 이상 채점**된 횟수 = `judgeSubmission` 호출이
결과 row 수보다 많은 만큼이다. 두 경로로 독립 계산되며 여섯 run 모두 일치했다.

```
duplicate judgements = stale completions - republishes - failures
                     = judge invocations - unique results - failures
```

**stale token completions를 중복 채점 수로 쓰면 안 된다.** 회수된 row는 **원래 실행까지 fence에
걸린다.** 회수 claim은 저장된 결과를 재발행하고 publish 경쟁에서 이기므로, 원래 실행의 fenced
completion도 매칭에 실패한다. 그래서 stale은 회수된 row마다 "중복이 아닌 실행" 1건을 함께 센다.
측정된 관계는 정확히 이렇다(여섯 run 전부):

```
stale = (judge invocations - unique results) + republishes + failures
```

production 코드에서 확인한 근거:

- `ContestSubmissionJudgeProcessor:44-53` — 저장된 결과가 있으면 재발행하고 **timed judge 호출
  앞에서 return**한다. 즉 재발행은 invocation이 아니다.
- `ContestJudgeExecutionMetrics:32-35` — `invocations`와 `duration`은 `judgeSubmission`을 감싼
  try/finally에서만 기록된다.
- `MysqlContestJudgeDispatcher:87-100` — 재발행 claim도 같은 `judge()`를 지나 `completeAll`을
  호출하므로 fenced completion을 1건 만든다.

이 구분이 실제로 갈라지는 지점이 MIF64/2s다. **회수 259 row인데 중복 채점은 163건뿐이다**
(republish 210건이 흡수). MIF64는 reserved가 최대 60까지 가는데 running은 32라 로컬 큐가 있고,
회수 시점에는 이전 시도가 이미 결과를 저장해 둔 경우가 많다. 반대로 MIF16/1s는 회수 270 row에
중복 채점 288건으로 **중복 채점이 더 많다.** 회수된 row의 이전 실행이 아직 2000ms 채점 중이라
회수된 claim도 실제로 채점까지 가기 때문이다. 즉 "짧은 lease가 중복 채점을 만든다"는 방향은
맞지만, **얼마나**는 `max-in-flight`가 정한다.

## 10. max-in-flight 16 해석

3절의 세 질문에 대한 답:

| 질문 | 관측 |
|---|---|
| 1s가 실제로 중복을 만드는가 | **만든다.** claim 560건/270 row, 중복 채점 288건(per10k 551.5). 270/5222 = 5.17%로 결정론적 프로파일의 긴 채점 비율 5%와 일치한다. |
| 2s 경계가 DB/poll/결과 저장 오버헤드 때문에 밀리는가 | **밀린다.** 유도한 상한이 정확히 2s였는데도 90건/72 row, 중복 채점 44건이 발생했다. |
| 2.5s에서 사라지는가 | **사라진다.** 측정 창에서 0건. |
| 2.5s 또는 3s를 지지할 수 있는가 | 2.5s는 중복 0이지만 **warm-up 창에서 2건**이 있었다(같은 2.5s). 1s·2s는 warm-up이 각각 296·129건이므로 cold-start 구간이 timeout에 훨씬 민감하다. 3s는 **측정하지 않았다.** |

attempts 분포가 메커니즘을 보여준다. 1s에서는 `1:4952, 3:250, 4:20`으로 **attempts=2가 없다.**
2000ms 작업에 1s lease면 회수가 1회에서 멈추지 않고 3회까지 진행되므로 분포가 3·4에만 나타난다.
2s에서는 `1:5148, 2:54, 3:18`로 attempts=2 구간이 생긴다. 즉 **1s는 긴 채점 작업을 확실히
회수하고, 2s는 그 일부만 회수한다.**

**실제 경계는 (2s, 2.5s]이다.** 폭 500ms가 이론값 위에 얹힌 오버헤드(DB 왕복 + poll 100ms +
결과 저장)의 크기다. 2.5s는 측정된 것 중 이 창에서 중복이 0인 가장 짧은 값이지만,
단일 실행이므로 3s와의 비교는 없다. **2.5s를 최종 운영값으로 단정하지 않는다.**

## 11. max-in-flight 64 해석

4절의 세 질문에 대한 답:

| 질문 | 관측 |
|---|---|
| 2s에서 큐 대기 때문에 중복이 생기는가 | **생긴다.** claim 373건/259 row(per10k 570.1). 259/6543 = 3.96%로 긴 채점 비율 5%에 근접한다. 다만 중복 채점은 **163건**뿐이다. |
| 4s에서 사라지거나 줄어드는가 | **사라진다.** 0건. |
| 10s가 더 개선하는가 | **개선하지 않는다.** 10s도 0건이고, 처리량·지연도 동등하다(98.067 vs 98.233 RPS). |
| 4s == 10s이면 4s를 지지할 수 있는가 | 중복과 처리량 기준으로는 지지된다. 다만 각 1회 실행이고, p95가 594.680(4s) vs 986.546(10s)로 4s가 더 낮은 것은 timeout으로 설명되지 않으므로 잡음·큐 변동으로 봐야 한다. |

**실제 경계는 (2s, 4s]이다.** MIF16의 (2s, 2.5s]보다 넓은데, 이는 mif=64가 노드당 48개를 로컬
큐에 두므로 한 claim이 worker를 잡기까지의 대기가 길어지기 때문이다. 그런데 MIF64의 회수는
대부분 재채점으로 이어지지 않는다 — 이 점이 12절의 비용 해석을 바꾼다.

## 12. timeout에 따른 처리량·적체·지연

**처리량은 timeout에 대해 사실상 평평하다.** MIF16: 78.417 → 78.583 → 78.783 RPS
(2.5s → 2s → 1s). MIF64: 98.233 → 98.067 → 97.983 RPS (10s → 4s → 2s). MIF16에서는 timeout을
줄일수록 처리량이 아주 조금 **오른다** — 회수된 row를 다른 worker가 집어갈 수 있기 때문이다.
80/100 RPS가 포화(116.296 / 147.963)의 약 70%라 중복 작업을 흡수할 여유가 있었다. **이 실험의
부하 구간에서 timeout은 처리 용량을 파괴하지 않았다.**

**적체는 어느 run에서도 지속 증가하지 않았다. 다만 그 판정은 부호가 아니라 임계값 위에 서 있다.**
측정 창의 순증가율은 -0.2034 ~ +0.1186 rows/s이지만 **후반 절반의 기울기는 그 범위가 아니다**:
+0.3104 / -0.3448 / -0.4829 / -0.2070 / -0.0690 / -0.2069 (run 순서). 여섯 중 넷이 그 범위를
벗어난다. 판정 게이트는 `secondHalfRowsPerSec <= +1`(OverloadThresholdRowsPerSec)이라 **단조
증가만 실격시키고 감소하는 후반은 실격시킬 수 없다.** 전체에서 가장 큰 기울기 절대값은 0.7243
(mif64/4s의 전반)으로 임계값 1 아래다.

그런데 그 수들의 크기는 잡음과 구별되지 않는다. tick당 표준편차가 11.3~25.8 row이고 한 tick
최대 변동이 24~93 row인데, 59초 동안의 순증가는 +4 / -8 / +3 / -3 / -12 / +7 row에 불과하다.
여섯 중 넷에서 최소제곱 기울기가 끝점 기울기와 **부호가 반대**다. 즉 "steady"는 부호가 아니라
1 rows/s 임계값이 만든 판정이고, **적체가 없었다는 증거는 순증가가 잡음보다 작다는 것이지
기울기가 음수라는 것이 아니다.** 전반/후반 부호가 뒤집히는 run이 여섯 중 셋(2s: +0.5863 →
-0.4829, mif64/2s: +0.0689 → -0.0690, mif64/4s: +0.7243 → -0.2069)이라는 사실이 그 위험을
보여준다.

**지연은 MIF에 따라 다른 곳에 비용을 낸다.**

| run | p50 total | p95 total | p99 total | p95 result | p95 scoreboard |
|---|---:|---:|---:|---:|---:|
| 16 / 2500ms | 301.559 | 2087.419 | 2319.962 | 2047.613 | 121.876 |
| 16 / 2s | 321.156 | 2089.975 | 2343.092 | 2044.463 | 121.081 |
| 16 / 1s | 1037.771 | 2288.879 | 3381.611 | 2221.723 | 121.531 |
| 64 / 10s | 325.228 | 986.546 | 2342.494 | 904.628 | 128.714 |
| 64 / 2s | 348.997 | 967.661 | 2360.489 | 898.991 | 130.088 |
| 64 / 4s | 319.640 | 594.680 | 2331.265 | 521.527 | 126.637 |

- **MIF16**: 1s에서 p50이 301.6 → 1037.8ms로 3.4배가 되고 p99도 3381.6ms로 오른다. 중복 채점
  288건이 worker를 점유한 대가가 중위 지연에 나타난다. 2s는 p50·p95가 2.5s와 거의 같고 p99만
  2343ms로 조금 높다.
- **MIF64**: p50이 320~349ms로 timeout에 반응하지 않고 p99도 2331~2361ms로 평평하다. 비용은
  지연이 아니라 **추가 채점 작업**(163건 × 50–2000ms)으로만 나타난다.
- **p95가 MIF 간에 다른 이유는 구조적이다.** MIF16의 p95(2087~2289ms)는 2000ms 채점 버킷에
  걸려 있고, MIF64의 p95(595~987ms)는 로컬 큐 대기 구간에 걸려 있다. MIF64는 reserved가 최대
  61까지 가는데 running은 32이므로 큐 대기가 p95를 지배한다. **두 MIF의 p95를 같은 의미로
  비교하면 안 된다.**
- scoreboard 지연은 121~130ms로 timeout과 무관하게 평평하다. 결과 발행 경로가 timeout 영향을
  받지 않는다는 뜻이다.
- drain은 MIF64/2s에서 4.175s로 다른 run(1.587~2.940s)보다 길다. 회수된 작업이 drain 중에
  정리된 결과로 보인다.

중복 채점의 시간 비용(프로파일 50ms/2000ms로 가격):

| run | 중복 채점 | 하한 | 상한 |
|---|---:|---:|---:|
| 16 / 1s | 288 | 14,400 ms | 576,000 ms |
| 16 / 2s | 44 | 2,200 ms | 88,000 ms |
| 64 / 2s | 163 | 8,150 ms | 326,000 ms |

MIF16/1s의 상한 576초는 측정 창 63초 동안 두 노드가 낼 수 있는 채점 시간에 비해 무시할 수 없는
양이다. 그런데 처리량이 떨어지지 않은 것은 그 비용을 흡수할 여유가 있었기 때문이며,
**포화 근처에서는 같은 중복이 처리량 손실로 바뀔 수 있다. 이 실험은 그 구간을 측정하지 않았다.**

## 13. 8초 후속이 필요한가

**필요하지 않다.** 4절이 후속 조건을 "4s에서 중복이 남고 10s에서 사라지는 경우"로 미리 정했는데,
MIF64/4s는 중복이 **0건**이다. 10s와 같은 값이므로 8s는 두 값 사이에 아무 정보를 더하지 않는다.
자동으로 추가 실행하지 않았고, 제안하지도 않는다.

## 14. 알려진 한계

- **설정마다 1회 실행이다.** 반복이 없으므로 신뢰구간이 없고, 비슷해 보이는 값(예: MIF64의
  4s와 10s)을 잡음과 구별할 수 없다. "같다"는 판정은 이 표본에서 관측된 범위가 겹친다는 뜻이다.
- **8s와 3s는 측정하지 않았다.** MIF16의 실제 경계가 (2s, 2.5s]라는 것까지가 관측이고,
  2.5s와 3s 중 무엇이 나은지는 이 실험의 답이 아니다.
- **MIF16/2.5s의 측정 창 중복은 0이지만 warm-up 창에는 2건이 있었다.** cold-start 구간은
  이 timeout에 대해 측정 창보다 민감하며, 그 구간은 격리되어 집계에서 제외된다. 즉 2.5s가
  "어떤 순간에도 회수가 없다"는 뜻은 아니다.
- **중복 채점 수는 파생값이고, 두 경로의 일치는 "제출마다 결과 row가 정확히 1개"라는 조건에
  기댄다.** 여섯 run 모두 integrity를 통과했으므로 성립했지만, 그 조건이 깨지는 run에서는 두
  경로가 갈라지고 그 불일치 자체가 발견 사항이 된다(분석기가 `routesAgree`로 노출한다).
- **하네스의 `workCost.totalJudgeMillis`는 원시 duration delta와 어긋나고 정수 초로 양자화돼
  있다**(run 1에서 788000 vs 원시 788144.969ms). 이 필드는 위 결론에 쓰지 않았다. 재유도가
  일치하지 않은 유일한 필드다.
- **카운터 모델에 규명되지 않은 경로가 하나 있다.** smoke run의 warm-up에서 invocations 274 vs
  results 265, reclaim 0, republish 0인데 9건이 결과를 만들지 않았다. 여섯 측정 run에서는
  invocations − results가 중복 + republish로 정확히 닫혔으므로 결론에 영향은 없지만,
  모델 밖의 경로가 존재한다는 뜻이다. read-only 분석으로는 규명하지 못했다.
- **`stale-reclaims.csv`의 timestamp는 reclaim 시각이 아니라 row의 마지막 write(`updated_at`)다.**
  결과가 늦게 확정된 row는 다른 창에 귀속되므로 이 파일은 시각 프록시이고, 창별 회수 수는
  prometheus `claim_stale` delta로 읽어야 한다.
- **비교표는 서로 다른 세 창을 한 표에 담는다.** 회수·invocation·중복 열은 run 전체(측정 phase
  baseline→end)인데, 그 창은 **63초 hold + drain이 아니라 약 75초다**: baseline은 warm-up이
  quiesce한 뒤 ramp 전에 뜨므로 창 안에 pre-ramp 유휴(측정된 run에서 3.76초) + ramp 5초 +
  hold 63초 + drain이 모두 들어간다(실측 75.177초). 백분위는 그 hold 안의 60초 정상 창(ramp 5초
  제외), 처리율 열은 같은 60초 창이다. per-10k 비율은 run 스코프 분자·분모로 서로 일관되지만 옆
  열과는 스코프가 다르다.
- **여섯 run의 `parameters.json`이 기록한 커밋은 그 run을 실행한 하네스를 담고 있지 않다.**
  기록된 `a518ed2`의 하네스에는 trace 인자 순서 결함이 남아 있어 그 커밋으로는 측정이 재현되지
  않는다(7절). 실행된 개정은 이제 `b520df3`으로 커밋됐지만, 여섯 run의 아티팩트에는 그 사실을
  드러내는 필드가 없다. 하네스가 `harnessScriptSha256`/`harnessTreeDirty`를 기록하는 것은 이
  라운드 이후부터다.
- **429/500/503은 여섯 run 모두 0이었다.** 오염이 없었다는 뜻이지만, HTTP 상태를 관측한 것은
  클라이언트(Gatling 로그)뿐이다.
- **MySQL CPU는 측정 불가로 `null`/이유를 기록했다.** 0으로 채우지 않았다.

## 15. 운영 후보와 추가 검증

**측정이 지지하는 것**

- MIF16의 실제 경계는 **(2s, 2.5s]**이다. 이 표본에서 측정 창 중복이 0인 가장 짧은 값은 2.5s다.
  1s와 2s는 중복 채점을 실제로 만들었고(288건, 44건), 그 비용은 1s에서 p50·p99로 관측됐다.
- MIF64의 실제 경계는 **(2s, 4s]**이다. 4s는 중복 0이고 10s와 처리량이 동등하므로, 중복과
  처리량 기준으로 4s로 충분하다. 8s 후속은 정당화되지 않는다(13절).
- **두 MIF에 같은 timeout을 강제할 근거는 없다.** 경계가 (2s, 2.5s]와 (2s, 4s]로 다르고,
  MIF64는 회수를 대부분 결과 재발행으로 흡수하므로 중복 채점 비용이 MIF16보다 작다.

**측정이 지지하지 않는 것**

- **어느 값도 최종 운영값으로 단정하지 않는다.** 설정마다 1회 실행이라 잡음과 구별할 수 없고,
  3s와 8s는 측정하지 않았다.
- MIF16에서 2.5s와 3s 중 무엇이 나은지, MIF64에서 4s가 3s보다 나은지는 이 실험의 답이 아니다.
- 포화 근처(116/148 RPS)에서 같은 timeout이 처리량을 깎는지는 측정하지 않았다. 이 실험은
  포화의 약 70%에서 돌았고, 그 구간에서는 중복 작업이 흡수됐다. **이는 용량 문서가 timeout
  실험에 권한 100~150 RPS와 다른 부하 구간이다.** 그 권고를 따른다면 mif=16은 100~116 RPS에서
  이미 overload 경계에 가까우므로 timeout 효과와 포화 효과가 섞인다. 이번 라운드가 70%를 택한
  이유는 timeout만 종속 변수로 두기 위해서이고, 그 대가로 포화 구간의 거동은 미측정으로 남는다.

**추가 검증 후보(실행하지 않음, 제안만)**

1. MIF16에서 2.5s와 3s를 각 3회 반복해 경계의 재현성과 잡음 폭을 확인한다.
2. MIF16/2.5s를 cold-start 포함 조건(측정 창을 ramp 직후까지 확장)으로 다시 측정해 2.5s가
   과도 구간에서도 안전한지 본다. 이번 라운드에서 warm-up에 2건이 있었다.
3. 포화 근처(MIF16에서 100~116 RPS)에서 timeout 1s와 2.5s를 비교해 중복 작업이 처리량 손실로
   바뀌는 지점을 찾는다. 이것이 운영값 선택에 가장 직접적인 정보가 된다.
4. 카운터 모델의 미규명 경로(14절)를 규명한다. 14절의 smoke run 관측이 재현되는지 확인한다.
5. 다음 라운드는 실행 전에 하네스 변경을 커밋하고, 각 run의 `parameters.json`에 기록되는
   `harnessScriptSha256`/`harnessTreeDirty`로 실행 개정이 커밋과 일치하는지 확인한다. 이번
   라운드는 그 필드가 생기기 전에 돌아서, 여섯 run의 개정을 아티팩트로 증명할 수 없다.

## 16. 실행 후 수정 (분석기·하네스·비교 스크립트·문서)

여섯 run의 측정이 끝난 뒤 다음을 고쳤다. **측정은 다시 돌리지 않았다** — 여섯 run은 모두 같은
하네스 개정으로 실행됐고(7절의 미커밋 개정. 이 진술은 아티팩트로 증명되지 않는다), 고친 것은
파생값의 선택·라벨·계산과 하네스의 방어, 그리고 이 문서의 서술이었으며, 원시 카운터
(`metrics/*.prom`, `db-verification.json`, `timeseries.csv`, `claim-attempts.tsv`)는 전부 보존돼
있기 때문이다. 고친 뒤 여섯 run을 재분석하고 비교표를 재생성했다.

- **중복 채점의 정의를 고쳤다.** 이전에는 `stale completions`를 그대로 중복 채점으로 보고해
  MIF16/1s에서 288을 558로, MIF64/2s에서 163을 373로 과대 보고했다. 회수된 row의 원래 실행도
  fence에 걸리므로 stale에는 중복이 아닌 실행 1건씩이 섞인다. 이제
  `stale − republishes − failures`와 `invocations − results − failures` 두 경로로 계산하고
  일치 여부를 `routesAgree`로 노출한다. 여섯 run 모두 일치했다.
- **회계 항등식을 고쳤다.** 이전 식 `invocations − results = republish + failure + stale`은
  유도가 틀려서 republish가 있는 run에서 잔차가 항등적으로 `-2 × republish`가 되고, 0에 도달할
  수 없었다. 재발행은 judge 호출이 아니라 completion만 만들기 때문이다. 올바른 식
  `invocations + republishes = results + stale + failures`로 바꿨고, 여섯 run 모두 잔차 0이다.
- **basis string 세 개를 측정에 맞게 고쳤다.** `success = results + republish`(실제로는
  `success = results`), `invocations − results`를 "상한"이라 부르던 설명(실제로는 중복 + 실패),
  두 설명이 서로 뒤바뀌어 있던 것.
- **중복 채점 시간 bounds를 올바른 수로 다시 계산했다.** 이전에는 `invocations − results`로
  가격을 매겨 약 2배 과소했고, 라벨은 stale 기준이라고 적혀 있었다. run 수준과 stage 수준이
  같은 기준을 쓰게 됐다.
- **`per10k`에서 `$null * 10000 == 0`이 되어 unavailable이 "0건"으로 인쇄되던 것을 막았다.**
- 라벨 수정: `stale reclaim rows (timestamps)` → `updated_at` 프록시임을 명시,
  `duplicate claim ms` → `duplicate judge ms`, `steadyStateQualified`와
  `reliableAsSteadyStateLatency`가 서로 다른 규칙임을 명시.
- **하네스가 실행된 개정을 기록한다.** `parameters.json`에 `harnessScriptSha256`과
  `harnessTreeDirty`를 추가했다(7절의 provenance 공백).
- **드라이버가 실패한 매트릭스를 성공으로 보고하지 않는다.** 깨끗한 summary를 쓰지 못한 run이
  하나라도 있으면 비정상 종료 코드를 세운다.
- **하네스가 0 이하 lease를 거부한다.** `0s`/`-1s`/`0ms`는 앱에서 `Duration.ZERO`가 되어 모든
  row를 다음 poll마다 회수 가능하게 만들므로, 실험이 아니라 고장난 설정이다.
- **비교 스크립트가 창 스코프를 정확히 적고 실패 run을 명시한다.** run 스코프 창을 "63초 hold +
  drain"이라 적어 약 75초를 16% 과소 서술하던 문장을 고쳤고, `summary.json`이 없는 디렉터리를
  "분석하지 않은 run"과 "실패한 run"으로 구분해 후자는 `failure.txt`의 사유와 함께 `excluded`에
  싣는다. 실패 run을 입력에 넣어 재생성했으므로 비교표는 이제 "Excluded: none"이 아니다.
- **이 문서 자체의 서술 3건**: 12절의 "후반 기울기도 같은 범위" (실제로는 여섯 중 넷이 범위 밖),
  7절의 증거 귀속(보존된 `simulation.log`로 확인되는 것과 콘솔에서만 본 것을 분리), 6절의
  `2.5s`→`2500ms` 변환(측정된 여섯 run은 변환을 태우지 않았다).

이 절의 수정 내역은 독립 read-only 리뷰에서 나온 지적과 본 실험 중 발견을 합친 것이다. 리뷰는
두 번째로 최종 상태를 원시 아티팩트와 다시 대조했고, 그 라운드에서 위의 provenance 공백, 12절
기울기 문장, 실패 run 미표시, 창 스코프 라벨 4건이 나왔다. 나머지 지적
(`Get-PromMetricDelta`의 단일 노드 delta 가능성, 실패 run의 Gatling 콘솔 로그 미보존)은 결론에
영향을 주지 않아 한계로 남겼다.

