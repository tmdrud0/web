# 스코어보드 복구 라이브 영향 — 40만 건, 세 개선안 비교

2026-09-29 측정. 대회 도중 Redis 스코어보드가 과거 스냅샷으로 되돌아갔을 때(롤백), 세 복구 구조가
라이브 반영에 주는 영향과 복구 비용을 같은 조건으로 비교했다. 모드마다 **한 번씩만** 측정했으므로
수치는 반복 측정 평균이 아니라 단일 관측이다.

## 1. 비교 대상

| 이름 | 브랜치 · 커밋 | 신규 반영 경로 | 롤백 복구 방식 |
|---|---|---|---|
| full-replay-bg | `codex/full-replay-background-replay` `55d4bf4` | RabbitMQ Stream consumer | MySQL의 대회 결과 **전체**를 최신순으로 백그라운드 재적용 |
| redis-seq (mysql-poll) | `codex/mysql-polling-redis-seq` `1cfdf8a` | MySQL poller (Stream 미사용) | MySQL watermark H와 Redis allocator R 비교 → 잃은 seq 범위 (R, H]만 재적용 |
| stream-offset | `codex/stream-offset-batch-apply` `da82448` | RabbitMQ Stream consumer, 배치 Lua | Redis checkpoint에서 재구독해 잃은 offset만 재독. checkpoint CAS, `applied-at-tracking=false` |

full-replay-bg 워크트리는 추적되지 않는 `tmp/`만 있어 `gitDirty=True`로 기록됐다. 코드 변경은 없다.

## 2. 조건

`gatling/run-recovery-live-impact.ps1`, 스택 MySQL(`-StackMySql`), 모든 run에서 공통:

- 사용자 40,000 · 문제 10 · 정답 비율 40% · 제출 간격 5초
- `TargetN=400000`: 롤백 시점에 DB 채점 결과가 약 40만 건이 되도록 seed(400 rps 380,000 / 100 rps 395,000)
- 부하 400 rps(세션 2,000) 또는 100 rps(세션 500), ramp 30초, 측정 창 약 5분
- 장애 주입: 스냅샷 → 수 초 뒤 Lua로 스냅샷 복원(롤백). harness가 복원 동안 batch 역할을 12~14초 멈춘다

### Redis 자동 저장(BGSAVE) 통제

롤백이 키 약 42만 개를 복원하면 Redis 기본 저장 규칙 `save 60 10000`이 바로 발동해 BGSAVE가 시작된다.
BGSAVE 중에는 Copy-on-Write로 쓰기가 느려져 배치 Lua가 평소 1.7ms에서 0.3~0.9s까지 늘었다.
첫 측정에서 stream-offset 두 run만 이 BGSAVE가 복구 구간과 겹쳤다(아래 7절). 그래서 표에는
**BGSAVE가 복구와 겹치지 않은 run만** 쓴다. stream-offset 두 run과 full-replay-bg 400 rps는
`CONFIG SET save ""`로 자동 저장을 끄고 측정했고, 나머지 세 run은 Redis 로그로 비겹침을 확인했다.
측정 후 설정은 `3600 1 300 100 60 10000`으로 되돌렸다.

## 3. 결과 — 400 rps

| | full-replay-bg | redis-seq (mysql-poll) | stream-offset |
|---|---|---|---|
| 잃은 결과 | 2,637 | 2,341 | 3,292 |
| 신규 반영 재개 | 2.0s | **1.1s** | 2.2s |
| 최장 정지 | 3s | **1s** | 3s |
| tail 복귀 | 13.8s | 6.7s | **6.4s** |
| 복구 완료 | 측정 창(316s) 안에 미완료 — 378,500행 처리 | **6.7s** | **6.4s** |
| 복구 작업량 | 전체 이력 재적용 | 잃은 2,341건 | 잃은 3,293건 재독 |
| 평시 지연 p50 / p95 | 0.17s / 0.44s | 0.27s / 1.11s | **0.11s / 0.24s** |
| 복구 중 지연 p50 / p95 | 4.44s / 6.48s | 3.27s / 4.98s | **0.11s / 2.12s** |
| backlog 평시 → 최대 | 336 → 3,407 | 686 → 2,731 | 688 → **1,325** |
| 끝내 미반영 | 0 | 0 | 0 |
| 판정 | C | C | C |

## 4. 결과 — 100 rps

| | full-replay-bg | redis-seq (mysql-poll) | stream-offset |
|---|---|---|---|
| 잃은 결과 | 912 | 853 | 695 |
| 신규 반영 재개 | 0.24s | **0.18s** | 1.28s |
| 최장 정지 | **0s** | 1s | **0s** |
| tail 복귀 | 4.4s | 4.1s | **3.4s** |
| 복구 완료 | 310s | **3.1s** | 3.4s |
| 복구 작업량 | 402,585행 전체 | 잃은 853건 | 잃은 696건 재독 |
| 평시 지연 p50 / p95 | 0.10s / 0.16s | 0.14s / 0.27s | **0.09s / 0.17s** |
| 복구 중 지연 p50 / p95 | 0.13s / 0.62s | 0.36s / 1.91s | **0.14s / 0.23s** |
| backlog 평시 → 최대 | 23 → 152 | 77 → 356 | 30 → **91** |
| 끝내 미반영 | 0 | 0 | 0 |
| 판정 | C | C | **B** |

### 지표 정의

- **신규 반영 재개**: 롤백(T_fault) 후 롤백 전에 반영된 적 없는 결과가 처음 반영되기까지.
- **최장 정지**: 1초 단위로, 채점된 결과가 있는데 신규 반영이 0건인 초가 연속된 최대 길이.
- **tail 복귀**: 롤백으로 잃은 결과가 모두 스코어보드에 돌아오기까지.
- **복구 완료**: 모드가 스스로 복구를 마쳤다고 보는 시점. full-replay-bg는 전체 replay 종료,
  redis-seq는 (R, H] 범위 COMPLETED, stream-offset은 재독이 롤백 전 위치를 다시 지난 시점(= tail 복귀).
- **지연**: `firstApplied − judgedAt`. 평시는 `[측정 시작, T_snapshot)`의 모든 결과,
  복구 중은 `[T_fault, T_recovered)`의 신규 결과(최소 10초, full-replay-bg 100 rps는 131초).
  스냅샷~롤백 구간(`tail`)은 harness의 batch 정지가 섞여 비교에서 뺐다.
- **판정**: C = 최장 정지 ≥ 2s 또는 복구 중 반영/채점 < 0.90 또는 backlog 증가 > 평시 채점률 × 1s.
  B = C가 아니고 복구 중 p95 > 평시 p95 × 1.2. 정의는 `PLAN.md` 3.5·판정 절.

## 5. 해석

- **복구 비용이 손실 크기에 비례하느냐**가 가장 큰 차이다. redis-seq와 stream-offset은 잃은 만큼만
  다시 적용했다. full-replay-bg는 잃은 결과가 수백~수천 건이어도 약 40만 행 전체를 다시 적용했고,
  400 rps에서는 5분 측정 창 안에 끝나지 않았다. 100 rps에서 사용자 체감(재개 0.24s, tail 4.4s)이
  좋았던 것은 최신순 백그라운드 replay 덕분이지만, 부하가 오르자 replay가 라이브 반영과 Redis를 나눠
  쓰며 복구 중 지연 p95가 6.5s까지 올랐다.
- **redis-seq (mysql-poll)** 은 신규 반영 재개가 두 부하 모두 가장 빨랐다(1.1s / 0.18s). 롤백 감지가
  poller의 주기 검사와 Lua expected-watermark 검사로 이뤄지고, 신규 반영이 복구를 기다리지 않는다.
  대신 polling 구조라 **평시 지연이 가장 높다**(400 rps p95 1.11s, stream-offset의 4.6배).
- **stream-offset** 은 평시·복구 중 지연과 backlog 증가가 가장 작았다. 배치 Lua로 적용 경로가
  짧아졌고, `applied_at` MySQL 쓰기를 뺐기 때문이다. 잃은 offset을 순서대로 다시 읽어야 신규로
  넘어가므로 재개는 1~2초 늦다. 또 복구가 순차 재독이라 Redis가 느려지는 상황(BGSAVE)에 가장 민감했다
  (7절: 겹쳤을 때 tail 13.5s, 재개 6.5s).
- 두 개선안은 **트레이드오프**다. 라이브 지연을 우선하면 stream-offset, Stream 인프라 없이 MySQL과
  Redis만으로 가고 재개 속도를 우선하면 redis-seq.

## 6. 이번 측정에서 발견하고 고친 결함

| 결함 | 영향 | 수정 |
|---|---|---|
| stream-offset 되감기 catch-up이 1초에 1배치만 진행 (2026-09-20 `30d3641`부터 존재) | 되감기 후 다음 배치가 `checkpoint < highestAppliedOffset`을 새 롤백으로 오판 → 실패 → supervisor(1s)가 다시 되감기. 1,782건 복구에 되감기 5회, 정지 8s | `a1c5c02`: 되감는 모드에서 해당 휴리스틱 제거(CAS가 대신 감지), supervisor가 catch-up 중임을 기억. 6배치 롤백 → 되감기 1회 |
| stream-offset checkpoint race | `resolveAdvance`가 lock 밖에서 checkpoint를 읽은 뒤 Lua 실행 전에 Redis가 복원되면, Lua가 offset > checkpoint만 보고 적용해 잃은 구간을 조용히 건너뜀. 재현 테스트에서 checkpoint 9 → 29 점프 | `af5900f`: 배치에 expected checkpoint floor를 넘기고 Lua 안에서 `현재 < floor`면 아무것도 바꾸지 않고 ROLLBACK 반환. `listreamoffset_r1_20260929020829`에서 롤백 0.06s 뒤 감지 |
| harness `-Build`가 옛 이미지로 migration | 새 Flyway 스크립트(V19, V20)가 적용되지 않아 run이 시작 전 실패 | `1cfdf8a` / `95cba05`: migration 전에 앱 이미지 빌드 |

## 7. BGSAVE와 겹친 run (참고, 표에서 제외)

| run | 조건 | BGSAVE (롤백 기준) | 재개 | tail | 복구 중 p95 |
|---|---|---|---|---|---|
| `listreamoffset_r1_20260929000940` | stream-offset, catch-up 버그 수정 전, 400 rps | −0.5s ~ +15.6s | 8.0s | 11.5s | 7.59s |
| `listreamoffset_r1_20260929020829` | stream-offset, 수정 후, 400 rps | −0.4s ~ +16.9s | 6.5s | 13.5s | 7.14s |
| `listreamoffset_r1_20260929022600` | stream-offset, 수정 후, 100 rps | −0.4s ~ +9.7s | 0.83s | 6.6s | 0.68s |

같은 코드(`da82448`)로 자동 저장만 끄자 400 rps에서 tail 13.5s → 6.4s, 복구 중 p95 7.14s → 2.12s가 됐다.
실제 운영에서도 RDB 복원 직후 쓰기가 몰리면 비슷한 상황이 생길 수 있으므로, 순차 재독 방식은 Redis
쓰기 지연에 취약하다는 관찰로 남긴다.

## 8. 한계

- 모드당 단일 run. 반복 측정 분산은 모른다.
- 부하 생성기(Gatling)와 스택이 같은 Windows 머신(Docker Desktop)에서 돌았다. 절대 처리량이 아니라
  같은 조건에서의 상대 비교로만 읽는다.
- 측정용 MySQL 컨테이너(메모리 2.5GB)가 run 3~4회마다 seed 도중 OOM으로 종료돼 두 번 재시작했다.
  종료된 run은 버리고 다시 측정했고, 이후에는 run마다 MySQL을 재시작했다.
- stream-offset 100 rps run은 supervisor가 롤백을 먼저 감지했는데 harness가 그 경로를 trace로
  남기지 않아 `detectedBy`가 `unavailable`이다.
- full-replay-bg 브랜치에는 배치 Lua가 없다(500행을 한 건씩 EVAL). 배치 Lua를 넣으면 전체 replay
  시간은 줄겠지만, 비용이 전체 이력에 비례한다는 구조는 그대로다.

## 9. 원자료

`results-400k/<runId>/`에 각 run의 `live-impact-summary.md`, `live-impact-summary.csv`,
`run-events.properties`를 복사해 두었다. trace·Gatling 로그 등 전체 산출물은 각 워크트리의
`var/scoreboard-recovery-live-impact/<runId>/`(git 미추적)에 있다.

| 표 칸 | runId |
|---|---|
| 400 rps full-replay-bg | `lifullreplay_r1_20260929071002` |
| 400 rps redis-seq | `liredisseq_r1_20260928234447` |
| 400 rps stream-offset | `listreamoffset_r1_20260929073334` |
| 100 rps full-replay-bg | `lifullreplay_r1_20260929002704` |
| 100 rps redis-seq | `liredisseq_r1_20260929013201` |
| 100 rps stream-offset | `listreamoffset_r1_20260929184535` |
