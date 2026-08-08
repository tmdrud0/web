[CmdletBinding()]
param(
    [ValidateSet("smoke", "target", "step", "submit-100", "submit-139", "submit-200", "submit-1000", "scoreboard-200", "scoreboard-300", "scoreboard-2000", "mixed", "mixed-target")]
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
$stateResetCsv = Join-Path $metricsRoot "state-reset.csv"
$jvmSummaryCsv = Join-Path $metricsRoot "jvm-summary.csv"
$rabbitmqSummaryCsv = Join-Path $metricsRoot "rabbitmq-summary.csv"
$runDiagnosticsCsv = Join-Path $metricsRoot "run-diagnostics.csv"
$script:stalenessCrossCheckFailed = $false
$script:resetRedisKeys = $null
$script:resetSubmissionOutboxRows = $null
$script:resetJudgeOutboxRows = $null
$script:resetTimestamp = $null

# The per-user cooldown costs a Redis round trip on every submission in production, so a run with
# it off measures a system cheaper than the deployed one by exactly that. It used to be on only
# for the closed-model scenarios, because the open-model ones drew a user at random per request
# and would have spent the run being refused by a limiter their own load model tripped. Every
# submission scenario is closed now - the API authenticates, so there is no other way to drive it -
# and every one of them paces each user deliberately, so it is simply on.
#
# That pace has to clear the cooldown, or the run manufactures 429s the product would never have
# produced. Both come from this one number so they cannot drift apart, and a rate that would need
# users to submit faster than the product allows fails at simulation start rather than quietly
# measuring refusals.
$rateLimitCooldownMillis = 2000
$minSubmitIntervalMillis = $rateLimitCooldownMillis + 1000
$env:CONTEST_RATE_LIMIT_STORE = "redis"
$env:CONTEST_RATE_LIMIT_COOLDOWN_MILLIS = "$rateLimitCooldownMillis"

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

# `ps` selects on the project label, not on the compose files passed to it, so it returns the
# observability stack too when that is brought up in the same project - which observability/README.md
# §1 is what tells you to do. The callers below count on getting exactly the nine application
# containers, so Wait-Healthy sat out its full timeout against a stack that was already healthy.
# Scoping the query to the services these files declare keeps the count meaning what it says.
function Get-LoadContainerIds {
    Push-Location $repoRoot
    try {
        $services = @(& docker compose "-p" $projectName @composeFiles config --services)
        if ($LASTEXITCODE -ne 0) {
            throw "Could not list load-test services."
        }
        $services = @($services | Where-Object { $_ })
        $ids = & docker compose "-p" $projectName @composeFiles ps -aq @services
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

function Invoke-LoadRedisScalar {
    param([Parameter(Mandatory = $true)][string]$Command)

    $value = Invoke-LoadCompose -Arguments @("exec", "-T", "redis", "redis-cli", "--raw", $Command)
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

# FLUSHDB resets the Redis sequence counter to zero, so every redis_seq already stored in the
# database becomes a value the next run will hand out again. That is exactly the signal section 5
# of the pipeline history defines as a Redis rollback, and the recovery worker acts on it: it
# requeues COMPLETED rows whose sequence now looks duplicated, they are applied a second time, and
# their processed_at is rewritten long after the run. Measured on the first completing run - 946
# sequence values shared between the previous contest and the current one, 924 rows at attempts=2,
# and a scoreboard-applied tail of p99 31s and max 178s against 4.5s for everything that ran once.
# The requeue loop was still firing five minutes after the load stopped.
#
# Redis and these two tables are one piece of state. Clearing half of it manufactures the failure
# the recovery path exists to repair. The drain above guarantees every row is terminal before this
# runs, so nothing in flight is discarded.
function Reset-LoadRedis {
    Invoke-LoadCompose -Arguments @("exec", "-T", "redis", "redis-cli", "FLUSHDB") | Out-Null
    Invoke-LoadCompose -Arguments @(
        "exec", "-T", "mysql", "mysql", "-uroot", "-p1234", "-D", $dbName, "-Nse",
        "DELETE FROM contest_submission_outbox; DELETE FROM contest_judge_outbox;") | Out-Null

    $script:resetTimestamp = (Get-Date).ToString("yyyy-MM-ddTHH:mm:ss.fffK")
    $script:resetRedisKeys = Invoke-LoadRedisScalar -Command "DBSIZE"
    $script:resetSubmissionOutboxRows = Invoke-LoadSqlScalar "SELECT COUNT(*) FROM contest_submission_outbox"
    $script:resetJudgeOutboxRows = Invoke-LoadSqlScalar "SELECT COUNT(*) FROM contest_judge_outbox"

    New-Item -ItemType Directory -Force -Path $metricsRoot | Out-Null
    @(
        "timestamp,redisKeys,submissionOutboxRows,judgeOutboxRows",
        "$($script:resetTimestamp),$($script:resetRedisKeys),$($script:resetSubmissionOutboxRows),$($script:resetJudgeOutboxRows)"
    ) | Set-Content -Path $stateResetCsv -Encoding utf8

    if ($script:resetRedisKeys -ne 0 -or
        $script:resetSubmissionOutboxRows -ne 0 -or
        $script:resetJudgeOutboxRows -ne 0) {
        throw "Load state reset was incomplete: Redis=$($script:resetRedisKeys), submission outbox=$($script:resetSubmissionOutboxRows), judge outbox=$($script:resetJudgeOutboxRows)."
    }
    Write-Host "Load state reset verified (Redis=0, submission outbox=0, judge outbox=0)."
}

# Reads the product's own scoreboard endpoint rather than a /perf mirror of it. The mirror
# returned the size of the page instead of the page and took no authentication, so the harness was
# checking a different query than the one it then went on to load.
function Get-ScoreboardSummary {
    param([Parameter(Mandatory = $true)][long]$ContestId)

    return Invoke-SetupRequest -Method Get -Uri "$baseUrl/api/contests/$ContestId/scoreboard?startRank=1&size=1"
}

# How many participants the population phase actually produced, so the read phase can draw ranks
# from the scoreboard that exists rather than from a constant. The read simulation refuses to start
# without this, which is what stops a hardcoded range coming back: paging past the last participant
# costs one Redis command instead of 102, and a run that mostly does that reports a rate it never
# served.
function Get-ScoreboardParticipants {
    param([Parameter(Mandatory = $true)][long]$ContestId)

    $participants = [int64](Get-ScoreboardSummary -ContestId $ContestId).totalParticipants
    if ($participants -le 0) {
        throw "Scoreboard has no participants; the read phase would measure empty pages."
    }
    Write-Host "Scoreboard reads will draw startRank from 1..$participants."
    return $participants
}

function Assert-PipelineMaterialized {
    param([Parameter(Mandatory = $true)][long]$ContestId)

    $submissionCount = Invoke-LoadSqlScalar "SELECT COUNT(*) FROM contest_submission WHERE contest_id = $ContestId"
    $resultCount = Invoke-LoadSqlScalar "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id = $ContestId"
    $resultTimestampCount = Invoke-LoadSqlScalar "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id = $ContestId AND result_saved_at IS NOT NULL"
    $scoreboardTimestampCount = Invoke-LoadSqlScalar "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id = $ContestId AND scoreboard_applied_at IS NOT NULL"
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
        $resultTimestampCount -ne $submissionCount -or
        $scoreboardTimestampCount -ne $submissionCount -or
        $outboxCount -ne $submissionCount -or
        $completedCount -ne $submissionCount) {
        throw "Pipeline count mismatch: submissions=$submissionCount results=$resultCount resultTimestamps=$resultTimestampCount scoreboardTimestamps=$scoreboardTimestampCount outbox=$outboxCount completed=$completedCount"
    }

    $scoreboard = Get-ScoreboardSummary -ContestId $ContestId
    if ([int64]$scoreboard.totalParticipants -le 0) {
        throw "Redis scoreboard is empty after the pipeline drained."
    }
    if ([int64]$scoreboard.totalParticipants -ne $dbParticipantCount) {
        throw "Scoreboard participant mismatch: DB=$dbParticipantCount Redis=$($scoreboard.totalParticipants)"
    }
    Write-Host "Pipeline materialized: submissions=$submissionCount, results=$resultCount, resultTimestamps=$resultTimestampCount, scoreboardTimestamps=$scoreboardTimestampCount, scoreboardParticipants=$($scoreboard.totalParticipants)"
}

function Get-EndToEndLatencyDistribution {
    param(
        [Parameter(Mandatory = $true)][long]$ContestId,
        [Parameter(Mandatory = $true)]
        [ValidateSet("result-saved", "scoreboard-applied", "legacy-result-created", "legacy-scoreboard-processed")]
        [string]$Endpoint
    )

    $endExpression = switch ($Endpoint) {
        "result-saved" { "csr.result_saved_at" }
        "scoreboard-applied" { "csr.scoreboard_applied_at" }
        "legacy-result-created" { "o.created_at" }
        "legacy-scoreboard-processed" { "o.processed_at" }
    }
    $outboxJoin = if ($Endpoint.StartsWith("legacy-")) {
        "JOIN contest_submission_outbox o ON o.contest_submission_id = cs.id"
    } else {
        ""
    }
    $sql = @"
WITH latencies AS (
    SELECT TIMESTAMPDIFF(MICROSECOND, cs.submitted_time, $endExpression) AS latency_us
    FROM contest_submission cs
    JOIN contest_submission_result csr ON csr.submission_id = cs.id
    $outboxJoin
    WHERE cs.contest_id = $ContestId
      AND $endExpression IS NOT NULL
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

    # Primary endpoints live on the result row. The outbox endpoints remain side by side only for
    # transition validation and can be removed once historical comparisons no longer need them.
    $result = Get-EndToEndLatencyDistribution -ContestId $ContestId -Endpoint "result-saved"
    $scoreboard = Get-EndToEndLatencyDistribution -ContestId $ContestId -Endpoint "scoreboard-applied"
    $legacyResult = Get-EndToEndLatencyDistribution -ContestId $ContestId -Endpoint "legacy-result-created"
    $legacyScoreboard = Get-EndToEndLatencyDistribution -ContestId $ContestId -Endpoint "legacy-scoreboard-processed"
    $distributions = @($result, $scoreboard, $legacyResult, $legacyScoreboard)
    if (@($distributions | Where-Object { $_.SampleCount -le 0 }).Count -gt 0) {
        throw "End-to-end staleness has missing samples: result=$($result.SampleCount), scoreboard=$($scoreboard.SampleCount), legacyResult=$($legacyResult.SampleCount), legacyScoreboard=$($legacyScoreboard.SampleCount)"
    }
    if (@($distributions | Where-Object { $_.SampleCount -ne $result.SampleCount }).Count -gt 0) {
        throw "End-to-end sample count mismatch: result=$($result.SampleCount), scoreboard=$($scoreboard.SampleCount), legacyResult=$($legacyResult.SampleCount), legacyScoreboard=$($legacyScoreboard.SampleCount)"
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
    Write-Host ("legacy result      p50 {0}  p95 {1}  p99 {2}  max {3}   n={4}" -f
        (Format-LatencySeconds $legacyResult.P50Micros),
        (Format-LatencySeconds $legacyResult.P95Micros),
        (Format-LatencySeconds $legacyResult.P99Micros),
        (Format-LatencySeconds $legacyResult.MaxMicros),
        $legacyResult.SampleCount)
    Write-Host ("legacy scoreboard  p50 {0}  p95 {1}  p99 {2}  max {3}   n={4}" -f
        (Format-LatencySeconds $legacyScoreboard.P50Micros),
        (Format-LatencySeconds $legacyScoreboard.P95Micros),
        (Format-LatencySeconds $legacyScoreboard.P99Micros),
        (Format-LatencySeconds $legacyScoreboard.MaxMicros),
        $legacyScoreboard.SampleCount)

    @(
        "scenario,metric,p50Micros,p95Micros,p99Micros,maxMicros,sampleCount",
        "$ScenarioName,result-queryable,$($result.P50Micros),$($result.P95Micros),$($result.P99Micros),$($result.MaxMicros),$($result.SampleCount)",
        "$ScenarioName,scoreboard-applied,$($scoreboard.P50Micros),$($scoreboard.P95Micros),$($scoreboard.P99Micros),$($scoreboard.MaxMicros),$($scoreboard.SampleCount)",
        "$ScenarioName,legacy-result-queryable,$($legacyResult.P50Micros),$($legacyResult.P95Micros),$($legacyResult.P99Micros),$($legacyResult.MaxMicros),$($legacyResult.SampleCount)",
        "$ScenarioName,legacy-scoreboard-applied,$($legacyScoreboard.P50Micros),$($legacyScoreboard.P95Micros),$($legacyScoreboard.P99Micros),$($legacyScoreboard.MaxMicros),$($legacyScoreboard.SampleCount)"
    ) | Set-Content -Path $stalenessCsv -Encoding utf8

    return [pscustomobject]@{
        Result = $result
        Scoreboard = $scoreboard
        LegacyResult = $legacyResult
        LegacyScoreboard = $legacyScoreboard
    }
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

function Get-NearestRankValue {
    param(
        [Parameter(Mandatory = $true)][double[]]$Values,
        [Parameter(Mandatory = $true)][double]$Percentile
    )

    if ($Values.Count -eq 0) { return $null }
    $ordered = @($Values | Sort-Object)
    $index = [math]::Max(0, [math]::Ceiling($ordered.Count * $Percentile) - 1)
    return [double]$ordered[$index]
}

function Save-JvmMetricsSummary {
    param([Parameter(Mandatory = $true)][string]$Phase)

    $jvmCsv = Join-Path $metricsRoot "jvm-metrics.csv"
    if (-not (Test-Path $jvmCsv)) { return @() }

    $summaries = @(
        Import-Csv $jvmCsv |
            Group-Object node |
            ForEach-Object {
                $rows = @($_.Group | Sort-Object timestamp)
                $throttleValues = [double[]]@($rows |
                    Where-Object { $_.throttleRatio -match '^[0-9]' } |
                    ForEach-Object { [double]$_.throttleRatio })
                $heapValues = [double[]]@($rows |
                    Where-Object { $_.heapUsedBytes -match '^[0-9]' } |
                    ForEach-Object { [double]$_.heapUsedBytes })
                $restartChanges = 0
                $gcDelta = 0d
                for ($index = 1; $index -lt $rows.Count; $index++) {
                    if ($rows[$index].processStartTimeSeconds -ne $rows[$index - 1].processStartTimeSeconds) {
                        $restartChanges++
                    }
                    if ($rows[$index].gcPauseSeconds -match '^[0-9]' -and
                        $rows[$index - 1].gcPauseSeconds -match '^[0-9]') {
                        $delta = [double]$rows[$index].gcPauseSeconds - [double]$rows[$index - 1].gcPauseSeconds
                        if ($delta -ge 0d) {
                            $gcDelta += $delta
                        }
                        else {
                            # A restart resets the cumulative counter. The run is invalid anyway,
                            # but retaining the post-restart contribution keeps the diagnostic honest.
                            $gcDelta += [double]$rows[$index].gcPauseSeconds
                        }
                    }
                }

                $medianThrottle = Get-NearestRankValue -Values $throttleValues -Percentile 0.50
                $p95Throttle = Get-NearestRankValue -Values $throttleValues -Percentile 0.95
                $medianHeap = Get-NearestRankValue -Values $heapValues -Percentile 0.50
                $p95Heap = Get-NearestRankValue -Values $heapValues -Percentile 0.95
                [pscustomobject]@{
                    Phase = $Phase
                    Node = $_.Name
                    Samples = $rows.Count
                    ThrottleMedianPercent = if ($null -ne $medianThrottle) { [math]::Round(100d * $medianThrottle, 2) } else { $null }
                    ThrottleP95Percent = if ($null -ne $p95Throttle) { [math]::Round(100d * $p95Throttle, 2) } else { $null }
                    RestartChanges = $restartChanges
                    GcPauseDeltaSeconds = [math]::Round($gcDelta, 6)
                    HeapMedianMiB = if ($null -ne $medianHeap) { [math]::Round($medianHeap / 1MB, 2) } else { $null }
                    HeapP95MiB = if ($null -ne $p95Heap) { [math]::Round($p95Heap / 1MB, 2) } else { $null }
                }
            }
    )

    if ($summaries.Count -gt 0) {
        $summaries | Export-Csv -Path $jvmSummaryCsv -NoTypeInformation -Encoding utf8
    }
    return $summaries
}

function Get-CounterRateSummary {
    param(
        [Parameter(Mandatory = $true)][object[]]$Rows,
        [Parameter(Mandatory = $true)][string[]]$Properties
    )

    $total = 0d
    $peak = 0d
    $resets = 0
    for ($index = 1; $index -lt $Rows.Count; $index++) {
        $seconds = ([datetime]$Rows[$index].timestamp - [datetime]$Rows[$index - 1].timestamp).TotalSeconds
        if ($seconds -le 0d) { continue }

        $current = 0d
        $previous = 0d
        foreach ($property in $Properties) {
            $current += [double]$Rows[$index].$property
            $previous += [double]$Rows[$index - 1].$property
        }
        $delta = $current - $previous
        if ($delta -lt 0d) {
            # Broker restart resets these counters. Preserve post-restart work in the total while
            # making the reset explicit; a comparison run with a reset is invalid independently.
            $resets++
            $delta = $current
        }
        $total += $delta
        $rate = $delta / $seconds
        if ($rate -gt $peak) { $peak = $rate }
    }

    $duration = if ($Rows.Count -ge 2) {
        ([datetime]$Rows[-1].timestamp - [datetime]$Rows[0].timestamp).TotalSeconds
    } else {
        0d
    }
    return [pscustomobject]@{
        Delta = $total
        AveragePerSecond = if ($duration -gt 0d) { $total / $duration } else { 0d }
        PeakPerSecond = $peak
        Resets = $resets
    }
}

function Save-RabbitMqMetricsSummary {
    $rabbitmqCsv = Join-Path $metricsRoot "rabbitmq-metrics.csv"
    if (-not (Test-Path $rabbitmqCsv)) { return @() }

    $summaries = @(
        Import-Csv $rabbitmqCsv |
            Group-Object phase, queue |
            ForEach-Object {
                $rows = @($_.Group | Sort-Object timestamp)
                $published = Get-CounterRateSummary -Rows $rows -Properties @("publishedTotal")
                $delivered = Get-CounterRateSummary -Rows $rows -Properties @("deliveredAckTotal", "deliveredAutoTotal")
                [pscustomobject]@{
                    Phase = $rows[0].phase
                    Queue = $rows[0].queue
                    Samples = $rows.Count
                    PeakReady = [int64](($rows | Measure-Object -Property ready -Maximum).Maximum)
                    PeakUnacked = [int64](($rows | Measure-Object -Property unacked -Maximum).Maximum)
                    ConsumerMin = [int64](($rows | Measure-Object -Property consumers -Minimum).Minimum)
                    ConsumerMax = [int64](($rows | Measure-Object -Property consumers -Maximum).Maximum)
                    PublishedDelta = [int64]$published.Delta
                    PublishedAveragePerSecond = [math]::Round($published.AveragePerSecond, 2)
                    PublishedPeakPerSecond = [math]::Round($published.PeakPerSecond, 2)
                    DeliveredDelta = [int64]$delivered.Delta
                    DeliveredAveragePerSecond = [math]::Round($delivered.AveragePerSecond, 2)
                    DeliveredPeakPerSecond = [math]::Round($delivered.PeakPerSecond, 2)
                    CounterResets = $published.Resets + $delivered.Resets
                }
            }
    )

    if ($summaries.Count -gt 0) {
        $summaries | Export-Csv -Path $rabbitmqSummaryCsv -NoTypeInformation -Encoding utf8
    }
    return $summaries
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
                    PeakRabbitLiveReady = ($_.Group | Measure-Object -Property rabbitLiveReady -Maximum).Maximum
                    PeakRabbitLiveUnacked = ($_.Group | Measure-Object -Property rabbitLiveUnacked -Maximum).Maximum
                    PeakRabbitDeadReady = ($_.Group | Measure-Object -Property rabbitDeadReady -Maximum).Maximum
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

    $rabbitmqSummaries = @(Save-RabbitMqMetricsSummary)
    if ($rabbitmqSummaries.Count -gt 0) {
        Write-Host "-- RabbitMQ per-queue baseline --"
        $rabbitmqSummaries |
            Sort-Object Phase, Queue |
            Format-Table -AutoSize | Out-String -Width 250 | Write-Host

        if (Test-Path $pipelineCsv) {
            Write-Host "-- judge outbox to live-queue ingress cross-check --"
            Import-Csv $pipelineCsv |
                Group-Object phase |
                ForEach-Object {
                    $rows = @($_.Group | Sort-Object timestamp)
                    $phase = $_.Name
                    $live = $rabbitmqSummaries |
                        Where-Object { $_.Phase -eq $phase -and $_.Queue -eq "contest.judge.live" } |
                        Select-Object -First 1
                    if ($rows.Count -ge 2 -and $null -ne $live) {
                        $outboxPublishedDelta = [int64]$rows[-1].judgeOutboxPublished - [int64]$rows[0].judgeOutboxPublished
                        [pscustomobject]@{
                            Phase = $phase
                            JudgeOutboxPublishedDelta = $outboxPublishedDelta
                            RabbitLivePublishedDelta = $live.PublishedDelta
                            AbsoluteDifference = [math]::Abs($outboxPublishedDelta - [int64]$live.PublishedDelta)
                            PeakJudgeOutboxPending = ($rows | Measure-Object -Property judgeOutboxPending -Maximum).Maximum
                            PeakRabbitLiveReady = ($rows | Measure-Object -Property rabbitLiveReady -Maximum).Maximum
                            PeakRabbitLiveUnacked = ($rows | Measure-Object -Property rabbitLiveUnacked -Maximum).Maximum
                        }
                    }
                } |
                Format-Table -AutoSize | Out-String -Width 250 | Write-Host
        }
    }
    else {
        Write-Host "RabbitMQ per-queue baseline unavailable (rabbitmq-per-queue Prometheus target was not sampled)."
    }

    if (Test-Path $bulkStatsCsv) {
        Write-Host "-- submission bulk metrics per phase --"
        Import-Csv $bulkStatsCsv | Format-Table -AutoSize | Out-String -Width 250 | Write-Host
    }

    if (Test-Path $httpSummaryCsv) {
        Write-Host "-- Gatling HTTP summary per phase --"
        Import-Csv $httpSummaryCsv | Format-Table -AutoSize | Out-String -Width 250 | Write-Host
    }

    $jvmSummaries = @(Save-JvmMetricsSummary -Phase $ScenarioName)
    if ($jvmSummaries.Count -gt 0) {
        Write-Host "-- JVM throttling, restarts, GC, and heap per node --"
        $jvmSummaries | Format-Table -AutoSize | Out-String -Width 250 | Write-Host
    }

    if ($ContestId -gt 0) {
        $retryRows = Invoke-LoadSqlScalar "SELECT COUNT(*) FROM contest_submission_outbox WHERE contest_id = $ContestId AND attempts > 1"
        $restartChanges = ($jvmSummaries | Measure-Object -Property RestartChanges -Sum).Sum
        $observedJvmNodes = @($jvmSummaries | Select-Object -ExpandProperty Node -Unique).Count
        @(
            "scenario,contestId,resetTimestamp,resetRedisKeys,resetSubmissionOutboxRows,resetJudgeOutboxRows,scoreboardRetryRows,jvmRestartChanges,observedJvmNodes",
            "$ScenarioName,$ContestId,$($script:resetTimestamp),$($script:resetRedisKeys),$($script:resetSubmissionOutboxRows),$($script:resetJudgeOutboxRows),$retryRows,$restartChanges,$observedJvmNodes"
        ) | Set-Content -Path $runDiagnosticsCsv -Encoding utf8
        Write-Host "Run diagnostics: attempts>1=$retryRows, JVM restart changes=$restartChanges, observed JVM nodes=$observedJvmNodes/5"

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
        if ($ScenarioName -in @("mixed", "mixed-target") -and -not $crossCheckPassed) {
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

# A closed model has no arrival rate. It has a population and a pace, and the rate falls out as
# users/interval, so the interval is not free: it decides how many participants the contest ends
# up with, how many logins the run pays, and how large a scoreboard the read scenarios page
# through. Half the seeded pool, which is where -UserCount 10000 against a 200/s peak already put
# mixed-target at 5,000 users. Derived rather than written per scenario so that -UserCount scales
# the field instead of breaking the run, and so no scenario can ask for more sessions than there
# are seeded accounts.
function Get-SubmitIntervalMillis {
    param([Parameter(Mandatory = $true)][double]$PeakRps)

    $millis = [long]([math]::Floor(0.5 * $UserCount / $PeakRps) * 1000)
    if ($millis -lt $minSubmitIntervalMillis) { $millis = [long]$minSubmitIntervalMillis }
    return $millis
}

# The population that delivers a rate at that pace, which is also the participant count and the
# number of logins: one per session, and sessions never rotate.
function Get-ConcurrentUsers {
    param(
        [Parameter(Mandatory = $true)][double]$Rps,
        [Parameter(Mandatory = $true)][long]$IntervalMillis
    )

    return [long][math]::Ceiling($Rps * $IntervalMillis / 1000.0)
}

# Area under an injection schedule. A ramp is a trapezoid between two rates, a hold is a
# rectangle, and a closed model delivers the rate it was sized for - so the same arithmetic counts
# its submissions as counted the open model's arrivals.
function Get-RampCount {
    param(
        [Parameter(Mandatory = $true)][double]$FromRps,
        [Parameter(Mandatory = $true)][double]$ToRps,
        [Parameter(Mandatory = $true)][int]$Seconds
    )

    return [long][math]::Floor((($FromRps + $ToRps) / 2.0) * $Seconds)
}

function Get-HoldCount {
    param(
        [Parameter(Mandatory = $true)][double]$Rps,
        [Parameter(Mandatory = $true)][int]$Seconds
    )

    return [long][math]::Floor($Rps * $Seconds)
}

# The common ramp-then-hold shape: up from one arrival a second to the target, then flat.
function Get-ScheduledCount {
    param(
        [Parameter(Mandatory = $true)][double]$Rps,
        [Parameter(Mandatory = $true)][int]$RampSeconds,
        [Parameter(Mandatory = $true)][int]$HoldSeconds
    )

    return (Get-RampCount -FromRps 1 -ToRps $Rps -Seconds $RampSeconds) +
           (Get-HoldCount -Rps $Rps -Seconds $HoldSeconds)
}

# Four percent, the convention every closed scenario uses. A closed population's phase is
# randomised over one interval, so the tight scheduling margin the open model could hold is not
# reachable here.
function Get-AssertionFloor {
    param([Parameter(Mandatory = $true)][long]$Expected)

    return [long][math]::Floor($Expected * 0.96)
}

function Invoke-GatlingScenario {
    param(
        [Parameter(Mandatory = $true)]$Seed,
        [Parameter(Mandatory = $true)][string]$SelectedScenario,
        # Measured from the scoreboard after the population phase drained, not assumed. Reads that
        # land past the last participant cost one Redis command instead of 102, so a range wider
        # than the contest turns a read benchmark into a ZCARD benchmark.
        [long]$ScoreboardParticipants = 0,
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
    # The submission simulations authenticate now, so they address users by seeded name rather than
    # by the id range the perf endpoint took in its body.
    $seedArgs = @(
        "-Dperf.contestId=$($Seed.contestId)",
        "-Dperf.problemId.start=$($Seed.firstProblemId)",
        "-Dperf.problemId.end=$($Seed.lastProblemId)"
    )
    $userArgs = @(
        "-Dperf.userPrefix=$seedPrefix",
        "-Dperf.userIndex.start=1",
        "-Dperf.userIndex.end=$UserCount"
    )

    switch ($SelectedScenario) {
        # Below the judge's drain rate on every profile in application-loadtest.properties, so the
        # queue stays empty and end-to-end latency is the pipeline's own cost rather than a wait
        # behind a backlog. That makes it the scenario to measure anything the backlog would
        # otherwise dominate - a consumer dying and its unacked messages being redelivered shows up
        # in `max` here and is invisible at 200.
        "submit-100" {
            $simulationClass = "my.oj.perf.ContestSubmissionSimulation"
            $submitInterval = Get-SubmitIntervalMillis -PeakRps 100
            $submissions = Get-ScheduledCount -Rps 100 -RampSeconds 30 -HoldSeconds 180
            $logins = Get-ConcurrentUsers -Rps 100 -IntervalMillis $submitInterval
            $minRequests = Get-AssertionFloor -Expected ($submissions + $logins)
            $script:expectedSubmissionCount = Get-AssertionFloor -Expected $submissions
            $scenarioArgs = @(
                "-Dperf.targetRps=100",
                "-Dperf.rampSeconds=30",
                "-Dperf.holdSeconds=180",
                "-Dperf.submitIntervalMillis=$submitInterval"
            ) + $userArgs + $seedArgs
        }
        "submit-139" {
            $simulationClass = "my.oj.perf.ContestSubmissionSimulation"
            # Submissions follow the rate schedule; the logins do not. Every session authenticates
            # once, so the run also pays one login per concurrent user, and leaving those out of
            # minRequests would let a run that never logged anybody in still clear the bar.
            $submitInterval = Get-SubmitIntervalMillis -PeakRps 139
            $submissions = Get-ScheduledCount -Rps 139 -RampSeconds 30 -HoldSeconds 180
            $logins = Get-ConcurrentUsers -Rps 139 -IntervalMillis $submitInterval
            $minRequests = Get-AssertionFloor -Expected ($submissions + $logins)
            $script:expectedSubmissionCount = Get-AssertionFloor -Expected $submissions
            $scenarioArgs = @(
                "-Dperf.targetRps=139",
                "-Dperf.rampSeconds=30",
                "-Dperf.holdSeconds=180",
                "-Dperf.submitIntervalMillis=$submitInterval"
            ) + $userArgs + $seedArgs
        }
        "submit-200" {
            $simulationClass = "my.oj.perf.ContestSubmissionSimulation"
            $submitInterval = Get-SubmitIntervalMillis -PeakRps 200
            $submissions = Get-ScheduledCount -Rps 200 -RampSeconds 30 -HoldSeconds 120
            $logins = Get-ConcurrentUsers -Rps 200 -IntervalMillis $submitInterval
            $minRequests = Get-AssertionFloor -Expected ($submissions + $logins)
            $script:expectedSubmissionCount = Get-AssertionFloor -Expected $submissions
            $scenarioArgs = @(
                "-Dperf.targetRps=200",
                "-Dperf.rampSeconds=30",
                "-Dperf.holdSeconds=120",
                "-Dperf.submitIntervalMillis=$submitInterval"
            ) + $userArgs + $seedArgs
        }
        "submit-1000" {
            $simulationClass = "my.oj.perf.ContestSubmissionSimulation"
            $submitInterval = Get-SubmitIntervalMillis -PeakRps 1000
            $submissions = Get-ScheduledCount -Rps 1000 -RampSeconds 30 -HoldSeconds 120
            $logins = Get-ConcurrentUsers -Rps 1000 -IntervalMillis $submitInterval
            $minRequests = Get-AssertionFloor -Expected ($submissions + $logins)
            $script:expectedSubmissionCount = Get-AssertionFloor -Expected $submissions
            $scenarioArgs = @(
                "-Dperf.targetRps=1000",
                "-Dperf.rampSeconds=30",
                "-Dperf.holdSeconds=120",
                "-Dperf.submitIntervalMillis=$submitInterval"
            ) + $userArgs + $seedArgs
        }
        "step" {
            # A staircase finds where the stack stops keeping up. A fixed-rate pass/fail run only
            # says whether one chosen rate worked.
            #
            # The steps are populations now, because the load is closed: a closed model cannot
            # overload a server the way an open one does - its users wait for their last response
            # instead of piling arrivals on top - so saturation reads as throughput falling short
            # of the step's target rather than as errors. minRequests stays at 1 because this run
            # is meant to be pushed until it fails and the samples are the point.
            $simulationClass = "my.oj.perf.ContestSubmissionStepLoadSimulation"
            $minRequests = 1L
            $submitInterval = Get-SubmitIntervalMillis -PeakRps $StepMaxRps
            $scenarioArgs = @(
                "-Dperf.startRps=$StepStartRps",
                "-Dperf.stepRps=$StepRps",
                "-Dperf.maxRps=$StepMaxRps",
                "-Dperf.rampSeconds=$StepRampSeconds",
                "-Dperf.stepHoldSeconds=$StepHoldSeconds",
                "-Dperf.submitIntervalMillis=$submitInterval"
            ) + $userArgs + $seedArgs
        }
        "scoreboard-200" {
            $simulationClass = "my.oj.perf.ContestScoreboardReadSimulation"
            $minRequests = Get-AssertionFloor -Expected (Get-ScheduledCount -Rps 200 -RampSeconds 30 -HoldSeconds 60)
            $scenarioArgs = @("-Dperf.targetRps=200", "-Dperf.rampSeconds=30", "-Dperf.holdSeconds=60", "-Dperf.contestId=$($Seed.contestId)", "-Dperf.startRank.min=1", "-Dperf.startRank.max=$ScoreboardParticipants", "-Dperf.pageSize=100")
        }
        "scoreboard-300" {
            $simulationClass = "my.oj.perf.ContestScoreboardReadSimulation"
            $minRequests = Get-AssertionFloor -Expected (Get-ScheduledCount -Rps 300 -RampSeconds 30 -HoldSeconds 120)
            $scenarioArgs = @("-Dperf.targetRps=300", "-Dperf.rampSeconds=30", "-Dperf.holdSeconds=120", "-Dperf.contestId=$($Seed.contestId)", "-Dperf.startRank.min=1", "-Dperf.startRank.max=$ScoreboardParticipants", "-Dperf.pageSize=100")
        }
        "scoreboard-2000" {
            $simulationClass = "my.oj.perf.ContestScoreboardReadSimulation"
            $minRequests = Get-AssertionFloor -Expected (Get-ScheduledCount -Rps 2000 -RampSeconds 30 -HoldSeconds 120)
            $scenarioArgs = @("-Dperf.targetRps=2000", "-Dperf.rampSeconds=30", "-Dperf.holdSeconds=120", "-Dperf.contestId=$($Seed.contestId)", "-Dperf.startRank.min=1", "-Dperf.startRank.max=$ScoreboardParticipants", "-Dperf.pageSize=100")
        }
        # mixed at the rates each README scenario is documented to pass on its own, run together.
        # mixed itself combines two loads the scenario table calls "not a pass criterion" -
        # submit-1000 and scoreboard-2000 - so it is an overload observation and cannot produce a
        # latency figure that means anything about the pipeline. Measured on the fixed budget: a
        # web instance reaches its 1.0 CPU ceiling seven seconds into the ramp, around 470 reads
        # per second, and both web instances plus judge-1 were OOM-killed once the submit peak
        # arrived on top of that. 139 -> 200 submits against 300 reads leaves headroom, so a
        # staleness distribution taken here describes the pipeline rather than the CPU limit.
        #
        # Counts still follow the rate schedule, because a closed model delivers the rate it was
        # sized for: submits are (1+avg)/2*ramp + avg*avgHold + (avg+peak)/2*peakRamp +
        # peak*peakHold = 35,865 and reads are (1+rps)/2*ramp + rps*(avgHold + peakRamp +
        # peakHold) = 67,515. What the closed model changes is where those submissions come from -
        # ceil(rps * interval) users pacing, rather than that many arrivals a second - and the
        # simulation spreads each user's first submission over one interval so the schedule holds
        # through the ramp instead of firing the whole population at once.
        #
        # The peak population also logs in once each, and those logins are requests. Leaving them
        # out is how minRequests came to sit at 99,835 against a run that now issues 108,380: a
        # floor 8% below the truth stops proving that the run ran.
        "mixed-target" {
            $simulationClass = "my.oj.perf.OjGoalLoadSimulation"
            $submitInterval = Get-SubmitIntervalMillis -PeakRps 200
            $submissions = (Get-RampCount -FromRps 1 -ToRps 139 -Seconds 30) +
                           (Get-HoldCount -Rps 139 -Seconds 120) +
                           (Get-RampCount -FromRps 139 -ToRps 200 -Seconds 30) +
                           (Get-HoldCount -Rps 200 -Seconds 60)
            $reads = Get-ScheduledCount -Rps 300 -RampSeconds 30 -HoldSeconds 210
            $logins = Get-ConcurrentUsers -Rps 200 -IntervalMillis $submitInterval
            $minRequests = Get-AssertionFloor -Expected ($submissions + $reads + $logins)
            $script:expectedSubmissionCount = Get-AssertionFloor -Expected $submissions
            $scenarioArgs = @(
                "-Dperf.rampSeconds=30",
                "-Dperf.avgHoldSeconds=120",
                "-Dperf.peakRampSeconds=30",
                "-Dperf.peakHoldSeconds=60",
                "-Dperf.submitAvgRps=139",
                "-Dperf.submitPeakRps=200",
                "-Dperf.readRps=300",
                "-Dperf.submitIntervalMillis=$submitInterval",
                "-Dperf.pageSize=100"
            ) + $userArgs + $seedArgs
        }

        # The same shape at rates the scenario table calls "not a pass criterion" on their own.
        # Its counts come from the same three formulas; only the rates differ.
        "mixed" {
            $simulationClass = "my.oj.perf.OjGoalLoadSimulation"
            $submitInterval = Get-SubmitIntervalMillis -PeakRps 1000
            $submissions = (Get-RampCount -FromRps 1 -ToRps 139 -Seconds 30) +
                           (Get-HoldCount -Rps 139 -Seconds 120) +
                           (Get-RampCount -FromRps 139 -ToRps 1000 -Seconds 30) +
                           (Get-HoldCount -Rps 1000 -Seconds 60)
            $reads = Get-ScheduledCount -Rps 2000 -RampSeconds 30 -HoldSeconds 210
            $logins = Get-ConcurrentUsers -Rps 1000 -IntervalMillis $submitInterval
            $minRequests = Get-AssertionFloor -Expected ($submissions + $reads + $logins)
            $script:expectedSubmissionCount = Get-AssertionFloor -Expected $submissions
            $scenarioArgs = @(
                "-Dperf.rampSeconds=30",
                "-Dperf.avgHoldSeconds=120",
                "-Dperf.peakRampSeconds=30",
                "-Dperf.peakHoldSeconds=60",
                "-Dperf.submitAvgRps=139",
                "-Dperf.submitPeakRps=1000",
                "-Dperf.readRps=2000",
                "-Dperf.submitIntervalMillis=$submitInterval",
                "-Dperf.pageSize=100"
            ) + $userArgs + $seedArgs
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
        Invoke-GatlingScenario -Seed $seed -SelectedScenario "scoreboard-200" `
            -ScoreboardParticipants (Get-ScoreboardParticipants -ContestId $seed.contestId)
    }
    elseif ($Scenario -eq "target") {
        Invoke-GatlingScenario -Seed $seed -SelectedScenario "submit-200"
        Wait-PipelineDrainWithSampling -TimeoutSeconds $DrainTimeoutSeconds -Phase "submit-200"
        Assert-PipelineMaterialized -ContestId $seed.contestId
        $pipelineValidated = $true
        Invoke-GatlingScenario -Seed $seed -SelectedScenario "scoreboard-300" `
            -ScoreboardParticipants (Get-ScoreboardParticipants -ContestId $seed.contestId)
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
        Invoke-GatlingScenario -Seed $seed -SelectedScenario $Scenario `
            -ScoreboardParticipants (Get-ScoreboardParticipants -ContestId $seed.contestId)
    }
    else {
        # gatling/README.md's scenario table calls these overload observation rather than pass
        # criteria, and the note on AllowAssertionFailure above says an exploration run's assertion
        # result is data rather than a verdict. Throwing here discards the drain, the materialization
        # check and the staleness distribution - the measurements the run exists to produce.
        $exploration = $Scenario -in @("submit-1000", "scoreboard-2000", "mixed")
        Invoke-GatlingScenario -Seed $seed -SelectedScenario $Scenario -AllowAssertionFailure:$exploration
    }

    if ($Scenario -in @("submit-100", "submit-139", "submit-200", "submit-1000", "mixed", "mixed-target", "step")) {
        Wait-PipelineDrainWithSampling -TimeoutSeconds $DrainTimeoutSeconds -Phase $Scenario
    }
    else {
        Wait-PipelineDrain -TimeoutSeconds $DrainTimeoutSeconds
    }
    if ($Scenario -in @("target", "submit-100", "submit-139", "submit-200", "submit-1000", "mixed", "mixed-target")) {
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
