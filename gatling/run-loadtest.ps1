[CmdletBinding()]
param(
    [ValidateSet("smoke", "target", "step", "submit-139", "submit-200", "submit-1000", "scoreboard-200", "scoreboard-300", "scoreboard-2000", "mixed", "mixed-real")]
    [string]$Scenario = "smoke",
    [int]$UserCount = 10000,
    [int]$ProblemCount = 5,
    [int]$DurationMinutes = 240,
    [int]$DrainTimeoutSeconds = 300,
    [int]$HealthTimeoutSeconds = 300,
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
$stalenessCsv = Join-Path $metricsRoot "end-to-end-staleness.csv"
$httpSummaryCsv = Join-Path $metricsRoot "http-summary.csv"
$script:stalenessCrossCheckFailed = $false

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

# The five application JVMs take about 170s to open their ports on the fixed 7.5-CPU budget,
# and `up -d --build` recreates them on every run, so this is the cold-start path every time
# rather than only on the first. The old 180s deadline was also stricter than the healthcheck it
# polls - compose allows interval 10s x retries 20 = 200s - so the script could fail a stack that
# Docker was still willing to wait for, and did. Measured 06:53:38 start to 06:56:28 first
# passing check, five instances within a second of each other.
function Wait-Healthy {
    param([int]$TimeoutSeconds = $HealthTimeoutSeconds)

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

# Keep the scalar helper's contract intact. Distribution queries return one tab-separated row
# with several values, so their caller must opt into this sibling explicitly.
function Invoke-LoadSqlRow {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $statement = ($Sql -replace "\r?\n", " ").Trim()
    $rows = @(Invoke-LoadCompose -Arguments @("exec", "-T", "mysql", "mysql", "-uroot", "-p1234", "-D", $dbName, "-Nse", $statement))
    $row = $rows | Select-Object -Last 1
    if ([string]::IsNullOrWhiteSpace($row)) {
        throw "SQL returned no row."
    }
    return $row -split "`t"
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

function Get-EndToEndLatencyDistribution {
    param(
        [Parameter(Mandatory = $true)][long]$ContestId,
        [Parameter(Mandatory = $true)]
        [ValidateSet("created_at", "processed_at")]
        [string]$EndColumn,
        [switch]$RequireResult
    )

    $resultJoin = if ($RequireResult) {
        "JOIN contest_submission_result csr ON csr.submission_id = cs.id"
    }
    else {
        ""
    }
    $sql = @"
WITH latencies AS (
    SELECT TIMESTAMPDIFF(MICROSECOND, cs.submitted_time, o.$EndColumn) AS latency_us
    FROM contest_submission cs
    JOIN contest_submission_outbox o ON o.contest_submission_id = cs.id
    $resultJoin
    WHERE cs.contest_id = $ContestId
      AND o.$EndColumn IS NOT NULL
), ranked AS (
    SELECT latency_us,
           ROW_NUMBER() OVER (ORDER BY latency_us) AS rn,
           COUNT(*) OVER () AS sample_count
    FROM latencies
)
SELECT COALESCE(MAX(CASE WHEN rn = CEIL(sample_count * 0.50) THEN latency_us END), 0),
       COALESCE(MAX(CASE WHEN rn = CEIL(sample_count * 0.95) THEN latency_us END), 0),
       COALESCE(MAX(CASE WHEN rn = CEIL(sample_count * 0.99) THEN latency_us END), 0),
       COALESCE(MAX(latency_us), 0),
       COALESCE(MAX(sample_count), 0)
FROM ranked
"@
    $values = @(Invoke-LoadSqlRow -Sql $sql)
    if ($values.Count -ne 5) {
        throw "Expected five latency distribution fields but received $($values.Count): $($values -join ', ')"
    }

    return [pscustomobject]@{
        P50Micros = [int64]$values[0]
        P95Micros = [int64]$values[1]
        P99Micros = [int64]$values[2]
        MaxMicros = [int64]$values[3]
        SampleCount = [int64]$values[4]
    }
}

function Format-LatencySeconds {
    param([Parameter(Mandatory = $true)][long]$Microseconds)

    $seconds = [double]$Microseconds / 1000000d
    return $seconds.ToString("0.######", [Globalization.CultureInfo]::InvariantCulture) + "s"
}

function Show-EndToEndStaleness {
    param(
        [Parameter(Mandatory = $true)][long]$ContestId,
        [Parameter(Mandatory = $true)][string]$ScenarioName
    )

    # created_at is stamped after the result batch INSERT and the result/outbox rows commit in the
    # same transaction. processed_at is stamped only after the Redis apply succeeds.
    $result = Get-EndToEndLatencyDistribution -ContestId $ContestId -EndColumn "created_at" -RequireResult
    $scoreboard = Get-EndToEndLatencyDistribution -ContestId $ContestId -EndColumn "processed_at"
    if ($result.SampleCount -le 0 -or $scoreboard.SampleCount -le 0) {
        throw "End-to-end staleness has no samples: result=$($result.SampleCount), scoreboard=$($scoreboard.SampleCount)"
    }
    if ($result.SampleCount -ne $scoreboard.SampleCount) {
        throw "End-to-end sample count mismatch: result=$($result.SampleCount), scoreboard=$($scoreboard.SampleCount)"
    }

    Write-Host "-- end-to-end staleness --"
    Write-Host ("result queryable    p50 {0}  p95 {1}  p99 {2}  max {3}   n={4}" -f
        (Format-LatencySeconds $result.P50Micros),
        (Format-LatencySeconds $result.P95Micros),
        (Format-LatencySeconds $result.P99Micros),
        (Format-LatencySeconds $result.MaxMicros),
        $result.SampleCount)
    Write-Host ("scoreboard applied  p50 {0}  p95 {1}  p99 {2}  max {3}   n={4}" -f
        (Format-LatencySeconds $scoreboard.P50Micros),
        (Format-LatencySeconds $scoreboard.P95Micros),
        (Format-LatencySeconds $scoreboard.P99Micros),
        (Format-LatencySeconds $scoreboard.MaxMicros),
        $scoreboard.SampleCount)

    @(
        "scenario,metric,p50Micros,p95Micros,p99Micros,maxMicros,sampleCount",
        "$ScenarioName,result-queryable,$($result.P50Micros),$($result.P95Micros),$($result.P99Micros),$($result.MaxMicros),$($result.SampleCount)",
        "$ScenarioName,scoreboard-applied,$($scoreboard.P50Micros),$($scoreboard.P95Micros),$($scoreboard.P99Micros),$($scoreboard.MaxMicros),$($scoreboard.SampleCount)"
    ) | Set-Content -Path $stalenessCsv -Encoding utf8

    return [pscustomobject]@{ Result = $result; Scoreboard = $scoreboard }
}

function Test-ScoreboardStalenessAgainstHeadLag {
    param(
        [Parameter(Mandatory = $true)]$ScoreboardDistribution,
        [Parameter(Mandatory = $true)][string[]]$SubmissionPhases
    )

    $pipelineCsv = Join-Path $metricsRoot "pipeline.csv"
    if (-not (Test-Path $pipelineCsv)) {
        Write-Host "head-lag cross-check  UNAVAILABLE (pipeline samples are missing)"
        return $false
    }

    $samples = @(Import-Csv $pipelineCsv | Where-Object { $_.phase -in $SubmissionPhases })
    if ($samples.Count -eq 0) {
        Write-Host "head-lag cross-check  UNAVAILABLE (no samples for $($SubmissionPhases -join ', '))"
        return $false
    }

    $peakLagMillis = ($samples | ForEach-Object { [double]$_.oldestPendingLagMs } |
        Measure-Object -Maximum).Maximum
    $scoreboardP99Millis = [double]$ScoreboardDistribution.P99Micros / 1000d
    if ($peakLagMillis -le 0d -or $scoreboardP99Millis -le 0d) {
        Write-Host ("head-lag cross-check  UNAVAILABLE (scoreboard p99={0}ms, sampled peak={1}ms)" -f
            [math]::Round($scoreboardP99Millis, 3), [math]::Round($peakLagMillis, 3))
        return $false
    }

    $ratio = [math]::Max($scoreboardP99Millis, $peakLagMillis) /
        [math]::Min($scoreboardP99Millis, $peakLagMillis)
    $status = if ($ratio -lt 10d) { "PASS" } else { "MISMATCH" }
    Write-Host ("head-lag cross-check  {0}  scoreboard p99={1}ms  sampled peak={2}ms  ratio={3}x" -f
        $status,
        [math]::Round($scoreboardP99Millis, 3),
        [math]::Round($peakLagMillis, 3),
        [math]::Round($ratio, 2))
    return $status -eq "PASS"
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

function Wait-PipelineDrainWithSampling {
    param(
        [Parameter(Mandatory = $true)][int]$TimeoutSeconds,
        [Parameter(Mandatory = $true)][string]$Phase
    )

    # The end-to-end query includes rows completed after injection stops. Keep sampling under the
    # same phase name during drain so the head-lag maximum covers that identical interval.
    $sampler = Start-LoadSampler -Phase $Phase
    try {
        Wait-PipelineDrain -TimeoutSeconds $TimeoutSeconds
    }
    finally {
        Stop-LoadSampler -Sampler $sampler
    }
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

function Save-GatlingHttpSummary {
    param(
        [Parameter(Mandatory = $true)][string]$Phase,
        [Parameter(Mandatory = $true)][string]$ResultsFolder,
        [Parameter(Mandatory = $true)][datetime]$RunStartedAt
    )

    $report = Get-ChildItem -Path $ResultsFolder -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -ge $RunStartedAt.AddSeconds(-2) } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $report) {
        Write-Host "Gatling HTTP summary unavailable: no report directory was created for $Phase."
        return
    }

    $globalStatsPath = Join-Path $report.FullName "js\global_stats.json"
    if (-not (Test-Path $globalStatsPath)) {
        Write-Host "Gatling HTTP summary unavailable: $globalStatsPath is missing."
        return
    }

    $stats = Get-Content -Path $globalStatsPath -Raw | ConvertFrom-Json
    $total = [int64]$stats.numberOfRequests.total
    $ok = [int64]$stats.numberOfRequests.ok
    $ko = [int64]$stats.numberOfRequests.ko
    $successPercent = if ($total -gt 0) { [math]::Round(100d * $ok / $total, 4) } else { 0d }
    $p95Millis = [int64]$stats.percentiles3.total

    if (-not (Test-Path $httpSummaryCsv)) {
        Add-Content -Path $httpSummaryCsv -Encoding utf8 -Value "phase,totalRequests,successfulRequests,failedRequests,successPercent,p95Millis,reportDirectory"
    }
    Add-Content -Path $httpSummaryCsv -Encoding utf8 -Value `
        "$Phase,$total,$ok,$ko,$successPercent,$p95Millis,$($report.Name)"
}

function Show-MetricsSummary {
    param(
        [long]$ContestId = 0,
        [string]$ScenarioName = ""
    )

    $containerCsv = Join-Path $metricsRoot "containers.csv"
    $pipelineCsv = Join-Path $metricsRoot "pipeline.csv"

    Write-Host ""
    Write-Host "=== Sampled metrics: $metricsRoot ==="

    if (Test-Path $containerCsv) {
        Write-Host "-- peak container CPU, throttling, and memory per phase --"
        Import-Csv $containerCsv |
            Group-Object phase, container |
            ForEach-Object {
                $peakCpu = ($_.Group | Measure-Object -Property cpuPercent -Maximum).Maximum
                $peakMem = ($_.Group | Measure-Object -Property memUsedMb -Maximum).Maximum
                $limit = ($_.Group | Select-Object -Last 1).memLimitMb
                $throttleRows = @($_.Group |
                    Where-Object { $_.cpuPeriods -match '^[0-9]+$' -and $_.cpuThrottledPeriods -match '^[0-9]+$' } |
                    Sort-Object timestamp)
                $throttledRatio = $null
                if ($throttleRows.Count -ge 2) {
                    $periodDelta = [double]$throttleRows[-1].cpuPeriods - [double]$throttleRows[0].cpuPeriods
                    $throttledDelta = [double]$throttleRows[-1].cpuThrottledPeriods - [double]$throttleRows[0].cpuThrottledPeriods
                    if ($periodDelta -gt 0d) {
                        $throttledRatio = [math]::Round(100d * $throttledDelta / $periodDelta, 2)
                    }
                }
                [pscustomobject]@{
                    Phase = $_.Group[0].phase
                    Container = $_.Group[0].container
                    PeakCpuPercent = [math]::Round([double]$peakCpu, 1)
                    ThrottledRatioPercent = $throttledRatio
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
                $peakScoreboardOutbox = ($_.Group | ForEach-Object {
                    [int64]$_.scoreboardPending + [int64]$_.scoreboardProcessing + [int64]$_.scoreboardFailed
                } | Measure-Object -Maximum).Maximum
                [pscustomobject]@{
                    Phase = $_.Name
                    PeakJudgeOutbox = ($_.Group | Measure-Object -Property judgeOutboxPending -Maximum).Maximum
                    PeakJudgeHeadLagMs = ($_.Group | Measure-Object -Property judgeHeadLagMs -Maximum).Maximum
                    PeakScoreboardOutbox = $peakScoreboardOutbox
                    PeakScoreboardPending = ($_.Group | Measure-Object -Property scoreboardPending -Maximum).Maximum
                    PeakScoreboardFailed = ($_.Group | Measure-Object -Property scoreboardFailed -Maximum).Maximum
                    PeakScoreboardHeadLagMs = ($_.Group | Measure-Object -Property oldestPendingLagMs -Maximum).Maximum
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

    if (Test-Path $httpSummaryCsv) {
        Write-Host "-- Gatling HTTP summary per phase --"
        Import-Csv $httpSummaryCsv | Format-Table -AutoSize | Out-String -Width 250 | Write-Host
    }

    if ($ContestId -gt 0) {
        $staleness = Show-EndToEndStaleness -ContestId $ContestId -ScenarioName $ScenarioName
        $submissionPhases = switch ($ScenarioName) {
            "smoke" { @("submit-139") }
            "target" { @("submit-200") }
            { $_ -in @("scoreboard-200", "scoreboard-300", "scoreboard-2000") } { @("submit-139") }
            default { @($ScenarioName) }
        }
        $crossCheckPassed = Test-ScoreboardStalenessAgainstHeadLag `
            -ScoreboardDistribution $staleness.Scoreboard `
            -SubmissionPhases $submissionPhases
        if ($ScenarioName -in @("mixed", "mixed-real") -and -not $crossCheckPassed) {
            $script:stalenessCrossCheckFailed = $true
        }
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
            # Default schedule: 95,865 submissions + 450,015 reads. A 30-request scheduling
            # margin still proves that both populations really ran instead of accepting one hit.
            $minRequests = 545850L
            $script:expectedSubmissionCount = 95835L
            $scenarioArgs = @(
                "-Dperf.rampSeconds=30",
                "-Dperf.avgHoldSeconds=120",
                "-Dperf.peakRampSeconds=30",
                "-Dperf.peakHoldSeconds=60",
                "-Dperf.submitAvgRps=139",
                "-Dperf.submitPeakRps=1000",
                "-Dperf.readRps=2000",
                "-Dperf.startRank.min=1",
                "-Dperf.startRank.max=100000",
                "-Dperf.pageSize=100"
            ) + $seedArgs
        }
        "mixed-real" {
            $simulationClass = "my.oj.perf.OjRealPathGoalLoadSimulation"
            # The closed submission model targets the same 95,865 logical submissions as mixed.
            # Session setup and initial jitter happen on the measured web path, so allow roughly
            # four percent scheduling margin while still requiring the full 450,015-read shape.
            # Each accepted submission contributes its POST and checked redirect to Gatling's
            # request total; the 3,100 peak sessions also each load login, authenticate, and load
            # the submission form once.
            $minRequests = 640000L
            $script:expectedSubmissionCount = 92000L
            $scenarioArgs = @(
                "-Dperf.rampSeconds=30",
                "-Dperf.avgHoldSeconds=120",
                "-Dperf.peakRampSeconds=30",
                "-Dperf.peakHoldSeconds=60",
                "-Dperf.submitAvgRps=139",
                "-Dperf.submitPeakRps=1000",
                "-Dperf.readRps=2000",
                "-Dperf.submitIntervalMillis=3100",
                "-Dperf.initialJitterMillis=3000",
                "-Dperf.userPrefix=$seedPrefix",
                "-Dperf.userIndex.start=1",
                "-Dperf.userIndex.end=$UserCount"
            ) + $seedArgs
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

    $runStartedAt = Get-Date
    $sampler = Start-LoadSampler -Phase $SelectedScenario
    try {
        & $javaExe @javaArgs
        $gatlingExitCode = $LASTEXITCODE
    }
    finally {
        Stop-LoadSampler -Sampler $sampler
    }
    Save-GatlingHttpSummary -Phase $SelectedScenario -ResultsFolder $resultsFolder -RunStartedAt $runStartedAt
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
$seed = $null
$pipelineValidated = $false
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
        Wait-PipelineDrainWithSampling -TimeoutSeconds $DrainTimeoutSeconds -Phase "submit-139"
        Assert-PipelineMaterialized -ContestId $seed.contestId
        $pipelineValidated = $true
        Invoke-GatlingScenario -Seed $seed -SelectedScenario "scoreboard-200"
    }
    elseif ($Scenario -eq "target") {
        Invoke-GatlingScenario -Seed $seed -SelectedScenario "submit-200"
        Wait-PipelineDrainWithSampling -TimeoutSeconds $DrainTimeoutSeconds -Phase "submit-200"
        Assert-PipelineMaterialized -ContestId $seed.contestId
        $pipelineValidated = $true
        Invoke-GatlingScenario -Seed $seed -SelectedScenario "scoreboard-300"
    }
    elseif ($Scenario -eq "step") {
        Invoke-GatlingScenario -Seed $seed -SelectedScenario "step" -AllowAssertionFailure
    }
    elseif ($Scenario -in @("scoreboard-200", "scoreboard-300", "scoreboard-2000")) {
        Write-Host "Populating the Redis scoreboard through the full submission pipeline first."
        Invoke-GatlingScenario -Seed $seed -SelectedScenario "submit-139"
        Wait-PipelineDrainWithSampling -TimeoutSeconds $DrainTimeoutSeconds -Phase "submit-139"
        Assert-PipelineMaterialized -ContestId $seed.contestId
        $pipelineValidated = $true
        Invoke-GatlingScenario -Seed $seed -SelectedScenario $Scenario
    }
    else {
        Invoke-GatlingScenario -Seed $seed -SelectedScenario $Scenario
    }

    if ($Scenario -in @("submit-139", "submit-200", "submit-1000", "mixed", "mixed-real", "step")) {
        Wait-PipelineDrainWithSampling -TimeoutSeconds $DrainTimeoutSeconds -Phase $Scenario
    }
    else {
        Wait-PipelineDrain -TimeoutSeconds $DrainTimeoutSeconds
    }
    if ($Scenario -in @("target", "submit-139", "submit-200", "submit-1000", "mixed", "mixed-real")) {
        Assert-PipelineMaterialized -ContestId $seed.contestId
        $pipelineValidated = $true
    }
    Show-WebMetrics -ContestId $seed.contestId
    Assert-NoOomKilled
}
finally {
    try {
        # Runs before teardown so a failed run still reports where the pressure was. End-to-end
        # SQL needs the materialization invariant, so only query it after that check passed.
        if (Test-Path $metricsRoot) {
            $summaryContestId = if ($pipelineValidated) { [long]$seed.contestId } else { 0L }
            Show-MetricsSummary -ContestId $summaryContestId -ScenarioName $Scenario
        }
    }
    finally {
        if ($started -and -not $KeepStack) {
            if ($RemoveData) {
                Invoke-LoadCompose -Arguments @("down", "-v")
            }
            else {
                Invoke-LoadCompose -Arguments @("down")
            }
        }
    }
}

if ($script:stalenessCrossCheckFailed) {
    throw "$Scenario end-to-end scoreboard p99 did not match the sampled scoreboard head-lag maximum within one order of magnitude."
}
