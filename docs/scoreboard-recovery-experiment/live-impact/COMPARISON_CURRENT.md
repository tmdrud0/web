# 세 모드 비교 (현재 코드, Run A) — full-replay / redis-seq / stream-offset

각 모드 1회 실행(단일 run)이다. 분포나 평균으로 말하지 않는다. 개선율·배수는 계산하지 않는다. 측정하지 못한 값은
`unavailable`로 그대로 적는다. 실행 조건과 calibration 이력은 [RUN_A.md](RUN_A.md)에 있다.

## 1. 실행 조건

| 항목 | full-replay | redis-seq | stream-offset |
|---|---|---|---|
| runId | `lifullreplay_r1_20260926152309` | `liredisseq_r1_20260926153528` | `listreamoffset_r1_20260926154709` |
| commit | `5c219e0` | `5c219e0` | `5c219e0` |
| jar sha256 | `f51e2411...c32a3a` | `f51e2411...c32a3a`(동일) | `f51e2411...c32a3a`(동일) |
| -TargetRps / -JudgedRatePerSecond | 500 / 457.733 | 500 / 457.733 | 500 / 457.733 |
| -SubmitIntervalMillis | 5000 | 5000 | 5000 |
| 실행 순서 | 1번째 | 2번째 | 3번째 (같은 세션에서 연속) |
| exit code | 2 (measured-incomplete) | 0 (complete) | 0 (complete) |
| observationSufficient | false (16s < 60s 요구) | true (328s) | true (347s) |
| N (실제) | 106,596 | 106,439 | 99,538 |
| contestId | 6 | 7 | 8 |

**조건 차이**: 세 run은 파라미터가 동일하지만 실행 순서(같은 세션에서 순차 실행)와 달성 유입률이 다르다.
stream-offset은 before 구간 제출 OK가 353.7/s로 다른 두 run(496–499/s)보다 약 −28.8% 낮고, KO 3,406건(전부
HTTP 429)이 있었다(다른 두 run은 KO 0). full-replay는 replay가 138초 걸려 `-RecoveryBudgetSeconds 300`·
`-ObserveAfterRecoverySeconds 60` 안에서 복구 뒤 관찰 시간이 16초로 부족해 exit 2(measured-incomplete)로
끝났다 — 수치 자체는 버려지지 않았고 §2 이하에 포함한다. RUN_A.md §4·§5에 상세 근거가 있다.

## 2. 판정과 이유

| 모드 | 판정 | 이유 |
|---|---|---|
| full-replay | **C** | 신규 반영이 138초 멈췄다(임계 2초 이상). backlog가 fault 직전 1,644에서 68,047로 늘었다 |
| redis-seq | **C** | 신규 반영이 15초 멈췄다. backlog가 1,676에서 8,853으로 늘었다 |
| stream-offset | **C** | 신규 반영이 3초 멈췄다. backlog가 884에서 1,842로 늘었다 |

세 모드 모두 C 판정이지만, 정지 시간과 backlog 최고점의 크기는 모드마다 크게 달랐다(§3).

## 3. 신규 반영 정지 시간 · backlog · tail 복귀

| 지표 | full-replay | redis-seq | stream-offset |
|---|---|---|---|
| newApplyStallLongestSeconds | 138 | 15 | 3 |
| newApplyStallTotalSeconds | 139 | 15 | 3 |
| T_longest_stall_start (fault로부터) | fault와 거의 동시 | fault와 거의 동시 | fault와 거의 동시 |
| backlogAtFault(baseline) | 1,644 | 1,676 | 884 |
| maxBacklogAfterFault | 68,047 | 8,853 | 1,842 |
| T_max_backlog − T_fault | 137,484 ms | 14,883 ms | 3,156 ms |
| backlogDrainedAfterFaultMs | 342,223 ms | 27,462 ms | 5,376 ms |
| lostCount(tail) | 4,006 | 3,532 | 2,107 |
| T_tail_returned − T_fault | 83,402 ms | 6,667 ms | 2,688 ms |
| reconsumedAfterFault | 4,007 | 3,533 | 2,108 |

lostCount·reconsumedAfterFault는 세 run의 유입률·tail 창(5s)이 같아도 fault 시점의 순간 처리량 차이로
2,107–4,006 사이에서 다르다. tail 복귀 시간(83.4s / 6.7s / 2.7s)과 backlog 해소 시간(342s / 27s / 5s)은
모드 사이에 큰 차이가 있지만, 각 모드 1회 실행이므로 이 차이가 모드 고유의 성질인지 이 세션의 우연인지는
이 결과만으로 구분할 수 없다.

## 4. replay/재소비 상세

| 지표 | full-replay | redis-seq | stream-offset |
|---|---|---|---|
| replayThread | `oj-batch-scheduling-3`(consumer 스레드) | `oj-batch-scheduling-1`(consumer 스레드) | `unavailable`(트레이스에 PASS_START 기록 없음) |
| replayPassKind | mysql-replay | sequence-check | unavailable |
| replayOutcome | COVERED | RETRYABLE_FAILURE | unavailable |
| replayDurationMs | 125,965 | 9,787 | unavailable |
| passesAfterRollback | 2 | 13 | 0 |
| passesSkippedAfterRollback | 117 | 14 | 0 |
| gapQuestionsAfterRollback | 118 | 10 | 0 |
| replayChunks / replayRows | 336 / 167,085 | 8 / 3,532 | unavailable / unavailable |
| chunkHoldP50Ms / MaxMs | 268 / 1,142 | 298 / 677 | unavailable |

full-replay는 `passesAfterRollback=2`로 PLAN §7-5가 코드상 가능하다고 본 "두 번째 replay"가 실제로 관찰됐다.
stream-offset은 트레이스에 `PASS_START`가 없어(§7-6의 rewind 경로는 이 기록을 남기지 않는다) replay 관련
지표가 전부 `unavailable`이다 — PLAN §3.1의 `T_detected` 정의가 명시한 한계다.

## 5. 구간별 처리량·지연 (p50/p95/p99, ms; 처리량은 초당)

### full-replay

| 구간 | 길이(s) | submitOk/s | submitKo/s | judged/s | applied/s | 반영지연 p50/p95/p99 |
|---|---|---|---|---|---|---|
| before | 44 | 496.205 | 0.000 | 431.000 | 424.568 | 169 / 591 / 2190 |
| tail | 9 | 422.556 | 0.000 | 580.222 | 445.111 | 1,363 / 140,894 / 141,119 |
| during | 343 | 501.347 | 0.000 | 465.344 | 468.793 | 67,959 / 132,337 / 137,915 |
| after | 16 | 421.125 | 0.000 | 484.500 | 500.875 | 386 / 646 / 773 |

### redis-seq

| 구간 | 길이(s) | submitOk/s | submitKo/s | judged/s | applied/s | 반영지연 p50/p95/p99 |
|---|---|---|---|---|---|---|
| before | 44 | 499.023 | 0.000 | 432.932 | 429.886 | 278 / 1,763 / 2,473 |
| tail | 10 | 420.400 | 0.000 | 500.400 | 353.200 | 1,831 / 17,936 / 18,123 |
| during | 28 | 526.179 | 0.000 | 472.679 | 475.714 | 9,404 / 15,981 / 16,622 |
| after | 328 | 496.979 | 0.000 | 459.348 | 464.091 | 138 / 281 / 694 |

### stream-offset

| 구간 | 길이(s) | submitOk/s | submitKo/s | judged/s | applied/s | 반영지연 p50/p95/p99 |
|---|---|---|---|---|---|---|
| before | 44 | 353.727 | 54.295 | 348.091 | 347.091 | 170 / 368 / 441 |
| tail | 10 | 258.200 | 39.400 | 293.300 | 214.700 | 215 / 6,356 / 6,590 |
| during | 10 | 452.700 | 61.400 | 345.300 | 427.100 | 214 / 3,664 / 3,775 |
| after | 348 | 499.667 | 0.026 | 455.037 | 455.060 | 136 / 280 / 606 |

stream-offset의 before·tail·during 구간에는 §1에서 기록한 429 KO가 섞여 있어, 같은 구간의 다른 두 모드보다
submitOk/s가 낮다(반면 during 구간 자체 길이도 10s로 다른 두 모드의 28–343s보다 훨씬 짧다 — `during`은
`[T_fault, T_recovered)`로 정의되므로 정지 시간이 짧을수록 구간도 짧다). 세 모드의 during 구간 길이가 다르므로
반영지연 수치를 모드 사이에서 직접 비교하지 않는다.

`duringThroughputRatio`(during 신규 반영/채점): full-replay 1.007, redis-seq 1.006, stream-offset 1.237.
세 모드 모두 판정 기준(< 0.90이면 C)의 처리량 조건에는 걸리지 않았다 — 세 모드 모두 C 판정을 받은 것은
반영 정지 시간 조건(§6.2 첫 조건, 최장 정지 ≥ 2s)이지 처리량 저하가 아니다.

## 6. 복구 중 추가 DB/Redis 부하

`run.counters.faultToLoadEnd.*`(fault부터 부하 종료까지 증분):

| 지표 | full-replay | redis-seq | stream-offset |
|---|---|---|---|
| mysql.Innodb_rows_read | 72,923,141 | 45,050,542 | 1,784,952 |
| mysql.Com_select | 434,629 | 433,400 | 414,831 |
| mysql.Com_update | 918,164 | 541,954 | 529,823 |
| batch.gcPauseCount | 399 | 237 | 180 |
| batch.gcPauseSecondsSum | 4.28 | 2.097 | 1.561 |
| batch.gcPauseMaxSeconds | 0.073 | 0.199 | 0.031 |
| redis(명령 수) | unavailable | unavailable | unavailable |

Redis `INFO commandstats` 증분은 세 run 모두 `unavailable`로 남았다(harness가 이번 실행에서 수집하지 못했다).
MySQL 쪽 `Innodb_rows_read`는 full-replay 72,923,141, redis-seq 45,050,542, stream-offset 1,784,952로
full-replay가 가장 많았다. full-replay의 replay가 대회 전체를 페이지 단위(1,000행)로 다시 읽는다는 PLAN §2 설명과
방향이 맞다. 단일 run이므로 모드 사이의 배수는 계산하지 않는다.

## 7. PLAN 가설과의 대조

| 가설 | 예상 | 관찰 |
|---|---|---|
| H1 (full-replay) | 신규 반영이 replay 내내 멈춘다. backlog ≈ 유입률 × replay 시간. tail은 replay가 끝날 때 돌아온다 | 정지 138s ≈ replayDurationMs 125,965ms(126s)에 가깝다. backlog 최고점 68,047 ≈ 유입률(약 465/s) × 138s(≈64,170)보다 크다 — replay 종료 후에도 재구독(§7-4, 재소비 4,007건)이 이어지며 backlog가 더 늘었다. tail 복귀(83.4s)는 replay 종료(125.97s)보다 **먼저** 왔다 — "replay가 끝날 때 tail이 돌아온다"는 예상과 다르다(tail은 스냅샷이 이미 처리했던 결과를 롤백 Lua가 지운 것이므로, 재구독이 그 offset을 다시 읽는 시점에 poller가 먼저 감지할 수 있다) |
| H2 (redis-seq) | 같은 동기 경로라 멈추지만 기간은 tail 후보 수·탐지 round 수에 비례한다 | 정지 15s, replayDurationMs 9,787ms. passesSkippedAfterRollback=14, gapQuestionsAfterRollback=10 — round 수가 두 자릿수로 세지만, full-replay보다 훨씬 짧다. tail 후보 3,532건은 full-replay(4,006)와 비슷한 규모였는데도 정지는 훨씬 짧아, "탐지 round 수에 비례"라는 정성적 방향은 맞지만 tail 후보 수만으로 기간을 설명하지는 못한다 |
| H3 (stream-offset) | consumer 재시작과 tail 재소비 동안 멈춘다. 기간 = 재시작 고정비 + tail에 비례 | 정지 3s로 세 모드 중 가장 짧다. replay 관련 지표가 모두 unavailable이라(§4) "재시작 고정비"를 트레이스로 분리하지 못했다. tail 2,107건, 복귀 2.7s — 세 모드 중 tail 후보가 가장 적고 복귀도 가장 빠르다는 점에서 방향은 가설과 맞는다 |
| H4 (Run B, 개선판) | 4단계에서 측정 | 이번 3단계에는 포함하지 않음 |

세 모드 모두 "replay/재구독 동안 신규 반영 0"이라는 §5의 공통 예측과 방향이 일치했다(정지 시간이 모두 2s 임계를
넘어 C 판정). 다만 정지 시간의 크기(138s vs 15s vs 3s)는 가설이 예측한 비례 관계(tail 후보 수·라운드 수)만으로는
설명되지 않고, 각 모드의 복구 경로(동기 MySQL 전체 재스캔 vs Redis 순서 확인 vs consumer rewind)가 더 크게
작용하는 것으로 보인다. 이는 각 모드 1회 실행의 관찰이며 일반화하지 않는다.

## 8. cleanup 요약

| 모드 | contest | prefix | 지운 대회 관련 행(대표) | Redis scoreboard 키 |
|---|---|---|---|---|
| full-replay | 6 | `sbrec_lifullreplay_r1_20260926152309_` | contest_submission 286,702 · contest_judge_outbox 209,589 | 92,115 |
| redis-seq | 7 | `sbrec_liredisseq_r1_20260926153528_` | contest_submission 286,697 · contest_judge_outbox 209,584 | 92,113 |
| stream-offset | 8 | `sbrec_listreamoffset_r1_20260926154709_` | contest_submission 277,807 · contest_judge_outbox 200,694 | 92,115 |

세 run 모두 최종 digest `identical: True`(참가자 10,000명 일치)였고, cleanup은 각 run의 contest id·prefix로만
스코프됐다(다른 대회·다른 실험의 행은 건드리지 않았다). 상세는 각 run의 `cleanup-scope.json`/`removed-rows.json`
(`var/scoreboard-recovery-live-impact/<runId>/`, git-ignored)에 있다.
