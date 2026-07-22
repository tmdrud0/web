param(
    [string]$SimulationClass = "my.oj.perf.ContestSubmissionSimulation",
    [string]$BaseUrl = "http://localhost:8080",
    [int]$TargetRps = 139,
    [int]$RampSeconds = 10,
    [int]$HoldSeconds = 30,
    [long]$UserIdStart = 1,
    [long]$UserIdEnd = 10000,
    [long]$ProblemIdStart = 1,
    [long]$ProblemIdEnd = 5,
    [string]$RunDescription = "",
    [string]$ResultsFolder = "",
    [string]$JavaExe = "C:\Program Files\Java\jdk-17\bin\java.exe"
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$classpathFile = Join-Path $scriptDir "build\standalone-gatling\classpath.txt"

if (-not (Test-Path $classpathFile)) {
    throw "Missing standalone Gatling classpath. Run '.\gradlew.bat :gatling:prepareStandaloneGatling' first."
}

if (-not (Test-Path $JavaExe)) {
    throw "Java executable not found at '$JavaExe'."
}

$classpath = Get-Content $classpathFile -Raw
if ([string]::IsNullOrWhiteSpace($classpath)) {
    throw "Standalone Gatling classpath file is empty."
}

$javaArgs = @(
    "-Dperf.baseUrl=$BaseUrl",
    "-Dperf.targetRps=$TargetRps",
    "-Dperf.rampSeconds=$RampSeconds",
    "-Dperf.holdSeconds=$HoldSeconds",
    "-Dperf.userId.start=$UserIdStart",
    "-Dperf.userId.end=$UserIdEnd",
    "-Dperf.problemId.start=$ProblemIdStart",
    "-Dperf.problemId.end=$ProblemIdEnd",
    "-cp", $classpath,
    "io.gatling.app.Gatling",
    "-s", $SimulationClass
)

if ($ResultsFolder) {
    $javaArgs += @("-rf", $ResultsFolder)
}

if ($RunDescription) {
    $javaArgs += @("-rd", $RunDescription)
}

& $JavaExe @javaArgs
exit $LASTEXITCODE
