# Gatling 부하 테스트 가이드

이 프로젝트에는 OJ 목표치 기준 시뮬레이션이 들어 있습니다.

- 제출 평균 TPS: `139`
- 제출 피크 TPS: `1000`
- 대회 조회 TPS: `2000`

## 1. 서버 실행 (perf 프로필)

PowerShell에서 루트(`web`) 기준:

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=perf"
```

`perf` 프로필이어야 `/perf/*` 엔드포인트가 열립니다.

## 2. 테스트용 데이터 시드

별도 터미널에서:

```powershell
$seed = Invoke-RestMethod -Method Post -Uri "http://localhost:8080/perf/contest/seed" -ContentType "application/json" -Body '{"prefix":"gatling","userCount":10000,"problemCount":5,"durationMinutes":240,"reset":true}'
$seed
```

출력에서 아래 값을 확인합니다.

- `contestId`
- `firstUserId`, `lastUserId`
- `firstProblemId`, `lastProblemId`

## 3. 실행 명령

### 3-1) 제출 평균 TPS (139)

```powershell
.\gradlew.bat :gatling:gatlingRun `
  -Dgatling.simulationClass=my.oj.perf.ContestSubmissionSimulation `
  -Dperf.baseUrl=http://localhost:8080 `
  -Dperf.targetRps=139 `
  -Dperf.rampSeconds=30 `
  -Dperf.holdSeconds=180 `
  -Dperf.userId.start=$($seed.firstUserId) `
  -Dperf.userId.end=$($seed.lastUserId) `
  -Dperf.problemId.start=$($seed.firstProblemId) `
  -Dperf.problemId.end=$($seed.lastProblemId)
```

### 3-2) 제출 피크 TPS (1000)

```powershell
.\gradlew.bat :gatling:gatlingRun `
  -Dgatling.simulationClass=my.oj.perf.ContestSubmissionSimulation `
  -Dperf.baseUrl=http://localhost:8080 `
  -Dperf.targetRps=1000 `
  -Dperf.rampSeconds=30 `
  -Dperf.holdSeconds=120 `
  -Dperf.userId.start=$($seed.firstUserId) `
  -Dperf.userId.end=$($seed.lastUserId) `
  -Dperf.problemId.start=$($seed.firstProblemId) `
  -Dperf.problemId.end=$($seed.lastProblemId)
```

### 3-3) 대회 조회 TPS (2000)

`ContestScoreboardReadSimulation`은 `GET /perf/contest/scoreboard`를 호출합니다.

```powershell
.\gradlew.bat :gatling:gatlingRun `
  -Dgatling.simulationClass=my.oj.perf.ContestScoreboardReadSimulation `
  -Dperf.baseUrl=http://localhost:8080 `
  -Dperf.contestId=$($seed.contestId) `
  -Dperf.targetRps=2000 `
  -Dperf.rampSeconds=30 `
  -Dperf.holdSeconds=120 `
  -Dperf.startRank.min=1 `
  -Dperf.startRank.max=100000 `
  -Dperf.pageSize=100
```

### 3-4) 목표 통합 시나리오 (평균→피크 + 조회 동시)

`OjGoalLoadSimulation`은 제출과 조회를 동시에 발생시킵니다.

```powershell
.\gradlew.bat :gatling:gatlingRun `
  -Dgatling.simulationClass=my.oj.perf.OjGoalLoadSimulation `
  -Dperf.baseUrl=http://localhost:8080 `
  -Dperf.contestId=$($seed.contestId) `
  -Dperf.userId.start=$($seed.firstUserId) `
  -Dperf.userId.end=$($seed.lastUserId) `
  -Dperf.problemId.start=$($seed.firstProblemId) `
  -Dperf.problemId.end=$($seed.lastProblemId)
```

기본값:
- `submitAvgRps=139`
- `submitPeakRps=1000`
- `readRps=2000`

필요하면 `-Dperf.submitAvgRps=... -Dperf.submitPeakRps=... -Dperf.readRps=...`로 변경합니다.

## 4. 리포트 확인

실행 후 HTML 리포트는 보통 아래 경로에 생성됩니다.

- `gatling/build/reports/gatling/*/index.html`

## 5. 처음 돌릴 때 추천

처음에는 아래처럼 낮은 TPS로 1분만 검증한 뒤 목표 TPS로 올리는 걸 권장합니다.

- 제출: `targetRps=50`
- 조회: `targetRps=200`
