# 4개 run 비교 — 현재 코드 세 모드 + 개선판 full-replay(Run B)

각 run은 1회 실행(단일 run)이다. 분포나 평균으로 말하지 않는다. 개선율·배수는 계산하지 않는다. 측정하지 못한 값은
`unavailable`로 그대로 적는다.

**Run B(`full-replay`, C2 브랜치 `codex/full-replay-background-replay`, background rollback replay)만 개선판
코드다. `full-replay`(Run A)·`redis-seq`·`stream-offset`은 모두 현재 코드(C1, `codex/scoreboard-recovery-live-impact`
`5c219e0`)다.** 세 가지 서로 다른 복구 경로(C1 3종)와 그중 하나를 고쳐 다시 잰 결과(C2 1종)의 비교이며, "4개 모드"의
비교가 아니다. Run A 세 run의 상세와 calibration 이력은 [RUN_A.md](RUN_A.md)·[COMPARISON_CURRENT.md](COMPARISON_CURRENT.md)에,
Run B의 상세는 [RUN_B.md](RUN_B.md)에 있다.

## 1. 실행 조건

| 항목 | full-replay(A, C1) | redis-seq(A, C1) | stream-offset(A, C1) | full-replay(B, **C2 개선판**) |
|---|---|---|---|---|
| 코드 | 현재 | 현재 | 현재 | **개선판(background replay)** |
| runId | `lifullreplay_r1_20260926152309` | `liredisseq_r1_20260926153528` | `listreamoffset_r1_20260926154709` | `lifullreplay_r1_20260926160914` |
| commit(run.gitHead) | `5c219e0` | `5c219e0` | `5c219e0` | `c7385aa`(merge) |
| jar sha256 | `f51e2411...c32a3a` | 동일 | 동일 | `e1573aec...be83459`(다름) |
| -TargetRps / -JudgedRatePerSecond | 500 / 457.733 | 500 / 457.733 | 500 / 457.733 | 500 / 457.733 |
| -SubmitIntervalMillis | 5000 | 5000 | 5000 | 5000 |
| rollback-replay 설정 | synchronous(C1 고정) | 해당 없음 | 해당 없음 | background(기본값) |
| exit code | 2(measured-incomplete) | 0(complete) | 0(complete) | 2(measured-incomplete) |
| exit 2의 원인 | 관측시간 부족(16s < 요구 60s) | — | — | **드레인 타임아웃**(관측은 충분: 334s > 60s. `dbPending=152`가 600s 안에 0이 되지 않음) |
| observationSufficient | false | true | true | true |
| 판정 | C | C | C | C |
| N (실제) | 106,596 | 106,439 | 99,538 | 105,196 |

Run A의 full-replay와 Run B의 exit 2는 **서로 다른 원인**이다 — 표면적으로 같은 exit code로 묶지 않는다
(RUN_B.md §6.2).

## 2. 신규 반영 정지 시간 · backlog · tail 복귀

| 지표 | full-replay(A) | redis-seq(A) | stream-offset(A) | **full-replay(B)** |
|---|---|---|---|---|
| newApplyStallLongestSeconds | 138 | 15 | 3 | **1** |
| newApplyStallTotalSeconds | 139 | 15 | 3 | **1** |
| backlogAtFault(baseline) | 1,644 | 1,676 | 884 | 1,774 |
| maxBacklogAfterFault | 68,047 | 8,853 | 1,842 | **2,320** |
| backlog 증가분(max − at fault) | 66,403 | 7,177 | 958 | **546** |
| T_max_backlog − T_fault | 137,484 ms | 14,883 ms | 3,156 ms | 1,758 ms |
| backlogDrainedAfterFaultMs | 342,223 ms | 27,462 ms | 5,376 ms | 15,758 ms |
| lostCount(tail) | 4,006 | 3,532 | 2,107 | 3,812 |
| T_tail_returned − T_fault | 83,402 ms | 6,667 ms | 2,688 ms | 10,362 ms |
| reconsumedAfterFault | 4,007 | 3,533 | 2,108 | **0** |
| replayThread | `oj-batch-scheduling-3`(consumer) | `oj-batch-scheduling-1`(consumer) | unavailable | **`scoreboard-full-replay`(전용 스레드)** |
| passesAfterRollback | 2 | 13 | 0 | 2 |

Run B의 정지 시간(1s)과 backlog 증가분(546)은 네 run 중 가장 작다 — 3단계에서 가장 나았던 stream-offset(정지 3s,
증가분 958)보다도 작다. 다만 tail 복귀(10,362ms)는 stream-offset(2,688ms)보다 늦고 redis-seq(6,667ms)보다도
늦다 — "정지 시간이 가장 짧다"가 "tail이 가장 빨리 돌아온다"를 뜻하지 않았다(RUN_B.md §6.1).

## 3. 유입률 spread와 KO

| 지표 | full-replay(A) | redis-seq(A) | stream-offset(A) | **full-replay(B)** |
|---|---|---|---|---|
| before.submitOkPerSecond | 496.205 | 499.023 | 353.727 | **429.860** |
| gatling.submitOk(전체) | 204,099 | 203,735 | 196,400 | 200,093 |
| gatling.submitKo(전체, 전부 HTTP 429) | 0 | 0 | 3,406 | **1,605** |
| gatling.ingressP95Ms | 393 | 251 | 5,037 | 4,607 |

**기록: Run B의 달성 유입률(429.860)이 Run A의 full-replay·redis-seq(496–499)보다 ±10% 넘게 낮다**
(각각 약 −13.4%, −13.9%). stream-offset(353.727, −28.8%)보다는 높다. Run B의 429 KO(1,605건, 전부 HTTP 429)는
loadStartMs 기준 30–110s 구간에 몰려 있고 fault(90.9s)보다 앞서 시작한다 — RUN_A.md §4가 기록한 stream-offset의
"fault와 무관한 제출 지연" 패턴과 같은 모양이며, 원인은 이 실험 범위 밖으로 남긴다. 네 run 중 두 run(stream-offset,
full-replay-B)에서 같은 모양의 429 편차가 나타났다는 것은 우연 이상일 수 있으나, 두 run만으로는 조건(실행 순서,
시각)을 통제하지 못했다.

## 4. 복구 중 추가 부하

| 지표 | full-replay(A) | redis-seq(A) | stream-offset(A) | **full-replay(B)** |
|---|---|---|---|---|
| mysql.Innodb_rows_read(fault→부하종료) | 72,923,141 | 45,050,542 | 1,784,952 | 37,479,042 |
| mysql.Com_update | 918,164 | 541,954 | 529,823 | 774,886 |
| batch.gcPauseCount | 399 | 237 | 180 | 275 |
| batch.gcPauseSecondsSum | 4.28 | 2.097 | 1.561 | 2.762 |
| redis(명령 수) | unavailable | unavailable | unavailable | unavailable |

judge tier의 채점 자체는 네 run 모두 제출을 크게 벗어나지 않았다(steady 구간 채점이 대체로 3,600–5,300/10s 대역,
부하 종료 뒤 tail은 전체 판정의 6–12% 안에서 25–47초 안에 빠졌다) — backlog·정지 시간의 차이는 judge tier의
처리 지연이 아니라 각 모드(그리고 Run B의 개선)가 복구 경로에서 실제로 무엇을 하는지에서 온다.

## 5. 선택 기준: 신규 반영 정지 시간 vs 복구 중 추가 부하가 N에 비례하는지 tail에 비례하는지

PLAN이 세운 두 축으로 네 run을 다시 본다.

**축 1 — 신규 반영 정지 시간(newApplyStallLongestSeconds).** 이 값이 작을수록 "복구 중에도 신규 제출이 계속
반영된다"는 이 실험의 핵심 질문에 직접 답한다. 순서: Run B(1s) < stream-offset(3s) < redis-seq(15s) <
full-replay-A(138s). Run B는 이 축에서 네 run 중 가장 좋다 — 개선(전용 스레드에서 즉시 COVERED 반환)이 실제로
정지를 없앴다는 증거다.

**축 2 — 복구 중 추가 부하가 N(대회 전체 행 수)에 비례하는지, tail(잃은 건수)에 비례하는지.** replay가 대회
전체를 다시 읽는 경로(full-replay-A: `replayRows` 167,085, 대회 전체 규모)는 N에 비례해 늘어나고, tail만
재소비하는 경로(stream-offset: tail 재소비 2,108건)는 tail에 비례한다. Run B는 `replayRows` 105,308로 여전히
대회 하나의 전체 규모(N=105,196)를 읽는다 — **범위 자체는 N에 비례하는 구조를 유지했다.** 개선은 "N에 비례한
replay를 없앤 것"이 아니라 "N에 비례한 replay를 라이브 경로 밖(전용 스레드)으로 옮겨, 그동안 신규 반영이
막히지 않게 한 것"이다(RUN_B.md §2). `mysql.Innodb_rows_read`(37.5M)가 redis-seq(45.1M)와 비슷한 자릿수이고
stream-offset(1.8M)보다 훨씬 큰 것이 이와 맞는다 — Run B도 여전히 MySQL을 크게 읽지만, 그 읽기가 신규 반영을
막지 않는다.

**결론.** 두 축을 같이 보면: full-replay 계열(A와 B)은 축 2에서 여전히 N에 비례한 무거운 replay를 한다. 다른
점은 축 1이다 — C1은 그 replay가 라이브 경로를 막아 정지 138s·backlog 68,047을 냈고, C2는 같은 크기의 replay를
배경으로 옮겨 정지 1s·backlog 546으로 줄였다. stream-offset은 애초에 축 2에서 가벼운(tail 비례) 경로라 정지도
짧았다(3s) — C2는 "무거운 replay를 유지하면서도 stream-offset에 준하는(그리고 이 두 run만 보면 더 짧은) 정지
시간"을 달성했다는 것이 이 4-run 비교의 핵심 관찰이다. 다만 각 조합 1회 실행이므로 이 순서가 항상 성립하는지는
확인하지 않았다.

## 6. stream-offset 유입률 이상치에 대한 유의사항

Run A의 stream-offset은 다른 두 run보다 달성 유입률이 −28.8% 낮았고(RUN_A.md §4), 원인 불명의 HTTP 429가 fault
이전부터 나타났다. §3에서 기록했듯 Run B에서도 정도는 다르지만 같은 모양(fault 이전 429, 유입률 저하)이
나타났다. stream-offset과 Run B 사이의 다른 지표(정지 시간, backlog, tail 복귀) 비교는 이 유입률 편차를 배경에
둔 채로 읽어야 한다 — 같은 유입률이었다면 두 run의 격차가 지금과 같았을지는 이 데이터로 확인할 수 없다.

## 7. 단일 run 주의

네 run 모두 각 조합 1회 실행이다. 배수·개선율은 계산하지 않았다. `unavailable`로 표기한 값(redis 명령 수,
stream-offset의 replay 관련 지표)은 0이 아니라 측정하지 못했다는 뜻이다. 이
결과들은 이 세션·이 스택(로컬 Docker Desktop, judge tier 관측 피크 약 460–490/s)에서의 관찰이며, 일반화하지
않는다.
