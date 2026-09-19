[CmdletBinding()]
param(
    [ValidateSet("rabbit", "mysql")][string]$DispatchMode = "rabbit",
    [double]$TargetRps = 20,
    [int]$DurationSeconds = 30,
    [int]$RampSeconds = 5,
    [int]$WorkerCount = 16,
    [int]$MySqlClaimBatchSize = 16,
    [int]$MySqlMaxInFlight = 16,
    [string]$MySqlClaimTimeout = "10s",
    [string]$MySqlPollInterval = "100ms",
    [int]$RabbitPrefetch = 1,
    [long]$LatencySeed = 20260919,
    [switch]$FaultEnabled,
    [int]$FaultAtSeconds = 15,
    [ValidateSet("judge-1", "judge-2")][string]$KilledNode = "judge-1",
    [int]$DownDurationSeconds = 15,
    [int]$DrainTimeoutSeconds = 180,
    [int]$UserCount = 2000,
    [string]$RunId = "",
    [switch]$KeepStack,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$composeArgs = @("-p", "oj-loadtest", "-f", "compose.yaml", "-f", "compose.loadtest.yaml")
$baseUrl = "http://127.0.0.1:18080"
$dbName = "oj_loadtest"
if (-not $RunId) { $RunId = Get-Date -Format "yyyyMMdd-HHmmss-$DispatchMode" }
if ($RunId -notmatch '^[A-Za-z0-9._-]+$') { throw "RunId may contain only letters, digits, dot, underscore, and dash." }
if ($TargetRps -le 0 -or $DurationSeconds -lt 5 -or $RampSeconds -lt 0) { throw "TargetRps must be positive and DurationSeconds must be >= 5." }
if ($WorkerCount -lt 1 -or $MySqlClaimBatchSize -lt 1 -or $MySqlMaxInFlight -lt 1 -or $RabbitPrefetch -lt 1) { throw "Worker, batch, in-flight, and prefetch values must be positive." }
if ($FaultEnabled -and ($FaultAtSeconds -le 0 -or $FaultAtSeconds -ge ($RampSeconds + $DurationSeconds))) { throw "FaultAtSeconds must fall inside the Gatling run." }
$neededUsers = [int][math]::Ceiling($TargetRps * 3.1)
if ($UserCount -lt $neededUsers) { throw "UserCount must be at least $neededUsers for the 3100ms per-user pace." }

$resultsRoot = Join-Path $repoRoot "results\mysql-judge-tradeoff"
$runDirectory = Join-Path $resultsRoot $RunId
if (Test-Path $runDirectory) { throw "Run directory already exists: $runDirectory" }
New-Item -ItemType Directory -Force -Path $runDirectory | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $runDirectory "metrics") | Out-Null

$gitCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
$parameters = [ordered]@{
    runId = $RunId; gitCommit = $gitCommit; dispatchMode = $DispatchMode
    targetRps = $TargetRps; durationSeconds = $DurationSeconds; rampSeconds = $RampSeconds
    workerCountPerNode = $WorkerCount; mysqlClaimBatchSize = $MySqlClaimBatchSize
    mysqlMaxInFlightPerNode = $MySqlMaxInFlight; mysqlClaimTimeout = $MySqlClaimTimeout
    mysqlPollInterval = $MySqlPollInterval; rabbitPrefetch = $RabbitPrefetch
    rabbitReservedPerNode = $WorkerCount * $RabbitPrefetch
    deterministicLatencySeed = $LatencySeed; latency = @{ baseMillis = 50; slowMillis = 2000; slowRatio = 0.05 }
    faultEnabled = [bool]$FaultEnabled; faultAtSeconds = $FaultAtSeconds
    killedNode = $KilledNode; downDurationSeconds = $DownDurationSeconds
    userCount = $UserCount; generatedAt = [datetimeoffset]::UtcNow.ToString("o")
}
$parameters | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $runDirectory "parameters.json") -Encoding utf8

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    Push-Location $repoRoot
    try {
        & docker compose @composeArgs @Arguments
        if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($Arguments -join ' ')" }
    } finally { Pop-Location }
}

function Invoke-SqlRows {
    param([Parameter(Mandatory = $true)][string]$Sql)
    $oneLine = ($Sql -replace "\r?\n", " ").Trim()
    $output = @(Invoke-Compose -Arguments @("exec", "-T", "mysql", "env", "MYSQL_PWD=1234", "mysql", "-uroot", "-D", $dbName, "-N", "-B", "-e", $oneLine))
    return @($output | ForEach-Object { [string]$_ } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and $_ -notmatch '^(Container|Network|mysql:) ' })
}

function Get-SqlScalar {
    param([Parameter(Mandatory = $true)][string]$Sql)
    $rows = @(Invoke-SqlRows $Sql)
    if ($rows.Count -eq 0) { return 0L }
    return [long]($rows[-1])
}

function Wait-Healthy {
    $deadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $deadline) {
        $ids = @(Invoke-Compose -Arguments @("ps", "-q") | Where-Object { $_ })
        if ($ids.Count -eq 9) {
            $bad = @(& docker inspect --format '{{.State.Running}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $ids | Where-Object { $_ -notmatch '^true (healthy|none)$' })
            if ($LASTEXITCODE -eq 0 -and $bad.Count -eq 0) { return }
        }
        Start-Sleep -Seconds 2
    }
    throw "Load-test stack did not become healthy in five minutes."
}

function Wait-JudgeMetrics {
    param([Parameter(Mandatory = $true)][string]$Node)
    $port = if ($Node -eq "judge-1") { 19001 } else { 19002 }
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        try {
            Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri "http://127.0.0.1:$port/actuator/prometheus" | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    throw "$Node metrics endpoint did not become available within 60 seconds."
}

function Save-MetricsSnapshot {
    param([Parameter(Mandatory = $true)][string]$Label)
    foreach ($entry in @(@("batch-1", 19000), @("judge-1", 19001), @("judge-2", 19002))) {
        $path = Join-Path $runDirectory "metrics\$Label-$($entry[0]).prom"
        try {
            (Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 -Uri "http://127.0.0.1:$($entry[1])/actuator/prometheus").Content |
                Set-Content $path -Encoding utf8
        } catch {
            "# unavailable: $($_.Exception.Message)" | Set-Content $path -Encoding utf8
        }
    }
    $status = Invoke-SqlRows "SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Innodb_row_lock_current_waits','Innodb_row_lock_time','Innodb_row_lock_waits','Questions','Com_select','Com_update');"
    @("metric`tvalue") + $status | Set-Content (Join-Path $runDirectory "metrics\$Label-mysql-status.tsv") -Encoding utf8
}

function Save-CapacitySample {
    param([Parameter(Mandatory = $true)][string]$Phase)
    $path = Join-Path $runDirectory "capacity.csv"
    if (-not (Test-Path $path)) {
        "timestamp,phase,node,running,localWaiting,reserved" | Set-Content $path -Encoding utf8
    }
    foreach ($entry in @(@("judge-1", 19001), @("judge-2", 19002))) {
        $values = @{}
        try {
            $content = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri "http://127.0.0.1:$($entry[1])/actuator/prometheus").Content
            foreach ($metric in @("running", "queued", "reserved")) {
                $match = [regex]::Match($content, "(?m)^contest_judge_executor_$metric(?:\{[^}]*\})?\s+([^\s]+)$")
                $values[$metric] = if ($match.Success) { $match.Groups[1].Value } else { "" }
            }
        } catch {
            $values = @{ running=""; queued=""; reserved="" }
        }
        "$([datetimeoffset]::UtcNow.ToString('o')),$Phase,$($entry[0]),$($values.running),$($values.queued),$($values.reserved)" |
            Add-Content $path -Encoding utf8
    }
}

function Save-BacklogSample {
    param([string]$Phase)
    $pending = Get-SqlScalar "SELECT COUNT(*) FROM contest_judge_outbox WHERE status <> 'PUBLISHED'"
    $unapplied = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission_result WHERE scoreboard_applied_at IS NULL"
    $path = Join-Path $runDirectory "backlog.csv"
    if (-not (Test-Path $path)) { "timestamp,phase,unfinished,scoreboardUnapplied" | Set-Content $path -Encoding utf8 }
    "$( [datetimeoffset]::UtcNow.ToString('o')),$Phase,$pending,$unapplied" | Add-Content $path -Encoding utf8
    return ($pending + $unapplied)
}

function Observe-FaultRecovery {
    param([string]$Phase)
    Save-BacklogSample $Phase | Out-Null
    if ($null -eq $events.firstStaleReclaimObservedAt -and $events.contestId) {
        $reclaimed = Get-SqlScalar "SELECT COALESCE(SUM(GREATEST(o.attempts - 1, 0)), 0) FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($events.contestId)"
        if ($reclaimed -gt $events.staleAttemptsBeforeFault) {
            # attempts is durable after the reclaim completes, unlike claimed_at/updated_at. Polling
            # once a second gives an explicit bounded observation error instead of pretending the
            # final row update timestamp is the instant at which the stale lease was acquired.
            $events.firstStaleReclaimObservedAt = [datetimeoffset]::UtcNow.ToString("o")
        }
    }
}

function Save-ClaimSnapshot {
    $columns = @(Invoke-SqlRows "SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema = '$dbName' AND table_name = 'contest_judge_outbox';")
    $hasClaimedBy = $columns -contains "claimed_by"
    $filter = "status = 'PUBLISHING'"
    if ($hasClaimedBy) {
        $workerId = if ($KilledNode -eq "judge-1") { "200" } else { "201" }
        $filter += " AND claimed_by IN ('$KilledNode', '$workerId')"
    }
    $rows = Invoke-SqlRows "SELECT submission_id, claim_token, claimed_at, attempts FROM contest_judge_outbox WHERE $filter ORDER BY submission_id;"
    $objects = @($rows | ForEach-Object {
        $p = $_ -split "`t"; [pscustomobject]@{ submissionId=$p[0]; claimToken=$p[1]; claimedAt=$p[2]; attempts=$p[3] }
    })
    $objects | Export-Csv (Join-Path $runDirectory "killed-node-claims.csv") -NoTypeInformation -Encoding utf8
    return [pscustomobject]@{
        exact = $hasClaimedBy
        # Never label the all-active upper bound as the killed node's exact cohort.
        ids = if ($hasClaimedBy) { @($objects.submissionId) } else { @() }
        observedActiveClaimCount = @($objects).Count
    }
}

function Export-Latencies {
    param($Events, $ClaimSnapshot)
    $rows = Invoke-SqlRows @"
SELECT cs.id, cs.submitted_time, csr.result_saved_at, csr.scoreboard_applied_at,
       TIMESTAMPDIFF(MICROSECOND, cs.submitted_time, csr.result_saved_at) / 1000.0,
       TIMESTAMPDIFF(MICROSECOND, csr.result_saved_at, csr.scoreboard_applied_at) / 1000.0,
       TIMESTAMPDIFF(MICROSECOND, cs.submitted_time, csr.scoreboard_applied_at) / 1000.0,
       o.attempts, o.updated_at
FROM contest_submission cs
LEFT JOIN contest_submission_result csr ON csr.submission_id = cs.id
LEFT JOIN contest_judge_outbox o ON o.submission_id = cs.id
WHERE cs.contest_id = $($Events.contestId)
ORDER BY cs.id;
"@
    $fault = if ($Events.faultInjectedAt) { [datetimeoffset]::Parse($Events.faultInjectedAt) } else { $null }
    $restart = if ($Events.nodeRestartedAt) { [datetimeoffset]::Parse($Events.nodeRestartedAt) } else { $null }
    $claimed = @{}; foreach ($id in @($ClaimSnapshot.ids)) { $claimed[[string]$id] = $true }
    $objects = foreach ($line in $rows) {
        # PowerShell's regex -split treats a negative count as "do not split".
        # String.Split preserves the tabular fields emitted by mysql -B, including NULL markers.
        $p = $line.Split([char]9)
        $submitted = [datetimeoffset]::MinValue
        if ($p.Count -lt 9 -or -not [datetimeoffset]::TryParse($p[1] + "Z", [ref]$submitted)) {
            continue
        }
        $cohorts = New-Object System.Collections.Generic.List[string]
        if ($fault) {
            if ($submitted -lt $fault.AddSeconds(-5)) { $cohorts.Add("pre-fault-normal") }
            if ($submitted -ge $fault.AddSeconds(-5) -and ($null -eq $restart -or $submitted -lt $restart.AddSeconds(5))) { $cohorts.Add("fault-window") }
            if ($submitted -ge $fault) { $cohorts.Add("post-fault-arrivals") }
        } else { $cohorts.Add("pre-fault-normal") }
        if ($claimed.ContainsKey($p[0])) { $cohorts.Add("killed-node-claimed") }
        [pscustomobject]@{
            submissionId=$p[0]; submittedAt=$p[1]; resultSavedAt=$p[2]; scoreboardAppliedAt=$p[3]
            L_result_ms=$p[4]; L_scoreboard_ms=$p[5]; L_total_ms=$p[6]; attempts=$p[7]
            outboxUpdatedAt=$p[8]; cohorts=($cohorts -join ";")
        }
    }
    $objects | Export-Csv (Join-Path $runDirectory "latency.csv") -NoTypeInformation -Encoding utf8
}

function Get-GatlingSubmitRequests {
    param([datetime]$StartedAt)
    $logs = @(Get-ChildItem (Join-Path $repoRoot "gatling\build\reports\gatling") -Filter simulation.log -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -ge $StartedAt } | Sort-Object LastWriteTime)
    if ($logs.Count -eq 0) { return $null }
    return [long](@(Select-String -Path $logs[-1].FullName -SimpleMatch "api-contest-submit").Count)
}

function Get-PromMetricSum {
    param([string]$Label, [string]$Metric, [string]$RequiredTag = "", [string]$OnlyNode = "")
    $sum = 0.0; $found = $false; $snapshotAvailable = $false
    foreach ($node in @("judge-1", "judge-2")) {
        if ($OnlyNode -and $node -ne $OnlyNode) { continue }
        $path = Join-Path $runDirectory "metrics\$Label-$node.prom"
        if (-not (Test-Path $path)) { continue }
        $snapshot = @(Get-Content $path)
        if ($snapshot.Count -gt 0 -and -not ($snapshot -match '^# unavailable:')) {
            $snapshotAvailable = $true
        }
        foreach ($line in $snapshot) {
            if ($line -match ("^" + [regex]::Escape($Metric) + '(?:\{([^}]*)\})?\s+([^\s]+)$')) {
                # Save captures before another -match/-notmatch overwrites PowerShell's
                # automatic $Matches variable.
                $tags = $Matches[1]
                $rawValue = $Matches[2]
                if ($RequiredTag -and $tags -notmatch [regex]::Escape($RequiredTag)) { continue }
                $value = 0.0
                if ([double]::TryParse($rawValue, [Globalization.NumberStyles]::Float,
                        [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) {
                    $sum += $value; $found = $true
                }
            }
        }
    }
    if (-not $found) { return $(if ($snapshotAvailable) { 0.0 } else { $null }) }
    return $sum
}

function Get-PromMetricDelta {
    param([string]$Metric, [string]$RequiredTag = "")
    $total = 0.0
    foreach ($node in @("judge-1", "judge-2")) {
        $start = Get-PromMetricSum "start" $Metric $RequiredTag $node
        $end = Get-PromMetricSum "end" $Metric $RequiredTag $node
        if ($null -eq $end) { return $null }
        # Lazy Micrometer meters do not exist in the startup scrape until first use.
        if ($null -eq $start) { $start = 0.0 }
        if ($FaultEnabled -and $node -eq $KilledNode) {
            $beforeKill = Get-PromMetricSum "pre-fault" $Metric $RequiredTag $node
            $afterRestart = Get-PromMetricSum "post-restart" $Metric $RequiredTag $node
            if ($null -eq $beforeKill) { $beforeKill = 0.0 }
            if ($null -eq $afterRestart) { $afterRestart = 0.0 }
            $total += [math]::Max(0, $beforeKill - $start) + [math]::Max(0, $end - $afterRestart)
        } else {
            $total += [math]::Max(0, $end - $start)
        }
    }
    return $total
}

$env:CONTEST_JUDGE_DISPATCH_MODE = $DispatchMode
$env:CONTEST_JUDGE_CONCURRENCY = "$WorkerCount"
$env:CONTEST_JUDGE_PREFETCH = "$RabbitPrefetch"
$env:CONTEST_JUDGE_MYSQL_WORKERS = "$WorkerCount"
$env:CONTEST_JUDGE_MYSQL_CLAIM_BATCH_SIZE = "$MySqlClaimBatchSize"
$env:CONTEST_JUDGE_MYSQL_MAX_IN_FLIGHT = "$MySqlMaxInFlight"
$env:CONTEST_JUDGE_MYSQL_CLAIM_TIMEOUT = $MySqlClaimTimeout
$env:CONTEST_JUDGE_MYSQL_POLL_INTERVAL = $MySqlPollInterval
$env:JUDGE_LATENCY_ENABLED = "true"
$env:JUDGE_LATENCY_SEED = "$LatencySeed"
$env:JUDGE_LATENCY_KEY_SOURCE = "code"
$env:JUDGE_BASE_MILLIS = "50"
$env:JUDGE_SLOW_MILLIS = "2000"
$env:JUDGE_SLOW_RATIO = "0.05"
$env:CONTEST_RATE_LIMIT_STORE = "redis"
$env:CONTEST_RATE_LIMIT_COOLDOWN_MILLIS = "2000"

if ($DryRun) {
    Invoke-Compose -Arguments @("config") | Set-Content (Join-Path $runDirectory "compose-config.yaml") -Encoding utf8
    "Dry run only; no containers or load were started." | Set-Content (Join-Path $runDirectory "DRY_RUN.txt") -Encoding utf8
    Write-Host "Dry run valid. Parameters and rendered Compose config: $runDirectory"
    exit 0
}

$events = [ordered]@{ runStartedAt=$null; loadStartedAt=$null; faultInjectedAt=$null; staleAttemptsBeforeFault=0; firstStaleReclaimObservedAt=$null; nodeRestartedAt=$null; loadEndedAt=$null; runEndedAt=$null; contestId=$null }
$started = $false
$claimSnapshot = [pscustomobject]@{ exact=$false; ids=@(); observedActiveClaimCount=0 }
try {
    Invoke-Compose -Arguments @("config") | Set-Content (Join-Path $runDirectory "compose-config.yaml") -Encoding utf8
    Push-Location $repoRoot
    try {
        & .\gradlew.bat bootJar :gatling:prepareStandaloneGatling --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle preparation failed." }
    } finally { Pop-Location }
    $events.runStartedAt = [datetimeoffset]::UtcNow.ToString("o")
    $started = $true
    Invoke-Compose -Arguments @("up", "-d", "--build")
    Wait-Healthy
    Invoke-Compose -Arguments @("restart", "nginx")
    Wait-Healthy
    $workloadPrefix = "tradeoff_seed_$LatencySeed"
    $seedRequest = @{ prefix=$workloadPrefix; userCount=$UserCount; problemCount=5; durationMinutes=60; reset=$true } | ConvertTo-Json -Compress
    $seed = Invoke-RestMethod -Method Post -Uri "$baseUrl/perf/contest/seed" -ContentType "application/json" -Body $seedRequest -TimeoutSec 60
    $events.contestId = [long]$seed.contestId
    Save-MetricsSnapshot "start"

    $classpath = (Get-Content (Join-Path $repoRoot "gatling\build\standalone-gatling\classpath.txt") -Raw).Trim()
    $resultsFolder = Join-Path $repoRoot "gatling\build\reports\gatling"
    $javaArgs = @(
        "-Xms256m", "-Xmx1g", "-Dperf.baseUrl=$baseUrl", "-Dperf.assert.minRequests=1",
        "-Dperf.assert.minSuccessPercent=95", "-Dperf.assert.p95Millis=60000",
        "-Dperf.targetRps=$TargetRps", "-Dperf.rampSeconds=$RampSeconds", "-Dperf.holdSeconds=$DurationSeconds",
        "-Dperf.submitIntervalMillis=3100", "-Dperf.userPrefix=$workloadPrefix", "-Dperf.workloadSeed=$LatencySeed",
        "-Dperf.userIndex.start=1", "-Dperf.userIndex.end=$UserCount",
        "-Dperf.contestId=$($seed.contestId)", "-Dperf.problemId.start=$($seed.firstProblemId)", "-Dperf.problemId.end=$($seed.lastProblemId)",
        "-cp", $classpath, "io.gatling.app.Gatling", "-s", "my.oj.perf.ContestSubmissionSimulation",
        "-rf", $resultsFolder, "-rd", "mysql-judge-tradeoff-$RunId"
    )
    $gatlingStarted = Get-Date
    $gatling = Start-Process -FilePath (Get-Command java.exe).Source -ArgumentList $javaArgs -PassThru -NoNewWindow
    # Java/Gatling startup can take longer than a short fault offset. Anchor the
    # experiment clock to the first persisted submission, not process creation.
    $loadStartDeadline = (Get-Date).AddSeconds(60)
    do {
        $startedSubmissions = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission WHERE contest_id=$($seed.contestId)"
        if ($startedSubmissions -gt 0) { break }
        if ($gatling.HasExited) { throw "Gatling exited before the first submission was persisted." }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $loadStartDeadline)
    if ($startedSubmissions -eq 0) { throw "No submission was persisted within 60 seconds of starting Gatling." }
    $events.loadStartedAt = [datetimeoffset]::UtcNow.ToString("o")
    Save-CapacitySample "load-start"
    if ($FaultEnabled) {
        $faultDeadline = (Get-Date).AddSeconds($FaultAtSeconds)
        while ((Get-Date) -lt $faultDeadline -and -not $gatling.HasExited) {
            Save-BacklogSample "pre-fault" | Out-Null
            Save-CapacitySample "pre-fault"
            Start-Sleep -Seconds 1
        }
        Save-MetricsSnapshot "pre-fault"
        $claimSnapshot = Save-ClaimSnapshot
        $events.staleAttemptsBeforeFault = Get-SqlScalar "SELECT COALESCE(SUM(GREATEST(o.attempts - 1, 0)), 0) FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($events.contestId)"
        $events.faultInjectedAt = [datetimeoffset]::UtcNow.ToString("o")
        Invoke-Compose -Arguments @("kill", $KilledNode)
        Observe-FaultRecovery "fault"
        $downDeadline = (Get-Date).AddSeconds($DownDurationSeconds)
        while ((Get-Date) -lt $downDeadline) {
            Start-Sleep -Seconds 1
            Observe-FaultRecovery "node-down"
            Save-CapacitySample "node-down"
        }
        Invoke-Compose -Arguments @("start", $KilledNode)
        $events.nodeRestartedAt = [datetimeoffset]::UtcNow.ToString("o")
        Wait-Healthy
        Wait-JudgeMetrics $KilledNode
        Save-MetricsSnapshot "post-restart"
    }
    if ($FaultEnabled) {
        while (-not $gatling.HasExited) {
            Observe-FaultRecovery "post-restart"
            Save-CapacitySample "post-restart"
            Start-Sleep -Seconds 1
        }
        Observe-FaultRecovery "load-end"
    } else {
        while (-not $gatling.HasExited) {
            Save-BacklogSample "load" | Out-Null
            Save-CapacitySample "load"
            Start-Sleep -Seconds 1
        }
    }
    $gatling.WaitForExit()
    $events.loadEndedAt = [datetimeoffset]::UtcNow.ToString("o")
    if ($gatling.ExitCode -ne 0) { throw "Gatling exited with code $($gatling.ExitCode)." }

    $deadline = (Get-Date).AddSeconds($DrainTimeoutSeconds)
    do {
        $backlog = Save-BacklogSample "drain"
        if ($backlog -eq 0) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    if ($backlog -ne 0) { throw "Pipeline did not drain within $DrainTimeoutSeconds seconds." }
    Save-MetricsSnapshot "end"
    $events.runEndedAt = [datetimeoffset]::UtcNow.ToString("o")
    $events | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $runDirectory "events.json") -Encoding utf8
    Export-Latencies $events $claimSnapshot

    $submissionCount = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission WHERE contest_id=$($seed.contestId)"
    $resultCount = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id=$($seed.contestId)"
    $scoreboardCount = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id=$($seed.contestId) AND scoreboard_applied_at IS NOT NULL"
    $uniqueCount = Get-SqlScalar "SELECT COUNT(DISTINCT id) FROM contest_submission WHERE contest_id=$($seed.contestId)"
    $requests = Get-GatlingSubmitRequests $gatlingStarted
    $attemptRows = Invoke-SqlRows "SELECT attempts, COUNT(*) FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($seed.contestId) GROUP BY attempts ORDER BY attempts"
    @("attempts`trows") + $attemptRows | Set-Content (Join-Path $runDirectory "claim-attempts.tsv") -Encoding utf8
    $reclaimRows = Invoke-SqlRows "SELECT updated_at, submission_id, attempts FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($seed.contestId) AND attempts > 1 ORDER BY updated_at"
    @($reclaimRows | ForEach-Object { $p=$_ -split "`t"; [pscustomobject]@{timestamp=([datetimeoffset]::Parse($p[0]+"Z").ToString("o"));submissionId=$p[1];attempts=$p[2]} }) |
        Export-Csv (Join-Path $runDirectory "stale-reclaims.csv") -NoTypeInformation -Encoding utf8
    $duplicateEstimate = Get-SqlScalar "SELECT COALESCE(SUM(GREATEST(attempts - 1, 0)),0) FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($seed.contestId)"
    $judgeInvocations = Get-PromMetricDelta "contest_judge_invocations_total"
    $judgeDurationSeconds = Get-PromMetricDelta "contest_judge_duration_seconds_sum"
    $claimCalls = Get-PromMetricDelta "contest_judge_claim_calls_total"
    $claimRows = Get-PromMetricDelta "contest_judge_claim_rows_total"
    $staleReclaims = Get-PromMetricDelta "contest_judge_claim_stale_total"
    $completionSuccess = Get-PromMetricDelta "contest_judge_completion_total" 'outcome="success"'
    $completionFailure = Get-PromMetricDelta "contest_judge_completion_total" 'outcome="failure"'
    $staleCompletions = Get-PromMetricDelta "contest_judge_completion_total" 'outcome="stale"'
    $storedRepublishes = Get-PromMetricDelta "contest_judge_stored_result_republish_total"
    $unavailable = New-Object System.Collections.Generic.List[string]
    if ($null -eq $judgeInvocations) { $unavailable.Add("judge invocation count: contest_judge_invocations_total was not exposed") }
    $unavailable.Add("duplicate judge time is bounded by the deterministic 50ms/2000ms profile; exact per-claim attribution is unavailable")
    if ($DispatchMode -eq "rabbit") { $unavailable.Add("Rabbit per-node running/local-waiting/reserved gauges are unavailable; worker-count x prefetch is recorded only as the configured normalized ceiling") }
    $unavailable.Add("MySQL CPU is not exposed by the stock mysql:8.0 container; connection and InnoDB lock counters are captured instead")
    if (-not $claimSnapshot.exact -and $FaultEnabled) { $unavailable.Add("killed-node claim attribution: schema has no claimed_by column; killed-node-claims.csv contains all active claims at kill time") }
    if ($null -eq $requests) { $unavailable.Add("HTTP submission request count: Gatling simulation.log was not found") }
    $duplicateJudgements = if ($null -eq $judgeInvocations) { $null } else { [math]::Max(0, $judgeInvocations - $resultCount) }
    $duplicateJudgeMillisLowerBound = if ($null -eq $duplicateJudgements) { $null } else { $duplicateJudgements * 50 }
    $duplicateJudgeMillisUpperBound = if ($null -eq $duplicateJudgements) { $null } else { $duplicateJudgements * 2000 }
    $verification = [ordered]@{
        counts = @{ requests=$requests; accepted=$submissionCount; uniqueSubmissions=$uniqueCount; results=$resultCount; scoreboardApplied=$scoreboardCount }
        integrity = @{ lostOrIncomplete=($submissionCount-$resultCount); finalResultMismatch=($resultCount-$scoreboardCount); passed=($submissionCount -eq $uniqueCount -and $submissionCount -eq $resultCount -and $resultCount -eq $scoreboardCount) }
        workCost = @{ duplicateClaimEstimate=$duplicateEstimate; duplicateJudgementEstimate=$duplicateJudgements; judgeInvocations=$judgeInvocations; totalJudgeMillis=if ($null -eq $judgeDurationSeconds) {$null} else {[math]::Round($judgeDurationSeconds*1000,3)}; duplicateJudgeMillisLowerBound=$duplicateJudgeMillisLowerBound; duplicateJudgeMillisUpperBound=$duplicateJudgeMillisUpperBound; claimCalls=$claimCalls; claimedRows=$claimRows; staleReclaims=$staleReclaims; completionSuccess=$completionSuccess; completionFailure=$completionFailure; staleTokenCompletions=$staleCompletions; storedResultRepublishes=$storedRepublishes; claimAttemptsFile="claim-attempts.tsv"; killedNodeClaimCount=if ($claimSnapshot.exact) {@($claimSnapshot.ids).Count} else {$null}; allActiveClaimsAtKill=$claimSnapshot.observedActiveClaimCount }
        cohortAvailability = @{ killedNodeClaimed=[bool]$claimSnapshot.exact }
        mysql = @{ statusSnapshots="metrics/*-mysql-status.tsv"; cpu=$null; lockAndConnectionCounters="captured" }
        unavailable = @($unavailable)
    }
    $verification | ConvertTo-Json -Depth 7 | Set-Content (Join-Path $runDirectory "db-verification.json") -Encoding utf8
    & (Join-Path $PSScriptRoot "Analyze-TradeoffRun.ps1") -RunDirectory $runDirectory
} catch {
    $events.runEndedAt = [datetimeoffset]::UtcNow.ToString("o")
    $events | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $runDirectory "events.json") -Encoding utf8
    $_ | Out-String | Set-Content (Join-Path $runDirectory "failure.txt") -Encoding utf8
    throw
} finally {
    if ($started -and -not $KeepStack) {
        try { Invoke-Compose -Arguments @("down") } catch { Write-Warning $_ }
    }
}

Write-Host "Experiment complete: $runDirectory"
