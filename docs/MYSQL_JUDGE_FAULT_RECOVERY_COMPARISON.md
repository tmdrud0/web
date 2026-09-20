# MySQL judge claim/lease SIGKILL 장애 복구 실험

## 1. 목적과 반증 가능한 가설

앞선 세 라운드는 정상상태만 측정했다. `max-in-flight`(mif)가 정상상태 용량을 결정한다는 것
(MIF16 포화 116.296 RPS, MIF64 포화 147.963 RPS), 정상상태 중복 채점 경계가 MIF16에서
`(2s, 2.5s]`, MIF64에서 `(2s, 4s]`라는 것, 그리고 mif=16/1s가 포화 근처에서 result 처리량을
23.36% 깎는다는 것까지 확인했다. **아직 답이 없는 질문은 judge 노드 하나가 사라졌을 때다.**

이 실험은 정상상태에서 이미 옳다고 확인된 세 후보 — mif=16/2500ms, mif=64/4s, mif=64/10s — 에서
judge-1을 SIGKILL로 죽이고, 그 자리를 같은 설정으로 되살렸을 때 무엇이 얼마나 나빠지고 얼마나
빨리 돌아오는지를 측정한다. 미리 정한 반증 가능한 가설은 다음과 같다.

1. claim timeout이 짧을수록 stale claim이 빨리 회수되므로, 죽은 노드가 들고 있던 작업이
   더 일찍 다시 채점된다. 즉 `T_stale`은 timeout에 대해 단조 증가한다.
2. 짧은 timeout은 영향을 받은 제출의 end-to-end 지연(`L_total`)을 줄인다.
3. max-in-flight가 클수록 장애 순간 미완료로 남는 claim(=`stranded` 상한)이 커지므로
   backlog peak가 커진다.
4. backlog 정상화 시간과 throughput 회복 시간은 down window(15s)와 재기동·readiness 시간에
   지배되고, claim timeout의 기여는 그 위에 더해지는 작은 항이다.
5. 어떤 조건에서도 최종 무결성 chain
   (`accepted == uniqueSubmissions == results == scoreboardApplied`)은 유지된다.

가설과 다른 결과가 나오더라도 조건을 바꾸거나 run을 추가하지 않기로 하고, 세 조건을 각각
**한 번만** 실행했다. 자동 재실행은 없다. 실패도 숨기지 않는다.

**이 실험은 fail-stop 복구 시험이다.** SIGKILL된 프로세스는 채점을 계속하지 않으므로
`attempts > 1`은 **복구를 위한 재claim**이지 동시 중복 CPU 실행이 아니다. 진짜 동시 중복 실행과
fencing은 별도의 `docker pause → timeout 초과 대기 → unpause` 실험이 필요하며, **이번 라운드에서는
실행하지 않고 §17의 후속 후보로만 기록한다.**

## 2. 세 조건 선택 근거

세 조건은 모두 정상상태 라운드에서 "옳다"고 확인된 조합이다. 1s와 2s는 정상상태에서 이미
실제 중복 채점을 만들어 반증됐으므로 이번 실험의 운영 후보로 쓰지 않았다.

| | A | B | C |
|---|---|---|---|
| max-in-flight / node | 16 | 64 | 64 |
| claim timeout | 2500ms | 4s | 10s |
| target RPS | 80 | 100 | 100 |
| 정상상태 근거 | MIF16 중복 0 | MIF64 경계 `(2s,4s]`의 상단 | MIF64 경계 밖 |

**MIF64/4s와 MIF64/10s가 이 실험의 중심이다.** 같은 mif, 같은 offered load이므로 두 run의
차이는 claim timeout 하나에만 귀속된다. 이 짝에서 timeout이 회수 지연·backlog·영향받은 지연에
얼마를 기여하는지를 직접 읽는다.

**MIF16/2500ms는 별도로 해석한다.** offered load가 80 RPS로 MIF64 짝의 100 RPS와 다르므로
raw RPS·raw latency로 우열을 단정할 수 없다. 정규화 지표만 두 그룹 사이에서 읽는다.

MIF16/2500ms를 포함한 이유는 timeout 축과 mif 축을 동시에 보기 위해서다. 2500ms는 두 MIF64
timeout보다 짧으므로, 이 run의 `T_stale`이 가장 짧게 나오는 것이 timeout 축의 예측이고,
`stranded / max-in-flight`가 가장 크게 나오는 것이 mif 축의 예측이다.

## 3. 실행 조건과 명령

| 항목 | 값 |
|---|---|
| dispatch | MySQL direct judge |
| judge node | 2 (`judge-1`, `judge-2`) |
| worker / max-in-flight / claim batch | 노드당 16 / 노드당 16(A) 또는 64(B·C) / 16 |
| poll interval | 100ms |
| latency | 95% 50ms / 5% 2000ms |
| seed / key | `20260920` / `code` |
| warm-up | 별도 contest, 30s hold, 완전 quiescence 확인 후 measurement baseline |
| measurement | 5s ramp + 3s guard + **고정 300s window** (hold 303s) |
| fault 대상 / 신호 | `judge-1` / SIGKILL |
| down duration | 15s (설정), `restartRequestedAt - faultInjectedAt`로 실측 |
| 재기동 | 동일 설정으로 `judge-1` 재시작 |
| fault 중 부하 | 계속 유지 |
| 관측 | backlog 정상화 후 ≥30s 추가 관측 |
| drain timeout | 600s |
| 실행 횟수 | 조건별 1회 |

세 run의 실측 설정은 다음과 같다.

| | A | B | C |
|---|---|---|---|
| RunId | `fault-mif16-timeout2500ms-rps80-20260920` | `fault-mif64-timeout4s-rps100-rerun1-20260920` | `fault-mif64-timeout10s-rps100-20260920` |
| matrix상 조건 id | `fault-mif16-timeout2500ms-rps80-20260920` | `fault-mif64-timeout4s-rps100-20260920` | `fault-mif64-timeout10s-rps100-20260920` |
| mif / timeout / RPS | 16 / 2500ms / 80 | 64 / 4s / 100 | 64 / 10s / 100 |
| warm-up contest / measurement contest | 48 / 49 | 52 / 53 | 54 / 55 |

실행 명령은 다음 두 개다. 첫 번째가 세 조건 전부를 실행하는 원래 명령이고, 두 번째가 중단된
B를 새 RunId로 다시 돌리면서 A는 이미 완료된 디렉터리를 재사용하게 한 명령이다.

```powershell
# 1차: 세 조건 전부 (A 완료, B는 14:35에 호스트 메모리 압박으로 중단)
.\scripts\mysql-judge-tradeoff\Invoke-FaultRecoveryMatrix.ps1

# 2차: B와 C만 실행, A는 기존 디렉터리 재사용
.\scripts\mysql-judge-tradeoff\Invoke-FaultRecoveryMatrix.ps1 `
  -Condition fault-mif64-timeout4s-rps100-20260920,fault-mif64-timeout10s-rps100-20260920
```

driver는 실행하지 않은 조건을 기존 디렉터리에서 재사용하고, 비교가 세 조건을 모두 덮는지
확인한 뒤에만 비교 스크립트를 호출한다. 재사용한 디렉터리는
`fault-recovery-matrix-outcomes.json`의 `reusedDirectories`에 기록된다. 이번에는 A가 여기 해당한다.
**재사용한 run을 다시 측정한 것처럼 표현하지 않는다** — A는 1차에서, B와 C는 2차에서 측정됐다.

실행 전 검증과 dry-run은 `Run-TradeoffExperiment.ps1`, `Analyze-TradeoffRun.ps1`,
`Compare-FaultRecoveryRuns.ps1`, `Invoke-FaultRecoveryMatrix.ps1`에 대해 통과시켰고, 실제 경로는
§19의 smoke run들로 태웠다.

## 4. Fault trigger와 fault 순간의 실제 active work

고정 시각만 보고 kill하지 않았다. measurement 시작 + 30s 이후에 trigger window를 열고,
`judge-1`의 executor gauge를 polling하여 `running >= 1 and reserved >= 4`를 최대 15s 기다린 뒤에만
주입했다. 세 run 모두 primary 조건에서 즉시 충족됐으므로 fallback으로 내려가지 않았다.

| | A | B | C |
|---|---|---|---|
| window 열린 시각 | 05:25:56.207 | 05:45:44.969 | 05:53:54.294 |
| 조건 관측 시각 | 05:25:56.234 | 05:45:44.973 | 05:53:54.573 |
| 대기 | 0.027s | 0.004s | 0.279s |
| escalation | `reserved>=4` | `reserved>=4` | `reserved>=4` |
| judge-1 running / reserved / queued | **4.0 / 4.0 / 0.0** | **9.0 / 9.0 / 0.0** | **8.0 / 8.0 / 0.0** |
| judge-2 running / reserved / queued | 3.0 / 3.0 / 0.0 | 15.0 / 15.0 / 0.0 | 16.0 / 16.0 / 0.0 |
| active work 관측 | yes | yes | yes |
| `faultNotInjectedWithActiveWork` | false | false | false |

세 run 모두 kill 순간 실제로 실행 중인 채점이 있었다. A는 judge-1이 4/4, judge-2가 3/3이었고,
B와 C는 judge-1이 9/9·8/8, judge-2가 15/15·16/16이었다. mif=64인 B·C의 15·16은 mif에 대해
포화가 아니며, kill 시점 judge-2의 per-poll claim 여유(`mif − reserved`)는 A 13, B 49, C 48이었다.
세 run 모두 조건은 "죽은 judge-1의 몫을 살아남은 judge-2 하나가 이어받는다"로 같다.

gauge는 Micrometer가 정수값을 `"4.0"`처럼 소수점을 붙여 렌더하므로 Float 스타일로 파싱한다.
읽지 못한 gauge는 0이 아니라 `null`로 남긴다(`thresholdReadings.basis`).

## 5. Fault/restart 타임라인

14개 타임스탬프 전부. 시각은 UTC다. `firstPostFaultResultAt`은 analyzer가 `latency.csv`에서,
나머지는 harness가 기록했다. analyzer 유도 값은 harness 유도 값과 별개로 계산되며, 두 값이
일치하는지는 `throughput recovery` 표의 agreement 열이 말한다.

| 타임스탬프 | A (16 / 2500ms / 80) | B (64 / 4s / 100) | C (64 / 10s / 100) | 출처 |
|---|---|---|---|---|
| `measurementStartedAt` | 05:25:26.2070000 | 05:45:14.9690000 | 05:53:24.2940000 | harness |
| `faultScheduledAt` (window open) | 05:25:56.2070000 | 05:45:44.9690000 | 05:53:54.2940000 | harness |
| `faultInjectedAt` | 05:25:57.8897911 | 05:45:46.1815071 | 05:53:56.0905446 | harness |
| `restartScheduledAt` | 05:26:12.8897911 | 05:46:01.1815071 | 05:54:11.0905446 | harness |
| `restartRequestedAt` | 05:26:12.8988048 | 05:46:01.1905003 | 05:54:11.4925826 | harness |
| `containerRunningAt` | 05:26:14.1813660 | 05:46:02.4228081 | 05:54:12.8701746 | harness |
| `nodeReadyAt` | 05:26:47.9871521 | 05:46:38.7752296 | 05:54:44.2571353 | harness |
| `firstStaleObservedAt` | 05:26:00.6021875 | 05:45:52.4196981 | 05:54:05.7927285 | harness |
| `firstPostFaultResultAt` | 05:25:57.9853560 | 05:45:46.2410070 | 05:53:56.1197960 | analyzer |
| `throughputRecoveredAt` | 05:26:50.4960569 | 05:46:40.0660619 | 05:54:44.7713906 | analyzer |
| `backlogNormalizedAt` | 05:27:28.4898031 | 05:47:05.0864214 | 05:55:09.3361981 | analyzer |
| `lastReclaimedSubmissionResultAt` | 05:26:01.6956410 | 05:45:52.5459420 | 05:54:07.9689260 | analyzer |
| `lastReclaimedSubmissionScoreboardAt` | 05:26:01.8025590 | 05:45:52.5752010 | 05:54:08.0101480 | analyzer |
| `drainCompletedAt` | 05:30:29.3436131 | 05:50:18.5453768 | 05:58:27.5212334 | harness |

**down window는 `restartRequestedAt - faultInjectedAt`이다.**

| | A | B | C |
|---|---|---|---|
| 실측 down window | 15.009s | 15.009s | **15.402s** |
| 설정값 | 15s | 15s | 15s |
| 오차 | +0.009s | +0.009s | **+0.402s** |
| `restartTimingErrorSeconds` | 0.009 | 0.009 | **0.402** |
| harness 자체 assertion (`≤ 0.5s`) | 통과 | 통과 | 통과 |

C의 down window는 설정보다 0.402s 길다. harness의 자체 허용치(0.5s) 안이지만 A·B의 0.009s보다
45배 크므로 반올림해서 지우지 않고 그대로 보고한다. 그 0.402s가 생긴 자리는 관측이 **시작되지
않도록** 떼어낸 마지막 2초이고, 그 구간에서 시계를 쥐고 있는 것은
`Run-TradeoffExperiment.ps1:1200`의
`Wait-UntilDeadline` 하나뿐이다. 이 함수는 I/O를 전혀 하지 않고 `Start-Sleep`을 200ms 이하
조각으로 나눠 deadline까지만 기다린다. 다만 guard가 막는 것은 관측의 **시작**이므로 deadline
직전에 시작한 관측은 그 구간으로 넘어올 수 있다. 아래 "관측 수집이 deadline을 지연시키지 않게
분리한 구조"가 원시 자료로 확인한 결과 **C에서는 실제로 그렇게 됐다** — 마지막 관측의 기록
시각이 deadline을 0.400s 넘겼고 재기동 요청은 그 0.002s 뒤였다. 초과분은 조용히 흡수되지 않고
`restartTimingErrorSeconds`로 기록되고 assertion에 걸린다.

**초과 방향이 중요하다.** down window가 길어지면 C의 from-fault 복구시간도 그만큼 길어진다.
즉 이 0.402s는 §13에서 10s가 보이는 우위를 **만들지 않고 오히려 0.393s 과소평가**한다.

**`containerRunningAt`과 `nodeReadyAt`을 같은 값으로 쓰지 않는다.** node readiness gate는
네 조건을 모두 요구한다: (a) 컨테이너 running, (b) `/actuator/health/readiness` UP,
(c) `/actuator/prometheus` scrape 성공, (d) `contest_judge_claim_calls_total`이 재시작 후 증가.
judge 컨테이너의 compose healthcheck는 `grep -aq java /proc/1/cmdline`이라 프로세스 생존만
보므로 readiness gate로 쓰지 않았다.

| gate | A | B | C |
|---|---|---|---|
| container → readiness | 32.180s | 34.510s | 29.838s |
| readiness → metrics | 0.543s | 0.633s | 0.523s |
| metrics → dispatcher 활성 | 1.083s | 1.209s | 1.026s |
| claim calls (첫 scrape → 활성) | 1 → 5 | 4 → 7 | 4 → 8 |
| `containerRunningAt` ≠ `nodeReadyAt` | 예 (33.806s 차) | 예 (36.352s 차) | 예 (31.387s 차) |

**재기동 시간의 대부분은 컨테이너가 뜬 뒤 readiness가 UP될 때까지다**(29.8–34.5s). 이 구간은
claim timeout도 max-in-flight도 지배하지 않는다. §13의 재영점 분석이 이 사실에 기대고 있다.

### 관측 수집이 deadline을 지연시키지 않게 분리한 구조

down window의 권위 있는 시계는 `Wait-UntilDeadline` 하나뿐이고, 이 함수는 I/O도 표본도 하지
않는다(`Run-TradeoffExperiment.ps1:1894-1922`). down 구간에서도 표본과 관측은 계속 돌지만
(관측 1회가 1초보다 오래 걸려 실제 간격은 2.7–4.1s다)
**마지막 2초는 관측에서 떼어낸다**: `downObserveUntil = restartScheduledAt - 2s` 이후에는 그
분기가 표본도 읽기도 하지 않고 deadline만 기다린다. 실제 down window 관측 횟수는 세 run 모두
4회이고(`events.json`의 `downWindowObservationCount`), 이는 `backlog.csv`의 `node-down` 행 수와
일치한다.

**관측이 시작될 수 있는 시각만은 `downObserveUntil`로 유계다.** 그 분기에 들어가는 조건이
`$now -lt $downObserveUntil`이고(`1896`), `$now`는 매 반복의 최상단에서 새로 읽는다(`1798`).
kill 이후에는 그보다 앞에서 I/O를 하는 분기(A의 trigger 평가, B의 주입)가 모두 닫혀 있으므로
관측 시작 시각은 그 반복의 `$now`와 같다. 주입 반복만 예외다 — 그 반복의 `$now`는 kill
**이전에** 읽혔으므로 guard가 열려 있고, `fault` 행을 찍는 관측에 이어 같은 반복에서 곧바로
`node-down` 관측이 시작한다(`fault` 행의 기록 시각은 fault+2.413s(A)·+1.871s(B)·+2.424s(C)).
`node-down` phase로 기록되는 행은 `Observe-FaultRecovery`의 `Save-BacklogSample`
하나뿐이므로, **`downObserveUntil` 이후에 시작한 관측은 존재할 수 없다.**

**그러나 유계되는 것은 시작 시각뿐이고, 관측은 deadline을 넘겨 끝났다.** `Save-BacklogSample`은
두 COUNT 질의가 끝난 뒤에 행을 찍으므로(`615-628`) 기록된 시각은 그 관측의 **끝에 가까운**
시각이다. 세 run의 down 구간 관측 시각은 `faultInjectedAt` 기준으로 아래와 같고,
`downObserveUntil`은 세 run 모두 13.0s, `restartScheduledAt`은 세 run 모두 15.0s다.

| | 마지막 관측 | `downObserveUntil` | 마지막 관측 → 재기동 요청 | `restartTimingErrorSeconds` |
|---|---:|---:|---:|---:|
| A | 14.791s | 13.0s | 0.218s | **+0.009s** |
| B | 14.501s | 13.0s | 0.508s | **+0.009s** |
| C | **15.400s** | 13.0s | **0.002s** | **+0.402s** |

세 run 모두 마지막 관측이 `downObserveUntil`을 넘겨 끝났다. 그 행이 존재한다는 사실 자체가
그 관측이 `downObserveUntil` **이전에 시작했다는 증거이면서 동시에 deadline 뒤까지 걸쳤다는
증거다** — 앞의 유계는 시작에만 걸리므로, 관측 1회의 비용이 남은 시간보다 크면 그 관측은
그대로 deadline 너머로 흘러간다.

A·B에서는 마지막 관측이 deadline 전에 끝나 남은 시간을 `Wait-UntilDeadline`이 흡수했고 초과는
두 run 모두 정확히 0.009s다. C에서는 마지막 관측의 행이 이미 deadline을 0.400s 지난 시각에
찍혔고 재기동 요청은 그 0.002s 뒤다. 그 0.002s 안에 들어가는 것은 attempts poll(이미
`firstStaleReclaimObservedAt`이 설정돼 생략된다, `630-642`)과, 이미 지나버린 deadline을 향한
`Wait-UntilDeadline` 두 번(그 관측 반복의 `$sliceEnd`와 다음 반복의 `restartScheduledAt`, 둘 다
즉시 반환)뿐이다. 즉 **C의 0.402s 초과는 관측이 deadline을 밀어낸 결과다.** 앞선 판본은 이것을
(i) 관측이 deadline을 넘겨 끝난 경우와 (ii) 대기 조각이 늦게 돌아온 경우로 나누고 원시 자료로
구분할 수 없다고 적었으나, 마지막 관측의 시각이 기록되어 있으므로 C는 (i)로 확정된다.

따라서 하네스가 `db-verification.json`의 `downWindowObservationBasis`에 스스로 기록한 문장
— "the sampler is stopped between faultInjectedAt and restartScheduledAt; only the two-count
backlog observation runs, and only while more than 2s remain, so the restart deadline is never
behind an observation" — 은 **이 실행에서 반증됐다.** down 구간에서 sampler는 멈추지 않았다
(세 run 모두 `recovery-samples.csv`에 그 구간 표본이 4개씩 남아 있고, 마지막 표본은
fault+12.032s(A)·+11.763s(B)·+12.508s(C)다 — 빠지는 것은 설계대로 마지막 2초뿐이다).
그리고 deadline은 실제로 관측 뒤에 있었다. 이 문장의 구절 중 "sampler가 멈춘다"와 "두 COUNT
관측만 돈다"(같은 주장의 두 표현이다) 그리고 "deadline이 관측 뒤에 오지 않는다"는 틀렸고,
"2초 넘게 남았을 때만"만 실제 동작과 맞다.
이 문장은 측정값이 아니라 하네스의 자기 기술이므로 위 표가 그 자리를 대신한다.

참고로 **down 구간의 관측 간격은 1초가 아니다.** 기록된 관측 행 사이의 간격은 `fault` 행에서
첫 `node-down` 행까지가 A 4.148 / B 4.093 / C 4.001s이고, 그 뒤 `node-down` 행 사이가
A 2.715·2.754·2.760s, B 3.082·2.715·2.740s, C 3.043·3.036·2.895s다. 루프는 관측 뒤에
`Wait-UntilDeadline`으로 그 반복의 `$now + 1s`를 기다리므로, 그 반복의 작업(1초 tick 표본과
두 COUNT 관측)이 1초보다 오래 걸리면 대기가 즉시 반환되고 **주기가 작업 시간에 묶인다.**
위 간격은 그 결과다. §13의 `T_stale` 판정과 §20의 해상도 한계가 이 값을 쓴다.

kill 시점 관측은 `faultInjectedAt` **이후**에 시작한다. `killToSnapshotStartSeconds`가 양수라는
것이 그 순서의 증거이며, A 0.606s / B 0.478s / C 0.625s였다.

## 6. 무결성과 correctness

chain은 `accepted == uniqueSubmissions == results == scoreboardApplied`다. 네 개를 하나의
비교로 평가하는 이유는 셋만 확인하고 넷째를 놓치는 일을 막기 위해서다.

| | A | B | C |
|---|---|---|---|
| accepted | 24378 | 30464 | 30465 |
| uniqueSubmissions | 24378 | 30464 | 30465 |
| results | 24378 | 30464 | 30465 |
| scoreboardApplied | 24378 | 30464 | 30465 |
| completed HTTP requests | 24368 | 30453 | 30454 |
| chain 유지 | yes | yes | yes |
| `lostOrIncomplete` | 0 | 0 | 0 |
| `finalResultMismatch` | 0 | 0 | 0 |
| `integrity.passed` | yes | yes | yes |
| HTTP 429 / 500 / 503 | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 |
| `runValidForRecovery` | true | true | true |
| `recoveryTimeout` | false | false | false |
| gate assertion 5종 | 전부 통과 | 전부 통과 | 전부 통과 |

completed HTTP requests가 accepted보다 10~11건 적은 것은 Gatling maxDuration 시점에 아직
비행 중이던 요청이 클라이언트 로그 종료 뒤에 완료될 수 있기 때문이며,
`completedHttpRequests`는 별도로 보고된다(`unavailable[]`에 사유 기록).

**warm-up이 measurement 집계에 섞이지 않았다.** warm-up과 measurement는 별도 contest다.

| | A | B | C |
|---|---|---|---|
| warm-up contest / measurement contest | 48 / 49 | 52 / 53 | 54 / 55 |
| warm-up accepted = results = scoreboard | 2557 | 3155 | 3113 |
| quiescence 근거 | outbox unfinished 0, judge reserved 0 | 같음 | 같음 |
| `warmupQuiescenceSeconds` | 7.904s | 6.555s | 7.856s |
| quiescence 후 accepted 증가 (`acceptedGrowthAfterBaseline`) | 0 | 0 | 0 |

warm-up accepted의 증가가 0이라는 것은 baseline 시점 이후 warm-up contest에 새 작업이
들어오지 않았다는 뜻이고, outbox unfinished 0 / judge reserved 0은 그 contest의 작업이 완전히
끝났다는 뜻이다. measurement는 그 뒤에 시작된 별도 contest이므로 warm-up 작업은 모든 cohort
밖에 있다.

**drain**은 부하가 멈춘 뒤 backlog가 0이 될 때까지 기다린 loop이며 A 1.648s / B 1.647s / C 1.583s로
전부 성공했다. drain은 부하 정지 후의 대기이므로 위 타임라인의 어떤 값과도 같은 축이 아니다.

## 7. Cohort별 지연

cohort는 측정 contest를 fault 기준으로 분할한다. 이 run들에는 측정 창 안에 kill이 있으므로
**어떤 창도 service time이 아니다.** analyzer는 그 이유로 run-level `measurement-steady` cohort를
`unavailable`로 표시하고, 아래 cohort들이 그 자리를 대신한다.

| cohort | 정의 |
|---|---|
| A `pre-fault-steady` | `faultInjectedAt - 30s` ~ `faultInjectedAt - 5s` |
| B `fault-down-arrivals` | `faultInjectedAt` ~ `nodeReadyAt` |
| C `reclaimed-after-fault` | fault 후 durable `attempts > 1`이 된 제출 (제출 시각으로 필터하지 않는다) |
| D `post-restart-recovery` | `nodeReadyAt` ~ `backlogNormalizedAt` |
| E `post-recovery-steady` | `backlogNormalizedAt + 10s`부터 측정 부하 종료까지 |
| F `all` | 측정 contest 전체 — 무결성과 최종 drain 전용, A–E를 희석하지 않는다 |

**cohort C의 정의를 정확히 적는다.** 이 cohort는 durable `attempts` 열 **하나로만** 만들어지며,
kill snapshot의 submission 목록은 쓰지 않는다. snapshot은 `claimed_by`가 없어 모든 노드의
in-flight 행을 담으므로 그것을 cohort로 삼으면 살아남은 노드의 평범한 작업이 죽은 노드의
cohort에 섞인다. snapshot의 행 수는 cohort 옆에 **상한**으로만 둔다.
또한 **제출 시각으로 필터하지 않는다.** kill이 고아로 만든 행은 kill **이전**에 제출됐으므로
`submittedAt >= faultInjectedAt`을 요구하면 정작 kill이 버린 행을 전부 떨어뜨린다.
`Analyze-TradeoffRun.ps1`의 cohort C 주석이 그 이유를 그대로 적고 있다.

이 구분은 artifact의 guard 필드로 확인된다: `reclaimedRowsStrandedByTheKill`이 A 3 / B 20 / C 13,
`reclaimedRowsSubmittedDuringOrAfterTheFault`가 **A 0 / B 0 / C 0**, `staleAttemptsBeforeFault`가
**A 0 / B 0 / C 0**이다. 즉 아래 cohort C의 n은 전부 kill이 고아로 만든 행이고, fault 이전에
회수된 행은 하나도 없다. (참고로 analyzer의 `reclaimSplitBasis`는 두 절반을 "보고서에서 합치지
않는다"고 적지만 cohort C의 지연은 합쳐진 목록 위에서 계산된다. 이 run들에서는 after-fault
절반이 0이라 수치 차이가 없다.)

### A–E

| run | cohort | n | `L_total` p50/p95/p99/max ms | >5s | >10s |
|---|---|---:|---|---:|---:|
| A | A pre-fault-steady | 1993 | 310.457/2119.712/2349.964/2451.374 | 0% | 0% |
| A | B fault-down-arrivals | 3997 | 7392.645/**12264.127**/**13506.457**/14376.874 | 80.06% | 39.054% |
| A | C reclaimed-after-fault | 3 | 4862.38/**4949.09**/**4949.09**/4949.09 | 0% | 0% |
| A | D post-restart-recovery | 3228 | 5242.264/9795.365/10110.168/12029.989 | 52.231% | 2.664% |
| A | E post-recovery-steady | 13416 | 312.29/2086.22/2337.392/2649.898 | 0% | 0% |
| B | A pre-fault-steady | 2497 | 325.193/729.642/2334.194/2535.163 | 0% | 0% |
| B | B fault-down-arrivals | 5240 | 6210.496/**10631.99**/**11777.414**/13172.408 | 65.859% | 13.874% |
| B | C reclaimed-after-fault | 20 | 5059.315/**6765.233**/**6954.923**/6954.923 | 100% | 0% |
| B | D post-restart-recovery | 2619 | 4583.986/8425.369/8724.61/10600.05 | 45.514% | 0.191% |
| B | E post-recovery-steady | 17984 | 325.331/754.891/2328.478/2614.348 | 0% | 0% |
| C | A pre-fault-steady | 2493 | 355.521/2111.894/2379.615/2563.739 | 0% | 0% |
| C | B fault-down-arrivals | 4790 | 6933.105/**10160.386**/**11339.823**/12832.885 | 80.042% | 8.038% |
| C | C reclaimed-after-fault | 13 | 12627.264/**13008.992**/**13008.992**/13008.992 | 100% | **100%** |
| C | D post-restart-recovery | 2511 | 4533.109/8290.526/8470.361/10393.093 | 42.015% | 0.398% |
| C | E post-recovery-steady | 18468 | 318.965/718.863/2325.923/2679.421 | 0% | 0% |

**percentile 표기 규칙.** nearest-rank 방식이므로 **n ≤ 100인 cohort에서 p99는 최대 관측값이고
p95는 n이 작아질수록 p99에 접근한다.** C cohort는 n이 3/20/13이므로 그 cohort의 p95/p99/max가
같은 값인 것은 정상이며, 네 개의 서로 다른 관측을 뜻하지 않는다. 반대로 cohort A는 세 run
모두 p95가 약 2100ms 근처에 있는데, 이는 5% slow(2000ms) 꼬리가 p95 위로 올라오는 결정적
latency 분포의 성질이다.

### 같은 cohort의 `L_result` (제출 → result 저장)

| run | cohort | n | `L_result` p50/p95/p99/max ms |
|---|---|---:|---|
| A | A pre-fault-steady | 1993 | 231.138/2054.802/2252.902/2321.408 |
| A | B fault-down-arrivals | 3997 | 7323.005/12180.063/**13403.186**/14237.524 |
| A | C reclaimed-after-fault | 3 | 4816.13/**4872.276**/**4872.276**/4872.276 |
| A | D post-restart-recovery | 3228 | 5160.837/9703.322/10036.087/11964.652 |
| A | E post-recovery-steady | 13416 | 238.374/2054.994/2252.329/2403.699 |
| B | A pre-fault-steady | 2497 | 237.258/421.946/2248.771/2334.249 |
| B | B fault-down-arrivals | 5240 | 6140.943/10519.612/**11664.371**/13069.346 |
| B | C reclaimed-after-fault | 20 | 5023.147/**6743.498**/**6925.664**/6925.664 |
| B | D post-restart-recovery | 2619 | 4507.159/8330.906/8632.732/10523.735 |
| B | E post-recovery-steady | 17984 | 243.656/632.849/2250.592/2528.362 |
| C | A pre-fault-steady | 2493 | 252.486/2055.465/2264.896/2540.262 |
| C | B fault-down-arrivals | 4790 | 6865.831/10045.727/**11256.778**/12615.56 |
| C | C reclaimed-after-fault | 13 | 12544.118/**12967.77**/**12967.77**/12967.77 |
| C | D post-restart-recovery | 2511 | 4461.348/8208.223/8379.516/10316.09 |
| C | E post-recovery-steady | 18468 | 240.989/647.737/2245.224/2607.022 |

### 같은 cohort의 `L_scoreboard` (result 저장 → scoreboard 반영)

| run | cohort | n | `L_scoreboard` p50/p95/p99/max ms |
|---|---|---:|---|
| A | A pre-fault-steady | 1993 | 73.598/133.972/175.168/246.279 |
| A | B fault-down-arrivals | 3997 | 72.084/118.294/146.932/465.198 |
| A | C reclaimed-after-fault | 3 | 76.814/106.918/106.918/106.918 |
| A | D post-restart-recovery | 3228 | 75.216/118.552/136.255/154.858 |
| A | E post-recovery-steady | 13416 | 71.396/113.817/146/532.982 |
| B | A pre-fault-steady | 2497 | 82.077/151.197/313.334/361.366 |
| B | B fault-down-arrivals | 5240 | 73.523/130.805/182.111/576.433 |
| B | C reclaimed-after-fault | 20 | 54.825/94.571/96.944/96.944 |
| B | D post-restart-recovery | 2619 | 80.65/139.14/164.678/189.786 |
| B | E post-recovery-steady | 17984 | 74.735/128.089/173.521/452.931 |
| C | A pre-fault-steady | 2493 | 91.928/190.715/252.581/317.954 |
| C | B fault-down-arrivals | 4790 | 74.73/136.218/192.443/608.13 |
| C | C reclaimed-after-fault | 13 | 42.16/96.964/96.964/96.964 |
| C | D post-restart-recovery | 2511 | 80.395/142.632/167.974/194.098 |
| C | E post-recovery-steady | 18468 | 73.395/123.05/158.151/362.71 |

**`L_scoreboard`은 어느 cohort에서도 수백 ms 규모다.** 즉 이 outage에서 scoreboard 반영
자체는 병목이 아니었다. outage의 지연은 `L_result`에 거의 전부 실려 있고, `L_total`과
`L_result`가 거의 같은 값인 것도 같은 사실의 다른 표현이다(예: C의 C cohort에서
`L_total` p50 12627.264ms 대 `L_result` p50 12544.118ms, 차이 83ms).

cohort E는 세 run 모두에서 `available`이며, 정상화 후 실제로 측정 부하가 남아 있었다.

| | A | B | C |
|---|---|---|---|
| E 창 시작 | 05:27:38.4898031 | 05:47:15.0864214 | 05:55:19.3361981 |
| E 실제 길이 / 표본 span | 170.546s / 170.538s | 183.142s / 182.161s | 187.895s / 187.888s |
| E 표본 수 | 172 | 184 | 190 |
| 요구 최소 길이 | 20s | 20s | 20s |

cohort C의 n이 3/20/13으로 작은 것은 이 cohort의 **정의** 때문이다: outbox row가 lease 만료 후
다시 나간 제출만 들어온다. 100 RPS에서도 20건 남짓이므로 이 cohort의 percentile은 모집단
percentile이 아니라 "그렇게 남은 소수의 관측"으로 읽어야 한다.

### F — 측정 contest 전체

| run | cohort | n | `L_total` p50/p95/p99/max ms | 용도 |
|---|---|---:|---|---|
| A | all | 24378 | 356.521/10456.705/12197.744/14376.874 | 무결성 + 최종 drain |
| B | all | 30464 | 360.562/9181.735/10550.986/13172.408 | 무결성 + 최종 drain |
| C | all | 30465 | 355.257/8843.718/10114.798/13008.992 | 무결성 + 최종 drain |
| A | post-fault-arrivals | 21447 | 365.423/10771.713/12239.8/14376.874 | outage 단일 수치 |
| B | post-fault-arrivals | 26834 | 367.511/9506.304/10612.037/13172.408 | outage 단일 수치 |
| C | post-fault-arrivals | 26780 | 357.37/9014.373/10133.859/12832.885 | outage 단일 수치 |

F의 percentile은 pre-fault 정상상태·outage·회복·drain을 한 분포에 섞은 값이므로 A–E를 대체하지
않는다. 세 run 모두 p50이 356~361ms인데 p95가 8844~10457ms인 것은 outage 구간이 분포의
꼬리가 아니라 큰 두 번째 덩어리로 들어와 있기 때문이다.

`killed-node-claimed` cohort는 세 run 모두 `unavailable`이다. outbox에 `claimed_by`가 없으므로
"judge-1이 들고 있던 제출"을 cohort로 만들 수 없다.

## 8. Fast/slow 분리

cohort B(fault-down-arrivals)를 제출 자체의 결정적 latency class로 나눈다. **fast 절반이 이
비교에서 가장 예리한 읽기다.** fast 제출은 기본 service time 외에 아무것도 필요하지 않았으므로,
기본값을 넘는 초과분은 전부 outage와 회복이다.

| run | class | n | `L_result` p50/p95/p99/max ms | >5s | >10s |
|---|---|---:|---|---:|---:|
| A | fast | 3770 | 7020.45/12073.4/**12301.277**/13554.681 | — | — |
| A | slow | 227 | 9822.271/13969.55/14223.726/14237.524 | — | — |
| A | whole | 3997 | 7323.005/12180.063/13403.186/14237.524 | 80.06% | 39.054% |
| B | fast | 4969 | 6057.049/10375.916/**11084.499**/11402.155 | — | — |
| B | slow | 271 | 8585.387/12315.346/12632.591/13069.346 | — | — |
| B | whole | 5240 | 6140.943/10519.612/11664.371/13069.346 | 65.859% | 13.874% |
| C | fast | 4537 | 6762.415/9959.545/**10779.148**/11101.216 | — | — |
| C | slow | 253 | 8961.942/11832.599/12141.134/12615.56 | — | — |
| C | whole | 4790 | 6865.831/10045.727/11256.778/12615.56 | 80.042% | 8.038% |

fast cohort의 `L_result` p99는 A 12301ms / B 11084ms / C 10779ms다. **A와 C의 차이는 약
1.5초인데 A는 80 RPS, C는 100 RPS를 받았다.** fast 제출조차 outage 길이(15s)에 가까운 지연을
보이므로, 이 cohort의 지연은 judge 작업 시간이 아니라 대기 시간이다.

slow 절반은 자기 자신의 2000ms 꼬리를 갖고 있어 outage에 훨씬 둔감하다. slow의 p50이 fast의
p50보다 2.2~2.8초 크지만(A 2801.8ms / B 2528.3ms / C 2199.5ms), p95 차이는 1.87~1.94초로 더
작다(A 1896.1 / B 1939.4 / C 1873.1ms). **이 p95 차이는 outage의 결과가 아니다.** 같은 run의
fault 이전 정상 구간에서 이미 class 간 차이는 p50/p95/p99 모두 약 1.94~1.97초이고
(A 1969.3 / 1962.5 / 1938.3ms, B 1950.1 / 1939.9 / 1957.1ms, C 1940.8 / 1943.3 / 1878.7ms),
outage가 p95 차이를 바꾼 양은 −66.4 / −0.4 / −70.2ms다. 즉 p95 차이는 outage 전후 모두
latency class 자체의 상수 오프셋(약 1.9초)이다.

반대로 p50 차이는 outage에서 **커진다**(+832.5 / +578.2 / +258.7ms). 두 class는 짝지어지지
않은 채 각자 자기 percentile을 읽으므로, 같은 percentile이라도 두 class가 서로 다른 queue
깊이에서 읽힌다. p50 차이의 증가는 그 표본화 효과이지 두 분포가 꼬리에서 접근한다는 뜻이
아니다.

**p99는 step-valued다.** latency가 결정적으로 50ms와 2000ms 두 class만 나오므로, 한 class
안의 차이는 같은 양의 judge 작업이다. §13은 이 성질을 근거로 407ms 차이에 우열을 선언하지
않는다.

## 9. Result·scoreboard throughput과 backlog 시계열

### throughput 회복 규칙

회복은 눈대중이 아니라 규칙으로 정의한다. **pre-fault result RPS의 90% 이상이 3개 연속
5초 창에서 유지되는 최초 시각.** 3개 연속을 요구하는 이유는 재기동 중의 우연한 창 하나가
회복으로 세어지지 않게 하기 위해서다. pre-fault baseline은 kill 30s 전부터 5s 전까지의
result RPS이므로 fault 주입 중 도착한 제출은 baseline에서 제외된다.

| | A | B | C |
|---|---:|---:|---:|
| pre-fault result RPS | 79.922915 | 99.762526 | 100.230138 |
| 90% 임계 | 71.930624 | 89.786273 | 90.207124 |
| 회복 시각 (fault 기준) | 52.606s | **53.885s** | **48.681s** |
| 회복 시각 (node ready 기준) | 2.509s | 1.291s | 0.514s |
| harness·analyzer 일치 | yes | yes | yes |

측정 phase 평균 accepted RPS는 A 80.419 / B 100.35 / C 100.469이고, phase 길이는 303.137s /
303.576s / 303.227s다. 이 평균은 pre-fault 정상상태와 outage와 drain을 모두 포함하므로 용량
수치가 아니다.

**drop-and-recover 시계열은 이 보고서가 제시하지 않는다.** 초당 result RPS는 `summary.json`에
없다. 원시 `recovery-samples.csv`에 누적 `results`가 초당 표본으로 남아 있으므로 계산은
가능하지만, 이 문서는 summary가 담지 않은 값을 다른 파일에서 파생해 채우지 않는다. 회복
판정은 §9의 규칙과 §11의 시각으로만 한다.

### backlog 시계열

judge backlog과 scoreboard pending backlog을 **각각** 보고한다. 합산값만 쓰지 않는다.
peak과 growth는 outage 구간(fault 시각 이후 모든 표본)에서만 계산한다. kill 이전이나 drain
이후의 표본은 outage가 아니며, 전체 series의 최대값은 run을 측정하게 된다.

| | A | B | C |
|---|---:|---:|---:|
| judge backlog pre-fault p95 | 32 | 44 | 52 |
| scoreboard pending pre-fault p95 | 18 | 18 | 19 |
| combined pre-fault p95 | 50 | 62 | 71 |
| **judge backlog peak** | 982 | **1057** | 1032 |
| **scoreboard pending peak** | 23 | 30 | 24 |
| **combined peak** | 1005 | **1087** | 1056 |
| peak / pre-fault p95 | 20.1 | 17.532 | 14.873 |
| peak judge growth (rows/s) | 50.715 | 67.205 | 55.603 |
| peak scoreboard growth (rows/s) | 22.005 | 24.209 | 21.933 |
| outage 표본 수 | 258 | 258 | 257 |

peak은 judge backlog이 지배한다. scoreboard pending peak은 23~30으로 pre-fault p95의 1.3~1.7배에
그친다. 즉 이 outage에서 고객이 기다린 것은 scoreboard 반영이 아니라 **judge 채점 자체**였다.

growth 열은 두 표본의 차이이므로 표본 간격을 물려받는다. pre-fault 구간에서 표본은 1초에 한
번이지만 outage 구간에서는 관측 1회가 그보다 오래 걸려 간격이 2.7–4.1s로 벌어지므로(§5), 그
구간의 상승은 더 넓은 격자로만 보인다. 어느 쪽이든 값은 "가장 가파른 상승"이 아니라 그 하한이다.
**level 열은 level로, growth 열은 방향으로 읽는다.**

정상화 시각은 judge와 scoreboard를 각각 구하고 combined는 그 둘 중 **나중** 시각이다.
combined를 정의로 삼는 이유는 두 queue가 모두 기준 아래로 내려와야 고객이 기다리지 않기
때문이다.

| | A | B | C |
|---|---|---|---|
| judge backlog 정상화 | 05:27:28.4898031 | 05:47:05.0864214 | 05:55:09.3361981 |
| scoreboard pending 정상화 | 05:26:48.4188024 | 05:46:39.5017117 | 05:55:04.3323866 |
| combined (= `backlogNormalizedAt`) | 90.6s | 78.905s | 73.246s |
| sustain span / 최대 gap / 표본 수 | 5.009s / 1.008s / 6 | 5.984s / 1.003s / 7 | 5.002s / 1.017s / 6 |

모든 run에서 **scoreboard가 judge보다 먼저 회복됐다**(A 40.071s 차, B 25.585s 차, C 5.004s 차).
C에서 그 차가 5.0s로 줄어든 것이 §13의 재영점 분석과 연결된다.

`earliest normalization`은 같은 탐색을 정상화 기준이 아니라 `faultInjectedAt`에서 시작한
결과다. 세 run 모두 gated 값과 **일치**했다(즉 더 이르지 않았다). 이 값은 항상 보고되며,
"탐색이 일치했다"와 "탐색을 하지 않았다"를 구분하기 위해 존재한다. 이 값은 **탐색 출발점
기준의 읽기이고 달성된 회복이 아니다**: 죽은 노드가 down인 동안 생존 노드는 죽은 노드가
받았을 작업을 공급받지 못하므로, backlog가 baseline 아래에 머무는 것이 "더 많이 비워서"가
아니라 "덜 들어와서"일 수 있고, 교체 노드가 돌아와 재claim된 row가 일괄 재publish되면 다시
올라갈 수 있다. 그래서 권위 있는 값은 gated `backlogNormalizedAt`이다.

## 10. Stale reclaim과 attempts 회계

`attempts > 1`은 **lease 만료 후의 복구 재claim**이다. claim을 들고 있던 프로세스는 SIGKILL로
사라졌으므로 그 row가 다시 나가는 동안 채점을 계속하지 않았다. **이것을 동시 중복 CPU 실행이라고
부르지 않는다.**

| | A | B | C |
|---|---:|---:|---:|
| stale reclaim (`SUM(attempts-1)`) | **3** | **20** | **13** |
| analyzer 계산 reclaimed rows | 3 | 20 | 13 |
| harness 계산 reclaimed rows | 3 | 20 | 13 |
| 두 계산 일치 | yes | yes | yes |
| kill snapshot의 PUBLISHING row | 18 | 50 | 22 |
| 그중 outage 중 회수된 수 | 3 | 20 | 13 |
| `storedResultRepublishes` | 0 | 0 | 0 |
| `staleTokenCompletions` | 0 | 0 | 0 |
| `completionFailure` | 0 | 0 | 0 |

analyzer와 harness가 durable한 outbox `attempts`에서 독립적으로 같은 수를 얻었고, 그 일치가
회계가 완전하다는 확인이다.

**stale reclaim이 0인 run은 없다.** 세 run 모두 관측됐으므로 "timeout/fault timing 또는 관측
오류를 조사한다"는 조건은 발동하지 않았다.

### kill 시점 claimed_at age 분포와 lease 만료 시각

`T_stale == 설정 timeout`이라고 가정하지 않는다. lease는 `claimed_at + timeout`에 만료되고,
`claimed_at`은 kill보다 그 row가 들고 있던 시간만큼 앞서므로 둘은 어느 방향으로도 다를 수 있다.
kill snapshot의 실제 age 분포는 다음과 같다.

| | A | B | C |
|---|---:|---:|---:|
| snapshot DB 시각 − `faultInjectedAt` | 0.856s | 0.643s | 0.874s |
| age min / p50 / p95 / max | 0.394 / 0.396 / 2.613 / 2.613 | 0.272 / 0.853 / 1.885 / 2.101 | 0.402 / 1.278 / 2.671 / 3.151 |
| snapshot row 수 | 18 | 50 | 22 |
| `attempts` 분포 | `2×1, 1×17` | `1×50` | `1×22` |
| `reclaimedRowsStrandedByTheKill` | 3 | 20 | 13 |
| `reclaimedRowsSubmittedDuringOrAfterTheFault` | 0 | 0 | 0 |
| `staleAttemptsBeforeFault` | 0 | 0 | 0 |
| 설정 timeout | 2.5s | 4s | 10s |
| **snapshot 잔존 최고령 claim의 lease 만료 시각** (`snapshot−fault + timeout − maxAge`) | 0.743s | 2.542s | 7.723s |
| **가장 이른 lease 만료** (A는 재claim 시각이 상한, B·C는 위 값이 하한) | **≤0.461s** | **2.542s** | **7.723s** |
| **관측 `T_stale`** | **2.712s** | **6.238s** | **9.702s** |
| 하한 초과분 | **≥2.251s** | 3.696s | 1.979s |

`age`는 **snapshot 시각 기준**(`dbNow − claimed_at`)이므로, snapshot에 남아 있는 가장 오래된
claim의 lease 만료 시각은 `snapshot−fault + timeout − maxAge`다(세 run 모두 양수이므로 0으로
자르는 항이 필요 없다). **그러나 이 값이 "가장 이른 lease 만료"인 것은 아니다.** kill snapshot은
kill 이후에 찍히고(`Run-TradeoffExperiment.ps1:1877-1881`; `killToSnapshotStartSeconds`가 그
오프셋), 그 사이에 회수된 row는 `claimed_at`이 재claim 시각으로 **재설정**되고 `attempts`만
증가한다(`ContestJudgeOutboxStore`: `SET claimed_at = CURRENT_TIMESTAMP(6), attempts = attempts + 1`).

A의 snapshot에는 그런 row가 하나 있고(`attempts` 분포 `2×1, 1×17`), 그 `claimedAt`은
`fault+0.461s`다. claim 술어는 `status='PUBLISHING' AND claimed_at < now − lease`이므로 재claim은
lease 만료를 전제한다. 즉 **A에서는 fault+0.461s 이전에 이미 lease가 만료된 claim이 있었다.**
그 원래 claim의 나이는 어디에도 남지 않는다(재설정된 `claimed_at`은 snapshot에서 0.396s짜리로
보인다). 따라서 snapshot의 `maxAge` 2.613s는 kill 순간 최고령 claim의 나이가 아니고, A의
0.743s는 하한이 아니다. B·C의 snapshot에는 `attempts=2` row가 없어(각각 `1×50`, `1×22`)
snapshot 이전에 재claim이 일어나지 않았으므로, 두 run에서는 2.542s·7.723s가 유효한 하한이다.

`reclaimedRowsStrandedByTheKill`/`...SubmittedDuringOrAfterTheFault`와 `staleAttemptsBeforeFault`는
artifact의 guard 필드이며, 이 cohort가 전부 kill이 고아로 만든 행이고 fault 이전 회수가 0임을
말한다(§7).

세 가지가 여기서 읽힌다.

1. **관측 `T_stale`은 timeout에 대해 단조 증가한다** (2.712 < 6.238 < 9.702, timeout 2.5 < 4 < 10).
   가설 1은 측정으로 지지된다.
2. **kill 순간에 만료되지 않은 claim만 보면, 남아 있는 것 중 가장 오래된 claim도 아직 만료
   전이었다.** snapshot 잔존 최고령 claim의 age 2.613s는 snapshot 시각(fault+0.856s) 기준이므로
   kill 순간의 age는 1.757s(= timeout의 70%)이고, 그 lease는 fault 후 **0.743s**에 만료된다.
   즉 그 claim의 만료는 kill보다 **뒤**다. (A에는 이보다 이른 만료가 있었다 — 위 문단.)
   kill snapshot에 있는 `attempts=2` row의 `claimedAt` 05:25:58.350588은 kill **이후**이므로,
   kill 이전에 만료된 lease의 증거가 아니라 kill 후 0.46s에 일어난 재claim의 증거다. **이 문단이
   말하는 것은 kill 순간의 관측이 아니라 설정 수준의 사실이다.** A는 timeout이 2.5s로 가장 짧아
   kill 시점에 snapshot 잔존 최고령 claim이 lease의 70%를 소진한 상태였고, 2500ms가 정상상태
   중복 경계에서 놓인 위치(앞선 라운드가 `mif=16`의 **측정 창**에서 중복 0을 확인한 값)는 장애
   설정으로서 그대로다. 같은 run의 warm-up 창에서는 그 값이 0이 아니었다(§14).
3. **관측값은 세 run 모두 자기 하한보다 크다.** 하한은 lease 만료 시각일 뿐이고, 실제 회수는
   살아남은 노드의 poll이 **여유 in-flight를 가진 순간**에 일어나기 때문이다. kill 직전
   judge-2는 A 3 running / 3 reserved, B 15/15, C 16/16이었고, B·C에서는 노드 하나가 100 RPS를
   홀로 받고 있었다. 즉 **timeout은 회수 지연의 하한을 정하지, 회수 지연 자체를 정하지 않는다.**
   (이 초과분의 크기를 정하는 요인은 이번 실행이 분리하지 못했다. §16의 추론 3.)

이 하한은 `cluster-wide` 상한집합에서 계산한 값이다. outbox에 `claimed_by`가 없으므로
snapshot의 row가 judge-1의 것인지 알 수 없고, 따라서 이 하한도 cluster-wide 하한이다.
또 DB 시각과 host 시각을 함께 쓰므로 소량의 clock offset이 섞인다.

### SIGKILL된 노드의 counter

| | A | B | C |
|---|---:|---:|---:|
| judge invocations | 24340 | 30437 | 30410 |
| invocations가 lower bound인가 | **yes** | **yes** | **yes** |
| claim calls | 4957 | 4980 | 5025 |
| claimed rows | 24354 | 30443 | 30413 |
| duplicate claim estimate | 3 | 20 | 13 |
| `cluster-wide claimed unfinished upper bound` | 18 | 50 | 22 |
| `stranded / max-in-flight` (분모 = mif × 2) | 0.563 | 0.391 | 0.172 |
| claim 귀속 정확도 (`attribution exact`) | no | no | no |

judge invocations와 그것에서 파생된 모든 값은 세 run 모두 **하한**이다. SIGKILL이 JVM을
데려갔으므로 마지막 scrape와 kill 사이의 증가분은 프로세스와 함께 사라졌다. **사라진 counter는
0이 아니고, 이 보고서는 그것을 0으로 읽지 않는다.** durable한 증거는 outbox의 `attempts` 열과
최종 row 상태이며, 위 표의 stale reclaim 열이 그 값이다.

`cluster-wide claimed unfinished upper bound`는 **cluster-wide 상한**이며 **judge-1의 active
claims가 아니다.** outbox에 `claimed_by`가 없으므로 snapshot은 어느 노드가 어느 row를 들고
있었는지 말할 수 없다. 이 수는 두 번 상한이다: 살아남은 노드가 곧 정상 종료할 row까지 포함하고,
kill 시각이 아니라 kill 주변에 찍은 snapshot에서 읽는다. `stranded / max-in-flight`의 분모가
`mif × 2`인 이유는 이 실험이 judge 컨테이너 2개를 돌리기 때문이다.

## 11. Recovery time 정의별 비교

"복구에 얼마나 걸렸는가"라는 질문에 답이 하나가 아니므로 정의별로 나눠 보고한다. 대부분은
`faultInjectedAt` 기준이고, 두 열만 `restartRequestedAt` 기준이다. 이 둘을 나란히 두는 이유는
lease 만료 시각과 노드가 돌아온 시각과 queue가 빈 시각이 전부 다른 사건이기 때문이다.

| 정의 | 기준 | A | B | C |
|---|---|---:|---:|---:|
| `T_stale` (최초 stale reclaim) | fault | 2.712s | 6.238s | 9.702s |
| `T_restart_requested` (= down window) | fault | 15.009s | 15.009s | 15.402s |
| `T_container_running` | restart requested | 1.283s | 1.232s | 1.378s |
| `T_node_ready` | restart requested | 35.088s | 37.585s | 32.765s |
| throughput recovery | fault | 52.606s | 53.885s | 48.681s |
| backlog 정상화 (judge) | fault | 90.6s | 78.905s | 73.246s |
| backlog 정상화 (scoreboard) | fault | 50.529s | 53.32s | 68.242s |
| backlog 정상화 (combined) | fault | 90.6s | 78.905s | 73.246s |
| `T_last_reclaimed_result` | fault | 3.806s | 6.364s | 11.878s |
| `T_last_reclaimed_scoreboard` | fault | 3.913s | 6.394s | 11.92s |
| drain | 부하 정지 | 1.648s | 1.647s | 1.583s |

`T_container_running`과 `T_node_ready`는 **나머지와 0점이 다르다.** harness가 이 둘을 재기동
요청 이후 초로 기록하므로 `T_node_ready`는 kill 이후 readiness까지의 시간이 아니고,
`T_stale`에 더하는 것은 의미가 없다. 두 0점 사이의 간격이 실측 down window다.

`T_stale`은 durable한 `SUM(attempts-1)`이 pre-fault 값을 넘는 것이 **관측된** 시각이다. 그
값이 실제 회수의 상한이고, 하한은 claim의 lease 만료다(§10) — 둘을 하나의 수로 접지 않는다.
관측 간격은 pre-fault 구간에서 약 1초지만 **outage 구간에서는 2.7–4.1s**이므로(§5) 이 상한의
여유도 그만큼 넓다. §13의 B vs C 판정이 이 여유를 쓰지 않고 lease 하한만으로 성립하는 이유다.
`T_last_reclaimed_*`은 재claim된 제출의 result row와 scoreboard 반영이 끝난 시각, 즉 그
작업을 기다린 고객이 마침내 결과를 본 시각이다.

**from-fault 기준 값을 node ready 기준으로 재영점하면 다음과 같다.** §13이 이 표에 기대고 있다.

| | A (16/2500ms/80) | B (64/4s/100) | C (64/10s/100) |
|---|---:|---:|---:|
| `nodeReadyAt - faultInjectedAt` | 50.097s | 52.594s | 48.167s |
| throughput recovery − fault | 52.606s | 53.885s | 48.681s |
| throughput recovery − node ready | 2.509s | 1.291s | 0.514s |
| backlog 정상화 − fault | 90.600s | 78.905s | 73.246s |
| backlog 정상화 − node ready | 40.503s | 26.311s | 25.079s |
| `nodeReadyAt - restartRequestedAt` | 35.088s | 37.585s | 32.765s |

## 12. SIGKILL counter 유실 한계

이 절은 §10의 counter 논의를 한계로 다시 진술한다.

- **SIGKILL된 JVM의 in-process counter는 하한이다.** judge invocations와 그것에서 파생된 모든
  invocation·duration·claim 증가분은 마지막 scrape와 kill 사이에 프로세스와 함께 사라졌다.
  사라진 counter는 0이 아니며, 이 비교는 그것을 0으로 읽지 않는다. durable한 증거는
  outbox의 `attempts` 열과 최종 row 상태다.
- **`attempts > 1`은 lease 만료 후의 복구 재claim이다.** 동시 중복 CPU 실행이라고 부르지 않는다.
- **judge-1이 실제로 들고 있던 row 수는 알 수 없다.** outbox에 `claimed_by`가 없으므로
  kill 시점 수는 cluster-wide 상한이다.
- **`killed-node-claimed` cohort는 세 run 모두 `unavailable`이다.**
- **진짜 동시 중복 실행과 fencing은 이 실험으로 시험할 수 없다.** SIGKILL은 fail-stop이므로
  두 judge가 같은 제출에 동시에 살아 있는 형상이 만들어지지 않는다. 그 형상은 별도의
  `docker pause → lease 만료까지 대기 → unpause` 실험이 필요하다(§17).

## 13. MIF64/4s vs MIF64/10s 직접 비교

- 4s run: `fault-mif64-timeout4s-rps100-rerun1-20260920`
- 10s run: `fault-mif64-timeout10s-rps100-20260920`
- delta 정의: **4s 값 − 10s 값.** 음수면 4s가 더 낮거나 빠르다.
- 두 run 모두 100 RPS, mif 64이므로 raw 차이는 **claim timeout에 대한 읽기**다.

### comparer의 판정

| 항목 | 4s | 10s | delta | sampling band | 판정 |
|---|---:|---:|---:|---:|---|
| `T_stale` | 6.238 | 9.702 | −3.464 | — (아래 참조) | **4s가 빠르다** (관측 격자와 무관) |
| backlog peak (combined) | 1087 | 1056 | +31 | 104.718 | band 안: 우열 없음 |
| backlog 정상화 (combined) | 78.905 | 73.246 | +5.659 | 2.094 | band 밖: 10s가 빠르다 |
| fault-down cohort `L_result` p99 | 11664.371 | 11256.778 | +407.593 | 50 (한 latency class) | 같은 class: 우열 없음 |
| throughput recovery | 53.885 | 48.681 | +5.204 | 2.094 | band 밖: 10s가 빠르다 |

band는 그 run의 측정이 분해할 수 있는 최소 차이다. 회복시간에는 표본 2개 간격(2 × 약
1047ms), backlog peak에는 offered load에서의 도착 1간격, percentile에는 결정적 latency
class 하나를 쓴다. 차이가 band 안이면 우열을 선언하지 않는다.

**아래 표에서 `T_stale` 행의 판정만 이 문서가 comparer의 band 판정을 대체한 것이다.**
comparer 자신은 이 행을 "delta −3.464s, band(2.094s) 밖"으로 판정하는데, outage 구간의
관측 격자가 2.094s보다 훨씬 넓으므로(아래 참조) 그 판정은 성립하지 않는다. 나머지 네 행은
comparer가 낸 verdict 그대로다.

이 band는 **hold 전체의 평균 표본 간격**(`staircase.samplingInterval.meanIntervalMs` ≈ 1047ms)
에서 나온 값이다. outage 구간의 실제 관측 간격은 그보다 훨씬 길고(2.7–4.1s, §5), 회수 시각을
읽는 poll도 같은 격자를 쓴다. 그러므로 outage 구간의 시간 해상도는 2.094s가 아니라 약
5.4–8.2s이고, 같은 구간 backlog 도착량의 해상도는 100 RPS에서 270–410행이다. **band를 outage
구간에 그대로 적용하면 해상도를 과대평가한다.**

**그래서 `T_stale` 행은 band로 판정하지 않는다.** 이 항목의 두 값은 관측 격자가 아니라 lease
만료로 유계된다. 회수는 lease가 만료된 claim에만 일어나므로:

- B의 회수는 관측 시각인 **6.238s 이하**다. 관측된 `attempts`는 durable하므로 그 시각까지
  회수가 이미 완료돼 있었다.
- C의 회수는 **7.723s보다 빠를 수 없다.** kill 순간 PUBLISHING이던 claim 중 가장 이른 만료가
  7.723s이고(§10의 lease 만료 시각), kill 이후에 새로 획득된 claim은 `claimed_at ≥ fault`이므로
  10s timeout에서 만료가 fault+10s 이상이다.

두 구간이 겹치지 않으므로 **B의 회수가 C의 회수보다 먼저 일어났음이 관측 격자와 무관하게
성립**하고, 그 최소 여유는 7.723 − 6.238 = **1.485s**다. 이 절의 `T_stale` 판정은 이 논증에
근거하며 2.094s band는 여기에 쓰이지 않는다.

### 재영점: "10s가 더 빠르다" 두 판정은 재기동 타이밍 교란이다

§11의 재영점 표에서 delta(4s − 10s)를 다시 계산하면 다음과 같다.

| 항목 | 4s | 10s | delta | band | 판정 |
|---|---:|---:|---:|---:|---|
| throughput recovery (fault 기준) | 53.885 | 48.681 | **+5.204** | 2.094 | band 밖: 10s가 빠르다 |
| throughput recovery (node ready 기준) | 1.291 | 0.514 | **+0.777** | 2.094 | **band 안: 우열 없음** |
| backlog 정상화 (fault 기준) | 78.905 | 73.246 | **+5.659** | 2.094 | band 밖: 10s가 빠르다 |
| backlog 정상화 (node ready 기준) | 26.311 | 25.079 | **+1.232** | 2.094 | **band 안: 우열 없음** |
| down window | 15.009 | 15.402 | −0.393 | 2.094 | band 안 |
| `nodeReadyAt - restartRequestedAt` | 37.585 | 32.765 | **+4.820** | 2.094 | band 밖: 4s가 늦다 |
| `nodeReadyAt - faultInjectedAt` | 52.594 | 48.167 | **+4.427** | 2.094 | band 밖: 4s가 늦다 |

**from-fault 기준 5.204s / 5.659s 차이 중 약 4.4s가 교체 노드의 readiness 타이밍이다.**
이 값은 max-in-flight도 claim timeout도 지배하지 않는다(§5의 gate 표: 컨테이너가 뜬 뒤
readiness까지 29.8~34.5s). 재영점하면 두 차이는 각각 0.777s와 1.232s로 band 안에 들어와
우열이 사라진다.

C의 down window가 0.402s 더 길다는 사실(§5)은 이 방향을 **강화**한다. C는 자기 outage가
더 길었음에도 from-fault 값이 더 작았으므로, 이 0.402s는 10s의 우위를 만든 것이 아니라
오히려 0.393s 과소평가하고 있다. down window는 표본이 아니라 두 타임스탬프의 차이이므로
band를 적용할 대상이 아니고, 그 0.402s의 원인은 §5에서 확정됐다 — C의 마지막 관측이
`restartScheduledAt`을 0.400s 넘겨 끝나 재기동 요청이 그만큼 밀렸다.

### lease에 귀속되는 차이 — 4s가 더 나은 설정인 근거

재기동 타이밍을 걷어내면 timeout에 실제로 귀속되는 차이는 두 개 남는다.

| 항목 | 4s | 10s | 차이 | 방향 |
|---|---:|---:|---:|---|
| `T_stale` | 6.238s | 9.702s | 3.464s | **4s가 빠르다** (관측 격자와 무관) |
| reclaimed cohort `L_total` p50 | 5059.315ms | 12627.264ms | 7567.949ms | **4s가 낫다** |
| reclaimed cohort `L_total` p95/p99/max | 6765.233 / 6954.923 / 6954.923 | 13008.992 / 13008.992 / 13008.992 | 6243.759 | **4s가 낫다** |
| reclaimed cohort `>10s` 비율 | **0%** | **100%** | — | **4s가 낫다** |
| reclaimed cohort n | 20 | 13 | — | — |
| `T_last_reclaimed_result` | 6.364s | 11.878s | 5.514s | 4s가 빠르다 |
| stranded / max-in-flight | 0.391 | 0.172 | 0.219 | 10s가 작다(도착 시점 차이) |

reclaimed cohort의 `L_total` 차이는 **역학적으로 자명하다**: lease가 길면 죽은 노드가 들고
있던 row가 늦게 풀리고, 그 row의 고객은 outage + lease 만료를 함께 기다린다. 4s의 그 cohort는
최대가 6954.923ms이고 10s의 그것은 p50이 이미 12627.264ms다 — **4s의 최대값이 10s의 중앙값보다
작다.** 10s에서는 13건 전부가 10초를 넘겼고, 4s에서는 20건 전부가 넘기지 않았다.

`stranded / max-in-flight`가 10s에서 더 작은 것은 timeout의 효과가 아니다. kill 순간 우연히
PUBLISHING 상태였던 row 수가 달랐기 때문이며(50 대 22), 이 값은 도착·claim 타이밍의 표본이다.

**정상상태 축을 함께 놓고 보면 판단이 갈린다.** MIF64에서 측정 창 중복이 0으로 확인된 가장
짧은 값은 4s였다(즉 4s 아래로는 2s에서 중복이 있었다). 그러므로 4s는 중복 0이 확인된 값 중
**아래쪽 여유가 가장 적은** 값이고, 10s는 그보다 멀다. 즉 4s는 장애 축에서 이기고 정상상태
축에서 여유가 더 적다.

**이 측정이 지지하는 판정: 장애 복구 축에서는 4s가 더 나은 설정이다.** 근거는 lease에 귀속되는
유일한 차이(`T_stale` 3.464s, 관측 격자와 무관하게 lease 만료로 유계됨)와 reclaimed cohort의
`L_total`이다. comparer가 "10s가
빠르다"고 표시한 throughput·backlog 두 항목은 node ready 기준으로 재영점하면 band 안으로
들어가고, 그 차이의 대부분은 이 실험이 제어하지 않는 재기동 타이밍이다.

이 판정은 **회수 지연과 그 지연을 기다린 소수의 지연**에 근거한다. 두 run의 전체 처리량이나
전체 무결성에는 차이가 없었고(§6), backlog peak에도 우열이 없었다.

**이 절의 두 귀속 판정은 "측정이 직접 지지하는 결론"이 아니라 "코드와 측정으로부터의 추론"
등급이다.** `stranded / max-in-flight`를 도착·claim 타이밍에 귀속하는 것과, reclaimed cohort의
`L_total` 차이를 lease 길이에 귀속하는 것은 둘 다 기제에 의존한다(§16의 등급 구분).

### 정규화 지표 (참고)

두 run은 같은 offered load이므로 정규화 지표가 raw와 다른 것을 말하지는 않는다. 그래도
방향을 기록한다(band가 summary에 없으므로 유의성 판정이 아니다).

| 지표 | 4s | 10s | delta |
|---|---:|---:|---:|
| backlog peak / accepted RPS | 10.896 | 10.536 | +0.36 |
| recovered rows / second | 0.253 | 0.177 | +0.076 |
| fault cohort p99 / configured timeout | 2.916 | 1.126 | +1.79 |
| stranded claimed rows / max-in-flight | 0.391 | 0.172 | +0.219 |
| recovery time / pre-fault throughput | 0.791 | 0.731 | +0.06 |

`fault cohort p99 / configured timeout`이 4s에서 2.916, 10s에서 1.126인 것은 두 run의 p99가
같은 결정적 latency class에 있기 때문이다. 분모(timeout)만 2.5배 커졌으므로 이 지표는
**timeout을 키우면 좋아 보이는 지표**이며, 실제로 고객이 기다린 시간이 줄었다는 뜻이 아니다.

## 14. MIF16/2500ms의 별도 해석

이 run은 자기 절대값과 자기 정규화 지표로만 보고하고 위 비교표에서 의도적으로 제외한다.

**raw RPS와 raw latency를 MIF64 두 run과 비교하는 것은 타당하지 않다.** 80 RPS를 받았고
MIF64 run들은 100 RPS를 받았다. offered load가 다르면 backlog도, queueing 지연도, outage 중
도착하는 제출의 비율도 달라지므로 raw 차이는 부하와 설정의 차이가 함께 섞인 값이고 귀속할
방법이 없다.

| 항목 | 값 |
|---|---|
| mif / timeout / offered RPS | 16 / 2500ms / 80 |
| accepted = unique = results = scoreboard | 24378 |
| chain 유지 / `integrity.passed` | yes / yes |
| pre-fault result RPS | 79.922915 |
| `T_stale` | **2.712s** (세 run 중 최단) |
| `nodeReadyAt - faultInjectedAt` | 50.097s |
| throughput recovery (fault / node ready) | 52.606s / 2.509s |
| backlog 정상화 (judge / scoreboard / combined) | 90.6s / 50.529s / **90.6s** |
| judge backlog peak / scoreboard peak | 982 / 23 |
| combined peak / pre-fault p95 | 20.1 |
| stale reclaim | **3** |
| stranded / max-in-flight | 0.563 |
| drain | 1.648s |
| post-recovery 관측창 | 170.546s |

정규화 지표(두 그룹 사이에서 읽을 수 있는 부분):

| 지표 | A (16/2500ms/80) | B (64/4s/100) | C (64/10s/100) |
|---|---:|---:|---:|
| backlog peak / accepted RPS | **12.575** | 10.896 | 10.536 |
| recovered rows / second | **0.033** | 0.253 | 0.177 |
| fault cohort p99 / configured timeout | **5.361** | 2.916 | 1.126 |
| stranded claimed rows / max-in-flight | **0.563** | 0.391 | 0.172 |
| recovery time / pre-fault throughput | **1.134** | 0.791 | 0.731 |

A의 위치는 네 가지로 요약된다.

1. **`T_stale`이 가장 짧다**(2.712s). timeout 축의 예측대로이며, 세 run 중 유일하게 3초 미만이다.
2. **`stranded / max-in-flight`가 가장 크다**(0.563). mif가 16으로 가장 작아 분모가 32인데,
   kill 순간 18 row가 PUBLISHING이었다. mif 축의 예측과 방향이 맞지만 n=1이므로 이 한 쌍으로
   비례 관계를 주장하지 않는다.
3. **`fault cohort p99 / configured timeout`이 5.361로 가장 크다.** 분모가 2.5s로 가장 작기
   때문이며, raw p99(13506.457ms)는 세 run 중 가장 크다. **이 지표가 가장 나쁘다는 것이 이
   run의 고객이 가장 오래 기다렸다는 뜻은 아니다** — 80 RPS에서 outage 중 도착 비율과 queueing이
   다르고 분모도 다르다.
4. **`recovered rows / second`가 0.033으로 가장 작다.** 재claim된 row가 3건뿐인데 정상화에는
   90.6s가 걸렸다. 이 지표는 분자가 작을 때 회복 속도를 대표하지 못한다.

**A의 backlog 정상화가 90.6s로 MIF64 두 run(78.905s / 73.246s)보다 긴 것은 judge backlog이
지배한다.** A의 scoreboard는 50.529s로 세 run 중 가장 빨랐지만 judge backlog이 90.6s까지
남았다. A는 mif가 16이라 홀로 80 RPS를 떠받칠 때 노드당 동시 실행이 16으로 제한되고, 그 결과
judge queue가 가장 오래 남는다. 이 해석은 §9의 peak과 §11의 정상화 시각이 함께 지지한다.

**"2500ms는 중복 0"은 측정 창에 한정된 진술이다.** 이 run의 warm-up contest에서는
`duplicateClaimsInWarmup = 2`(attemptsHistogram `2×2, 1×2555`)였다. 앞선 라운드의
`normal-mif16-timeout2500ms-20260920-retry`도 warm-up에서 같은 2건을 기록했고 측정 창에서는
0건이었다. warm-up은 이 실험의 어떤 cohort에도 들어가지 않으며 artifact가
`excludedFromMeasuredAggregates`로 명시하지만, **§1이 정리한 MIF16의 정상상태 중복 경계가
`(2s, 2.5s]`라 2500ms가 그 위쪽 끝에 놓인 가장 짧은 값이라는 사실(§2가 그 경계에서 1s·2s를
반증한 그 경계)과 같은 방향의 관측**이므로 여기 함께 남긴다. 즉 "중복 0"은 여유가 넓다는
뜻이 아니라 여유가 얇다는 뜻이다. B·C의 warm-up은 0건이었다.

## 15. 정규화 지표 정의

비교표의 모든 정규화 cell에 의미를 부여하기 위해 다섯 비율을 여기서 한 번 정의한다. 각각
summary가 담고 있는 두 양의 나눗셈이고, 각각 guard가 있으며, 한쪽이 없거나 분모가 0이면
JSON에서 `null`, 표에서 `unavailable`이다. 0으로 읽으면 "추가 비용 없음"이 되는데 그것은
측정되지 않은 값의 반대 의미다.

- `backlog peak / accepted RPS` — recovery-samples.csv의 peak를 그 run의 pre-fault accepted
  RPS로 나눈 값. peak backlog이 몇 초 분량의 도착 작업이었는지를 뜻하며, 부하가 다른 run
  사이에서도 살아남는 유일한 peak 읽기다.
- `recovered rows / second` — fault 후 outbox row가 다시 나간 제출 수를 combined 정상화
  시간으로 나눈 값. 남은 작업이 비워진 속도다.
- `fault cohort p99 / configured timeout` — fault-down cohort `L_result` p99를 설정 timeout(ms)로
  나눈 값. 1을 넘으면 outage 중 도착한 제출이 그 설정이 함의하는 lease보다 오래 기다렸다는
  뜻이고, timeout 값을 고객 가시 지연에 묶는 읽기다.
- `stranded claimed rows / max-in-flight` — cluster-wide claimed unfinished 상한을 `mif × 2`로
  나눈 값. kill이 cluster 전체 claim 용량 중 얼마를 미완료로 남겼는지다.
- `recovery time / pre-fault throughput` — combined 정상화 시간을 pre-fault result RPS로 나눈 값.
  단위가 초/(result/초)인 shape 비율이며 duration이 아니다. 그 run이 나르던 처리량 단위당
  얼마의 회복 시간을 샀는지를 뜻한다.

`backlog drain rows / second`는 recovered-rows 비율 옆에 보고한다. 둘은 다른 것을 측정한다:
recovered rows는 재claim된 outbox row를 세고, drain rate는 peak queue가 자기 pre-fault p95 위로
올라간 양을 같은 회복 시간으로 나눈 값, 즉 row가 어떻게 거기 도달했든 queue가 비워진 속도다.
A 10.541 / B 12.99 / C 13.448 rows/s.

## 16. 결론의 증거 등급

### 측정이 직접 지지하는 결론

1. **세 조건 모두 최종 무결성 chain이 유지됐다.** accepted = uniqueSubmissions = results =
   scoreboardApplied가 A 24378, B 30464, C 30465이고, `lostOrIncomplete` 0,
   `finalResultMismatch` 0, HTTP 429/500/503 전부 0, drain 성공이다. 가설 5는 지지된다.
2. **세 run 모두 fault 순간 실제 active work가 있었다.** judge-1의 running/reserved는
   A 4/4, B 9/9, C 8/8이었고, primary 조건(`reserved >= 4`)에서 0.279s 이내에 주입됐다.
   `faultNotInjectedWithActiveWork`는 세 run 모두 false다.
3. **down window는 A 15.009s, B 15.009s, C 15.402s다.** A·B는 설정과 0.009s 차이고, C는
   0.402s 초과했으나 harness 자체 허용치 0.5s 안이다. 세 run 모두 `restartRequestedAt`이
   `faultInjectedAt`에 정박되어 있어 관측이 느려도 outage가 짧아지지 않고 길어진다. C의 초과는
   마지막 관측이 deadline을 0.400s 넘겨 끝난 데서 왔고(§5), 방향이 outage를 **늘리는** 쪽이므로
   C의 from-fault 복구시간은 과소평가된 것이다.
4. **`containerRunningAt`과 `nodeReadyAt`은 다른 값이다.** 차이는 A 33.806s, B 36.352s,
   C 31.387s이고, 재기동 요청 기준으로 컨테이너가 뜬 뒤 readiness까지가 A 32.180s,
   B 34.510s, C 29.838s를 차지한다.
5. **관측 `T_stale`은 timeout에 대해 단조 증가한다**: 2.712s(2.5s), 6.238s(4s),
   9.702s(10s). 가설 1은 세 점에서 지지된다.
6. **세 run 모두 자기 lease 만료 시각보다 늦게 회수했다**(초과분 ≥2.251s / 3.696s / 1.979s).
   timeout은 회수 지연의 하한을 정하지 회수 지연 자체를 정하지 않는다.
7. **stale reclaim은 3 / 20 / 13건이다.** analyzer와 harness가 durable outbox에서 독립적으로
   같은 수를 얻었다. `storedResultRepublishes`와 `staleTokenCompletions`는 세 run 모두 0이다.
8. **backlog peak은 1005 / 1087 / 1056이고 peak/pre-fault-p95는 20.1 / 17.532 / 14.873이다.**
   peak은 judge backlog이 지배하고(982 / 1057 / 1032), scoreboard pending peak은 23 / 30 / 24로
   작다. 가설 3의 방향(mif가 크면 stranded가 크다)은 `stranded / max-in-flight`가
   A 0.563 > B 0.391 > C 0.172로 나온 것과 일치하지만, mif와 도착 타이밍이 함께 변했으므로
   n=1에서 비례 관계를 주장하지 않는다.
9. **from-fault 회복 시간**: throughput 52.606 / 53.885 / 48.681s, backlog 정상화
   90.6 / 78.905 / 73.246s. **node-ready 기준으로는** throughput 2.509 / 1.291 / 0.514s,
   backlog 정상화 40.503 / 26.311 / 25.079s다. 가설 4는 지지된다: 회복 시간의 대부분은
   down window와 재기동이며 claim timeout의 기여는 그 위의 작은 항이다.
10. **MIF64 짝에서 `T_stale`은 4s가 3.464s 빠르다.** 이 판정은 관측 격자가 아니라 lease 만료로
    유계된다: B의 관측 상한 6.238s와 C의 lease 하한 7.723s가 겹치지 않으므로 최소 여유 1.485s가
    격자와 무관하게 성립한다(§13). band(2.094s)는 hold 평균 간격에서 나온 값이라 outage 구간의
    해상도(약 5.4–8.2s)를 과대평가한다.
11. **MIF64 짝에서 backlog peak 차이(+31)는 band(104.718) 안이고, fault-down cohort `L_result`
    p99 차이(+407.593ms)는 같은 결정적 latency class 안이다.** 둘 다 우열 없음이다.
12. **MIF64 짝에서 reclaimed cohort의 `L_total`은 두 run이 사실상 분리된다.** 4s는 p95/p99/max
    6765.233/6954.923/6954.923ms에 `>10s` 0%, 10s는 13008.992/13008.992/13008.992ms에
    `>10s` 100%이고, **4s의 최대값(6954.923ms)이 10s의 중앙값(12627.264ms)보다 작다.**
    가설 2는 이 cohort에서 지지된다.
13. **scoreboard pending은 세 run 모두 judge backlog보다 먼저 정상화됐다**(40.071s / 25.585s /
    5.004s 차). 이 outage에서 고객이 기다린 것은 scoreboard 반영이 아니라 judge 채점이다.
14. **warm-up은 모든 cohort 밖에 있다.** warm-up과 measurement는 별도 contest이고,
    `acceptedGrowthAfterBaseline`이 세 run 모두 0이며 quiescence 근거가 기록돼 있다.
15. **MIF16/2500ms의 `T_stale`이 2.712s로 가장 짧고**, 정규화 지표
    `backlog peak / accepted RPS` 12.575와 `stranded / max-in-flight` 0.563이 세 run 중 가장 크다.

### 코드와 측정으로부터의 추론

1. **comparer가 10s에 준 "throughput recovery 5.204s 빠름"과 "backlog 정상화 5.659s 빠름"
   판정은 재기동 타이밍 교란이다.** node ready 기준으로 재영점하면 0.777s와 1.232s로 각각
   band 안에 들어간다. 차이의 약 4.4s는 `nodeReadyAt - restartRequestedAt`(4s 37.585s 대
   10s 32.765s)이며, 이 구간은 claim timeout도 max-in-flight도 지배하지 않는다.
2. **C의 0.402s 초과 down window는 이 방향을 강화한다.** C는 outage가 더 길었는데도 from-fault
   값이 더 작았으므로, 초과분은 10s의 우위를 만든 것이 아니라 약 0.393s 과소평가한다. 그 초과는
   하네스가 마지막 관측을 deadline 앞에서 끝내지 못한 데서 왔으므로(§5) claim timeout의 효과가
   아니고, 오히려 10s 쪽에 유리하게 작용한 교란이다.
3. **관측 회수가 lease 만료보다 늦는 이유는 이번 실행이 분리하지 못했다.** 회수는 살아남은
   노드의 poll이 claim할 여유를 가진 순간에 일어나므로 후보 기제는 poll 간격 100ms, 생존
   노드의 부하, 그리고 `mif − reserved`인 per-poll claim 여유다. 그런데 kill 직전 judge-2는
   A 3 running / 3 reserved(mif 16, 여유 13), B 15/15(mif 64, 여유 49), C 16/16(mif 64, 여유 48)로
   **B·C의 여유가 A보다 훨씬 컸는데도 초과분은 A의 하한 2.251s와 C의 1.979s가 2s 근처에
   몰리고 B만 3.696s로 단조가 아니다.**
   더 근본적으로는 **이 초과분이 자기 관측 불확실성보다 작다**: T_stale을 읽는 관측의 간격이
   outage 중 2.7~4.1초이므로(§20) 1.7초짜리 초과분 차이에는 아무것도 읽을 수 없다. 즉 이
   초과분은 위 후보 중 어느 하나에도 귀속되지 않으며, 세 run 모두 T_stale이 timeout에 대해
   단조 증가한다는 사실(§10)만 남는다.
4. **reclaimed cohort `L_total` 차이는 lease 길이에서 역학적으로 나온다.** 그 cohort의
   `L_total`은 제출에서 scoreboard 반영까지이므로 outage 전체를 포함하고, lease가 길면
   row가 늦게 풀려 고객이 그만큼 더 기다린다. 두 run에서 이 cohort가 겹치지 않는 것이 그 증거다.
5. **A의 backlog 정상화가 90.6s로 가장 긴 것은 judge queue 때문이다.** A는 scoreboard가
   50.529s로 세 run 중 가장 빨랐지만 judge backlog이 90.6s까지 남았다. mif=16에서 노드 하나가
   80 RPS를 홀로 받으면 노드당 동시 실행이 16으로 제한되어 judge queue가 가장 오래 남는다.
6. **장애 복구 축에서 4s가 더 나은 설정이다.** lease에 귀속되는 유일한 차이가 `T_stale`
   3.464s(관측 격자와 무관하게 유계됨)이고, reclaimed cohort의 `L_total`이 4s에서 절반 이하이며 `>10s` 비율이
   0% 대 100%다. 정상상태 축에서는 반대 방향의 고려가 있다: MIF64에서 중복 0이 확인된 가장
   짧은 값이 4s이므로 4s는 그중 아래쪽 여유가 가장 적고, 10s는 그보다 멀다.
7. **`fault cohort p99 / configured timeout` 지표는 timeout을 키우면 좋아 보인다.** 두 run의
   p99가 같은 결정적 latency class에 있으므로 분모만 2.5배 커진다. 실제로 고객이 기다린
   시간이 줄었다는 뜻이 아니므로 이 지표로 timeout을 정하면 안 된다.
8. **timeout은 고객 가시 지연에 두 경로로만 들어온다.** (a) 죽은 노드가 들고 있던 row가
   풀리는 시각(=`T_stale`), (b) 그 row의 고객이 기다린 end-to-end 지연. backlog peak과 전체
   처리량에는 timeout의 효과가 band 안이었다.

### 이번 실행으로 판단할 수 없는 사항

1. **진짜 동시 중복 실행과 fencing.** SIGKILL은 fail-stop이므로 두 judge가 같은 제출에 동시에
   살아 있는 형상이 만들어지지 않았다. `attempts > 1`은 복구 재claim이며 동시 중복 CPU
   실행이 아니다.
2. **judge-1의 in-process counter 실제 값.** 프로세스와 함께 사라졌다. 남은 값은 하한이다.
3. **judge-1이 실제로 들고 있던 row 수.** outbox에 `claimed_by`가 없다.
4. **재기동 32~37s의 내역.** 컨테이너 기동, JVM 기동, readiness UP, dispatcher 활성 중
   어디에 얼마가 걸렸는지는 이 계측으로 분해되지 않는다.
5. **`T_stale`의 정확한 시각.** outage 구간의 poll 간격이 2.7–4.1s이고(§5), A에서는 첫 poll이
   이미 회수를 보았으므로 A의 관측값 2.712s에는 관측이 주는 하한이 없다. 그래서 §10은 lease
   만료 하한과 durable `attempts`로 따로 논증한다. B·C의 구간 폭은 3.696s·1.979s다.
6. **재claim이 실제로 시작된 시각과 그 순간의 queue 상태.** 1초 표본 사이의 사건이다.
7. **MIF16/2500ms와 MIF64 두 run의 raw RPS·raw latency 우열.** offered load가 다르다.
8. **mif와 stranded의 비례 관계.** 세 run에서 mif와 도착 타이밍과 부하가 함께 변했다.
9. **다른 하드웨어·다른 부하·다른 latency 분포에서의 재현성.** 조건별 1회, 단일 호스트,
   judge 컨테이너 2개, slow 비율 5%다.
10. **timeout의 최적값.** 4s와 10s만 비교했다. 3s·6s 같은 값은 측정하지 않았다.

## 17. 후속 실험 후보

**이번 라운드에서는 실행하지 않는다.** 기록만 한다.

1. **`docker pause → lease 만료까지 대기 → unpause`.** 이번 실험이 시험할 수 없었던 유일한
   형상이다. pause된 프로세스는 죽지 않았으므로 unpause 후 자기 claim이 이미 회수되어
   다른 노드가 채점 중인 row를 완료하려 시도한다. 여기서 fencing(claim token 검증이 중복
   scoreboard 반영을 막는가)이 실제로 시험된다. 관측해야 할 것: `staleTokenCompletions`,
   `completionFailure{outcome}`의 증가, 최종 `finalResultMismatch`, 그리고 같은 제출에 대한
   두 번의 judge invocation.
2. **heartbeat 기반 lease 갱신.** 이번 측정의 `T_stale`은 세 run 모두 lease 만료보다 늦었고,
   회수는 생존 노드의 poll이 claim할 때 일어나므로 lease 만료는 하한일 뿐이다(§10, §16 추론 3).
   heartbeat가 있으면 죽은 노드의 claim을 timeout까지 기다리지 않고 회수할 수 있다. 관측할 것:
   같은 조건에서 `T_stale`과 reclaimed cohort `L_total`이 얼마나 줄어드는가.
3. **재기동 시간 단축.** §5의 gate 표에서 컨테이너 기동 후 readiness까지가 29.8~34.5s로
   from-fault 회복 시간의 약 4.4s 차이를 만들었다. 이 구간을 줄이는 것이 timeout을 2.5배
   늘리는 것보다 backlog 정상화에 더 큰 효과를 낼 수 있다. 관측할 것: 같은 세 조건에서
   readiness 시간을 줄였을 때 from-fault 회복 시간의 변화.
4. **회수 지연의 초과분을 정하는 요인.** §16 추론 3이 분리하지 못한 부분을 직접 시험한다.
   kill 순간 생존 노드의 per-poll claim 여유는 A 13, B 49, C 48이었는데 초과분은 A의 하한
   2.251s와 C의 1.979s가 2s 근처에 몰리고 B만 3.696s로 단조가 아니었다. mif와 offered load를
   각각 고정·변화시켜 후보 기제(poll 간격 100ms, 생존 노드의 부하, `mif − reserved` 여유)를
   하나씩 분리해야 한다.
5. **MIF64/4s의 정상상태 여유 재확인.** 4s는 MIF64에서 중복 0이 확인된 가장 짧은 값이라
   아래쪽 여유가 가장 적다. §13의 판정이 4s를 권하면서도 이 사실을 함께 남기는 이유다. 더 긴
   hold와 더 높은 RPS에서 4s의 정상상태 중복이 0으로 유지되는지 확인해야 한다.
6. **timeout을 연속 축으로.** 2.5s·4s·10s 세 점은 단조성을 보였지만 최적값을 주지 않는다.
   같은 mif·같은 RPS에서 3s·5s·6s를 측정하면 `T_stale`과 reclaimed `L_total`의 곡선을 얻는다.
7. **하네스 down window guard 수정 후 재측정.** 현재 guard는 관측의 시작만 deadline 앞으로
   제한하므로(§5, §18), 세 run 모두 마지막 관측이 deadline을 넘겼고 C는 재기동 요청을 0.400s
   늦췄다. 관측을 deadline **안에서 끝내도록** 고치면(예: 남은 시간이 COUNT 왕복보다 짧으면
   관측을 건너뛰고, `downWindowObservationBasis` 문장도 실제 동작에 맞게 다시 쓴다) down
   duration이 정확히 15s가 되고 C의 from-fault 회복 시간이 그만큼 짧아진다. 수정은 측정값을
   바꾸므로 분석기 재계산으로 흡수할 수 없다 — 새 RunId로 다시 측정해야 하고, 그러면 위 1~6과
   같은 이유로 **이번 라운드의 3조건 고정 행렬 밖**이다.

## 18. Provenance와 재현성

| 항목 | A | B | C |
|---|---|---|---|
| 실행 커밋 | `67b3e9634f82af93206799b6fecd9c46de8dfb92` | `b3674c1bbce1e5d4c35d495e0a8a0db3a162247e` | `b3674c1bbce1e5d4c35d495e0a8a0db3a162247e` |
| `gitTreeDirty` | false | false | false |
| `harnessTreeDirty` | false | false | false |
| harness SHA-256 | `DD69F1478553F34F0F80DADC9F7F46BFC028EE9BFEBC5FB827324F34E1963EF0` | 동일 | 동일 |
| analyzer SHA-256 | `9D5D5907C09A33DF902E1CE49E0B1C2C10ECBFF07FC1AD0BFC6AE9893F98CA3B` | 동일 | 동일 |
| 실행 시점 | 1차 (05:23–05:30 UTC) | 2차 (05:43–05:50 UTC) | 2차 (05:51–05:58 UTC) |

**A와 B·C는 커밋이 다르지만 harness와 analyzer의 SHA-256이 바이트 단위로 동일하다.**
A는 `67b3e96`, B·C는 `b3674c1`에서 실행됐고, 두 커밋 사이의 차이는 driver
(`Invoke-FaultRecoveryMatrix.ps1`)에 조건 부분집합 선택을 추가한 것뿐이다. **측정 경로를
구현하는 두 스크립트는 두 시점에서 동일하므로 세 run의 측정 코드는 같다.** 이 사실이
성립하는 근거는 커밋 메시지가 아니라 위의 SHA-256 두 개다.

세 run 모두 `gitTreeDirty = false`이므로 각 run의 `parameters.json`에 기록된 커밋을 checkout하면
그 run의 harness와 analyzer를 재현할 수 있다.

`parameters.json`에는 위 provenance 외에 `dispatchMode`, `targetRps`, `workerCountPerNode`,
`mysqlClaimBatchSize`, `mysqlMaxInFlightPerNode`, `mysqlClaimTimeout`, `mysqlPollInterval`,
`deterministicLatencySeed`, `latency`, `killedNode`, `downDurationSeconds`,
`drainTimeoutSeconds`, `judgeNodeCount`가, `faultRecovery` 블록에는 trigger 규칙·window·
down duration·measurement hold·`expectedPlan`이, `db-verification.json`에는 trigger 결과,
kill 시 실제 running/reserved, cluster-wide claimed 상한, down window 오차, nodeReady gate 증거,
`recoveryTimeout` 여부가 기록된다.

**측정 후 analyzer만 수정한 경우가 있었다.** smoke 단계에서 두 결함을 `67b3e96`에서 고쳤다:
(1) `earliestNormalizedAt`이 계산 가능한데도 비어 있던 문제, (2) **cohort C의 범위** —
그전에는 제출 시각 조건이 붙어 있어 kill이 고아로 만든 행을 떨어뜨렸고, 그 결과 smoke3에서
cohort C가 n=0으로 비었다. 수정 후 같은 원시 데이터에서 n=15가 됐다(§19). 두 수정 모두
원시 데이터를 보존한 채 분석만 다시 돌린 것이다. 최종 세 run의 analyzer SHA는 모두
`9D5D59...` 하나이며, 이 값은 위 수정을 포함한 상태다. **측정을 다시 돌리지 않았으므로 다시
실행한 것처럼 표현하지 않는다.**

**측정 후 하네스 결함도 하나 드러났으나 고치지 않았다.** down window의 관측 guard는 관측의
**시작**만 deadline 앞으로 제한하고, `Save-BacklogSample`은 두 COUNT 뒤에 행을 찍으므로 마지막
관측이 deadline을 넘길 수 있다(§5, §20). 세 run 모두 마지막 관측이 `downObserveUntil`을 넘겼고
C는 `restartScheduledAt`까지 0.400s 넘겨 재기동 요청을 그만큼 늦췄다. 이 결함은 **측정값 자체를
바꾸는 종류**이므로 분석기 수정처럼 같은 원시 데이터에서 다시 계산할 수 없다. 고친 harness로
다시 측정하면 down duration·`restartTimingErrorSeconds`·C의 throughput recovery가 달라지지만,
그것은 **네 번째·다섯 번째 run을 새로 만드는 일**이고 이 라운드의 3조건 고정 행렬을 벗어난다.
따라서 수정은 §17 후속 후보로만 남기고, **이 문서의 수치는 위 harness SHA가 만든 그대로**임을
명시한다. 세 run을 이 결함 기준으로 다시 실행한 적은 없다.

원시 결과는 `results/mysql-judge-tradeoff/`에 있다(`.gitignore`로 무시되며 커밋되는 산출물은
이 문서다). 실패·중단 run도 삭제하지 않았다.

## 19. 제외·진단 run

이번 라운드에서 세 조건 외에 **측정 부하를 실제로 건** run 전부와, 조건 B의 중단된 1차 시도다.
`dryrun-*` 접두 디렉터리는 파라미터·compose config만 검증하고 부하를 걸지 않으므로 이 표에
넣지 않았다. **이 표의 어떤 run에서도 비교 수치를 파생하지 않았다.**

| run directory | 조건 | 상태 | 사유 | 보존 |
|---|---|---|---|---|
| `fault-mif64-timeout4s-rps100-20260920` | B 1차 시도 (64 / 4s / 100) | `summary.json` 없음, `failure.txt` 없음 | 2026-09-20T14:35 호스트 메모리 압박 reaper가 프로세스를 강제 종료. **하네스 결함이 아니다** — 강제 종료라 하네스의 catch가 실행될 기회 자체가 없었고, 그래서 `failure.txt`도 없다. kill snapshot과 그 이후 원시 관측은 남아 있다. | 원시 11개 파일 (`kill-snapshot.json`, `killed-node-claims.csv`, `timeseries.csv`, `capacity.csv`, `backlog.csv`, `stage-trace.csv`, `parameters.json`, `compose-config.yaml`, `metrics`, warm-up gatling 산출물 3개) |
| `smoke-fault-recovery-20260920` | 16 / 2500ms / 80, warm-up 12s + measurement 120s | `summary.json` 있음, 커밋 `164b4fd` | 최초 end-to-end fault smoke. fault 경로 전체를 처음 태운 run이며 측정 hold가 120s였다. | 23개 파일 |
| `smoke2-fault-recovery-20260920` | 위와 같되 measurement 105s | `failure.txt` 있음 | **운영자 유발 파일 잠금.** 분석 중 `timeseries.csv`를 다른 프로세스가 점유한 상태에서 harness의 `Add-Content`가 `IOException`으로 실패했다. 결함 수정 후 새 RunId로 재실행했고 실패 run은 보존했다. | 12개 파일(`failure.txt` 포함) + `metrics` |
| `smoke3-fault-recovery-20260920` | 위와 같음, 커밋 `164b4fd` | `summary.json` 있음, hash가 stale | 기능적으로 유효. run 중 cosmetic 편집으로 기록된 hash가 stale해졌다. **분석기 수정 전의 판정을 `summary-prefix-sustain.json`/`.md`로 보존**했고, 수정 후 판정이 바뀌었다(cohort C가 `available=false`·n=0에서 `true`·n=15로). 두 판정을 모두 남긴다. | 25개 파일 (prefix 보존본 2개 포함) |
| `smoke4-fault-recovery-20260920` | 위와 같음, 커밋 `68821e3` | `summary.json` 있음 | 105s 창에서의 end-to-end 검증. 조건부 trigger·down window·nodeReady gate 4단·cohort·복구 anchor가 실제 경로에서 동작함을 확인했다. | 23개 파일 |

B의 1차 시도는 `fault-recovery-matrix-outcomes.json`의 `conditions[1].rerunReason`에 같은 사유로
기록돼 있고, 승인된 정책(결함 수정 후 새 RunId로 재실행, 실패 run은 보존하고 실패 사유 명시)에
따라 B는 `fault-mif64-timeout4s-rps100-rerun1-20260920`으로 다시 측정됐다. 참고로 비교 산출물
`fault-recovery-comparison.json`의 `excluded`는 **빈 배열**이다 — 실패 run은 `summary.json`이
없어 comparer에 입력조차 되지 않았기 때문이다. 즉 그 run이 성공으로 집계된 적은 없고, 실패
사유는 `rerunReason`과 보존된 `failure.txt`에 있다.
**자동 재실행이 아니라 운영자가 사유를 확인하고 RunId를 정해 실행한 것이다.**

이번 라운드 검증에서 발견해 고친 결함:

1. **warm-up trace 인자 순서.** 이번 라운드 이전 커밋에서 이미 수정돼 HEAD에 있었다.
2. **sampler 파일 쓰기 잠금에 대한 재시도 부재.** smoke2가 이 결함을 드러냈다
   (`timeseries.csv`가 다른 프로세스에 점유되면 `Add-Content`가 실패하고 run이 죽었다).
   수정 후 harness는 `sampleWriteRetries`를 세며, 최종 세 run은 모두 0이다.
3. **`earliestNormalizedAt`이 계산 가능한데도 비어 있던 문제.** ungated 탐색 결과를 gated
   값보다 엄격히 이른 경우에만 기록하고 있었는데, combined는 두 backlog 중 나중 값이라
   탐색 출발점을 앞당겨도 값이 늦어지지 않으므로 "두 탐색이 일치"한 경우가 `null`이 되어
   "탐색을 하지 않음"과 구분되지 않았다. `67b3e96`에서 값과 per-backlog 시각, 두 boolean을
   항상 보고하고 그 값이 탐색 출발점 기준의 읽기이며 달성된 회복이 아니라는 근거를 함께
   기록하도록 고쳤다. 최종 세 run 모두 ungated 값이 gated 값과 일치한다.
4. **중단 후 driver가 전체 행렬 재실행을 거부하던 문제.** B·C만 다시 돌릴 수 없어서
   `-Condition` 조건 선택과 `rerunRunId`를 추가하고(`b3674c1`), 실행하지 않은 조건은 기존
   디렉터리를 재사용하되 **비교가 세 조건을 모두 덮는지 확인**하도록 했다. 재사용한
   디렉터리는 `reusedDirectories`에 기록된다.
5. **Micrometer가 정수 gauge를 `"16.0"`처럼 렌더하는 문제.** Float 스타일로 파싱해야 하며,
   읽지 못한 gauge는 0이 아니라 `null`로 남긴다. kill snapshot의 `"running": "4.0"`이 그 흔적이고
   `thresholdReadings.basis`에 근거가 기록돼 있다.

## 20. 알려진 한계

- **조건별 1회 측정이다.** 세 run 모두 n=1이므로 위의 모든 차이는 그 run들 사이의 차이이고
  모집단에 대한 추정이 아니다. band는 그 run의 표본 간격에서 나온 분해 한계이지 신뢰구간이
  아니다.
- **MIF64 두 run의 재기동 타이밍 교란은 비교 대상 효과와 같은 크기다.** §13이 재영점하는
  근거인 `nodeReadyAt - restartRequestedAt` 차이(4.820s)가, 재영점 전 문제였던 from-fault
  차이(5.204s / 5.659s)와 같은 크기다. 즉 재영점은 그 교란을 제거하지만, 교란 자체가 n=1에서
  측정됐으므로 "재기동 시간이 같았다면"이라는 반사실을 이 실험이 직접 관측한 것은 아니다.
- **재영점은 재기동 타이밍이 claim timeout과 독립이라는 가정 위에 있다.** 이 가정을 반증하는
  기제는 없다(더 짧은 timeout이 Spring 기동을 늦출 경로가 없고, 더 느린 재기동은 4s run 쪽이다).
  다만 관측된 사실이 아니라 가정이므로 "코드와 측정으로부터의 추론" 등급에 둔다. §13.
- **단일 호스트, 컨테이너 2개, slow 비율 5%.** 재기동 32~37s와 포화 거동은 이 환경의 값이다.
- **`T_stale`의 관측 격자는 1초가 아니다.** pre-fault 구간은 1초지만 outage 구간에서 관측 1회는
  2.7–4.1s를 쓴다. A는 첫 poll이 이미 회수를 보았으므로 관측만으로는 회수 시각이 분해되지 않고,
  B·C의 구간 폭은 3.696s·1.979s다. §13의 B vs C 판정은 이 격자가 아니라 lease 만료 하한과 관측
  상한이 겹치지 않는다는 사실에 근거한다. §5.
- **down window의 마지막 2초 guard는 관측의 시작 시각만 제약한다.** `downObserveUntil`은 관측
  1회가 남은 시간보다 짧다는 가정 위에 서 있고, 세 run 모두 마지막 관측이 그 경계를 넘겨 끝났다
  (A 14.791s / B 14.501s / C 15.400s). C에서는 그 관측이 `restartScheduledAt`(15.0s)까지 넘겨
  재기동 요청이 0.402s 밀렸다. §5.
- **backlog peak과 growth는 표본에서 나온다.** pre-fault 구간의 표본 간격은 1초이고, 1초 안의
  상승은 보이지 않으며 growth는 하한이다. outage 구간의 표본 간격은 2.7–4.1s이므로 그 구간
  peak의 해상도는 100 RPS에서 270–410행이다.
  backlog peak 차이(+31)가 band(104.718) 안이라는 판정은 이 더 넓은 해상도에서도 성립한다.
- **judge-1의 counter는 하한이고, judge-1의 claim 귀속은 불가능하다.** §12.
- **cohort C의 n이 3/20/13이다.** 이 cohort의 percentile은 소수 관측이며 p99는 최대값이다.
- **`cluster-wide claimed unfinished upper bound`는 두 번 상한이다.** §10.
- **DB 시각과 host 시각을 함께 쓰는 계산에 소량의 clock offset이 섞인다.** lease 만료 시각
  (§10의 하한)이 그런 계산이다.
- **`attempts > 1`은 복구 재claim이다.** 동시 중복 CPU 실행이 아니다. §12.
- **timeout의 최적값은 이 실험이 답하지 않는다.** 4s와 10s 두 점만 비교했다.

## 21. 산출물

| 경로 | 내용 |
|---|---|
| `scripts/mysql-judge-tradeoff/Run-TradeoffExperiment.ps1` | harness. `-FaultRecovery` 모드, `Wait-UntilDeadline`, `Save-FaultSnapshot`, `Wait-ContainerRunning`, `Wait-JudgeNodeReady`, `Wait-MeasurementPhaseWithFault` |
| `scripts/mysql-judge-tradeoff/Analyze-TradeoffRun.ps1` | cohort A–E/F, 복구 시간 정의, pre-fault baseline, post-recovery 창 validity gate |
| `scripts/mysql-judge-tradeoff/Compare-FaultRecoveryRuns.ps1` | 세 요약의 비교표와 직접 비교, 정규화 지표 |
| `scripts/mysql-judge-tradeoff/Invoke-FaultRecoveryMatrix.ps1` | 세 조건 고정 행렬 driver, `-Condition` 부분집합 선택 |
| `results/mysql-judge-tradeoff/<runId>/` | run별 원시 산출물 23개 (git-ignored) |
| `results/mysql-judge-tradeoff/fault-recovery-comparison.{json,md}` | comparer 출력 (git-ignored) |
| `results/mysql-judge-tradeoff/fault-recovery-matrix-outcomes.json` | driver 결과와 조건 선택 기록 (git-ignored) |
| 이 문서 | `docs/MYSQL_JUDGE_FAULT_RECOVERY_COMPARISON.md` |
