# Standalone Gatling

Prepare the standalone classpath once:

```powershell
.\gradlew.bat :gatling:prepareStandaloneGatling
```

Run Gatling without Gradle:

```powershell
.\gatling\run-standalone.ps1 `
  -SimulationClass my.oj.perf.ContestSubmissionSimulation `
  -BaseUrl http://localhost:8080 `
  -TargetRps 139 `
  -RampSeconds 10 `
  -HoldSeconds 30 `
  -UserIdStart 1 `
  -UserIdEnd 10000 `
  -ProblemIdStart 1 `
  -ProblemIdEnd 5
```

Optional:

```powershell
.\gatling\run-standalone.ps1 `
  -ResultsFolder C:\tmp\gatling-reports `
  -RunDescription "bulk-139"
```
