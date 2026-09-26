# Run A — calibration 이력과 세 모드 실행 (현재 코드)

이 문서는 3단계(Run A: `full-replay`, `redis-seq`, `stream-offset`, 모두 현재 코드)의 calibration 이력과
실행 조건·핵심 수치를 담는다. 세 모드 비교표와 가설 대조는 [COMPARISON_CURRENT.md](COMPARISON_CURRENT.md)에 있다.

## 1. calibration 이력

### 1.1 첫 실패 (이 세션 이전)

기본값 `-SubmitIntervalMillis 10000`으로 `-TargetRps 1000`을 돌렸을 때, 초당 1,000건 페이스면 세션 1만 개
(=전체 사용자)가 30초 ramp 동안 한꺼번에 로그인한다. web(1 CPU × 2)이 밀려 nginx `limit_conn proxied 3000`에
67,254번 걸렸고, KO의 대부분이 nginx 503이었다(429는 약 1천 건). 포트폴리오의 submit-1000 실험은 간격
5000ms·세션 5,000개로 거절률 1.06%였으므로, 이후 calibration은 모두 **`-SubmitIntervalMillis 5000`**을 썼다.

### 1.2 harness 블로커 두 건 (이번 세션에서 수정, 이미 커밋됨)

| run | 결과 | 원인 | 수정 |
|---|---|---|---|
| `lifullreplay_c1_20260926142255` | exit 1, 부하 없음 | 사전-seed 잔여 확인이 placeholder contest id 1로 스코프되어 다른 실험의 contest와 충돌 | `1041e9a`: 이 run이 seed한 contest 이름으로 스코프 |
| `lifullreplay_c1_20260926142851` | exit 1, 부하 없음(seed는 정리됨) | 공유 볼륨 `oj-loadtest-mysql-data`에 다른 브랜치의 V18이 적용되어 있어 Flyway 체크섬 불일치, app tier가 뜨지 못함 | `5c219e0`: `-StackMySql` 모드에 전용 볼륨 `oj-loadtest-mysql-live-impact-data`를 부여(사용자 승인) |

### 1.3 재calibration: 단계별 결과

조건: `-Mode full-replay -Phase calibration -StackMySql -SubmitIntervalMillis 5000` (표기 없으면 동일).

| TargetRps | runId | 판정 | submitOk/s | koRatio | judgedPerSecond | submit OK p50/p95/p99 ms | 429 | 503 | nginx limiting connections |
|---|---|---|---|---|---|---|---|---|---|
| 1000 | `lifullreplay_c1_20260926143950` | ko | 459.7 | 0.375 | 424.7 | 5391 / 11589 / 13404 | 3679 | 15799 | 33595(로그인+제출) |
| 850 | `lifullreplay_c1_20260926144836` | ko | 508.6 | 0.236 | 450.7 | 4711 / 9603 / 11382 | 2469 | 7850 | 16963 |
| 700 | `lifullreplay_c1_20260926145448` | ko | 528.8 | 0.069 | 473.8 | 2813 / 10181 / 11433 | 2356 | 0 | 0 |
| 550 | `lifullreplay_c1_20260926150611` | stable | 548.7 | 0 | 453.0 | 145 / 581 / 1204 | 0 | 0 | 0 |
| **500** | `lifullreplay_c1_20260926151428` | **stable** | 497.8 | 0 | 457.7 | 119 / 332 / 588 | 0 | 0 | 0 |

**적용값: `-TargetRps 500 -JudgedRatePerSecond 457.733`.**

### 1.4 429 해석 (사용자 가설, 확인됨)

- loadtest의 제출 쿨다운은 2000ms(`RedisContestSubmissionRateLimiter`의 Redis SETIFABSENT, `application-loadtest.properties`)로
  페이스 5000ms보다 짧다.
- 429는 제출 지연이 같은 사용자의 두 제출을 처리 시점에서 2초 안으로 밀어넣을 때만 나타난다. p50이 2초를 넘은
  1000/850/700에서 나타났고, p99가 1.2초 아래인 550/500에서는 사라졌다.

### 1.5 500을 고른 이유 (550이 아니라)

- calibration의 `stable` 판정은 스코어보드 backlog(judged − applied)만 본다. judge backlog(submitted − judged)는
  보지 않는다.
- `judged.csv`(seed=0, loadStartMs 기준 10초 bucket)로 보면:
  - 550에서는 실시간 채점이 4,569–5,156/10s로 제출 약 5,487/10s를 따라가지 못했다. 6,622건이 부하 종료 뒤에
    채점됐고, tail은 12.8s 뒤에 빠졌다 — judge backlog가 선형으로 쌓이는 중이었다.
  - 500에서는 4,588–5,186/10s로 제출 약 4,978/10s를 거의 따라갔다. 2,362건만 부하 종료 뒤에 채점됐고,
    5.9s 만에 빠졌다 — in-flight 분량에 가깝다.
- 그래서 500을 이 환경의 관측 피크로 삼았다. judge tier의 한계는 대략 460–490/s로 보인다.

**중요: `-TargetRps 500`은 이 환경(judge tier가 host CPU를 나눠 쓰는 로컬 Docker Desktop 스택)의 관측 피크이며,
제품의 처리 한계 주장이 아니다.** 포트폴리오의 submit-1000 실험은 실제로 초당 1,000건 제출을 밀어넣었지만, 그 실험은
backlog가 쌓이는 실험(과부하로 큐가 늘어나는 상황을 관찰)이었고, 이 문서의 500/s는 그와 달리 **steady-state**(큐가
늘지 않는 상태)를 유지하려는 값이다. 두 수치는 서로 다른 질문에 답한다.

## 2. Run A 조건

세 모드(`full-replay`, `redis-seq`, `stream-offset`) 모두 같은 commit, 같은 조건으로 1회씩 실행했다.

```
-Phase run -StackMySql -TargetRps 500 -JudgedRatePerSecond 457.733 -SubmitIntervalMillis 5000
```

| 항목 | 값 |
|---|---|
| commit | `5c219e0d7776e553fed3b030d0b622b23f279ab0` (세 run 모두 동일, `run.gitHead`) |
| jar sha256 | `f51e241114769ce6fea65bb9f1409ea0ab7c221fe7ff42734351d1d3a6c32a3a` (세 run 모두 동일) |
| ramp / baseline / tail / recovery budget / observe after | 30s / 30s / 5s / 300s / 60s |
| N (실제) | full-replay 106,596 · redis-seq 106,439 · stream-offset 99,538 |
| userCount / problemCount / acceptPermille | 10,000 / 10 / 400 |
| DB | `oj-loadtest-mysql`, `oj_loadtest`, 스키마 V18(migrated this run=False, 세 run 모두) |

## 3. Run A 핵심 수치 (모드별, 단일 run)

| 지표 | full-replay | redis-seq | stream-offset |
|---|---|---|---|
| runId | `lifullreplay_r1_20260926152309` | `liredisseq_r1_20260926153528` | `listreamoffset_r1_20260926154709` |
| exit code | 2 (measured-incomplete) | 0 (complete) | 0 (complete) |
| 판정 | C | C | C |
| 판정 이유 | 신규 반영 138s 정지, backlog 1,644→68,047 | 신규 반영 15s 정지, backlog 1,676→8,853 | 신규 반영 3s 정지, backlog 884→1,842 |
| replayThread | oj-batch-scheduling-3 (consumer) | oj-batch-scheduling-1 (consumer) | unavailable (트레이스에 PASS_START 없음) |
| replayPassKind | mysql-replay | sequence-check | unavailable |
| replayOutcome | COVERED | RETRYABLE_FAILURE | unavailable |
| passesAfterRollback | 2 | 13 | 0 |
| passesSkippedAfterRollback | 117 | 14 | 0 |
| reconsumedAfterFault | 4,007 | 3,533 | 2,108 |
| lostCount (tail) | 4,006 | 3,532 | 2,107 |
| T_tail_returned − T_fault | 83,402 ms | 6,667 ms | 2,688 ms |
| newApplyStallLongestSeconds | 138 | 15 | 3 |
| maxBacklogAfterFault | 68,047 | 8,853 | 1,842 |
| backlogDrainedAfterFaultMs | 342,223 | 27,462 | 5,376 |
| observationSufficient | false (관측 16s < 요구 60s) | true (328s) | true (347s) |
| 최종 digest | consistent=True | consistent=True | consistent=True |

단일 run이므로 위 수치는 분포가 아니다. 세 모드 사이의 배수·개선율은 계산하지 않는다.

## 4. 유입률 spread (달성치)

before 구간(fault 전, 정상 운전)의 `submitOkPerSecond`:

| 모드 | before.submitOkPerSecond | gatling.submitOk (전체) | gatling.submitKo (전체) | gatling.ingressP95Ms |
|---|---|---|---|---|
| full-replay | 496.205 | 204,099 | 0 | 393 |
| redis-seq | 499.023 | 203,735 | 0 | 251 |
| stream-offset | **353.727** | 196,400 | **3,406**(전부 HTTP 429) | **5,037** |

**기록: stream-offset의 달성 유입률이 다른 두 run보다 ±10% 넘게 낮다.** full-replay·redis-seq 대비 약
−28.8%(353.727 vs 496–499). `simulation.log`를 보면 stream-offset의 KO 3,406건은 전부
`status.find.is(202), but actually found 429`이고, nginx `limiting connections`는 관여하지 않았다(503 없음).
`judged.csv`를 loadStartMs 기준 10초 bucket으로 보면 이 429는 fault(로드 시작 후 약 84s) 이전인 40–90s
구간부터 나타나 fault와 무관하게 제출 지연이 이미 높았다: 이 구간의 제출 OK가 초당 약 320–460으로,
같은 시각대의 다른 두 run(약 490–520/s)보다 낮다. 원인은 이 run 하나에서만 관찰됐고(세 run은 이 자리 순서로
연속 실행됨), 이 run에서만 제출 지연이 429 임계(2s 쿨다운)를 넘었다는 것 외에 harness나 조건의 차이는 찾지
못했다. 세 run 모두 같은 스택·같은 조건이었으므로, 이 편차의 원인 규명은 이 run 밖의 범위로 남긴다.

## 5. judge-lag 확인 (judged.csv, seed=0, loadStartMs 기준 10초 bucket)

복구 영향(신규 반영 정지)과 judge tier 자체의 지연을 구분하기 위해, 실시간 채점 속도가 제출 속도를 따라가고 있었는지를
모드별로 확인했다.

| 모드 | steady 구간 실시간 채점/10s | 같은 구간 제출 OK/10s | 부하 종료 뒤 채점된 건수(tail) | tail이 빠지는 데 걸린 시간 |
|---|---|---|---|---|
| full-replay | 4,203–5,437(대부분 4,400–5,000) | 약 4,985–5,000 | 13,109 | 24,014 ms |
| redis-seq | 4,135–5,639(대부분 4,400–5,000) | 약 4,978–5,000 | 16,247 | 29,963 ms |
| stream-offset | 초반(40–90s, 429 구간) 2,507–4,758, 이후 4,096–5,228 | 초반 3,183–4,593, 이후 약 4,975–5,000 | 16,400 | 32,663 ms |

세 모드 모두 judge tier가 제출을 거의 실시간으로 따라갔고(부하 종료 뒤 남은 tail은 총 판정 건수의 6–8% 수준이며
25–33초 안에 빠졌다), 이번 §3의 backlog·정지 시간 차이가 judge tier 자체의 처리 지연이 아니라 각 모드의 복구
경로 차이에서 온다는 것과 들어맞는다. stream-offset의 초반 채점 저하는 §4에서 기록한 제출 측 429 편차를 그대로
반영한 것으로, judge tier의 용량 문제는 아니다.

## 6. cleanup

세 run 모두 `cleanup-scope.json`/`removed-rows.json`에 남긴 대로 각자의 contest(6/7/8)와
`sbrec_<runId>_` prefix 행만 지웠고, 최종 digest는 세 run 모두 `identical: True`였다. 상세 수치는
[COMPARISON_CURRENT.md](COMPARISON_CURRENT.md)와 원본 산출물(`var/scoreboard-recovery-live-impact/<runId>/`,
git-ignored)에 있다.
