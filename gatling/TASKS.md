# Gatling Task Shortcuts

These tasks run exactly one simulation each.

## Contest submissions

```powershell
.\gradlew.bat :gatling:contestSubmissionRun `
  -Dperf.baseUrl=http://localhost:8080 `
  -Dperf.targetRps=139 `
  -Dperf.rampSeconds=30 `
  -Dperf.holdSeconds=180 `
  -Dperf.userId.start=1 `
  -Dperf.userId.end=10000 `
  -Dperf.problemId.start=1 `
  -Dperf.problemId.end=5
```

## Contest submissions step load

```powershell
.\gradlew.bat :gatling:contestSubmissionStepLoadRun `
  -Dperf.baseUrl=http://localhost:8080 `
  -Dperf.startRps=200 `
  -Dperf.stepRps=200 `
  -Dperf.maxRps=1000 `
  -Dperf.rampSeconds=5 `
  -Dperf.stepHoldSeconds=10 `
  -Dperf.userId.start=1 `
  -Dperf.userId.end=10000 `
  -Dperf.problemId.start=1 `
  -Dperf.problemId.end=5
```

## Contest scoreboard reads

```powershell
.\gradlew.bat :gatling:contestScoreboardReadRun `
  -Dperf.baseUrl=http://localhost:8080 `
  -Dperf.contestId=1 `
  -Dperf.targetRps=2000 `
  -Dperf.rampSeconds=30 `
  -Dperf.holdSeconds=120 `
  -Dperf.startRank.min=1 `
  -Dperf.startRank.max=100000 `
  -Dperf.pageSize=100
```

## Mixed goal load

```powershell
.\gradlew.bat :gatling:ojGoalLoadRun `
  -Dperf.baseUrl=http://localhost:8080 `
  -Dperf.contestId=1 `
  -Dperf.userId.start=1 `
  -Dperf.userId.end=10000 `
  -Dperf.problemId.start=1 `
  -Dperf.problemId.end=5
```
