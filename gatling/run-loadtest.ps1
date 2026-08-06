[CmdletBinding()]
param(
    [ValidateSet("smoke", "target", "step", "submit-139", "submit-200", "submit-1000", "scoreboard-200", "scoreboard-300", "scoreboard-2000", "mixed")]
    [string]$Scenario = "smoke",
    [int]$UserCount = 10000,
    [int]$ProblemCount = 5,
    [int]$DurationMinutes = 240,
    [int]$DrainTimeoutSeconds = 300,
    [int]$P95Millis = 10000,
    [string]$GatlingMaxHeap = "2g",
    [double]$StepStartRps = 200,
    [double]$StepRps = 200,
    [double]$StepMaxRps = 1400,
    [int]$StepRampSeconds = 10,
    [int]$StepHoldSeconds = 30,
    [switch]$KeepStack,
    [switch]$RemoveData
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFiles = @("-f", "compose.yaml", "-f", "compose.loadtest.yaml")
$projectName = "oj-loadtest"
$baseUrl = "http://127.0.0.1:18080"
$dbName = "oj_loadtest"
$seedPrefix = "loadtest"
$expectedSubmissionCount = 0L
$metricsRoot = Join-Path $repoRoot ("var\loadtest-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
$bulkStatsCsv = Join-Path $metricsRoot "bulk-stats.csv"

function Invoke-SetupRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [string]$ContentType,
        [string]$Body,
        [int]$MaxAttempts = 60
    )

    for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
        try {
            $request = @{
                Method = $Method
                Uri = $Uri
                TimeoutSec = 15
            }
            if ($ContentType) {
                $request.ContentType = $ContentType
            }
            if ($PSBoundParameters.ContainsKey("Body")) {
                $request.Body = $Body
            }
            return Invoke-RestMethod @request
        }
        catch {
            if ($attempt -eq $MaxAttempts) {
                throw
            }
            Write-Host "Setup request failed ($attempt/$MaxAttempts): $Method $Uri. Retrying in 2 seconds."
            Start-Sleep -Seconds 2
        }
    }
}

function Invoke-LoadCompose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    Push-Location $repoRoot
    try {
        & docker compose "-p" $projectName @composeFiles @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose failed: $($Arguments -join ' ')"
        }
    }
    finally {
        Pop-Location
    }
}

function Get-LoadContainerIds {
    Push-Location $repoRoot
    try {
        $ids = & docker compose "-p" $projectName @composeFiles ps -aq
        if ($LASTEXITCODE -ne 0) {
            throw "Could not list load-test containers."
        }
        return @($ids | Where-Object { $_ })
    }
    finally {
        Pop-Location
    }
}

function Assert-NormalStackStopped {
    $normal = @(& docker ps -q --filter "name=^/oj-(nginx|mysql|redis|rabbitmq|web-1|web-2|batch-1|judge-1|judge-2)$")
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect existing Docker containers."
    }
    if ($normal.Count -gt 0) {
        throw "Normal OJ containers are running. Stop them before starting the load-test stack; both stacks exceed the fixed 8-vCPU budget."
    }
}

function Wait-Healthy {
    param([int]$TimeoutSeconds = 180)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $ids = Get-LoadContainerIds
        if ($ids.Count -eq 9) {
            $states = @(& docker inspect --format '{{.State.Running}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $ids)
            if ($LASTEXITCODE -eq 0 -and ($states | Where-Object { $_ -notmatch '^true healthy$|^true none$' }).Count -eq 0) {
                return
            }
        }
        Start-Sleep -Seconds 2
    }
    throw "Load-test stack did not become healthy within $TimeoutSeconds seconds."
}

function Invoke-LoadSqlScalar {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $value = Invoke-LoadCompose -Arguments @("exec", "-T", "mysql", "mysql", "-uroot", "-p1234", "-D", $dbName, "-Nse", $Sql)
    return [int64]($value | Select-Object -Last 1)
}

function Get-RabbitBacklog {
    $lines = @(Invoke-LoadCompose -Arguments @("exec", "-T", "rabbitmq", "rabbitmqctl", "list_queues", "-q", "name", "messages_ready", "messages_unacknowledged"))
    $total = 0L
    foreach ($line in $lines) {
        $parts = ($line -split '\s+') | Where-Object { $_ }
        $ready = 0L
        $unacknowledged = 0L
        if ($parts.Count -ge 3 -and
            [int64]::TryParse($parts[$parts.Count - 2], [ref]$ready) -and
            [int64]::TryParse($parts[$parts.Count - 1], [ref]$unacknowledged)) {
            $total += $ready + $unacknowledged
        }
    }
    return $total
}

function Wait-PipelineDrain {
    param([int]$TimeoutSeconds)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        Assert-AllLoadContainersRunning
        $judgePending = Invoke-LoadSqlScalar "SELECT COUNT(*) FROM contest_judge_outbox WHERE status <> 'PUBLISHED'"
        $scoreboardPending = Invoke-LoadSqlScalar "SELECT COUNT(*) FROM contest_submission_outbox WHERE status <> 'COMPLETED'"
        $rabbitPending = Get-RabbitBacklog
        if ($judgePending -eq 0 -and $scoreboardPending -eq 0 -and $rabbitPending -eq 0) {
            Write-Host "Pipeline drained (judge outbox=0, Rabbit=0, scoreboard outbox=0)."
            return
        }
        Write-Host "Waiting for drain: judge outbox=$judgePending, Rabbit=$rabbitPending, scoreboard outbox=$scoreboardPending"
        Start-Sleep -Seconds 2
    }
    throw "Pipeline did not drain within $TimeoutSeconds seconds."
}

function Assert-AllLoadContainersRunning {
    $ids = Get-LoadContainerIds
    if ($ids.Count -ne 9) {
        throw "Expected 9 load-test containers but found $($ids.Count)."
    }

    $states = @(& docker inspect --format '{{.Name}} {{.State.Status}} OOMKilled={{.State.OOMKilled}}' $ids)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect load-test container states."
    }
    $failed = @($states | Where-Object { $_ -notmatch ' running OOMKilled=false$' })
    if ($failed.Count -gt 0) {
        throw "Load-test container stopped or was OOM-killed: $($failed -join '; ')"
    }
}

function Assert-NoOomKilled {
    Assert-AllLoadContainersRunning
    $ids = Get-LoadContainerIds
    $oomStates = @(& docker inspect --format '{{.Name}} OOMKilled={{.State.OOMKilled}}' $ids)
    if ($LASTEXITCODE -ne 0) {
        throw "Could not inspect OOM state."
    }
    $oomStates | ForEach-Object { Write-Host $_ }
    if ($oomStates | Where-Object { $_ -match 'OOMKilled=true' }) {
        throw "At least one load-test container was OOM-killed."
    }
}

function Get-WebContainerAddress {
    param([Parameter(Mandatory = $true)][string]$Service)

    Push-Location $repoRoot
    try {
        $containerId = (& docker compose "-p" $projectName @composeFiles ps -q $Service | Select-Object -Last 1)
        if ($LASTEXITCODE -ne 0 -or -not $containerId) {
            throw "Could not find the container for service $Service."
        }
        $address = & docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $containerId
        if ($LASTEXITCODE -ne 0 -or -not $address) {
            throw "Could not read the container address for service $Service."
        }
        return ($address | Select-Object -Last 1).Trim()
    }
    finally {
        Pop-Location
    }
}

# nginx resolves the names in its upstream block once, when it loads its config, and there is no
# resolver directive to refresh them. Compose recreating a web container hands it a new IP while
# nginx keeps proxying to the old one, and proxy_next_upstream quietly retries onto the surviving
# node - so every request succeeds while half the cluster sits idle. Restarting nginx after the
# stack is healthy is what forces a fresh resolution.
function Reset-LoadBalancer {
    Invoke-LoadCompose -Arguments @("restart", "nginx")
    Wait-Healthy
}

# Client-side success rate cannot see a missing upstream, so the split has to be proven before the
# load starts rather than inferred from the run afterwards.
function Assert-WebDistribution {
    param([int]$Probes = 20)

    $expected = @((Get-WebContainerAddress "web-1"), (Get-WebContainerAddress "web-2")) | Sort-Object
    $served = New-Object System.Collections.Generic.HashSet[string]

    for ($probe = 1; $probe -le $Probes; $probe++) {
        $response = Invoke-WebRequest -Uri "$baseUrl/perf/contest/submission-bulk-stats" -UseBasicParsing -TimeoutSec 15
        $upstream = $response.Headers['X-Upstream-Addr']
        if (-not $upstream) {
            throw "nginx did not return X-Upstream-Addr; cannot verify the two-web split."
        }
        if ($upstream -match ',') {
            throw "nginx retried an upstream during preflight ($upstream). It is still proxying to an address no web node holds."
        }
        [void]$served.Add(($upstream -split ':')[0])
    }

    $observed = @($served) | Sort-Object
    if (Compare-Object $expected $observed) {
        throw "nginx did not spread preflight traffic across both web nodes. expected=$($expected -join ', ') observed=$($observed -join ', ')"
    }
    Write-Host "Preflight: nginx is balancing across $($observed -join ', ')."
}

function Reset-WebMetrics {
    foreach ($webPort in 18081, 18082) {
        Invoke-SetupRequest -Method Post -Uri "http://127.0.0.1:$webPort/perf/contest/submission-bulk-stats/reset" | Out-Null
    }
}

function Reset-LoadRedis {
    Invoke-LoadCompose -Arguments @("exec", "-T", "redis", "redis-cli", "FLUSHDB") | Out-Null
}

function Get-ScoreboardSummary {
    param([Parameter(Mandatory = $true)][long]$ContestId)

    return Invoke-SetupRequest -Method Get -Uri "$baseUrl/perf/contest/scoreboard?contestId=$ContestId&startRank=1&size=1"
}

function Assert-PipelineMaterialized {
    param([Parameter(Mandatory = $true)][long]$ContestId)

    $submissionCount = Invoke-LoadSqlScalar "SELECT COUNT(*) FROM contest_submission WHERE contest_id = $ContestId"
    $resultCount = Invoke-LoadSqlScalar "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id = $ContestId"
    $outboxCount = Invoke-LoadSqlScalar "SELECT COUNT(*) FROM contest_submission_outbox WHERE contest_id = $ContestId"
    $completedCount = Invoke-LoadSqlScalar "SELECT COUNT(*) FROM contest_submission_outbox WHERE contest_id = $ContestId AND status = 'COMPLETED'"
    $dbParticipantCount = Invoke-LoadSqlScalar "SELECT COUNT(DISTINCT user_id) FROM contest_submission WHERE contest_id = $ContestId"

    if ($submissionCount -le 0) {
        throw "No contest submissions were persisted for contest $ContestId."
    }
    if ($expectedSubmissionCount -gt 0 -and $submissionCount -lt $expectedSubmissionCount) {
        throw "Persisted only $submissionCount submissions; expected at least $expectedSubmissionCount for the configured RPS."
    }
    if ($resultCount -ne $submissionCount -or
        $outboxCount -ne $submissionCount -or
        $completedCount -ne $submissionCount) {
        throw "Pipeline count mismatch: submissions=$submissionCount results=$resultCount outbox=$outboxCount completed=$completedCount"
    }

    $scoreboard = Get-ScoreboardSummary -ContestId $ContestId
    if ([int64]$scoreboard.totalParticipants -le 0) {
        throw "Redis scoreboard is empty after the pipeline drained."
    }
    if ([int64]$scoreboard.totalParticipants -ne $dbParticipantCount) {
        throw "Scoreboard participant mismatch: DB=$dbParticipantCount Redis=$($scoreboard.totalParticipants)"
    }
    Write-Host "Pipeline materialized: submissions=$submissionCount, results=$resultCount, scoreboardParticipants=$($scoreboard.totalParticipants)"
}

function Show-WebMetrics {
    param([Parameter(Mandatory = $true)][long]$ContestId)

    $totalProcessed = 0L
    $totalRejected = 0L
    $totalFailedChunks = 0L
    foreach ($webPort in 18081, 18082) {
        $metrics = Invoke-SetupRequest -Method Get -Uri "http://127.0.0.1:$webPort/perf/contest/submission-bulk-stats"
        Write-Host "web:$webPort submissions=$($metrics.totalSubmissionCount) rejected=$($metrics.rejectedSubmissionCount) currentInFlight=$($metrics.currentInFlight) maxInFlight=$($metrics.maxInFlight)"
        if ([int64]$metrics.totalSubmissionCount -le 0) {
            throw "web:$webPort processed no submissions; two-web distribution was not exercised."
        }
        $totalProcessed += [int64]$metrics.totalSubmissionCount
        $totalRejected += [int64]$metrics.rejectedSubmissionCount
        $totalFailedChunks += [int64]$metrics.failedChunkCount
    }
    if ($expectedSubmissionCount -gt 0) {
        $persisted = Invoke-LoadSqlScalar "SELECT COUNT(*) FROM contest_submission WHERE contest_id = $ContestId"
        if ($totalProcessed -ne $persisted) {
            throw "Web metrics/DB mismatch: web total=$totalProcessed persisted=$persisted"
        }
        if ($totalProcessed -lt $expectedSubmissionCount) {
            throw "Web nodes processed only $totalProcessed submissions; expected at least $expectedSubmissionCount."
        }
        if ($totalRejected -ne 0 -or $totalFailedChunks -ne 0) {
            throw "Passing scenario had rejected submissions or failed chunks: rejected=$totalRejected failedChunks=$totalFailedChunks"
        }
    }
}

# Gatling reports what the client saw. It cannot say which container ran out of CPU or where work
# piled up, so a background sampler records the server side on the same timeline as each run.
function Start-LoadSampler {
    param([Parameter(Mandatory = $true)][string]$Phase)

    New-Item -ItemType Directory -Force -Path $metricsRoot | Out-Null
    $stopFile = Join-Path $metricsRoot "sampler.stop"
    if (Test-Path $stopFile) {
        Remove-Item $stopFile -Force
    }

    $samplerScript = Join-Path $PSScriptRoot "sample-loadtest.ps1"
    $job = Start-Job -FilePath $samplerScript -ArgumentList @(
        $repoRoot, $projectName, $dbName, $metricsRoot, $stopFile, $Phase
    )
    return [pscustomobject]@{ Job = $job; StopFile = $stopFile }
}

function Stop-LoadSampler {
    param($Sampler)

    if (-not $Sampler) {
        return
    }
    New-Item -ItemType File -Path $Sampler.StopFile -Force | Out-Null
    Wait-Job -Job $Sampler.Job -Timeout 30 | Out-Null
    Receive-Job -Job $Sampler.Job -ErrorAction SilentlyContinue | Out-Null
    Remove-Job -Job $Sampler.Job -Force -ErrorAction SilentlyContinue
    Remove-Item $Sampler.StopFile -Force -ErrorAction SilentlyContinue
}

# The bulk metrics are cumulative counters, so a snapshot per phase is what makes chunk size,
# admission pressure and completion-queue depth attributable to one scenario.
function Save-WebMetricsSnapshot {
    param([Parameter(Mandatory = $true)][string]$Phase)

    New-Item -ItemType Directory -Force -Path $metricsRoot | Out-Null
    if (-not (Test-Path $bulkStatsCsv)) {
        Add-Content -Path $bulkStatsCsv -Encoding utf8 -Value ("phase,webPort,submissions,chunks,avgChunkSize," +
            "avgChunkMillis,maxChunkMillis,maxChunkSize,maxPendingAfter,maxActiveWorkers,rejected,failedChunks," +
            "maxInFlight,completionCallerRuns,maxCompletionQueueDepth,maxCompletionQueueDelayMillis")
    }

    foreach ($webPort in 18081, 18082) {
        $metrics = Invoke-SetupRequest -Method Get -Uri "http://127.0.0.1:$webPort/perf/contest/submission-bulk-stats"
        $avgChunkSize = 0
        if ([int64]$metrics.chunkCount -gt 0) {
            $avgChunkSize = [math]::Round([double]$metrics.totalSubmissionCount / [double]$metrics.chunkCount, 2)
        }
        Add-Content -Path $bulkStatsCsv -Encoding utf8 -Value ("$Phase,$webPort," +
            "$($metrics.totalSubmissionCount),$($metrics.chunkCount),$avgChunkSize," +
            "$($metrics.averageChunkElapsedMillis),$($metrics.maxChunkElapsedMillis),$($metrics.maxChunkSize)," +
            "$($metrics.maxPendingAfter),$($metrics.maxActiveWorkers),$($metrics.rejectedSubmissionCount)," +
            "$($metrics.failedChunkCount),$($metrics.maxInFlight),$($metrics.completionCallerRunsCount)," +
            "$($metrics.maxCompletionQueueDepth),$($metrics.maxCompletionQueueDelayMillis)")
    }
}

function Show-MetricsSummary {
    $containerCsv = Join-Path $metricsRoot "containers.csv"
    $pipelineCsv = Join-Path $metricsRoot "pipeline.csv"

    Write-Host ""
    Write-Host "=== Sampled metrics: $metricsRoot ==="

    if (Test-Path $containerCsv) {
        Write-Host "-- peak container CPU and memory per phase --"
        Import-Csv $containerCsv |
            Group-Object phase, container |
            ForEach-Object {
                $peakCpu = ($_.Group | Measure-Object -Property cpuPercent -Maximum).Maximum
                $peakMem = ($_.Group | Measure-Object -Property memUsedMb -Maximum).Maximum
                $limit = ($_.Group | Select-Object -Last 1).memLimitMb
                [pscustomobject]@{
                    Phase = $_.Group[0].phase
                    Container = $_.Group[0].container
                    PeakCpuPercent = [math]::Round([double]$peakCpu, 1)
                    PeakMemMb = [math]::Round([double]$peakMem, 1)
                    MemLimitMb = $limit
                }
            } |
            Sort-Object @{ Expression = "Phase" }, @{ Expression = "PeakCpuPercent"; Descending = $true } |
            Format-Table -AutoSize | Out-String -Width 250 | Write-Host
    }

    if (Test-Path $pipelineCsv) {
        Write-Host "-- peak backlog and lag per phase --"
        Import-Csv $pipelineCsv |
            Group-Object phase |
            ForEach-Object {
                [pscustomobject]@{
                    Phase = $_.Name
                    PeakJudgeOutbox = ($_.Group | Measure-Object -Property judgeOutboxPending -Maximum).Maximum
                    PeakScoreboardPending = ($_.Group | Measure-Object -Property scoreboardPending -Maximum).Maximum
                    PeakScoreboardFailed = ($_.Group | Measure-Object -Property scoreboardFailed -Maximum).Maximum
                    PeakLagMs = ($_.Group | Measure-Object -Property oldestPendingLagMs -Maximum).Maximum
                    PeakRabbitReady = ($_.Group | Measure-Object -Property rabbitReady -Maximum).Maximum
                    PeakRabbitUnacked = ($_.Group | Measure-Object -Property rabbitUnacked -Maximum).Maximum
                    PeakMysqlThreads = ($_.Group | Measure-Object -Property mysqlThreadsConnected -Maximum).Maximum
                }
            } |
            Format-Table -AutoSize | Out-String -Width 250 | Write-Host

        # Client RPS counts accepted requests. This is the rate rows actually landed in MySQL,
        # which is what separates "the load generator asked for more" from "the server wrote more".
        Write-Host "-- peak observed insert rate (rows/s between samples) --"
        Import-Csv $pipelineCsv |
            Group-Object phase |
            ForEach-Object {
                $rows = @($_.Group | Sort-Object timestamp)
                $peak = 0d
                for ($index = 1; $index -lt $rows.Count; $index++) {
                    $seconds = ([datetime]$rows[$index].timestamp - [datetime]$rows[$index - 1].timestamp).TotalSeconds
                    if ($seconds -le 0) { continue }
                    $delta = [double]$rows[$index].submissionRows - [double]$rows[$index - 1].submissionRows
                    $rate = $delta / $seconds
                    if ($rate -gt $peak) { $peak = $rate }
                }
                [pscustomobject]@{ Phase = $_.Name; PeakInsertRowsPerSecond = [math]::Round($peak, 1) }
            } |
            Format-Table -AutoSize | Out-String -Width 250 | Write-Host
    }

    if (Test-Path $bulkStatsCsv) {
        Write-Host "-- submission bulk metrics per phase --"
        Import-Csv $bulkStatsCsv | Format-Table -AutoSize | Out-String -Width 250 | Write-Host
    }
}

function New-Seed {
    $request = @{
        prefix = $seedPrefix
        userCount = $UserCount
        problemCount = $ProblemCount
        durationMinutes = $DurationMinutes
        reset = $true
    } | ConvertTo-Json -Compress

    return Invoke-SetupRequest -Method Post -Uri "$baseUrl/perf/contest/seed" -ContentType "application/json" -Body $request
}

function Invoke-GatlingScenario {
    param(
        [Parameter(Mandatory = $true)]$Seed,
        [Parameter(Mandatory = $true)][string]$SelectedScenario,
        # Exploration scenarios are meant to be pushed past the point where they fail. Their
        # assertion result is data, not a verdict, and tearing the stack down would discard the
        # samples that say where the limit was.
        [switch]$AllowAssertionFailure
    )

    $shared = @(
        "-Dperf.baseUrl=$baseUrl",
        "-Dperf.assert.minSuccessPercent=99",
        "-Dperf.assert.p95Millis=$P95Millis"
    )
    $seedArgs = @(
        "-Dperf.contestId=$($Seed.contestId)",
        "-Dperf.userId.start=$($Seed.firstUserId)",
        "-Dperf.userId.end=$($Seed.lastUserId)",
        "-Dperf.problemId.start=$($Seed.firstProblemId)",
        "-Dperf.problemId.end=$($Seed.lastProblemId)"
    )

    switch ($SelectedScenario) {
        "submit-139" {
            $simulationClass = "my.oj.perf.ContestSubmissionSimulation"
            $minRequests = 27105L
            $script:expectedSubmissionCount = $minRequests
            $scenarioArgs = @("-Dperf.targetRps=139", "-Dperf.rampSeconds=30", "-Dperf.holdSeconds=180") + $seedArgs
        }
        "submit-200" {
            $simulationClass = "my.oj.perf.ContestSubmissionSimulation"
            $minRequests = 27000L
            $script:expectedSubmissionCount = $minRequests
            $scenarioArgs = @("-Dperf.targetRps=200", "-Dperf.rampSeconds=30", "-Dperf.holdSeconds=120") + $seedArgs
        }
        "submit-1000" {
            $simulationClass = "my.oj.perf.ContestSubmissionSimulation"
            $minRequests = 135000L
            $script:expectedSubmissionCount = $minRequests
            $scenarioArgs = @("-Dperf.targetRps=1000", "-Dperf.rampSeconds=30", "-Dperf.holdSeconds=120") + $seedArgs
        }
        "step" {
            # A staircase finds the rate the stack sustains cleanly. A fixed-RPS pass/fail run only
            # says whether one chosen rate worked.
            $simulationClass = "my.oj.perf.ContestSubmissionStepLoadSimulation"
            $minRequests = 1L
            $scenarioArgs = @(
                "-Dperf.startRps=$StepStartRps",
                "-Dperf.stepRps=$StepRps",
                "-Dperf.maxRps=$StepMaxRps",
                "-Dperf.rampSeconds=$StepRampSeconds",
                "-Dperf.stepHoldSeconds=$StepHoldSeconds"
            ) + $seedArgs
        }
        "scoreboard-200" {
            $simulationClass = "my.oj.perf.ContestScoreboardReadSimulation"
            $minRequests = 15000L
            $scenarioArgs = @("-Dperf.targetRps=200", "-Dperf.rampSeconds=30", "-Dperf.holdSeconds=60", "-Dperf.contestId=$($Seed.contestId)", "-Dperf.startRank.min=1", "-Dperf.startRank.max=100000", "-Dperf.pageSize=100")
        }
        "scoreboard-300" {
            $simulationClass = "my.oj.perf.ContestScoreboardReadSimulation"
            $minRequests = 40500L
            $scenarioArgs = @("-Dperf.targetRps=300", "-Dperf.rampSeconds=30", "-Dperf.holdSeconds=120", "-Dperf.contestId=$($Seed.contestId)", "-Dperf.startRank.min=1", "-Dperf.startRank.max=100000", "-Dperf.pageSize=100")
        }
        "scoreboard-2000" {
            $simulationClass = "my.oj.perf.ContestScoreboardReadSimulation"
            $minRequests = 270000L
            $scenarioArgs = @("-Dperf.targetRps=2000", "-Dperf.rampSeconds=30", "-Dperf.holdSeconds=120", "-Dperf.contestId=$($Seed.contestId)", "-Dperf.startRank.min=1", "-Dperf.startRank.max=100000", "-Dperf.pageSize=100")
        }
        "mixed" {
            $simulationClass = "my.oj.perf.OjGoalLoadSimulation"
            $minRequests = 1L
            $scenarioArgs = $seedArgs
        }
        default { throw "Unsupported Gatling scenario: $SelectedScenario" }
    }
    $scenarioArgs += "-Dperf.assert.minRequests=$minRequests"

    $classpathFile = Join-Path $repoRoot "gatling\build\standalone-gatling\classpath.txt"
    if (-not (Test-Path $classpathFile)) {
        throw "Standalone Gatling classpath is missing. Run :gatling:prepareStandaloneGatling first."
    }
    $classpath = (Get-Content $classpathFile -Raw).Trim()
    if ([string]::IsNullOrWhiteSpace($classpath)) {
        throw "Standalone Gatling classpath is empty."
    }

    $javaExe = (Get-Command java.exe -ErrorAction Stop).Source
    $logbackConfig = (Resolve-Path (Join-Path $repoRoot "gatling\src\gatling\resources\logback.xml")).Path
    $resultsFolder = Join-Path $repoRoot "gatling\build\reports\gatling"
    New-Item -ItemType Directory -Force -Path $resultsFolder | Out-Null
    $javaArgs = @(
        "-Xms512m",
        "-Xmx$GatlingMaxHeap",
        "-Dlogback.configurationFile=$logbackConfig"
    ) + $shared + $scenarioArgs + @(
        "-cp", $classpath,
        "io.gatling.app.Gatling",
        "-s", $simulationClass,
        "-rf", $resultsFolder,
        "-rd", "oj-loadtest $SelectedScenario"
    )

    $sampler = Start-LoadSampler -Phase $SelectedScenario
    try {
        & $javaExe @javaArgs
        $gatlingExitCode = $LASTEXITCODE
    }
    finally {
        Stop-LoadSampler -Sampler $sampler
    }
    Save-WebMetricsSnapshot -Phase $SelectedScenario

    if ($gatlingExitCode -ne 0) {
        if (-not $AllowAssertionFailure) {
            throw "Gatling assertions failed for $SelectedScenario."
        }
        Write-Host "Gatling assertions failed for $SelectedScenario; continuing because this is an exploration scenario."
    }
}

Assert-NormalStackStopped
$started = $false
try {
    Invoke-LoadCompose -Arguments @("config") | Out-Null
    Push-Location $repoRoot
    try {
        & .\gradlew.bat bootJar :gatling:prepareStandaloneGatling --console=plain
        if ($LASTEXITCODE -ne 0) {
            throw "bootJar failed."
        }
    }
    finally {
        Pop-Location
    }
    $started = $true
    Invoke-LoadCompose -Arguments @("up", "-d", "--build")
    Wait-Healthy
    Reset-LoadBalancer
    Assert-WebDistribution
    Wait-PipelineDrain -TimeoutSeconds $DrainTimeoutSeconds

    Reset-LoadRedis
    $script:expectedSubmissionCount = 0L
    $seed = New-Seed
    Reset-WebMetrics
    $seed | Format-List | Out-Host

    if ($Scenario -eq "smoke") {
        Invoke-GatlingScenario -Seed $seed -SelectedScenario "submit-139"
        Wait-PipelineDrain -TimeoutSeconds $DrainTimeoutSeconds
        Assert-PipelineMaterialized -ContestId $seed.contestId
        Invoke-GatlingScenario -Seed $seed -SelectedScenario "scoreboard-200"
    }
    elseif ($Scenario -eq "target") {
        Invoke-GatlingScenario -Seed $seed -SelectedScenario "submit-200"
        Wait-PipelineDrain -TimeoutSeconds $DrainTimeoutSeconds
        Assert-PipelineMaterialized -ContestId $seed.contestId
        Invoke-GatlingScenario -Seed $seed -SelectedScenario "scoreboard-300"
    }
    elseif ($Scenario -eq "step") {
        Invoke-GatlingScenario -Seed $seed -SelectedScenario "step" -AllowAssertionFailure
    }
    elseif ($Scenario -in @("scoreboard-200", "scoreboard-300", "scoreboard-2000")) {
        Write-Host "Populating the Redis scoreboard through the full submission pipeline first."
        Invoke-GatlingScenario -Seed $seed -SelectedScenario "submit-139"
        Wait-PipelineDrain -TimeoutSeconds $DrainTimeoutSeconds
        Assert-PipelineMaterialized -ContestId $seed.contestId
        Invoke-GatlingScenario -Seed $seed -SelectedScenario $Scenario
    }
    else {
        Invoke-GatlingScenario -Seed $seed -SelectedScenario $Scenario
    }

    Wait-PipelineDrain -TimeoutSeconds $DrainTimeoutSeconds
    if ($Scenario -in @("target", "submit-139", "submit-200", "submit-1000", "mixed")) {
        Assert-PipelineMaterialized -ContestId $seed.contestId
    }
    Show-WebMetrics -ContestId $seed.contestId
    Assert-NoOomKilled
}
finally {
    # Runs before teardown so a failed run still reports where the pressure was.
    if (Test-Path $metricsRoot) {
        Show-MetricsSummary
    }
    if ($started -and -not $KeepStack) {
        if ($RemoveData) {
            Invoke-LoadCompose -Arguments @("down", "-v")
        }
        else {
            Invoke-LoadCompose -Arguments @("down")
        }
    }
}
