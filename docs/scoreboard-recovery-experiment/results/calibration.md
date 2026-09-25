# Calibration — 고정 변수 확정 (2026-09-25)

이 문서는 본 실험(pilot)에 쓸 **고정 변수의 확정값**과, 그 값을 그렇게 정한 **근거**를 기록한다.
측정 결과가 아니라 조건이다. 값을 "운영 규모"라고 부르지 않고 이 환경에서 실제로 재서 정했다.

* suite: `var/scoreboard-recovery/20260925-094406-suite-calibration` (git-ignored, commit하지 않음)
* 명령: `run-recovery-pilot-suite.ps1 -Phase calibration -HoldSeconds 120`
* 실행 revision: `352ff43` (suite-metadata.json의 `gitHead`), `git status --porcelain` 빈 값
* 소요: 11.3분, run 3개(모드당 1개, `runIndex=0`), DB `oj_test` @ `127.0.0.1:3307`
* 판정: **1 complete / 2 measured-but-incomplete / 0 not measured** (exit 2)

---

## 1. 확정값

`runIndex 1–3` × 모드 3개 = 9 run. calibration에서 쓴 값과 **같은 값**을 pilot에 넘긴다.
달라지는 것은 `-HoldSeconds` 하나뿐이고, 그 이유는 §3에 있다.

| 항목 | 값 | calibration에서 | pilot에서 | 비고 |
|---|---|---|---|---|
| `-UserCount` | 200 | 같음 | 같음 | contest당 200명 |
| `-ProblemCount` | 5 | 같음 | 같음 | |
| `-ContestDurationMinutes` | 90 | 같음 | 같음 | seed contest 기간 |
| `-TargetRps` | 40 | 같음 | 같음 | 실제 달성 유입률은 §4 |
| `-SubmitIntervalMillis` | 5000 | 같음 | 같음 | 세션당 pacing |
| `-RampSeconds` | 15 | 같음 | 같음 | |
| `-HoldSeconds` | **240** | 120 | 240 | §3에서 올림 |
| `-BaselineResults` | 60 | 같음 | 같음 | baseline 대기 하한 |
| `-BaselineWindowSeconds` | 20 | 같음 | 같음 | latency 기준 구간 |
| `-TailResults` | 20 | 같음 | 같음 | **하한**이다. §5를 반드시 읽을 것 |
| `-PollIntervalSeconds` | 2 | 같음 | 같음 | poll 1회 실측 2.4–4.3초 |
| `-SettleTimeoutSeconds` | 120 | 같음 | 같음 | |
| `-DrainTimeoutSeconds` | 180 | 같음 | 같음 | |
| `-GatlingTimeoutSeconds` | 900 | 같음 | 같음 | |
| `-IngressSloP95Millis` | 60000 | 같음 | 같음 | **SLO 상수(문턱값)이며 측정값이 아니다** |
| `-Repeats` | 3 | 1 | 3 | 모드당 3회 |
| DB / port | `oj_test` / 3307 | 같음 | 같음 | 각 run이 batch-1의 접속 포트와 대조 |

pilot 명령:

```powershell
$env:DB_PASSWORD = '<docs/ENVIRONMENT.md에서 읽음>'
$env:DB_PORT = '3307'
.\gatling\run-recovery-pilot-suite.ps1 -Phase pilot -HoldSeconds 240 -StopOnFirstFailure
```

요약 산출물도 **같은 `-HoldSeconds`를 넘겨야 한다**. `summarize-recovery-pilot.ps1`의 기본값은 240이지만
`-UserCount`/`-TargetRps` 등도 표에 그대로 복사되므로, 실제로 돌린 값을 명시하는 편이 안전하다.

---

## 2. calibration 3 run의 실측값

전부 `recovery-summary.csv`에서 읽은 값이다. `T_fault`는 `faultAtUtc`.

| | full-replay | redis-seq | stream-offset |
|---|---|---|---|
| run / contest | `fullreplay_0` / 440 | `redisseq_0` / 441 | `streamoffset_0` / 442 |
| K 시점 `processed` | 1222 | 1357 | 1324 |
| capture pause | 32154 ms | 34286 ms | 32643 ms |
| fault pause | 43796 ms | 47015 ms | 45014 ms |
| `lostCount` (되돌린 결과 수) | 1661 | 1809 | 1719 |
| `T_consistent − T_fault` | 47708.7 ms | 51138.2 ms | 55035.5 ms |
| 위 값 − fault pause | **3912.7 ms** | **4123.2 ms** | **10021.5 ms** |
| `T_consistent − T_detected` | 4160.3 ms | 4409.3 ms | 10307.1 ms |
| `detectedKind` | detected-rewinding | detected-rewinding | detected-rewinding |
| 최종 digest 일치 | True (5083/5083) | True (5091/5091) | True (5103/5103) |
| 되돌린 결과 재적용 | 1661/1661 | 1809/1809 | 1719/1719 |
| `complete` | True | False | False |

* **정합성은 3 run 모두 회복됐다.** `finalDigestMatches=True`이고, 잃은 결과가 전부 다시 반영됐으며
  (`1661/1661` 등), 최종 `processed`와 `applied`가 같다. 즉 "측정이 안 된 것"이 아니라
  **측정은 됐고 완전성 조건 하나를 못 넘긴 것**이다(exit 2).
* `complete=False`의 사유는 두 run 모두 동일하다: `the recovery did not finish before the load's hold ended`.

---

## 3. `-HoldSeconds`를 120에서 240으로 올린 근거

`recoveryCompletedInsideLoad`는 **유입이 계속 들어오는 동안 회복이 끝났는가**를 뜻한다. 이 실험은
"회복 중 신규 유입의 영향"을 재는 것이므로, hold가 끝난 뒤에 정합해진 run은 그 질문에 답하지 못한다.

실측 타임라인 (load는 `T_load + RampSeconds + HoldSeconds`에 끝난다. `recoveryCompletedInsideLoad`의
정의가 그 부등식이다):

| | full-replay | redis-seq | stream-offset |
|---|---|---|---|
| `T_load` | 00:45:09.834 | 00:48:53.346 | 00:52:39.323 |
| `T_fault − T_load` | 84.5초 | 90.0초 | 86.9초 |
| load 종료 (= `T_load`+135초) | 00:47:24.834 | 00:51:08.346 | 00:54:54.323 |
| `T_consistent` | 종료 **2.7초 전** | 종료 **6.1초 후** | 종료 **6.9초 후** |

120초 hold에서 필요 시간은 `fault pause(44–47초) + 모드 자체 회복(4–10초) + poll 입도(2–4초)` ≈
50–61초인데, 남는 시간이 약 45–50초였다. **full-replay만 2.7초 차이로 통과**했고 나머지 둘은 실패했다.
2.7초는 재현 가능한 여유가 아니다(같은 모드의 capture pause만 봐도 회차마다 수 초 흔들린다).

240초 hold에서는 load가 `T_load+255초`까지 살아 있고 fault가 약 85–90초에 주입되므로 남는 시간이
약 165초 — 가장 느린 stream-offset이 요구하는 61초의 2.7배다. 그래서 **240을 확정값으로 쓴다.**
비용은 run당 약 2분(9 run 전체로 약 20분)이다.

---

## 4. 실제 데이터 크기와 실제 유입률

"200명 / 40 rps"는 **설정값**이고 아래는 **측정값**이다.

| | full-replay | redis-seq | stream-offset |
|---|---|---|---|
| 최종 결과 수 (scoreboard = oracle) | 5083 | 5091 | 5103 |
| 유입 성공률 (`gatlingSuccessPercent`) | 100% | 100% | 100% |
| 유입 실패 요청 | 0 | 0 | 0 |
| ingress p95 (`gatlingObservedP95Millis`) | 218 ms | 227 ms | 213 ms |
| ingress max (`gatlingObservedMaxMillis`) | 345 ms | 536 ms | 573 ms |
| baseline 신규 결과 반영 지연 p50 / p95 | 78 / 128 ms | 76 / 119 ms | 105 / 156 ms |
| `maxUnappliedResults` (회복 중 미반영 최대) | 182 | 44 | 1576 |
| `appliedDeltaDuringRecovery` | 569 | 1435 | 1113 |
| `maxStreamOldestReadySeconds` | 0.15초 | 0.54초 | **48.63초** |
| `minStreamQueueConsumers` | 1 | 1 | **0** |
| `repairDurationMs` (정합 후 배출 완료까지) | 13099.2 ms | 7811.9 ms | 11812.5 ms |

* 최종 결과 수는 run당 약 5.1천 건이다. `HoldSeconds=240`이면 **약 2배(약 1만 건)가 될 것으로 예상**하며,
  이는 설계 기대이지 측정값이 아니다.
* **`gatlingP95Millis=60000`은 측정값이 아니라 `-IngressSloP95Millis` 상수다.** 측정된 p95는
  `gatlingObservedP95Millis`(213–227 ms)이며, 요약표도 이 열을 쓴다.
* `recoveryLagP50Ms`와 `lagP95IncreaseMs`는 **3 run 모두 `unavailable`**이다. 회복 구간에 반영 지연을
  정의할 수 있는 행이 없었다는 뜻이고, 원인은 §8의 4번에 적었다. 0으로 읽지 말 것.
* stream-offset에서만 `minStreamQueueConsumers=0`(소비자가 사라진 poll이 있었다)이고
  `maxStreamOldestReadySeconds=48.63초`(큐가 안고 있던 가장 오래된 ready 이벤트의 나이)다. 나머지 두
  모드는 1과 0.5초 이하다. 두 판독이 같은 사건을 가리키는 것으로 보이며, pilot에서 확인한다.

---

## 5. `-TailResults`는 롤백 깊이의 "하한"이지 깊이가 아니다

plan은 롤백 지점과 소실 결과 집합을 **고정 변수**로 둔다. 그런데 `-TailResults 20`은 하한이고,
실제로 되돌려진 양은 **1661 / 1809 / 1719** — 요청한 20의 약 85배다.

기구는 이렇다. tail 대기는 `processed` 집합이 K 시점보다 `TailResults`만큼 커지기를 기다리는데,
그 판정이 **poll 안에서** 이뤄지고 poll 1회가 2.4–4.3초 걸린다. capture pause가 applier를 32–34초
동안 얼려 놓는 사이 유입은 계속 들어오므로, 풀린 직후에는 이미 큰 backlog가 쌓여 있다. 그 backlog가
한 poll 만에 반영되면서 대기가 발화하고, 그 시점의 `processed`가 곧 롤백 깊이가 된다
(`fullreplay_0`: K `processed=1222` → 다음 poll도 1222 → 6.4초 뒤 poll 2775에서 발화 → 직전 판독 2883 →
`lostCount=1661`).

**그래서 세 모드의 `lostCount`가 같지 않고, 그 차이(±8%)는 모드가 아니라 injector가 만든 것이다.**
pilot 보고서는 `lostCount`를 설정값이 아니라 **달성값**으로 모드별로 병기하고, 모드 간 회복 시간 비교는
injector를 뺀 값(§2의 3912.7 / 4123.2 / 10021.5 ms)으로 한다. 다음 본 실험에서 바꿀 점은
`deferred-harness-fixes.md` item 14에 적었다(Redis 전용 대기 루프로 setpoint 근처에서 발화시키고
달성 깊이를 분포로 보고).

---

## 6. injector footprint — `consistencyOutageMs`를 모드의 회복 시간으로 읽으면 안 된다

`consistencyOutageMs`(= `T_consistent − T_fault`)에는 injector가 batch-1을 얼려 둔 시간이 들어 있다.
fault pause는 44–47초이고, 위 표의 "− fault pause" 열이 그 시간을 뺀 값이다. 세 모드의 **원시 outage
차이(47.7 / 51.1 / 55.0초)는 대부분 pause 길이의 차이**이며 모드의 차이가 아니다
(pause만 43.8 / 47.0 / 45.0초).

`faultAtUtc`는 pause **안에서** 찍힌다. `Pause-Batch` → pre-rollback 집합 판독 → `T_fault` →
DEL/RESTORE → `Resume-Batch` 순서이므로 `faultPauseMs`에는 `T_fault` 이전의 판독 시간이 포함된다.
그 크기는 두 추정치의 차이로 0.3초 이하로 bound된다(3912.7 vs 4160.3 ms 등). 즉 위의 "− fault pause"
열은 모드 자체 회복 시간을 **약간 과소평가**하며, 그 오차는 0.3초 이내다.

---

## 7. 이 세 행에서 `detectionLatencyMs` 부호가 뒤집혀 있다

`recovery-summary.csv`의 `detectionLatencyMs`는 `Format-PilotElapsed`에 두 시각을 **거꾸로** 넘겨
계산됐다(`fault − detected`). 따라서 세 run 모두 음수로 기록됐다:

| | `T_detected − T_fault` (실제) | 기록된 값 |
|---|---|---|
| full-replay | +43548.4 ms | −43548.4 |
| redis-seq | +46728.9 ms | −46728.9 |
| stream-offset | +44728.4 ms | −44728.4 |

* 수정은 pilot 시작 **전에** 반영됐다(commit은 §9). pilot의 9개 run은 수정된 runner로 돌므로
  `results/summary.csv`에는 올바른 부호가 들어간다.
* 위 세 행의 올바른 값은 각 run의 `faultAtUtc`와 `recovery-log-events.json`에서 재계산한 것이며,
  suite를 다시 돌려 얻은 것이 아니다(부호만 바뀌는 표시 문제이고 측정값은 변하지 않는다).
* 이 값 자체를 모드의 검출 지연으로 읽지 말 것. 43–47초는 **injector가 applier를 얼려 둔 시간**이다.
  풀린 뒤 첫 로그까지의 시간은 `T_detected − (T_fault + faultPause)` ≈ **−0.25 ~ +0.4초**로,
  batch-1은 세 모드 모두 풀리자마자 되감기를 알아챈다(`detectedKind=detected-rewinding`).

---

## 8. 이 calibration이 말하지 **않는** 것

1. **모드 간 우열이 아니다.** 모드당 1 run이고, §5의 깊이 차이가 남아 있다. §2의
   3912.7 / 4123.2 / 10021.5 ms에서 stream-offset이 2.4배인 것은 관찰이지 결과가 아니다.
   (깊이는 stream-offset이 redis-seq보다 얕으므로 깊이로는 설명되지 않는다 — 그래서 pilot에서 확인한다.
   같은 run에서 소비자가 0까지 떨어지고 ready 이벤트가 48.63초 머문 것도 같은 관찰의 일부다.)
2. **정합성 판정 방식의 타당성.** `T_consistent`는 plan대로 Redis-vs-MySQL oracle digest 일치로만
   결정된다. 다만 이 harness는 거기에 `lostComplete`(되돌린 결과가 전부 돌아왔는가)를 **추가로** 요구한다
   (`deferred-harness-fixes.md` item 4). 이번 3 run에서는 두 조건이 같은 poll에서 함께 성립했으므로
   결과를 바꾸지 않았지만, 그 조건이 언제나 성립하는지는 pilot의 9 run이 답한다.
3. **poll 입도.** 회복 구간 poll은 2–3개뿐이고 poll 1회가 2.4–4.3초이므로 `T_consistent`의 정밀도는
   대략 ±poll 1회다. 초 단위 비교는 되지만 그 이하의 차이는 이 harness로 구분되지 않는다.
4. **`recoveryLag*`가 왜 전부 `unavailable`인지.** 그 값은
   `scoreboard_applied_at − COALESCE(final_judged_at, provisional_judged_at)`이고, 구간은
   `[injector 종료, T_consistent)`이다. 이 세 run에서 그 구간에 `scoreboard_applied_at`을 얻은 행이
   **0건**이었다 — applier가 얼어 있다가 풀린 뒤의 반영은 `T_consistent` **이후**에 몰려 있었다
   (`fullreplay_0`: `T_consistent` 시점 `applied=2883`이 4.2초 뒤 `4949`). 즉 "회복 중 신규 결과가
   늦게 반영됐다"가 아니라 **"회복 구간에 반영된 결과가 없다"**이며, 표본 0건이므로 `unavailable`이고
   절대 0이 아니다. 신규 유입 영향은 다른 열(`maxUnappliedResults`, `appliedDeltaDuringRecovery`,
   `gatlingObservedP95Millis`)로 판단해야 한다. 참고로 `scoreboard_applied_at`은 제품이 읽지 않는
   비권위 열이고 `repairPending`이 비동기로 보정하므로, 이 열의 시각 자체가 scoreboard의 진척보다
   늦게 움직인다.
5. **자원 비용의 모드 간 비교.** `maxAppProcessCpu`(1.000 / 0.992 / 0.983), `repairDurationMs`
   (13099.2 / 7811.9 / 11812.5)는 회복 구간 길이가 달라 그대로 비교할 수 없다. pilot 요약표의
   `*PerSecond` 비율 열을 쓴다.
6. **롤백 깊이가 고정됐다는 것.** §5 참조. 요청값 20에 대해 달성값이 1661 / 1809 / 1719이고,
   이는 pilot에서도 같다.

---

## 9. 이 문서를 쓴 revision

이 문서와 calibration 수치는 revision `352ff43`에서 실행된 suite의 산출물이다.
`detectionLatencyMs` 부호 수정(§7)은 그 뒤에 반영됐고, **pilot의 9 run은 모두 수정 후 revision으로
돌린다.** 수정은 한 열의 부호만 바꾸는 표시 변경이며, `-TargetRps`·`-HoldSeconds`·깊이·timeout 등
**측정 조건과 측정량은 하나도 바뀌지 않는다.** 따라서 이 문서의 calibration 값과 pilot 값을 같은
조건 위에서 비교할 수 있다.
