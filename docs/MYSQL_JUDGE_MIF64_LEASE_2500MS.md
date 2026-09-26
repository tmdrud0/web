# MIF 64에서 lease 2.5초 vs 4초 — 정상 부하와 포화 근처

## 1. 배경과 질문

`docs/MYSQL_JUDGE_NORMAL_TIMEOUT_DUPLICATION.md`의 MIF 64 · 100 RPS 정상 부하 실험은
timeout {2s, 4s, 10s}만 측정했다. 2.5초는 없었다. `docs/MYSQL_JUDGE_DUPLICATE_SATURATION_COST.md`는
MIF 16에서 포화 근처(110 RPS, 포화값의 94.6%)의 2.5s와 1s를 비교해 1s가 처리량을 23% 깎는 것을
보였지만, 이 비교는 MIF 16에서만 있었다.

질문은 그대로다.

> MIF 64(worker 16/node, 로컬 큐 최대 48건/node)에서 lease 2.5초는 "2초 채점 + 로컬 큐 대기" 때문에
> 정상 작업을 회수하는가? 그 중복 비용이 포화 근처에서 다른 요청의 지연과 처리량으로 번지는가?

결과를 미리 정하지 않는다. 아래에서 보듯 답은 "예/아니오"로 깔끔하게 갈리지 않았고, 관측된 그대로
보고한다.

## 2. 실행한 것과 조건

공통: dispatch `mysql`, judge 2노드, worker 16/노드, MIF 64, claim batch 16, poll 100ms, 채점 지연
95% 50ms / 5% 2000ms(seed `20260920`, key-source `code`), user 1000, drain timeout 600s, warm-up
30s + 별도 contest, ramp 5s, guard 3s, 측정 60s. 정확한 명령(정상 부하 두 건):

```powershell
$h = "scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1"
$common = @("-DispatchMode","mysql","-NormalTimeout","-WorkerCount",16,
            "-MySqlMaxInFlight",64,"-MySqlClaimBatchSize",16,"-MySqlPollInterval","100ms",
            "-LatencySeed",20260920,"-UserCount",1000,"-DrainTimeoutSeconds",600,
            "-WarmupSeconds",30,"-MeasurementSeconds",60,"-RampSeconds",5,"-SteadyGuardSeconds",3,
            "-ResetMySqlVolume")
& $h @common -TargetRps 100 -MySqlClaimTimeout 2500ms -RunId normal-mif64-timeout2500ms-20260926
& $h @common -TargetRps 100 -MySqlClaimTimeout 4s     -RunId normal-mif64-timeout4s-rerun-20260926
```

포화 근처 두 건은 `Invoke-DuplicateSaturationExperiment.ps1`을 MIF/RPS/timeout/RunId 파라미터로
확장해 실행했다(6절). 내부적으로 위와 동일한 `Run-TradeoffExperiment.ps1` 인자를 `-TargetRps 140`,
`-MySqlClaimTimeout 4s`/`2500ms`로 호출한다 — 정상 부하 두 건과 측정창·warm-up 방식이 같다.

**140 RPS 선택 근거.** `docs/MYSQL_JUDGE_POLL_INTERVAL_CAPACITY.md`가 측정한 MIF 64 포화값은
147.963 RPS(09-20, 이전 하네스) / 149.296 RPS(09-25, 현재에 가까운 하네스)다. MIF 16 포화-근접
실험이 포화값의 94.6%(110/116.296)를 썼으므로 같은 비율을 149.296에 적용하면 141.2 RPS다. 140 RPS는
그 근처의 정수값이며, 149.296 대비 93.8%, 147.963 대비 94.6%다. 사후에 RPS를 바꾸지 않았다.

**하네스 추가 기본값 확인.** `-PeakProfile`은 `-NormalTimeout`과 함께 쓸 수 없고(스크립트가
throw), claim batch 자동 스케일(`max(16, worker×4)`)은 `Invoke-PeakProfileMatrix.ps1`에만 있고
`Run-TradeoffExperiment.ps1` 자체의 기본값에는 없다. 네 실행 모두 claim batch는 지정한 16 그대로다.

## 3. 4회 실행 — 유효/무효, 그리고 하네스 결함 하나

**계획한 4회는 모두 유효 결과를 남겼다.** 그런데 그전에 정상 부하 2.5ms 조건(A1)이 같은 이유로
**다섯 번 연속 실패**했고, 원인 규명과 하네스 수정 뒤 여섯 번째 시도가 성공했다. 이 절이 그 경위다.

### 3.1 증상

`normal-mif64-timeout2500ms-20260926`의 시도 1~5가 모두 `"Load-test stack did not become healthy
in five minutes."`로 정확히 5분 17초~5분 31초 사이에 실패했다(`events.json`의
`runStartedAt`/`runEndedAt`):

| 시도 | 시작(UTC) | 종료(UTC) | 경과 |
|---|---|---|---:|
| 1 | 11:51:09 | 11:56:40 | 5m31s |
| 2 | 11:58:26 | 12:03:52 | 5m26s |
| 3 | 12:05:33 | 12:10:50 | 5m17s |
| 4 | 12:12:51 | 12:18:08 | 5m17s |
| 5 | 12:19:51 | 12:25:08 | 5m17s |

시도 4는 시작 약 55초 뒤 수동으로 `docker ps`를 찍어 9개 서비스가 전부 `healthy`인 것을 직접
확인했는데도 **런은 그대로 5m17s에 실패했다.** 이 관측이 핵심 단서였다 — 문제가 "스택이 안 뜬다"가
아니라 뭔가 다른 것이었다.

### 3.2 처음 세운 가설과 기각

시도 1~3 사이에는 호스트 메모리 압박을 의심했다(여유 메모리가 1.24 → 1.75 → 2.86GB로 시도마다
회복되는데도 계속 실패). 유휴 Gradle 데몬(`gradlew --stop`)을 정리해 메모리를 늘렸지만 시도 3도
실패했다. 시도 4가 메모리 여유가 오히려 더 적은 상태(0.7~0.9GB free)에서 컨테이너를 55초 만에
healthy로 올린 뒤에도 똑같이 실패한 것을 보고 이 가설을 기각했다.

### 3.3 실제 원인

`Wait-Healthy`(`Run-TradeoffExperiment.ps1` 약 1140행)는 `docker compose ps -q`가 정확히
**9개**를 돌려줄 때만 건강 검사를 진행한다. 그런데 observability 오버레이
(`compose.observability.yaml`: grafana, prometheus, alertmanager, cadvisor, mysqld-exporter,
nginx-exporter, redis-exporter)가 **같은 compose 프로젝트 이름 `oj-loadtest`** 아래 별도로 떠
있었다 — 이 세션이 시작되기 전부터 5~10시간째 떠 있던 것으로, 언제 누가 띄웠는지는 확인할 수
없었다. `docker inspect`로 `oj-loadtest-mysqld-exporter`의 `com.docker.compose.project` 라벨이
`oj-loadtest`인 것을 확인했다. 이 Compose 버전(v5.3.0)의 `ps`는 `down`과 달리 `-f`로 지정한
서비스만이 아니라 **프로젝트 전체의 컨테이너**(orphan 포함)를 기본으로 나열한다. 그래서
`docker compose -p oj-loadtest -f compose.yaml -f compose.loadtest.yaml ps -q`가 9개가 아니라
16개(9 + 7)를 돌려줬고, `Wait-Healthy`는 아홉 서비스가 전부 healthy여도 개수가 절대 9가 되지
않으므로 5분 내내 기다리다 항상 타임아웃했다. 시도 1~5 전부 이것이었다.

`Get-ContainerStates`(진단용 컨테이너 상태 판독)와 컨테이너 CPU 샘플러의 대상 목록도 같은
가정("프로젝트의 모든 컨테이너 = 부하 테스트 스택")에 기대고 있었다. `docker compose down`은
`-f`로 지정한 두 파일의 서비스만 대상으로 하고(`--remove-orphans` 없음) 다른 곳에서 kill/start/logs를
호출하는 곳은 이미 서비스 이름을 명시하고 있었으므로, 실제로 고쳐야 할 지점은 세 곳(`Wait-Healthy`,
`Get-ContainerStates`, 컨테이너 CPU 샘플러 목록)이었다. `down`이 observability 컨테이너를 내리는지도
확인했다 — 시도 1~5 실패 뒤 매번 `docker ps`로 observability 7개 + `oj-test-mysql`은 그대로 남고
부하 테스트 9개만 정리된 것을 확인했으므로, `down` 자체는 이미 안전했다.

### 3.4 수정과 검증

`scripts/mysql-judge-tradeoff/Run-TradeoffExperiment.ps1`에 아홉 서비스 이름을 담은
`$loadTestServiceNames`를 추가하고, 위 세 곳의 `ps` 호출에 그 목록을 인자로 넘겨 개수와 대상을
부하 테스트 스택으로 한정했다. observability 스택은 건드리지 않았다(내리지도, 세지도 않는다).
수정 후 dry-run 통과를 확인하고 별도 커밋(`fix(loadtest): scope Wait-Healthy's container count to
the load-test stack`)한 뒤, **같은 명령으로 여섯 번째 시도를 실행해 스택이 healthy가 되고 런이
끝까지 진행되는 것을 확인했다** — 이것이 이 문서의 실제 A1 결과다. 실패한 다섯 시도의 디렉터리는
`normal-mif64-timeout2500ms-20260926-attempt{1..5}-failed`로 보존했다.

### 3.5 observability 스택 동거에 대한 기록 (감독자 지적)

observability 스택은 이 배치의 네 유효 실행 내내(그리고 그 전 다섯 번의 실패 시도 내내) 계속
떠 있었다 — 껐다 켜지 않았고, 하네스 수정도 그것을 끄지 않는다. 따라서:

- **이 배치 안의 네 비교(2.5s/4s 정상 부하, 4s/2.5s 포화)는 공정하다.** 네 실행 모두 같은
  observability 오버레이가 같은 조건으로 떠 있었다.
- **기존 09-20/09-25 결과와의 직접 비교는 이 차이를 한계로 가진다.** 그 실행들 때 observability
  스택이 떠 있었는지는 확인할 수 없다(그 문서들에는 기록이 없다). `MYSQL_JUDGE_POLL_INTERVAL_CAPACITY.md`가
  이미 "같은 poll 100ms에서 하네스 차이만으로 MIF 16 포화값이 6% 움직였다"고 기록했듯, 이번 라운드도
  하네스/환경 차이가 있을 수 있다는 뜻이며 그 차이의 크기는 분리해서 측정하지 않았다.
- **호스트 CPU.** `Run-TradeoffExperiment.ps1`의 호스트 CPU 샘플러(`HostCpuSampler.ps1`)는
  `-PeakProfile` 모드에서만 켜진다(`hostCpuSampler = (-not $SkipHostCpu) -and $peakMode`, 807행).
  `-NormalTimeout` 모드에는 이 배치의 네 실행 모두 호스트 CPU가 계측되지 않았다 — 이것이 이
  실험의 실행 전에는 드러나지 않은 계측 공백이다. 대신 두 가지를 남긴다.
  - **컨테이너 CPU 합(대용치).** 하네스가 이미 수집하는 `container-cpu-1s.csv`(cgroup 기준, 부하
    테스트 9개 컨테이너)의 초당 합계 평균/최대(코어, 호스트 12코어 기준):

    | run | 평균 합계 코어 | 최대 합계 코어 |
    |---|---:|---:|
    | normal / 2500ms | 1.686 | 4.562 |
    | normal / 4s | 2.011 | 5.241 |
    | saturation / 4s @140rps | 2.060 | 5.163 |
    | saturation / 2500ms @140rps | 2.024 | 5.006 |

    이것은 observability 스택의 CPU를 포함하지 않으며, Windows 호스트 전체 CPU도 아니다 — Docker
    Desktop WSL2 VM 안에서 cgroup이 본 부하 테스트 컨테이너만의 합이다.
  - **비공식 호스트 CPU 표본.** 실패한 시도들을 진단하던 중 PowerShell `Get-Counter
    '\Processor(_Total)\% Processor Time'`로 두 시점을 한 번씩 쟀다: 21:16(KST) 24.3%, 21:16+2m
    26.35%(호스트 12코어, Windows 작업 관리자 기준). 이는 특정 유효 실행에 귀속된 값이 아니고
    단일 시점 샘플이며, 참고용 하한선 정도로만 읽는다. 이 배치의 네 유효 실행 동안 호스트
    여유 메모리는 시종 빠듯했다(0.7~2.9GB free, 총 15.91GB, Docker Desktop WSL2 VM 한도
    13.65GB) — 이 역시 정밀 계측이 아니라 진단 중 스팟 체크다.

## 4. 표 1 — 정상 부하 100 RPS

기존 09-20 값(2s/4s/10s)은 `MYSQL_JUDGE_NORMAL_TIMEOUT_DUPLICATION.md` 8절 그대로다. 2.5s와
4s(재측정)는 이번 09-26 실행이다. 회계는 세 문서가 공유하는 규칙을 그대로 쓴다: **실제 중복
채점 = 채점 호출 수 − 결과 수 − 실패 수**, 교차 확인은 `stale − republish − failure`. 두 경로는
네 실행 모두 일치했다(`routesAgree = true`, residual 0).

| lease | 출처 | accepted | result RPS | backlog 순증가(rows/s) | durable dup claim | 실제 중복 채점 | stale completion | drain(s) | L_total p95 | L_total p99 | fast p95(비중복) |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 2s | 기존(09-20) | 6543 | 97.983 | -0.2034 | 373 | 163 | 373 | 4.175 | 967.661 | 2360.489 | (기존 문서 미분리) |
| **2.5s** | **신규(09-26)** | 6537 | 97.75 | +0.034 | **0** | **0** | 0 | 1.292 | 531.372 | 2320.077 | 416.39 |
| 4s | 기존(09-20) | 6539 | 98.067 | +0.1186 | 0 | 0 | 0 | 1.587 | 594.680 | 2331.265 | (기존 문서 미분리) |
| **4s** | **재측정(09-26)** | 6542 | 97.8 | -0.136 | 0 | 0 | 0 | 1.196 | 562.194 | 2332.506 | 428.141 |
| 10s | 기존(09-20) | 6549 | 98.233 | -0.0509 | 0 | 0 | 0 | 1.643 | 986.546 | 2342.494 | (기존 문서 미분리) |

**하네스 차이를 4s 재측정으로 분리.** 같은 조건(MIF 64, lease 4s, 100 RPS)을 다른 하네스/환경에서
다시 재면 09-20 594.680ms이던 p95가 09-26에는 562.194ms로 5.5% 낮다. p99는 2331.265 → 2332.506ms로
사실상 같다. drain은 1.587 → 1.196s. result RPS는 98.067 → 97.8로 0.3% 차이. 이 정도가 **환경
차이 하나만으로 생기는 잡음의 크기**이고, 09-26의 2.5s(531.372ms)와 이 4s 재측정(562.194ms)의
차이(5.5%)는 이 잡음 폭 안에 있다 — **2.5s가 4s보다 유의미하게 빠르다고 주장하지 않는다.**

**결론: 정상 부하 100 RPS에서 2.5s는 안전했다.** durable duplicate claim 0, 실제 중복 채점 0,
stale completion 0. attempts histogram은 `1:6537` 하나뿐이다. 이는 기존 4s·10s와 같은 자리이며,
2s(373/163)와는 뚜렷이 다르다. **이 부하 구간에서는 "2.5s에서도 안전하다" 쪽이 관측됐다.**

## 5. 표 2 — 포화 근처 140 RPS

| lease | accepted | result RPS | backlog 시작→종료(rows) | backlog 순증가(rows/s) | durable dup claim | 실제 중복 채점 | stale completion | drain(s) | L_total p95 | L_total p99 | fast p95(비중복) |
|---|---:|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 4s | 9135 | 136.85 | 75 → 62 | -0.2211 | 0 | 0 | 0 | 2.317 | 1264.113 | 2479.342 | 806.175 |
| 2500ms | 9136 | 137.233 | 95 → 64 | -0.5268 | **18** | **7** | 18 | 4.514 | 1316.884 | 2515.29 | 816.129 |

두 조건 모두 `classification = steady`, `backlogNotPersistentlyGrowing = true`,
`integrityPassed = true`, 429/500/503 = 0, `apiRateLimitSuspected = no`. 회계 항등식(둘 다 잔차 0):

```text
2500ms: invocations(9143) + republishes(11) = results(9136) + stale(18) + failures(0)  →  9154 = 9154
4s:     invocations(9135) + republishes(0)  = results(9135) + stale(0)  + failures(0)  →  9135 = 9135
```

**계산상 추가 worker 비용.** 2500ms의 duplicate 7건은 모두 slow(2000ms) 클래스였고 fast(50ms)
클래스의 중복은 0건이다(`latencyClassAccounting.fast.duplicateJudgeExecutions = 0`,
`.slow.duplicateJudgeExecutions = 7`). 기본 profile 비용 대비 추가 비용
(`duplicateJudgeMillisPerUniqueExpectedJudgeMillis`)은 **1.04%**(14,000ms / 1,346,000ms), 4s는
0%다. 실측 invocation 시간 총합 대비로도 2500ms가 1,361,268ms, 4s가 1,347,077ms로 약 1.05% 더
쓴다 — 두 계산이 서로 맞는다. accepted 대비로는 7/9136 = 0.0766%.

**backlog 추세.** 두 조건 모두 measurement window에서 backlog가 줄었다(양수 성장이 아님).
2500ms의 순감소(-0.5268 rows/s)가 4s(-0.2211 rows/s)보다 오히려 크다 — 중복 채점이 있었음에도
backlog가 더 빨리 줄었다는 뜻이며, 이는 처리량 손실의 신호가 아니다. result RPS도 2500ms가
137.233로 4s(136.85)보다 근소하게 높다(+0.28%) — MIF16/1s@110rps에서 본 "회수가 다른 worker에게
넘어가 처리량이 오히려 오르는" 이전 관측과 같은 방향이지만 이번에는 표본이 극히 작다(7건).

**지연.** fast(비중복) cohort의 L_total p95는 4s 806.175ms, 2500ms 816.129ms로 1.2% 차이 — 잡음
폭 안이다. 전체 p95/p99도 각각 4.1%/1.5% 차이로 크지 않다. MIF16/1s@110rps에서 본 fast cohort
p95가 22.9배로 뛰는 것과는 질적으로 다르다 — **이번 중복은 다른 요청의 지연으로 번지지 않았다.**

## 6. 메커니즘 확인

**claim 시각 자체는 남지 않는다.** `ContestJudgeOutboxStore.completeAll`(파일
`src/main/java/my/oj/web/contest/submission/messaging/ContestJudgeOutboxStore.java` 84~120행)은
PUBLISHED/PENDING 전이 시 `claimed_at = NULL`로 지운다. 즉 완료된 행의 `claimed_at`은 끝까지
보존되지 않고, 완료 직후 스냅샷을 뜨더라도 이미 사라진 뒤다. `judge_started_at`은
`contest_submission_result`에 남지만(같은 트리에서 `csr.judge_started_at`으로 조회 가능), 짝이 될
claim 시각이 없으므로 "claim → judge 시작" 로컬 대기 분포를 직접 재구성할 수 없다 — 이는 사전에
예상한 한계였다.

**대체 근거: reserved/running/localWaiting 게이지.**

| run | node | running avg | localWaiting avg | reserved avg |
|---|---|---:|---:|---:|
| normal / 2500ms (100rps) | judge-1 | 8.235 | 1.147 | 9.382 |
| normal / 2500ms (100rps) | judge-2 | 8.353 | 0.382 | 8.735 |
| saturation / 4s (140rps) | judge-1 | 13.294 | 10.412 | 23.706 |
| saturation / 4s (140rps) | judge-2 | 12.75 | 6.309 | 19.059 |
| saturation / 2500ms (140rps) | judge-1 | 13.456 | 10.426 | 23.882 |
| saturation / 2500ms (140rps) | judge-2 | 13.059 | 7.147 | 20.206 |

정상 부하(100 RPS)에서는 `localWaiting`(로컬 큐 대기 중, worker를 아직 못 잡은 claim) 평균이
0.4~1.1로 거의 없다. 포화 근처(140 RPS)에서는 judge-1 기준 10.4, judge-2 기준 6.3~7.1로 **10배
가까이 늘어난다.** `reserved`(claim했지만 아직 완료 안 된 전체)도 9~10 → 20~24로 늘었다. 이는
정확한 대기 시간 분포는 아니지만, "포화 근처일수록 claim 이후 실제 채점 시작까지의 로컬 대기가
길어진다"는 방향과 일치한다 — 대략 `localWaiting / (result RPS / 2)`로 추정하면 judge-1은
10.412 / (137.233/2) × 1000 ≈ 152ms의 평균 추가 대기가 얹힌다는 계산이 나온다. **이것은 정밀한
계측이 아니라 게이지 두 개의 비율로 만든 추정이다.**

**class별 중복 귀속이 더 직접적인 증거다.** 2500ms 조건의 실제 중복 채점 7건은 **전부 slow(2000ms)
클래스였고 fast(50ms) 클래스는 0건**이다. 이는 정확히 가설이 예측한 모양이다 — 2000ms 채점 자체가
lease 2500ms에 근접하고, 거기에 로컬 대기가 조금이라도 얹히면 회수가 일어난다. 4s 조건은 같은
로컬 대기 프로필(`localWaiting` 평균 10.4~10.4, `reserved` 23.7~23.9로 2500ms와 거의 동일)을
가지고도 회수가 0건이었다 — 즉 **로컬 대기 자체의 크기(judge-1 약 10개, judge-2 약 6~7개 큐)는
두 조건이 사실상 같았고, 회수를 가른 것은 lease 값 자체(4s vs 2.5s)였다.** 이는 "채점 시간(최대
2000ms) + 로컬 대기(추정 약 150ms)가 2.5s에 근접하지만 4s에는 크게 못 미친다"는 산수와 맞는다.

**한계.** 개별 claim의 정확한 대기 시간은 계측하지 못했다. `stale-reclaims.csv`의 시각은
`updated_at`(마지막 쓰기 시각)이라 회수가 일어난 시점이 아니라 그 claim이 최종적으로 처리된
시점의 프록시다(기존 두 문서가 이미 지적한 한계와 동일). `claim-attempts.tsv`는 attempts 분포만
주고(2500ms: `1:9118, 2:18`) 시간 정보는 없다.

## 7. 결론

관측에 맞는 문장은 셋 중 어느 하나로 완전히 접히지 않는다. **부하 구간에 따라 답이 다르다.**

- **정상 부하(100 RPS, 포화의 약 67%)에서는 2.5초도 안전했다.** durable duplicate claim 0, 실제
  중복 채점 0 — 기존 4s·10s와 같은 자리다. 이 구간만 보면 "2.5초를 더 줄일 여지가 있었다"는
  방향이 지지된다(다만 이 실험은 2.5초보다 짧은 값을 정상 부하에서 다시 재지 않았다 — 그건
  MIF64의 상한 유도가 8s이므로 2.5s 자체가 이미 상한의 31%인 적극적인 값이었다).
- **포화 근처(140 RPS, 관측 포화값의 약 94%)에서는 2.5초가 안전하지 않았다** — 다만 그 정도가
  작다. durable duplicate claim 18건(accepted의 0.20%), 실제 중복 채점 7건(0.08%), 전부 slow
  클래스. 4s는 같은 부하에서 0건이었다. **"lease는 채점 시간이 아니라 채점 시간 + 로컬 대기로
  정해야 한다"는 방향은 이 표본에서 지지된다** — 로컬 대기(localWaiting 게이지)가 정상 부하보다
  10배 가까이 커진 조건에서만 2.5s와 4s가 갈렸다.
- **그런데 그 비용은 MIF16/1s@110rps처럼 번지지 않았다.** result RPS는 오히려 2500ms가 근소하게
  높았고(+0.28%), backlog는 더 빨리 줄었고(-0.5268 vs -0.2211 rows/s), fast cohort 지연 차이는
  1.2%로 잡음 폭 안이었다. **포화 근처에서도 이 규모(7건)의 중복은 다른 요청의 지연·처리량으로
  측정 가능한 수준으로는 번지지 않았다.**

종합하면: **MIF64에서 2.5초는 "완전히 안전"과 "MIF16/1s급 위험" 둘 다 아니다.** 메커니즘(채점
시간 + 로컬 대기가 lease에 근접하면 회수된다)은 이 표본에서도 재현됐지만, MIF64의 로컬 대기
크기(약 150ms 추정)가 MIF16/1s의 그것(로컬 큐가 아예 없는 조건에서 1s 자체가 짧아 만든 문제와는
질적으로 다르다)보다 훨씬 작아 회수 건수와 그 파급이 둘 다 작았다. **4s는 정상 부하와 포화 근처
모두에서 0건으로 일관됐다** — 운영값 후보로는 4s가 2.5s보다 명백히 더 안전한 여유를 갖는다.

## 8. 알려진 한계

- **각 조건 1회 실행이다.** 7건/18건 같은 작은 수는 반복 없이는 재현성을 주장할 수 없다. 특히
  2500ms@140rps의 7건은 시행마다 크게 흔들릴 수 있는 크기다.
- **호스트 CPU가 이 배치에서 계측되지 않았다**(3.5절). `-NormalTimeout` 모드는 호스트 CPU
  샘플러를 켜지 않는다. 컨테이너 CPU 합과 비공식 스팟 체크만 남겼다.
- **observability 스택이 이 배치 내내 떠 있었다.** 배치 내부 비교는 공정하지만, 09-20/09-25
  기존 결과와의 직접 비교(예: 표 1의 4s 재측정 vs 09-20 4s)에는 이 환경 차이가 하네스 차이와
  분리되지 않은 채 섞여 있다.
- **로컬 대기 시간을 직접 재구성하지 못했다.** `claimed_at`이 완료 시 NULL로 지워지므로(6절)
  claim 시각과 `judge_started_at`을 직접 짝지을 수 없었다. reserved/running/localWaiting 게이지
  비율로 만든 약 150ms 추정은 근사치다.
- **140 RPS는 관측 포화값(147.963~149.296)의 93.8~94.6%다.** 포화값 자체가 하네스에 따라 1%
  안팎으로 움직였으므로(`MYSQL_JUDGE_POLL_INTERVAL_CAPACITY.md`), 140 RPS가 정확히 "포화의
  94.6%"인지는 근사다.
- **부하 생성기와 서버가 같은 물리 머신이다.** 절대 RPS·CPU 수치는 용량이 아니라 조건 표기로만
  읽는다.
- **Wait-Healthy 결함(3절)은 이 실험이 우연히 재현 가능하게 드러낸 것**이지, 이 실험이 의도적으로
  찾은 것이 아니다. observability 스택이 이 세션 시작 전부터 같은 프로젝트 이름으로 떠 있지
  않았다면 드러나지 않았을 결함이다 — 다른 하네스 사용자가 observability 오버레이 없이 실행하면
  재현되지 않는다.

## 9. 포트폴리오

**한 줄 요약.** "MySQL claim 기반 채점에서 lease를 2.5초로 줄이면, 정상 부하에서는 중복이
없었지만 포화의 94%에 가까운 부하에서는 로컬 큐 대기가 채점 시간에 얹혀 slow 작업만 골라
0.08%(9,136건 중 7건) 중복 채점됐다 — 4초는 두 부하 모두에서 0건이었다."

**인용해도 되는 수치.**
- 정상 부하 100 RPS: 2.5s durable duplicate claim 0, 실제 중복 채점 0 (accepted 6537).
- 포화 근처 140 RPS: 2.5s durable duplicate claim 18건(accepted의 0.197%), 실제 중복 채점
  7건(0.077%), 전부 slow(2000ms) 클래스, fast 클래스는 0건.
- 같은 부하에서 4s는 0건 (durable duplicate claim, 실제 중복 채점 모두).
- 포화 근처에서 중복이 있었던 2.5s의 result RPS(137.233)가 중복이 없었던 4s(136.85)보다 근소하게
  높았다 — 이 규모의 중복은 처리량을 깎지 않았다.

**피해야 할 표현.**
- "2.5초는 안전하다"를 정상 부하와 포화 근처를 구분하지 않고 일반화하지 않는다 — 부하 구간에
  따라 다른 결과가 나왔다.
- "MIF64가 MIF16보다 안전하다"를 이 실험만으로 주장하지 않는다 — 로컬 대기 크기가 다르고,
  각 조건 1회라 비교의 통계적 근거가 약하다.
- 140 RPS를 "MIF64의 처리 용량"으로 인용하지 않는다 — 조건 표기일 뿐이다(부하 생성기와 서버가
  같은 머신).
- 7건/18건을 정밀한 재현 가능 수치로 인용하지 않는다 — 각 조건 1회의 표본이다.

## 10. 산출물

- `results/mysql-judge-tradeoff/normal-mif64-timeout2500ms-20260926/`
- `results/mysql-judge-tradeoff/normal-mif64-timeout2500ms-20260926-attempt{1,2,3,4,5}-failed/`
  (Wait-Healthy 결함 진단 증거)
- `results/mysql-judge-tradeoff/normal-mif64-timeout4s-rerun-20260926/`
- `results/mysql-judge-tradeoff/saturation-mif64-timeout4s-rps140-20260926/`
- `results/mysql-judge-tradeoff/saturation-mif64-timeout2500ms-rps140-20260926/`

각 유효 run에는 `parameters.json`, `events.json`, `timeseries.csv`, `latency.csv`, `capacity.csv`,
`container-cpu-1s.csv`, Prometheus/MySQL 스냅샷, Gatling raw 로그, `claim-attempts.tsv`,
`stale-reclaims.csv`, `db-verification.json`, `summary.json`, `summary.md`가 보존돼 있다. 네 유효
run 모두 커밋 `dfe7b09`(Wait-Healthy 수정 포함), `harnessTreeDirty = false`다.
