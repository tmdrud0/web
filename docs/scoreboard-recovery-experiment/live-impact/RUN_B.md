# Run B — full-replay 개선판(background rollback replay) 1회 실행

이 문서는 4단계(Run B: `full-replay`, 개선판 코드, C2 브랜치 `codex/full-replay-background-replay`)의 실행 조건과
핵심 수치를 담는다. 3단계(현재 코드) 세 run과의 4개 run 비교는
[FINAL_COMPARISON.md](FINAL_COMPARISON.md)에 있다. 개선안 자체의 설계는
[PLAN.md](PLAN.md) §8에 있다.

## 1. merge와 빌드

- worktree `C:\Users\Home\spring\web\web-full-replay-bg`, 브랜치 `codex/full-replay-background-replay`.
- merge 시작 전: HEAD `4df51fa5`(live-impact `7416969`에서 분기), `git status` 클린(추적되지 않는 `tmp/` 제외).
- `codex/scoreboard-recovery-live-impact`(HEAD `e1ffe6d0`)를 merge. 충돌은
  `docs/scoreboard-recovery-experiment/live-impact/README.md` 1건뿐이었고 `src/main`은 건드리지 않았다.
  - 충돌 내용: "3.3 Run B" 안내 문단. 개선판 쪽(HEAD)은 `-FullReplayRollbackReplay background`를 명시한 예시를,
    live-impact 쪽은 일반적인 "그 브랜치에서 bootJar 후 같은 명령" 안내를 갖고 있었다. `-FullReplayRollbackReplay`의
    기본값이 `background`임을 `gatling/run-recovery-live-impact.ps1:74`에서 확인한 뒤, live-impact 쪽의 `-StackMySql`
    실행 골격에 개선판의 스위치 설명을 얹어 병합했다(하네스 동작 자체를 바꾸는 결정이 아니라 두 문서를 합치는
    것이었으므로, 어느 브랜치 "기준"이라기보다 정보를 합쳤다). `src/main`이나 하네스 로직(.ps1) 파일에는 충돌이 없었다.
  - merge commit: `c7385aa` ("Merge branch 'codex/scoreboard-recovery-live-impact' into codex/full-replay-background-replay").
- migration 확인: 두 브랜치 모두 `V18__contest_result_scoreboard_applied_seq.sql`이 최신이다. 개선판이 분기 이후
  추가하거나 바꾼 migration 파일은 없다(`db/migration/` 목록에 V19 이상 없음). 전용 볼륨
  `oj-loadtest-mysql-live-impact-data`에 대한 Flyway 체크섬 불일치 위험 없음 — 실제로 이 run에서
  `migratedThisRun=False`, schema version 18(target 18)로 확인됐다.
- 빌드: `gradlew.bat bootJar` (BUILD SUCCESSFUL, `compileJava` UP-TO-DATE — merge가 `src/main`을 바꾸지 않았으므로
  당연하다), `gradlew.bat :gatling:classes :gatling:prepareStandaloneGatling` (BUILD SUCCESSFUL).
  `gradlew test`는 실행하지 않았다.
- jar `build\libs\web-0.0.1-SNAPSHOT.jar` sha256 `e1573aec2e2dc6b9473c28ad2310f54a3862d83fa615ba62702b3fed6be83459` —
  Run A 세 run의 jar sha256 `f51e2411...c32a3a`와 다르다(개선판 코드가 실제로 실행됐다는 증거).
  컨테이너 안에서도 같은 값(`run.containerJarSha256`)으로 확인됐다.

## 2. 개선판이 무엇을 바꿨나 (`7416969`..`4df51fa`, PLAN.md §8 요약)

| | C1 (synchronous, Run A의 full-replay) | C2 (background, Run B) |
|---|---|---|
| replay가 도는 곳 | 먼저 물은 스레드(consumer 또는 supervisor) | 전용 스레드 `scoreboard-full-replay` |
| 라이브 경로의 응답 | replay가 끝나야 COVERED, 그동안 거절 | 요청을 넘기고 즉시 COVERED, anchor 후 계속 적용 |
| 재구독 | replay 뒤 스냅샷 checkpoint부터 다시 읽음 | 거절이 없어 일어나지 않음(예상) |
| 대회 범위 | DB의 모든 대회 | 롤백된 checkpoint 이상에서 이 JVM이 쓴 대회만(범위 밖이면 전체) |
| replay 순서 | 대회 id 오름차순, submission id 오름차순 | 대회 id 내림차순, submission id **내림차순**(잃은 tail이 첫 청크에) |

commit 5개(`5ea88f0` 기능 추가, `3ec005a`/`4df51fa` 두 건의 안전성 수정, `aaf7b5d` 러너 스위치, `5dcd7cc` 문서):
- `5ea88f0`: rollback replay를 전용 스레드로 옮기고 즉시 COVERED를 반환하도록 바꿈. 대회 범위를 "이 JVM이 쓴 대회"로
  좁히고 replay 순서를 최신 대회·최신 submission부터로 바꿈.
- `3ec005a`: 컨테이너 환경변수가 빈 문자열일 때 enum이 null로 바인딩되어 조용히 synchronous로 떨어지던 버그 수정.
- `aaf7b5d`: 러너에 `-FullReplayRollbackReplay background|synchronous` 스위치 추가(기본 background).
- `4df51fa`: background 모드에서 "같은 top으로의 두 번째 롤백"이 `rebuiltAlready()`나 `(checkpoint, H)` 중복 제거에
  걸려 무시되던 경쟁 조건 수정 — 이제 시작하지 않은 pass를 기다리는 요청만 합치고, pass 시작 후 요청은 다음 pass를
  예약한다.

## 3. Run B 실행 조건

3단계(Run A)와 같은 조건: `-Mode full-replay -Phase run -StackMySql -TargetRps 500 -JudgedRatePerSecond 457.733 -SubmitIntervalMillis 5000 -Build`.

| 항목 | 값 |
|---|---|
| runId | `lifullreplay_r1_20260926160914` |
| commit(run.gitHead) | `c7385aa` (merge commit; `gitDirty=True`는 이 worktree의 추적되지 않는 `tmp/` 때문) |
| jar sha256 | `e1573aec...be83459`(Run A 세 run의 `f51e2411...c32a3a`와 다름) |
| ramp / baseline / hold / tail / recovery budget / observe after | 30s / 30s / 405s / 5s / 300s / 60s |
| N (실제) / N_total | 105,196 / 106,638 |
| userCount / problemCount / acceptPermille | 10,000 / 10 / 400 |
| DB | `oj-loadtest-mysql`, `oj_loadtest`, 스키마 V18(migrated this run=False) |
| rollback-replay | `background`(기본값, `run.fullReplayRollbackReplay`로 확인) |
| exit code | 2 (measured-incomplete) |
| 판정 | **C** |

## 4. 핵심 수치

| 지표 | Run B (full-replay, 개선판) |
|---|---|
| newApplyStallLongestSeconds / TotalSeconds | 1 / 1 |
| backlogAtFault(baseline) | 1,774 |
| maxBacklogAfterFault | 2,320 (증가분 546) |
| T_max_backlog − T_fault | 1,758 ms |
| backlogDrainedAfterFaultMs | 15,758 ms |
| lostCount(tail) | 3,812 |
| T_tail_returned − T_fault(tailReturnedAfterFaultMs) | 10,362 ms |
| reconsumedAfterFault | 0 |
| replayThread | `scoreboard-full-replay`(전용 스레드, consumer 아님) |
| replayPassKind / replayOutcome | mysql-replay / true |
| replayDurationMs | 116,705 ms |
| passesAfterRollback / passesSkippedAfterRollback | 2 / 0 |
| gapQuestionsAfterRollback | 1 |
| replayChunks / replayRows | 211 / 105,308 (N=105,196에 가깝다 — 대회 하나 분량, N_total 아님) |
| chunkLockWaitP50Ms / MaxMs | 94 / 725 |
| chunkHoldP50Ms / MaxMs | 295 / 757 |
| observationSufficient | true (334s > 요구 60s) |
| run.drained | **False** — exit 2의 원인(§6) |
| 최종 digest | consistent=True (10,000 vs 10,000 참가자) |
| duringThroughputRatio | 1.189 (판정 기준 0.90 미달 아님 — C는 backlog 조건에서 나왔다) |

## 5. PLAN §8.4 대조 (H4)

| 확인 항목 | 예상(PLAN §8.4, H4) | 관찰 |
|---|---|---|
| replayThread | `scoreboard-full-replay` | 일치 |
| newApplyStallLongestSeconds < 2 | 멈추지 않는다 | 1초 — 일치 |
| reconsumedAfterFault ≈ 0 | 재구독 없음(거절이 없으므로) | 0 — 일치 |
| replayRows | 대회 하나의 행 수(N_total 아니라 N) | 105,308 ≈ N(105,196) — 일치 |
| passesAfterRollback | 부하 중 첫 배달이 먼저 anchor하면 1, supervisor가 먼저 물으면 2(C2-fix, 정상) | 2 — 정상 범위 |
| tailReturnedAfterFaultMs가 첫 청크 시간 수준인가 | 첫 청크 처리 시간(chunkHoldP50=295ms대) 근방 | **10,362 ms — 첫 청크 시간(295~757ms)보다 한 자릿수 이상 길다.** H4가 명시적으로 예측한 것과 다르다(§6에서 상세) |

전반적으로 H4의 핵심 주장("멈추지 않는다")은 확인됐다 — 정지 1초, backlog 증가분 546은 3단계 세 run 중 가장 작았던
stream-offset(증가분 958)보다도 작다. 다만 tail 복귀 시각이 "첫 청크 근방"이라는 예상보다 훨씬 늦었다(§6).

## 6. 관찰된 차이와 미해결 항목

### 6.1 tail 복귀가 첫 청크보다 늦다

`tailReturnedAfterFaultMs`(10,362ms)는 `chunkHoldP50Ms`(295ms)·`chunkHoldMaxMs`(757ms)보다 훨씬 크다. 개선안은
"대회 id 내림차순, submission id 내림차순"으로 잃은 tail이 **첫 청크**에 오도록 설계됐다(PLAN §8.1). 이 run에서는
`replayChunks=211`이고 tail이 210번째 이후 청크에서야 나온 것처럼 보이지는 않는다(정지 자체는 1초로 끝났으므로
tail 복귀 지연이 replay 자체의 진행 지연은 아니다) — 그러나 tail이 **poller가 감지하기까지**의 지연(Redis
`sbrec:lost:<runId>` 집합에서 사라지는 시점)과 "replay가 첫 청크를 이미 apply했다"는 것이 같은 시각이 아닐 수 있다.
이 run 하나만으로는 원인(청크 안에서 submission 정렬 순서, apply와 poller 관측 사이의 지연, 또는 다른 요인)을
가르지 못한다. Run A의 full-replay(C1)에서도 tail 복귀(83.4s)가 replay 종료(126s)보다 먼저 왔다는, 예상과 다른
순서가 관찰됐었다(RUN_A.md §7 H1) — 이 run과 마찬가지로 poller 관측과 replay 내부 진행 사이의 관계는 이 실험
범위에서 완전히 규명되지 않았다.

### 6.2 exit 2의 원인: 정지가 아니라 부하 종료 뒤 드레인 타임아웃

Run A의 full-replay(3단계)는 관측 시간 부족(16s < 요구 60s)으로 exit 2였다. Run B는 다르다: `observationSufficient=true`
(334s), `traceComplete=true`, `finalConsistent=True`이고 **`run.drained=False`만** exit 2의 원인이다
(`$complete = $drained -and $final.Matches -and traceComplete -and observationSufficient`,
`gatling/run-recovery-live-impact.ps1`). 즉 이 run의 복구 측정 자체는 완결됐고(§4·§5), 부하 종료 뒤 파이프라인이
운영상 정지(quiescent) 상태에 도달하는 것을 기다리는 별도 점검에서 타임아웃(600초)했다.

로그: `drain: the pipeline after the load did not reach operational quiescence within 600 seconds (judge=0,
unapplied=0, pending=0, live=0/0, dead=0/0, stream=1181401/0/c1, dbPending=152)`. judge outbox·unapplied·pending
이벤트·live/dead 큐는 모두 0으로 정상이었고, RabbitMQ 스트림의 `1181401`은 스트림이 보존하는 로그 총량이라
quiescence 판정에서 이미 빠져 있다(`RecoveryExperiment.Sampler.ps1`의 주석 참고). 유일하게 비지 않은 값은
Redis 집합 `contest:scoreboard:stream:db-pending`의 카디널리티(`dbPending=152`)다. 이 키는
`RedisContestScoreboardApplier`/`ContestScoreboardAppliedAtCompletion`(`src/main/java/.../scoreboard/redis/`,
`.../scoreboard/stream/`)이 쓰고 지우는 완료 표시 집합이며, 정지 조건은 정확히 0을 요구한다.

**이 세션에서 원인을 규명하지 못했다.** 600초 동안 152가 그대로였는지, 서서히 줄고 있었는지는 harness가 최종
값만 기록해 이 run의 산출물로는 구분할 수 없다(중간 폴링 로그는 남지 않는다). 개선판이 배경 스레드로 replay를
분리하면서 완료 콜백 경로가 조금 더 오래 걸리는지, 아니면 이 run에만 있던 우연인지는 이 run 하나로 판단할 수
없다. `src/main`은 이번 작업에서 고치지 않았고, 이 관찰은 harness 버그가 명확하지 않으므로 별도 커밋으로
고치지도 않았다 — 사실만 기록한다.

### 6.3 달성 유입률과 429

`before.submitOkPerSecond`는 429.860으로 Run A의 full-replay(496.205)·redis-seq(499.023)보다 약 −13.4%·−13.9%
낮다(±10% 밖). `gatling.submitKo`는 1,605건, 전부 HTTP 429(`status.find.is(202), but actually found 429`) —
nginx `limiting connections`는 이 run에서 0건이었고 503도 없었다. loadStartMs 기준 429의 분포는 30–110s 구간에
집중돼 있고(40–60s 구간이 가장 많음, 251+602건), fault는 90.9s 시점이다 — 즉 429의 대부분이 **fault 이전**에
이미 나타났다. 이는 RUN_A.md §4에서 기록한 stream-offset의 패턴(fault와 무관하게 제출 지연이 이미 높아 429가
나던 것)과 같은 모양이며, 이 run에서만 재현된 원인 불명의 편차로 남긴다(RUN_A.md §4가 남긴 범위 밖 문제와 동일
범주로 취급한다).

judge tier 자체는 이 run에서도 실시간 채점이 제출을 크게 벗어나지 않았다 — `judged.csv`(seed=0, loadStartMs
기준 10초 bucket)로 보면 steady 구간(ramp 뒤부터 부하 종료까지) 채점이 대체로 3,600–5,300/10s였고, 부하 종료 뒤
tail 25,273건(총 판정 204,922건의 12.3%)이 47.3초 안에 빠졌다. 3단계 세 run의 tail 비율(6–8%, 25–33초)보다
약간 크고 느리지만 같은 자릿수다.

## 7. cleanup

`cleanup-scope.json`/`removed-rows.json`대로 contest 9와 `sbrec_lifullreplay_r1_20260926160914_` prefix 행만
지웠다: `contest_submission` 282,035행, `contest_judge_outbox` 204,922행, `contest_submission_result` 282,035행,
`user` 10,000행, `problem` 10행, `contest` 1행(그 외 테이블은 0행). Redis에서 `sbrec:snap:...` 90,174키,
`sbrec:lost:...` 1키, 스코어보드 키 92,113개를 지웠다. 최종 digest `identical: True`(참가자 10,000명 일치).
원본 산출물은 `var/scoreboard-recovery-live-impact/lifullreplay_r1_20260926160914/`(git-ignored)에 있다.
