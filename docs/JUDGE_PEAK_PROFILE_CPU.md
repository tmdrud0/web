# 채점을 실제 CPU 연산으로 바꿨을 때 기본 실험의 결론이 유지되는가 (CPU 부하 변형)

## 질문

`docs/JUDGE_PEAK_PROFILE_BASE.md`(기본 실험)는 채점을 sleep으로 흉내 냈다. sleep은 스레드를 점유하지만
CPU를 쓰지 않는다. 이 실험은 같은 부하 모양·같은 조건에서 채점만 실제 CPU 연산으로 바꿔 반복한다.

> 같은 명목 k에서 sleep → CPU 연산으로 바꿀 때 두 방식(RabbitMQ, MySQL)의 유효 k(효율)와 백로그 지속
> 시간이 어떻게 바뀌는가? 기본 실험의 방식 간 차이는 유지되는가?

결과를 미리 정하지 않았다.

## 1. 구현과 검증

### 구현

`CpuLoadProfileContestJudgement`(`src/main/java/my/oj/web/submission/judge/CpuLoadProfileContestJudgement.java`)를
`LatencyProfileContestJudgement`(sleep)와 나란히 추가했다.

- **같은 분류 로직**. 두 구현 모두 `ContestJudgeLatencyProperties.deterministicDraw`/`isSlow`를 그대로 쓴다.
  같은 seed·같은 제출은 sleep이든 CPU든 같은 slow/fast를 받는다(단위 테스트
  `CpuLoadProfileContestJudgementTests.slowAndFastClassificationMatchesTheSleepJudgeForTheSameSeedAndDraws`가
  30개 제출에 대해 두 구현을 나란히 돌려 fast/slow 카운트가 일치함을 확인한다).
- **모드 선택**: `contest.submission.judge.latency.mode`(`sleep` 기본값, `cpu` 옵션, 환경변수
  `JUDGE_LATENCY_MODE`). `@ConditionalOnExpression`으로 `enabled=true` AND `mode=sleep`(또는 미설정)일 때만
  sleep 구현이, `enabled=true` AND `mode=cpu`일 때만 CPU 구현이 빈으로 등록되어 기존의 "구현이 정확히
  하나만 존재" 규칙(`ContestProvisionalJudgement`와 합쳐 정확히 하나)을 유지한다.
- **CPU 시간 기준**: 목표는 벽시계가 아니라 `ThreadMXBean.getCurrentThreadCpuTime()`이다. 생성자에서
  `isCurrentThreadCpuTimeSupported()`를 확인하고, 지원되지 않으면 빈 생성에서 즉시 `IllegalStateException`을
  던져 노드가 시작하지 않는다(로컬/CI를 포함해 이번에 쓴 모든 호스트에서 지원됨을 확인했다 — 실행 중 한 번도
  이 경로를 타지 않았다). 50,000회 연산 단위(청크)마다 한 번만 CPU 시간을 읽어 그 자체의 비용을 줄였다.
- **JIT 제거 방지**: 청크 결과를 `volatile long sink`에 매 청크 발행해 루프가 죽은 코드로 제거되지 않게 했다.
- **지표**: 기존 `contest.judge.latency.class.duration`(벽시계) 옆에
  `contest.judge.latency.class.cpu.duration`을 추가했다(`ContestJudgeLatencyClassMetrics.recordCpu`).
  sleep 구현도 이번에 같이 CPU 시간을 재도록 고쳐, 두 모드에서 벽시계/CPU 비율을 같은 방법으로 비교할 수 있다.

### 검증 결과

- 단위 테스트: `CpuLoadProfileContestJudgementTests`(목표 CPU 시간 근처에서 멈추는지, 분류 일치)와
  `ContestJudgeLatencyPropertiesTests`(mode 기본값/인식)가 통과한다. `gradlew.bat test`(전체 스위트)가
  통과한다.
- **목표 CPU 시간에 정확히 닿는다.** k=8.14 정상 배분(judge cpus=3/3) 실행의 judge-1 Prometheus 스크레이프:
  fast 평균 CPU 50.09~50.1ms(목표 50ms), slow 평균 CPU 2000.06~2000.1ms(목표 2000ms). 벽시계도 거의 같다
  (fast 50.4ms, slow 2011.5ms) — 정상 배분에서는 호스트 경합이 거의 없다는 뜻이다.
- **과할당(judge cpus=1.5/1.5, 3 worker)에서는 CPU 시간은 그대로, 벽시계만 늘어난다.** 같은 judge-1의
  fast: CPU 50.09ms(목표대로) vs 벽시계 78.66ms(비율 1.57). slow: CPU 2000.06ms vs 벽시계 3573.5ms(비율
  1.79). 이것이 설계 의도의 핵심 증거다 — 코어를 뺏겨도 "한 일의 양"은 줄지 않고, 그 대가는 벽시계로만
  나타난다.
- **judge 컨테이너 CPU 한도가 의도대로 적용됨**: `docker compose config`로 `CONTEST_JUDGE_CPUS=3` /
  `_2_CPUS=3` / `JUDGE_LATENCY_MODE=cpu`가 judge-1·judge-2에 반영됨을 확인했고, 미설정 시 기존 값(0.75,
  sleep)과 바이트 단위로 같았다. 실행 중 `container-cpu-1s.csv`에서: 정상 배분(judge cpus=worker 수)은
  `meanCoresTopPeak`가 한도 근방(2.4~2.5, 한도 3)이고 `throttledMsLoad`가 360,000ms 창에서 0.8~2.6초로
  미미했다. **과할당(judge cpus=1.5)은 `meanCoresTopPeak`가 한도에 정확히 고정(1.497~1.500, 한도 1.5)되고
  `throttledMsLoad`가 344,000~349,000ms** — 360초 부하 창 거의 전체에서 CFS에 쓰로틀링당했다는 뜻이다.

## 2. sleep vs CPU: 같은 방식·같은 k끼리

sleep 값은 기본 실험(`peak-comparison-b5-20260925/comparison.md`, 반복 중앙값)을 그대로 재사용했다. CPU
값은 이번 실행(`peak-comparison-b5cpu-20260926/comparison.md`)이다. 정상 배분(judge cpus = worker 수)만
비교하고, 과할당은 3절에서 따로 다룬다.

### 유효 용량과 백로그

| k_nom | 방식 | 채점 | n | 백로그 s | k_eff | 효율 k_eff/k_nom | L>10s | 피크 p99 s |
|---:|---|---|---:|---|---:|---:|---|---|
| 5.42 | MySQL | sleep | 3 | 220 [220–230] | 4.18–4.24 | 0.771 [0.770–0.781] | 3708 [3681–4164] | 64.2 [61.6–69.7] |
| 5.42 | MySQL | CPU | 2 | 230 [230–230] | 4.11–4.14 | 0.760 [0.758–0.763] | 4164 [4147–4180] | 71.9 [71.3–72.6] |
| 5.42 | RabbitMQ | sleep | 3 | 210 [210–230] | 4.34–4.36 | 0.802 [0.801–0.804] | 3942 [3647–4187] | 62.7 [58.6–63.0] |
| 5.42 | RabbitMQ | CPU | 2 | 225 [220–230] | 4.23–4.28 | 0.784 [0.780–0.788] | 4144 [4026–4262] | 68.0 [66.3–69.8] |
| 6.78 | MySQL | sleep | 2 | 165 [160–170] | 5.19–5.20 | 0.766 [0.765–0.767] | 2758 [2695–2821] | 30.6 [30.1–31.2] |
| 6.78 | MySQL | CPU | 1 | 180 | 5.07 | 0.748 | 3069 | 33.5 |
| 6.78 | RabbitMQ | sleep | 2 | 165 [160–170] | 5.37–5.38 | 0.792 [0.792–0.793] | 2536 [2379–2692] | 25.7 [24.4–26.9] |
| 6.78 | RabbitMQ | CPU | 1 | 170 | 5.22 | 0.770 | 2822 | 30.8 |
| 8.14 | MySQL | sleep | 2 | 95 [90–100] | 6.41–6.53 | 0.796 [0.788–0.803] | 1064 [895–1234] | 14.8 [13.9–15.7] |
| 8.14 | MySQL | CPU | 1 | 140 | 5.95 | 0.731 | 1577 | 17.2 |
| 8.14 | RabbitMQ | sleep | 2 | 70 [60–80] | 6.71–7.00 | 0.843 [0.825–0.860] | 490 [151–830] | 11.8 [10.3–13.3] |
| 8.14 | RabbitMQ | CPU | 1 | 90 | 6.46 | 0.794 | 757 | 13.2 |

- **모든 조건에서 sleep보다 CPU에서 유효 k와 효율이 낮다.** 절대 차이는 k가 커질수록 커진다: 5.42에서
  MySQL −0.011pt(0.771→0.760), RabbitMQ −0.018pt; 6.78에서 MySQL −0.018pt, RabbitMQ −0.022pt; **8.14에서
  MySQL −0.065pt(0.796→0.731), RabbitMQ −0.049pt(0.843→0.794)**. k=5.42·6.78의 차이는 기본 실험이 보고한
  반복 간 변동 폭(±0.2~1.8%p)과 겹치거나 그 근처다. **k=8.14의 차이는 그 폭을 크게 넘는다** — n=1(RabbitMQ는
  2)이라 통계적으로 약하지만, 방향이 4개 조건 모두 일관되고 크기가 k와 함께 커지는 패턴은 우연으로 보기
  어렵다.
- **백로그도 같은 방향**: 8.14 MySQL 95s(sleep) → 140s(CPU), 8.14 RabbitMQ 70s → 90s.

### replay@k_eff — "이 실행은 자기 유효 k 하나로 재현되는가"

| k_nom | 방식 | 채점 | 백로그 실측 | replay@k_eff |
|---:|---|---|---:|---:|
| 5.42 | MySQL | CPU | 230 | 230–240 |
| 5.42 | RabbitMQ | CPU | 220–230 | 230 |
| 6.78 | MySQL | CPU | 180 | 180 |
| 6.78 | RabbitMQ | CPU | 170 | 170 |
| 8.14 | MySQL | CPU | 140 | 140 |
| 8.14 | RabbitMQ | CPU | 90 | 90 |

CPU 모드의 8개 실행 모두 replay@k_eff가 실측 백로그를 0–10s 차이로 맞춘다 — 기본 실험과 같은 정도의
정확도다. **유효 k 하나로 그 실행을 설명할 수 있다는 결론은 CPU 모드에서도 유지된다.**

### 채점 벽시계/CPU와 채점 외 점유 (Decompose, 정상 배분)

| k_nom | 방식 | 명목(측정 벽시계 평균) ms | 채점 외 점유 합 ms | implied(명목+채점외) ms | 효율(명목/implied) | 참고: sleep implied ms / 효율 |
|---:|---|---:|---:|---:|---:|---|
| 5.42 | MySQL | 154.3–154.5 | 20.8–21.5 | 193.2–194.5 | 0.795–0.799 | 188.9–191.5 / 0.81 |
| 5.42 | RabbitMQ | 153.6–154.2 | 16.2–16.4 | 187.1–189.1 | 0.816–0.821 | 183.6–184.2 / 0.84 |
| 6.78 | MySQL | 156.3 | 24.2 | 197.2 | 0.793 | 192.3–192.8 / 0.81 |
| 6.78 | RabbitMQ | 156.4 | 14.3 | 191.4 | 0.817 | 186.0–186.3 / 0.84 |
| 8.14 | MySQL | 156.7 | 27.9 | 201.7 | 0.777 | 183.7–187.2 / 0.80–0.81 |
| 8.14 | RabbitMQ | 147.8 | 27.8 | 185.7 | 0.796 | 171.5–178.9 / 0.81–0.83 |

포화 구간 안에서 **제출 1건당 점유(디스패치 오버헤드 포함)는 sleep과 CPU가 거의 같다**(187~202ms vs
172~193ms, 두 모드 모두 방식 간 순서도 유지: RabbitMQ가 MySQL보다 낮다). 즉 "채점이 CPU를 쓰면 채점
1건당 처리 시간이 늘어난다"는 가설(a)의 좁은 의미(디스패치 코드 자체가 더 오래 걸린다)는 이번 측정
범위에서는 뚜렷하지 않다 — k=8.14 MySQL만 27.9ms로 기본 실험의 16.4~20.2ms보다 확실히 높고, 나머지는
sleep과 겹친다.

**그런데 위 표의 효율(0.78~0.82)은 앞 절의 k_eff/k_nom 효율(0.73~0.79)보다 언제나 높다.** 이 차이가
CPU 모드 손실의 실제 위치를 가리킨다: Decompose는 포화 구간 "안에서" 집힌 작업의 점유만 재므로,
포화 구간 자체가 비는 시간(작업이 줄 서 있는데도 아무 worker도 집지 못하는 짧은 공백)은 들어가지
않는다. k_eff는 그 공백까지 포함한 처리율이다. 그래서 **CPU 모드에서 커진 손실은 "제출 1건의 디스패치가
더 오래 걸려서"가 아니라, "worker가 집을 준비가 됐는데도 호스트 전체 CPU가 모자라 잠깐씩 못 집어서"에
가깝다.**

## 3. 판정: 방식 간 차이는 유지되는가, 그 설명은

**유지된다 — 오히려 k가 커질수록 확대됐다.** RabbitMQ가 MySQL보다 항상 효율이 높다(5.42: +0.024pt,
6.78: +0.022pt, 8.14: **+0.063pt**). 기본 실험의 격차(+0.031, +0.026, +0.047)와 비교하면 5.42·6.78은
거의 그대로이고, **8.14는 기본 실험보다 벌어졌다**(0.047 → 0.063).

세 가지 후보 설명 중:

1. **CPU 경쟁(디스패치 코드가 채점 CPU와 직접 다툰다)** — Decompose의 "채점 외 점유"가 sleep과 거의
   같으므로(8.14 MySQL만 예외) 이 경로의 증거는 약하다. MySQL의 디스패치 코드(claim, outbox
   `completeAll`)는 실제로 CPU를 쓰는 코드이지만, 그 자체가 유의미하게 느려졌다는 신호는 8.14 MySQL 한
   조건에서만 보인다.
2. **호스트 CPU가 부족해지는 순간의 "유휴 은닉"(worker가 비었는데 호스트가 못 준다)** — 이번 측정에서
   가장 설득력 있다. k_eff 손실(호스트 전체 처리율)이 Decompose 손실(포화 구간 내부 점유)보다 항상 크고,
   그 간극이 k와 함께 커진다. 호스트 CPU도 같은 방향이다: k=5.42 피크 평균 61.7~70.5%, k=6.78
   71.6~73.7%, **k=8.14 81.5~83.1%**(정상 배분 두 실행 모두)로, k가 커질수록(judge cpus 한도가 커질수록)
   호스트가 바빠진다. 12 논리 코어 중 judge만 6코어(3+3)를 한도로 쓰는 조건에서 MySQL·부하 생성기·JVM
   자신까지 더하면 여유가 얇아진다.
3. **MySQL lease 만료 → 재claim 악화 고리** — 정상 배분에서는 관측되지 않았다. staleReclaims는
   5.42에서 12–14건, 6.78에서 6건, 8.14에서 5건으로 기본 실험의 2–12건과 같은 범위다. judgeTime p99도
   8.14 MySQL 정상 배분에서 2017ms로 4s lease 아래다. **이 고리는 정상 배분에서는 CPU 모드에서도 켜지지
   않는다** — 4절의 과할당에서만 켜진다.

**따라서 8.14에서 벌어진 격차는 "유휴 은닉"으로 설명하는 것이 가장 근거가 맞다.** RabbitMQ는 채점 후
점유가 더 짧아(1건당 6~8ms 덜 쓴다, 기본 실험과 동일한 이유) 같은 호스트 CPU 부족 상황에서 MySQL보다
빈틈을 더 잘 메운다 — CPU 경쟁이 심해질수록 그 고정 차이가 상대적으로 더 크게 작동한다고 읽을 수 있다.
다만 8.14는 각 방식 n=1(RabbitMQ n=2)이라 반복을 더 쌓기 전에는 이 크기 자체를 확정할 수 없다.

**replay@k_eff는 CPU 모드에서도 여전히 실측을 재현한다**(0–10s 오차, 8개 실행 전부) — "방식 간 차이는
유효 k 하나로 설명된다"는 기본 실험의 결론 형태는 CPU 모드에서도 유지된다. 다만 그 유효 k 자체가
sleep보다 낮고, 그 낮아지는 정도가 k와 함께 커진다는 점이 이번 실험에서 새로 드러났다.

## 4. 과할당(judge cpus=1.5, worker 3+3): lease 악화 고리

방식당 1회, worker 3+3(명목 k=8.14)을 judge cpus 1.5/1.5(코어당 0.5 worker, 정상 배분의 절반)에 뒀다.

| | MySQL | RabbitMQ |
|---|---:|---:|
| RunId | `peak-b5cpu-k814-mysql-overalloc15-r1-20260925` | `peak-b5cpu-k814-rabbit-overalloc15-r1-20260926` |
| valid | True | True |
| k_eff (효율) | 3.239 (0.398) | 3.724 (0.458) |
| 참고: 같은 k, 정상 배분(3+3 cpus) | 5.95 (0.731) | 6.46 (0.794) |
| 참고: 같은 k, sleep | 6.47 (0.796) | 6.85 (0.843) |
| 백로그 s | 270 | 260 |
| L>10s / L>30s | 4576 / 3961 | 4485 / 3627 |
| 최대 대기 / 피크 p99 s | 130.8 / 128.6 | (peak p99 미기재; 최대 대기 기준 유사) |
| 채점 벽시계 p50/p99 ms | 87.5 / 3862 | 90.3 / 3898 |
| judge 컨테이너 meanCoresTopPeak (한도 1.5) | 1.497 / 1.497 | 1.500 / 1.500 |
| judge throttledMsLoad (360s 창) | 344,114 / 345,248 | 349,324 / 344,128 |
| staleReclaims / storedResultRepublishes | **343 / 288** | 해당 없음(방식 특성상 lease 없음) |
| 참고: 같은 k, 정상 배분 staleReclaims | 5 | — |
| 호스트 피크 평균 CPU | 77.5% (연속 90%+ 최대 3s) | 54.0% (연속 90%+ 없음) |

- **judge 컨테이너는 부하 창 거의 전체(96~97%)에서 CFS에 쓰로틀링당했다.** `meanCoresTopPeak`가 1.5 한도에
  정확히 고정된 것이 그 증거다 — judge JVM이 항상 자기 몫을 다 쓰고 있었다.
- **효율이 정상 배분의 절반 수준으로 무너졌다**(MySQL 0.731→0.398, RabbitMQ 0.794→0.458). 채점 벽시계
  p99가 3.86~3.90초로 4초 MySQL lease에 바짝 붙었다.
- **MySQL의 lease 악화 고리가 뚜렷하게 켜졌다.** staleReclaims가 정상 배분의 5건에서 **343건(약 60배)**으로
  뛰었고, storedResultRepublishes(288건)도 거의 같이 늘었다 — 로컬 큐에 slow 작업이 쌓인 채로 4초 lease가
  만료돼 다른 worker가 재claim하는 일이 정상 배분보다 훨씬 잦아졌다는 뜻이다. 재claim 1건은 대부분 이미
  저장된 결과의 재발행으로 끝나 채점을 두 번 하지는 않지만(기본 실험과 같은 해석), 그 건수 자체가
  "MySQL만 가진 고리가 CPU 경쟁 아래서 실제로 존재한다"는 직접 증거다.
- RabbitMQ는 애초에 lease가 없으므로(prefetch 1의 재전달만 있고, 이번 과할당 실행에서는 redelivered 건도
  기본 실험 수준으로 적었다) 이 고리가 성립하지 않는다. 그런데도 효율은 MySQL과 비슷하게(오히려 조금 더)
  무너졌다(0.458 vs 0.398) — **과할당에서의 손실 대부분은 lease 고리가 아니라 공통의 CPU 부족 자체**라는
  뜻이다. MySQL의 lease 고리는 이미 무너진 위에 얹히는 추가 손실이며, 이번 조건에서 그 추가분의 크기까지는
  분리해내지 못했다(n=1).

**판정**: 과할당에서 (b) lease 악화 고리는 실제로 관측됐다(MySQL staleReclaims 60배). 하지만 그것이
효율 붕괴의 주 원인은 아니다 — RabbitMQ도 거의 같은 폭으로 무너졌기 때문이다. 주 원인은 judge 컨테이너가
자기 한도(1.5 코어)에 갇혀 채점 자체의 벽시계가 늘어난 것이고, lease 고리는 MySQL 쪽에서 그 위에 얹히는
부차적 악화다.

## 5. 호스트 경쟁으로 표시된 실행

**없다.** 스모크(k=8.14 MySQL 정상 배분)를 포함해 10개 실행 전부 `longestRunAbove90Seconds` ≤ 7초로,
"30초 이상 연속 90% 초과" 기준을 넘지 않았다. 계획대로 스모크 1회를 본 실행으로 인정했다.

다만 2절·3절에서 다뤘듯, **연속 90% 초과는 아니어도 k=8.14 정상 배분에서는 호스트 CPU 피크 평균이
81.5~83.1%까지 올라갔다** — "경쟁으로 표시"할 만큼은 아니지만 CPU 모드 손실 확대를 설명하는 배경으로
읽어야 한다. 과할당 두 실행도 호스트 피크 평균 54.0~77.5%로 기준을 넘지 않았다(judge 자체가 1.5 코어에
갇혀 있어 오히려 호스트 여유는 더 있었다).

## 실행 목록

| RunId | 방식 | worker | judge cpus | 비고 |
|---|---|---|---|---|
| peak-b5cpu-k542-mysql-r1/r2-20260925 | mysql | 2+2 | 2/2 | |
| peak-b5cpu-k542-rabbit-r1/r2-20260925 | rabbit | 2+2 | 2/2 | |
| peak-b5cpu-k678-mysql-r1-20260925 | mysql | 3+2 | 3/2 | |
| peak-b5cpu-k678-rabbit-r1-20260925 | rabbit | 3+2 | 3/2 | |
| peak-b5cpu-k814-mysql-smoke1-20260925 | mysql | 3+3 | 3/3 | 스모크, 본 실행으로 인정 |
| peak-b5cpu-k814-rabbit-r1-20260925 | rabbit | 3+3 | 3/3 | |
| peak-b5cpu-k814-mysql-overalloc15-r1-20260925 | mysql | 3+3 | 1.5/1.5 | 4절 |
| peak-b5cpu-k814-rabbit-overalloc15-r1-20260926 | rabbit | 3+3 | 1.5/1.5 | 4절 |

전부 유효(503/429/500 없음, integrity 통과, 세션 재사용 0). 무효 처리된 실행 없음.

분석: `python scripts\mysql-judge-tradeoff\Compare-PeakProfileRuns.py --out results\mysql-judge-tradeoff\peak-comparison-b5cpu-20260926 --svg docs\img\judge-peak-profile-cpu-backlog-vs-k.svg results\mysql-judge-tradeoff\peak-b5cpu-k542-*-2026* results\mysql-judge-tradeoff\peak-b5cpu-k678-*-2026* results\mysql-judge-tradeoff\peak-b5cpu-k814-mysql-smoke1-20260925 results\mysql-judge-tradeoff\peak-b5cpu-k814-rabbit-r1-20260925`,
`python scripts\mysql-judge-tradeoff\Decompose-PeakOccupancy.py <같은 8개 정상 배분 디렉터리>`.

## 실행 명령 (재현용)

```powershell
.\scripts\mysql-judge-tradeoff\Invoke-PeakProfileMatrix.ps1 -Plan custom -JudgeMode cpu -Suffix 20260925 `
  -Custom "k542-rabbit-r1,rabbit,2,2,False;k542-mysql-r1,mysql,2,2,False;k678-mysql-r1,mysql,3,2,False;k678-rabbit-r1,rabbit,3,2,False;k814-rabbit-r1,rabbit,3,3,False"
.\scripts\mysql-judge-tradeoff\Invoke-PeakProfileMatrix.ps1 -Plan custom -JudgeMode cpu -Suffix 20260925 `
  -Custom "k542-mysql-r2,mysql,2,2,False;k542-rabbit-r2,rabbit,2,2,False"
# 과할당은 Invoke-PeakProfileMatrix의 judge cpus = worker 수 규칙과 다르므로 Run-TradeoffExperiment를 직접:
.\scripts\mysql-judge-tradeoff\Run-TradeoffExperiment.ps1 -DispatchMode mysql -PeakProfile -PeakBaseRps 5 `
  -WorkerCount 3 -Judge2WorkerCount 3 -MySqlMaxInFlight 12 -Judge2MaxInFlight 12 -MySqlClaimBatchSize 16 `
  -MySqlClaimTimeout 4s -MySqlPollInterval 100ms -RabbitPrefetch 1 -LatencySeed 20260920 `
  -WarmupTargetRps 10 -WarmupSeconds 30 -UserCount 6400 -BurstAuthRps 100 -BurstAuthSeconds 63 `
  -SteadyGuardSeconds 0 -DrainTimeoutSeconds 900 -ResetMySqlVolume -ResetBrokerAndCacheVolumes `
  -JudgeLatencyMode cpu -JudgeCpus 1.5 -Judge2Cpus 1.5 -RunId peak-b5cpu-k814-mysql-overalloc15-r1-20260925
# (rabbit도 동일, -DispatchMode rabbit)
```

## 구현·하네스 변경 (실험 전 커밋)

| 커밋 | 내용 |
|---|---|
| `a3d1673` | `CpuLoadProfileContestJudgement`, `mode` 속성, 두 구현 모두 CPU 시간 지표 기록 |
| `5741972` | `-JudgeCpus`/`-Judge2Cpus`/`-JudgeLatencyMode`(Run-TradeoffExperiment), `-JudgeMode`(Invoke-PeakProfileMatrix) |

## 규모 실험(B=70, sleep)에서 참고할 점

- 이번 실험은 judge cpus=worker 수(1코어=1 worker)에서도 **12코어 호스트에서 k=8.14(judge만 6코어 한도)
  부근부터 호스트 피크 평균이 80%대에 올라섰다.** B=70은 절대 처리량이 커지므로 더 많은 worker/judge
  코어가 필요하고, sleep이라도 다른 컨테이너(MySQL, broker, batch-1)의 CPU가 함께 커진다. CPU 채점을
  B=70에 적용한다면 호스트 코어 수(12)를 먼저 예산으로 잡고, judge cpus 합이 그 예산을 넘지 않게 설계해야
  한다 — 이번 실험의 8.14(judge 6코어)도 이미 여유가 얇았다.
- **과할당(judge cpus < worker 수)은 피해야 한다.** 이번 실험에서 judge cpus를 정상의 절반으로 줄이자
  효율이 절반 가까이 떨어졌고, MySQL은 재claim이 60배로 늘었다. B=70에서 "코어가 부족해도 worker 수만
  늘리면 된다"는 가정은 이번 결과로 보아 성립하지 않는다.
- CPU 채점의 wall/CPU 비율(`contest_judge_latency_class_cpu_duration_seconds_*` vs
  `contest_judge_latency_class_duration_seconds_*`)은 CPU 경쟁의 직접적인 온도계다. B=70에서도 이 비율을
  먼저 확인하면 "코어가 부족한지"를 채점 지연 지표보다 먼저 알 수 있다.
