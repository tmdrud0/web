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
    [switch]$DryRun,
    # Staircase (steady-state capacity sweep). With -Staircase the run does a warm-up hold followed
    # by one hold per -StageRps entry, all inside one stack/JVM lifetime, and -TargetRps,
    # -DurationSeconds, and -FaultEnabled no longer apply.
    [switch]$Staircase,
    [string]$StageRps = "50,50,100,150,200,230",
    [int]$WarmupStageCount = 1,
    [int]$StageHoldSeconds = 30,
    [int]$SteadyGuardSeconds = 3,
    [double]$OverloadThresholdRowsPerSec = 1.0,
    [int]$AssertMinSuccessPercent = 95,
    [int]$AssertP95Millis = 60000,
    [int]$TraceAlignmentToleranceSeconds = 5
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$composeArgs = @("-p", "oj-loadtest", "-f", "compose.yaml", "-f", "compose.loadtest.yaml")
$baseUrl = "http://127.0.0.1:18080"
$dbName = "oj_loadtest"
# The date pattern is a separate -f operand on purpose: inside a single format string the "t" of
# "rabbit" and the "m"/"s"/"y" of "mysql" are .NET date specifiers, so a default RunId came out as
# "20260920-081149-rabbi오" and failed the character check below.
if (-not $RunId) { $RunId = "{0}-{1}" -f (Get-Date -Format "yyyyMMdd-HHmmss"), $DispatchMode }
if ($RunId -notmatch '^[A-Za-z0-9._-]+$') { throw "RunId may contain only letters, digits, dot, underscore, and dash." }
if ($TargetRps -le 0 -or $DurationSeconds -lt 5 -or $RampSeconds -lt 0) { throw "TargetRps must be positive and DurationSeconds must be >= 5." }
if ($WorkerCount -lt 1 -or $MySqlClaimBatchSize -lt 1 -or $MySqlMaxInFlight -lt 1 -or $RabbitPrefetch -lt 1) { throw "Worker, batch, in-flight, and prefetch values must be positive." }
if ($FaultEnabled -and ($FaultAtSeconds -le 0 -or $FaultAtSeconds -ge ($RampSeconds + $DurationSeconds))) { throw "FaultAtSeconds must fall inside the Gatling run." }

$stageRpsList = @()
if ($Staircase) {
    if ($FaultEnabled) { throw "-Staircase measures steady-state capacity and does not inject faults." }
    $stageRpsList = @($StageRps -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ } | ForEach-Object { [double]$_ })
    if ($stageRpsList.Count -lt 2) { throw "-StageRps must list a warm-up stage and at least one measured stage." }
    if (@($stageRpsList | Where-Object { $_ -le 0 }).Count -gt 0) { throw "Every -StageRps entry must be greater than 0." }
    if ($WarmupStageCount -lt 0 -or $WarmupStageCount -ge $stageRpsList.Count) { throw "-WarmupStageCount must leave at least one measured stage." }
    if ($StageHoldSeconds -lt 12) { throw "-StageHoldSeconds must be at least 12 so a stage has enough samples to classify." }
    # The analyzer refuses to call a stage steady or overloaded on fewer than 10 backlog samples, and
    # the sampler can run at up to about 1.5s per tick, so a measured window shorter than this would
    # produce a run whose every stage comes back unclassified.
    if ($SteadyGuardSeconds -lt 0 -or ($StageHoldSeconds - $SteadyGuardSeconds) -lt 12) { throw "-SteadyGuardSeconds must leave at least 12 measured seconds inside each hold, or the analyzer cannot classify the stage." }
    if ($UserCount -lt 1000) { throw "Staircase runs require -UserCount of at least 1000." }
    if ($DrainTimeoutSeconds -lt 300) { throw "Staircase runs require -DrainTimeoutSeconds of at least 300." }
}

# The staircase drives the stack into overload on purpose, where the API rate limiter refuses
# requests the judge never saw. Those refusals are part of the measurement, not a fault, so the
# staircase default is looser than the fault experiments' 95%; an explicit value still wins, and
# Gatling exiting 2 over it is recorded rather than treated as a failed run.
$assertMinSuccess = if ($Staircase) {
    if ($PSBoundParameters.ContainsKey("AssertMinSuccessPercent")) { $AssertMinSuccessPercent } else { 80 }
} else { 95 }

$neededUsers = if ($Staircase) {
    [int][math]::Ceiling(($stageRpsList | Measure-Object -Maximum).Maximum * 3.1)
} else {
    [int][math]::Ceiling($TargetRps * 3.1)
}
if ($UserCount -lt $neededUsers) { throw "UserCount must be at least $neededUsers for the 3100ms per-user pace." }

# What the harness expects the JVM to plan, derived from the parameters alone. The authoritative
# boundaries are the ones the simulation writes to the trace file; this is here so a dry run can
# be checked against the intended shape before a four minute stack is started for real.
$expectedPlan = $null
if ($Staircase) {
    $populations = @($stageRpsList | ForEach-Object { [int][math]::Max(1, [math]::Ceiling($_ * 3100 / 1000)) })
    $expectedPlan = [ordered]@{
        populations = $populations
        maxConcurrentUsers = ($populations | Measure-Object -Maximum).Maximum
        segmentCount = 1 + $stageRpsList.Count + ($stageRpsList.Count - 1)
        totalSeconds = ($RampSeconds + $StageHoldSeconds) * $stageRpsList.Count
        measuredStageCount = $stageRpsList.Count - $WarmupStageCount
    }
}

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
if ($Staircase) {
    $parameters.staircase = [ordered]@{
        enabled = $true
        stageRps = $stageRpsList
        warmupStageCount = $WarmupStageCount
        transitionRampSeconds = $RampSeconds
        stageHoldSeconds = $StageHoldSeconds
        steadyGuardSeconds = $SteadyGuardSeconds
        overloadThresholdRowsPerSec = $OverloadThresholdRowsPerSec
        assertMinSuccessPercent = $assertMinSuccess
        assertP95Millis = $AssertP95Millis
        traceAlignmentToleranceSeconds = $TraceAlignmentToleranceSeconds
        simulationClass = "my.oj.perf.ContestSubmissionStepLoadSimulation"
        stageTraceFile = "stage-trace.csv"
        expectedPlan = $expectedPlan
    }
}
$parameters | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $runDirectory "parameters.json") -Encoding utf8

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    Push-Location $repoRoot
    $stderrFile = [System.IO.Path]::GetTempFileName()
    try {
        # compose writes build and pull progress to stderr, and PowerShell 5.1 turns a native
        # command's stderr into an ErrorRecord. Under the caller's $ErrorActionPreference = "Stop"
        # that terminated the script on a successful build ("Image oj-loadtest-judge-2 Building"),
        # so stderr goes to a file: callers that parse stdout (Invoke-SqlRows, the dry run's
        # `config`) stay clean, the exit code alone decides success, and a real failure still
        # carries compose's own message.
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            & docker compose @composeArgs @Arguments 2>$stderrFile
            $exitCode = $LASTEXITCODE
        } finally { $ErrorActionPreference = $previousPreference }
        if ($exitCode -ne 0) {
            $detail = (Get-Content $stderrFile -Raw -ErrorAction SilentlyContinue)
            throw "docker compose failed (exit $exitCode): $($Arguments -join ' ')`n$detail"
        }
    } finally {
        Remove-Item -LiteralPath $stderrFile -Force -ErrorAction SilentlyContinue
        Pop-Location
    }
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
    # An empty result is not a zero. A COUNT(*) that returns no row means the query did not run,
    # and reporting 0 for it would let the four integrity counts agree on all-zero and pass a run
    # whose verification never happened, or let the drain gate see "0 unfinished" and break early.
    if ($rows.Count -eq 0) { return $null }
    $value = 0L
    if (-not [long]::TryParse([string]$rows[-1], [Globalization.NumberStyles]::Integer,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) { return $null }
    return $value
}

function ConvertTo-Int64OrNull {
    param($Value)
    # A count that did not arrive is not a zero. `[long]$null` and `[long]""` are both 0, so casting a
    # missing cell would let the staircase drain gate read 0 unfinished rows from a tick whose SQL
    # never ran, break on its first iteration, and record the run as drained.
    if ($null -eq $Value) { return $null }
    $parsed = 0L
    if (-not [long]::TryParse([string]$Value, [Globalization.NumberStyles]::Integer,
            [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) { return $null }
    return $parsed
}

function Wait-Healthy {
    param([switch]$ObserveRecovery)
    $deadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $deadline) {
        if ($ObserveRecovery) { Observe-FaultRecovery "health-wait" }
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
    param(
        [Parameter(Mandatory = $true)][string]$Node,
        [switch]$ObserveRecovery
    )
    $port = if ($Node -eq "judge-1") { 19001 } else { 19002 }
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        if ($ObserveRecovery) {
            Observe-FaultRecovery "restart-wait"
        }
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

function Get-JudgeGauges {
    param([Parameter(Mandatory = $true)][int]$Port)
    $values = @{ running = ""; queued = ""; reserved = "" }
    try {
        $content = (Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri "http://127.0.0.1:$Port/actuator/prometheus").Content
        foreach ($metric in @("running", "queued", "reserved")) {
            $match = [regex]::Match($content, "(?m)^contest_judge_executor_$metric(?:\{[^}]*\})?\s+([^\s]+)$")
            if ($match.Success) { $values[$metric] = $match.Groups[1].Value }
        }
    } catch {
        # An unreachable node is recorded as an empty value, never as zero.
        $values = @{ running = ""; queued = ""; reserved = "" }
    }
    return $values
}

function Save-CapacitySample {
    param([Parameter(Mandatory = $true)][string]$Phase)
    $path = Join-Path $runDirectory "capacity.csv"
    if (-not (Test-Path $path)) {
        "timestamp,phase,node,running,localWaiting,reserved" | Set-Content $path -Encoding utf8
    }
    foreach ($entry in @(@("judge-1", 19001), @("judge-2", 19002))) {
        $values = Get-JudgeGauges -Port $entry[1]
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
    # An unavailable count is written as an empty cell rather than a zero, and the sample returns
    # null so a caller cannot read "0" as a drained pipeline.
    $pendingText = if ($null -eq $pending) { "" } else { [string]$pending }
    $unappliedText = if ($null -eq $unapplied) { "" } else { [string]$unapplied }
    "$( [datetimeoffset]::UtcNow.ToString('o')),$Phase,$pendingText,$unappliedText" | Add-Content $path -Encoding utf8
    if ($null -eq $pending -or $null -eq $unapplied) { return $null }
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

function Find-GatlingReport {
    param([datetime]$StartedAt)
    $logs = @(Get-ChildItem (Join-Path $repoRoot "gatling\build\reports\gatling") -Filter simulation.log -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -ge $StartedAt } | Sort-Object LastWriteTime)
    if ($logs.Count -eq 0) { return $null }
    return $logs[-1]
}

function Get-GatlingSubmitRequests {
    param([datetime]$StartedAt)
    $log = Find-GatlingReport -StartedAt $StartedAt
    if ($null -eq $log) { return $null }
    return [long](@(Select-String -Path $log.FullName -SimpleMatch "api-contest-submit").Count)
}

function Get-GatlingLastRequestMillis {
    param([datetime]$StartedAt)
    $log = Find-GatlingReport -StartedAt $StartedAt
    if ($null -eq $log) { return $null }
    $last = $null
    foreach ($line in [System.IO.File]::ReadLines($log.FullName)) {
        if (-not $line.StartsWith("REQUEST`t")) { continue }
        $p = $line.Split([char]9)
        if ($p.Count -lt 6) { continue }
        $value = 0L
        # A request that never returned carries an empty end timestamp; the last *completed*
        # request is the one that can be compared against the predicted plan.
        if ([long]::TryParse($p[4], [ref]$value) -and ($null -eq $last -or $value -gt $last)) { $last = $value }
    }
    return $last
}

function Copy-GatlingArtifacts {
    param([datetime]$StartedAt)
    $log = Find-GatlingReport -StartedAt $StartedAt
    if ($null -eq $log) { return $null }
    Copy-Item $log.FullName (Join-Path $runDirectory "gatling-simulation.log") -Force
    $stats = Join-Path $log.Directory.FullName "js\global_stats.json"
    if (Test-Path $stats) { Copy-Item $stats (Join-Path $runDirectory "gatling-global-stats.json") -Force }
    return $log.Directory.FullName
}

function Get-StaircaseTrace {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path $Path)) { return $null }
    $rows = @(Import-Csv -Path $Path -Header epochMillis, event, segmentIndex, kind, stageIndex, isWarmup, population, targetRps)
    $anchor = @($rows | Where-Object { $_.event -eq "anchor" })
    $done = @($rows | Where-Object { $_.event -eq "planDone" })
    # A partially written plan is not a plan: refuse it rather than guess the missing boundaries.
    if ($anchor.Count -eq 0 -or $done.Count -eq 0) { return $null }
    $segments = New-Object System.Collections.Generic.List[object]
    $open = $null
    foreach ($row in $rows) {
        if ($row.event -eq "segmentStart") {
            $open = [pscustomobject]@{
                index = [int]$row.segmentIndex; kind = $row.kind; stageIndex = [int]$row.stageIndex
                isWarmup = ($row.isWarmup -eq "1"); population = [int]$row.population
                targetRps = [double]$row.targetRps; startMillis = [long]$row.epochMillis; endMillis = $null
            }
        } elseif ($row.event -eq "segmentEnd" -and $null -ne $open -and $open.index -eq [int]$row.segmentIndex) {
            $open.endMillis = [long]$row.epochMillis
            $segments.Add($open)
            $open = $null
        }
    }
    if ($open) { return $null }
    return [pscustomobject]@{
        anchorMillis = [long]$anchor[0].epochMillis
        planEndMillis = [long]$done[0].epochMillis
        segments = $segments
    }
}

function Get-StaircaseStages {
    param($Trace)
    $stages = New-Object System.Collections.Generic.List[object]
    foreach ($segment in @($Trace.segments | Where-Object { $_.kind -eq "hold" })) {
        $stages.Add([pscustomobject]@{
            stageIndex = $segment.stageIndex
            label = if ($segment.isWarmup) { "warmup" } else { "stage-$($segment.stageIndex)" }
            isWarmup = $segment.isWarmup
            targetRps = $segment.targetRps
            population = $segment.population
            startMillis = $segment.startMillis
            endMillis = [long]$segment.endMillis
            # The guard drops the head of the hold, where users added by the preceding ramp are
            # still delivering their first (jittered) submission, which puts the offered rate
            # above the stage target.
            measurementStartMillis = [math]::Min([long]$segment.startMillis + ($SteadyGuardSeconds * 1000), [long]$segment.endMillis)
            measurementEndMillis = [long]$segment.endMillis
            traceSegmentIndex = $segment.index
            prometheusStartLabel = "seg-$($segment.index)-start"
            prometheusEndLabel = "seg-$($segment.index)-end"
        })
    }
    return $stages
}

function Resolve-StaircasePosition {
    param($Trace, [long]$NowMillis)
    if ($null -eq $Trace) { return [pscustomobject]@{ label = "pre-load"; stageIndex = $null; targetRps = $null; inHold = $false } }
    $current = $null
    foreach ($segment in $Trace.segments) {
        if ($NowMillis -ge $segment.startMillis -and $NowMillis -lt $segment.endMillis) { $current = $segment; break }
    }
    if ($null -eq $current) {
        $label = if ($NowMillis -ge $Trace.planEndMillis) { "post-run" } else { "pre-load" }
        return [pscustomobject]@{ label = $label; stageIndex = $null; targetRps = $null; inHold = $false }
    }
    if ($current.kind -eq "hold") {
        $label = if ($current.isWarmup) { "warmup" } else { "stage-$($current.stageIndex)" }
        return [pscustomobject]@{ label = $label; stageIndex = $current.stageIndex; targetRps = $current.targetRps; inHold = $true }
    }
    return [pscustomobject]@{ label = "transition-$($current.stageIndex)"; stageIndex = $null; targetRps = $current.targetRps; inHold = $false }
}

function Save-StaircaseSample {
    param(
        [Parameter(Mandatory = $true)][string]$Phase,
        $Trace,
        [Parameter(Mandatory = $true)][long]$ContestId
    )
    $path = Join-Path $runDirectory "timeseries.csv"
    if (-not (Test-Path $path)) {
        "timestamp,epochMillis,sampleIntervalMs,sampleElapsedMs,phase,stageIndex,stageLabel,targetRps,inHold," +
        "acceptedTotal,resultsTotal,scoreboardTotal,unfinishedOutbox,unfinishedOutboxGlobal,unappliedScoreboard," +
        "judge1Running,judge1Queued,judge1Reserved,judge2Running,judge2Queued,judge2Reserved," +
        "threadsConnected,threadsRunning,innodbRowLockCurrentWaits,innodbRowLockWaits,questions" |
            Set-Content $path -Encoding utf8
    }

    $tickStart = [datetimeoffset]::UtcNow
    $sampleIntervalMs = if ($null -ne $script:staircaseLastTickUtc) {
        [math]::Round(($tickStart - $script:staircaseLastTickUtc).TotalMilliseconds, 1)
    } else { "" }
    $script:staircaseLastTickUtc = $tickStart

    # One statement, one round trip. The stock mysql:8.0 image exposes no CPU counter, so the
    # connection and InnoDB lock counters are what the container can actually report.
    $sql = @"
SELECT 'accepted', COUNT(*) FROM contest_submission WHERE contest_id=$ContestId
UNION ALL SELECT 'results', COUNT(*) FROM contest_submission_result WHERE contest_id=$ContestId
UNION ALL SELECT 'scoreboard', COUNT(*) FROM contest_submission_result WHERE contest_id=$ContestId AND scoreboard_applied_at IS NOT NULL
UNION ALL SELECT 'unfinishedContest', COUNT(*) FROM contest_judge_outbox o JOIN contest_submission s ON s.id = o.submission_id WHERE s.contest_id=$ContestId AND o.status <> 'PUBLISHED'
UNION ALL SELECT 'unappliedContest', COUNT(*) FROM contest_submission_result WHERE contest_id=$ContestId AND scoreboard_applied_at IS NULL
UNION ALL SELECT 'unfinishedGlobal', COUNT(*) FROM contest_judge_outbox WHERE status <> 'PUBLISHED'
UNION ALL SELECT 'threadsConnected', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_connected'
UNION ALL SELECT 'threadsRunning', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_running'
UNION ALL SELECT 'rowLockCurrentWaits', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_current_waits'
UNION ALL SELECT 'rowLockWaits', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_waits'
UNION ALL SELECT 'questions', VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Questions';
"@
    $values = @{}
    foreach ($row in @(Invoke-SqlRows $sql)) {
        $parts = $row.Split([char]9)
        if ($parts.Count -ge 2) { $values[$parts[0]] = $parts[1] }
    }

    $judge1 = Get-JudgeGauges -Port 19001
    $judge2 = Get-JudgeGauges -Port 19002
    $gatherMs = [math]::Round(([datetimeoffset]::UtcNow - $tickStart).TotalMilliseconds, 1)

    $position = Resolve-StaircasePosition -Trace $Trace -NowMillis $tickStart.ToUnixTimeMilliseconds()
    $stageIndex = if ($null -eq $position.stageIndex) { "" } else { $position.stageIndex }
    $targetRps = if ($null -eq $position.targetRps) { "" } else { $position.targetRps }

    $row = "$($tickStart.ToString('o')),$($tickStart.ToUnixTimeMilliseconds()),$sampleIntervalMs,$gatherMs," +
        "$Phase,$stageIndex,$($position.label),$targetRps,$([int]$position.inHold)," +
        "$($values.accepted),$($values.results),$($values.scoreboard),$($values.unfinishedContest)," +
        "$($values.unfinishedGlobal),$($values.unappliedContest)," +
        "$($judge1.running),$($judge1.queued),$($judge1.reserved)," +
        "$($judge2.running),$($judge2.queued),$($judge2.reserved)," +
        "$($values.threadsConnected),$($values.threadsRunning),$($values.rowLockCurrentWaits)," +
        "$($values.rowLockWaits),$($values.questions)"
    $row | Add-Content $path -Encoding utf8

    # capacity.csv keeps the run-level executor aggregate the analyzer already reports.
    $capacityPath = Join-Path $runDirectory "capacity.csv"
    if (-not (Test-Path $capacityPath)) {
        "timestamp,phase,node,running,localWaiting,reserved" | Set-Content $capacityPath -Encoding utf8
    }
    foreach ($entry in @(@("judge-1", $judge1), @("judge-2", $judge2))) {
        "$($tickStart.ToString('o')),$Phase,$($entry[0]),$($entry[1].running),$($entry[1].queued),$($entry[1].reserved)" |
            Add-Content $capacityPath -Encoding utf8
    }

    return [pscustomobject]@{
        unfinishedContest = ConvertTo-Int64OrNull $values.unfinishedContest
        unfinishedGlobal = ConvertTo-Int64OrNull $values.unfinishedGlobal
        unappliedContest = ConvertTo-Int64OrNull $values.unappliedContest
    }
}

function Save-StaircaseBoundarySnapshots {
    param($Trace, [long]$NowMillis, $Captured)
    if ($null -eq $Trace) { return }
    foreach ($segment in $Trace.segments) {
        foreach ($edge in @(@("start", $segment.startMillis), @("end", $segment.endMillis))) {
            $label = "seg-$($segment.index)-$($edge[0])"
            if ($Captured.ContainsKey($label)) { continue }
            if ($NowMillis -lt [long]$edge[1]) { continue }
            # A counter delta is only as good as the pair of scrapes it is taken from, so the
            # scrape is taken live at the boundary and the tick that took it is recorded, which
            # makes its own lag explicit rather than assumed.
            Save-MetricsSnapshot $label
            $Captured[$label] = $NowMillis
        }
    }
}

function Get-PromMetricSum {
    param([string]$Label, [string]$Metric, [string]$RequiredTag = "", [string]$OnlyNode = "")
    $sum = 0.0; $found = $false
    foreach ($node in @("judge-1", "judge-2")) {
        if ($OnlyNode -and $node -ne $OnlyNode) { continue }
        $path = Join-Path $runDirectory "metrics\$Label-$node.prom"
        if (-not (Test-Path $path)) { continue }
        $snapshot = @(Get-Content $path)
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
    if (-not $found) { return $null }
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
            if ($null -eq $beforeKill) { $beforeKill = 0.0 }
            # The killed JVM contributes start..pre-fault. Its replacement JVM
            # starts counters at zero, so its complete end value is the recovery
            # contribution; subtracting a post-restart scrape would drop work.
            $total += [math]::Max(0, $beforeKill - $start) + [math]::Max(0, $end)
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

# Declared before the try so the failure path can tell "Gatling never started" from "it started and
# the artifacts are missing" instead of passing $null to a [datetime] parameter and losing the
# original exception behind a binding error.
$gatlingStarted = $null

if ($DryRun) {
    Invoke-Compose -Arguments @("config") | Set-Content (Join-Path $runDirectory "compose-config.yaml") -Encoding utf8
    "Dry run only; no containers or load were started." | Set-Content (Join-Path $runDirectory "DRY_RUN.txt") -Encoding utf8
    if ($Staircase) {
        # The boundaries themselves come from the JVM trace at run time; this only states the
        # shape the parameters imply, so a wrong ladder is caught before the stack is built.
        $expectedPlan | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $runDirectory "expected-plan.json") -Encoding utf8
        Write-Host "Staircase: $($stageRpsList -join ',') RPS, warm-up stages $WarmupStageCount, hold ${StageHoldSeconds}s, guard ${SteadyGuardSeconds}s, total $($expectedPlan.totalSeconds)s, top population $($expectedPlan.maxConcurrentUsers)."
    }
    Write-Host "Dry run valid. Parameters and rendered Compose config: $runDirectory"
    exit 0
}

$events = [ordered]@{ runStartedAt=$null; loadStartedAt=$null; faultScheduledAt=$null; faultInjectedAt=$null; faultTimingErrorSeconds=$null; staleAttemptsBeforeFault=0; firstStaleReclaimObservedAt=$null; restartRequestedAt=$null; nodeRestartedAt=$null; nodeReadyAt=$null; loadEndedAt=$null; runEndedAt=$null; contestId=$null }
$events.warmupEndedAt = $null; $events.measurementStartedAt = $null
$events.drainStartedAt = $null; $events.drainEndedAt = $null; $events.drainSeconds = $null
$events.traceAnchorUtc = $null; $events.tracePlanEndUtc = $null; $events.stageWindowAlignment = $null
$events.stageWindowAlignmentErrorSeconds = $null; $events.gatlingExitCode = $null; $events.gatlingAssertionFailed = $false
$staircaseTrace = $null; $staircaseStages = @(); $capturedBoundaries = @{}
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
    $contestId = [long]$seed.contestId
    Save-MetricsSnapshot "start"

    if ($Staircase) {
        # The per-second sampler reads these in the same statement as the backlog counts; prove
        # they are readable now rather than discovering it once a four minute load is under way.
        $probe = @(Invoke-SqlRows "SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_connected'")
        if ($probe.Count -eq 0) { throw "performance_schema.global_status is not readable; the staircase sampler needs it." }
        # Every backlog number in this experiment is a difference, so nothing may be left over
        # from an earlier run: a stale row would be charged to the first stage's growth rate.
        $leftover = Get-SqlScalar "SELECT COUNT(*) FROM contest_judge_outbox WHERE status <> 'PUBLISHED'"
        if ($leftover -ne 0) { throw "contest_judge_outbox still holds $leftover unfinished rows from an earlier run." }
    }

    $classpath = (Get-Content (Join-Path $repoRoot "gatling\build\standalone-gatling\classpath.txt") -Raw).Trim()
    $resultsFolder = Join-Path $repoRoot "gatling\build\reports\gatling"
    $tracePath = (Join-Path $runDirectory "stage-trace.csv") -replace '\\', '/'
    $javaArgs = @(
        "-Xms256m", "-Xmx1g", "-Dperf.baseUrl=$baseUrl", "-Dperf.assert.minRequests=1",
        "-Dperf.assert.minSuccessPercent=$assertMinSuccess", "-Dperf.assert.p95Millis=$AssertP95Millis",
        "-Dperf.submitIntervalMillis=3100", "-Dperf.userPrefix=$workloadPrefix", "-Dperf.workloadSeed=$LatencySeed",
        "-Dperf.userIndex.start=1", "-Dperf.userIndex.end=$UserCount",
        "-Dperf.contestId=$($seed.contestId)", "-Dperf.problemId.start=$($seed.firstProblemId)", "-Dperf.problemId.end=$($seed.lastProblemId)"
    )
    if ($Staircase) {
        $javaArgs += @(
            "-Dperf.rampSeconds=$RampSeconds", "-Dperf.stepHoldSeconds=$StageHoldSeconds",
            "-Dperf.stageRps=$($stageRpsList -join ',')", "-Dperf.warmupStageCount=$WarmupStageCount",
            "-Dperf.stageTraceFile=$tracePath",
            "-cp", $classpath, "io.gatling.app.Gatling", "-s", "my.oj.perf.ContestSubmissionStepLoadSimulation"
        )
    } else {
        $javaArgs += @(
            "-Dperf.targetRps=$TargetRps", "-Dperf.rampSeconds=$RampSeconds", "-Dperf.holdSeconds=$DurationSeconds",
            "-cp", $classpath, "io.gatling.app.Gatling", "-s", "my.oj.perf.ContestSubmissionSimulation"
        )
    }
    $javaArgs += @("-rf", $resultsFolder, "-rd", "mysql-judge-tradeoff-$RunId")
    $gatlingStarted = Get-Date
    # Start-Process -PassThru -NoNewWindow hands back a Process whose ExitCode stays empty on this
    # PowerShell 5.1 even after WaitForExit(), which made the assertion check below read $null and
    # fail every run with "Gatling exited with code .". System.Diagnostics.Process populates it, and
    # UseShellExecute = $false without output redirection still lets Gatling write to this console.
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = (Get-Command java.exe).Source
    $startInfo.UseShellExecute = $false
    $startInfo.Arguments = (($javaArgs | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' ')
    $gatling = [System.Diagnostics.Process]::Start($startInfo)
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
        $events.faultScheduledAt = $faultDeadline.ToUniversalTime().ToString("o")
        # Leave a guard window for the synchronous pre-kill metric scrape. Without
        # it, probe latency itself moves a short configured fault several seconds.
        $captureDeadline = $faultDeadline.AddSeconds(-5)
        while ((Get-Date) -lt $captureDeadline -and -not $gatling.HasExited) {
            Save-BacklogSample "pre-fault" | Out-Null
            Save-CapacitySample "pre-fault"
            $remainingMillis = [math]::Floor(($captureDeadline - (Get-Date)).TotalMilliseconds)
            if ($remainingMillis -gt 0) { Start-Sleep -Milliseconds ([math]::Min(1000, $remainingMillis)) }
        }
        Save-MetricsSnapshot "pre-fault"
        $events.staleAttemptsBeforeFault = Get-SqlScalar "SELECT COALESCE(SUM(GREATEST(o.attempts - 1, 0)), 0) FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($events.contestId)"
        $remainingMillis = [math]::Floor(($faultDeadline - (Get-Date)).TotalMilliseconds)
        if ($remainingMillis -gt 0) { Start-Sleep -Milliseconds $remainingMillis }
        Invoke-Compose -Arguments @("kill", $KilledNode)
        $events.faultInjectedAt = [datetimeoffset]::UtcNow.ToString("o")
        $events.faultTimingErrorSeconds = [math]::Round(((Get-Date) - $faultDeadline).TotalSeconds, 3)
        $downDeadline = (Get-Date).AddSeconds($DownDurationSeconds)
        # Capture after kill so synchronous SQL inspection cannot postpone the
        # fault. Without claimed_by this remains an all-node active upper bound.
        $claimSnapshot = Save-ClaimSnapshot
        Observe-FaultRecovery "fault"
        while ((Get-Date) -lt $downDeadline) {
            Observe-FaultRecovery "node-down"
            $remainingMillis = [math]::Floor(($downDeadline - (Get-Date)).TotalMilliseconds)
            if ($remainingMillis -gt 0) { Start-Sleep -Milliseconds ([math]::Min(1000, $remainingMillis)) }
        }
        $events.restartRequestedAt = [datetimeoffset]::UtcNow.ToString("o")
        Invoke-Compose -Arguments @("start", $KilledNode)
        $events.nodeRestartedAt = [datetimeoffset]::UtcNow.ToString("o")
        Wait-Healthy -ObserveRecovery
        Wait-JudgeMetrics $KilledNode -ObserveRecovery
        $events.nodeReadyAt = [datetimeoffset]::UtcNow.ToString("o")
        Save-MetricsSnapshot "post-restart"
    }
    if ($FaultEnabled) {
        while (-not $gatling.HasExited) {
            Observe-FaultRecovery "post-restart"
            Save-CapacitySample "post-restart"
            Start-Sleep -Seconds 1
        }
        Observe-FaultRecovery "load-end"
    } elseif ($Staircase) {
        # Each round trip costs most of a second, so a loop that sleeps a further full second
        # samples at about 1.5s. This one sleeps only up to the next tick and records the period
        # it actually achieved, which is what the per-stage windows are read against.
        $staircaseTrace = Get-StaircaseTrace -Path (Join-Path $runDirectory "stage-trace.csv")
        if ($null -eq $staircaseTrace) {
            throw "The staircase trace file is missing or incomplete, so stage boundaries cannot be placed."
        }
        $staircaseStages = Get-StaircaseStages -Trace $staircaseTrace
        $warmupStages = @($staircaseStages | Where-Object { $_.isWarmup })
        if ($warmupStages.Count -gt 0) {
            $events.warmupEndedAt = [datetimeoffset]::FromUnixTimeMilliseconds($warmupStages[-1].endMillis).ToString("o")
        }
        $measuredStages = @($staircaseStages | Where-Object { -not $_.isWarmup })
        if ($measuredStages.Count -gt 0) {
            $events.measurementStartedAt = [datetimeoffset]::FromUnixTimeMilliseconds($measuredStages[0].measurementStartMillis).ToString("o")
        }
        $events.traceAnchorUtc = [datetimeoffset]::FromUnixTimeMilliseconds($staircaseTrace.anchorMillis).ToString("o")
        $events.tracePlanEndUtc = [datetimeoffset]::FromUnixTimeMilliseconds($staircaseTrace.planEndMillis).ToString("o")

        $script:staircaseLastTickUtc = $null
        $nextTick = [datetimeoffset]::UtcNow
        while (-not $gatling.HasExited) {
            $nextTick = $nextTick.AddSeconds(1)
            $nowMillis = [datetimeoffset]::UtcNow.ToUnixTimeMilliseconds()
            Save-StaircaseBoundarySnapshots -Trace $staircaseTrace -NowMillis $nowMillis -Captured $capturedBoundaries
            Save-StaircaseSample -Phase "load" -Trace $staircaseTrace -ContestId $contestId | Out-Null
            $remaining = ($nextTick - [datetimeoffset]::UtcNow).TotalMilliseconds
            if ($remaining -gt 0) {
                Start-Sleep -Milliseconds ([math]::Round($remaining))
            } elseif ($remaining -lt -1000) {
                # A whole period behind: re-anchor instead of firing a burst of catch-up samples.
                $nextTick = [datetimeoffset]::UtcNow
            }
        }
    } else {
        while (-not $gatling.HasExited) {
            Save-BacklogSample "load" | Out-Null
            Save-CapacitySample "load"
            Start-Sleep -Seconds 1
        }
    }
    $gatling.WaitForExit()
    $events.loadEndedAt = [datetimeoffset]::UtcNow.ToString("o")
    $events.gatlingExitCode = $gatling.ExitCode
    # An unreadable exit code means the outcome is unknown, which is neither a pass nor a failure.
    if ($null -eq $gatling.ExitCode) { throw "Gatling's exit code could not be read after it exited; the run's outcome is unknown." }
    if ($gatling.ExitCode -ne 0) {
        # Exit 2 is an assertion failure, and the report is written before assertions are
        # evaluated. A staircase deliberately drives the stack into overload, so the measurement
        # is still valid and its HTTP outcomes are accounted per stage; a missing report is not
        # an assertion failure and stays fatal.
        $reportLog = if ($Staircase -and $gatling.ExitCode -eq 2) { Find-GatlingReport -StartedAt $gatlingStarted } else { $null }
        if ($null -ne $reportLog -and (Test-Path (Join-Path $reportLog.Directory.FullName "js\global_stats.json"))) {
            $events.gatlingAssertionFailed = $true
            Write-Warning "Gatling reported an assertion failure (exit 2); keeping the run and reporting per-stage HTTP outcomes instead."
        } else {
            throw "Gatling exited with code $($gatling.ExitCode)."
        }
    }

    $events.drainStartedAt = [datetimeoffset]::UtcNow.ToString("o")
    $deadline = (Get-Date).AddSeconds($DrainTimeoutSeconds)
    $backlog = $null
    do {
        if ($Staircase) {
            # This run's own work, not every contest row in the database.
            $drainSample = Save-StaircaseSample -Phase "drain" -Trace $staircaseTrace -ContestId $contestId
            # A tick whose counts did not arrive leaves the backlog unknown, not zero. Reading it as
            # zero would end the drain on an unmeasured sample and record a drained run, so the
            # backlog stays $null and the loop keeps polling; the loop's exit test below refuses to
            # call a run drained while the last reading is unknown.
            if ($null -eq $drainSample.unfinishedContest -or $null -eq $drainSample.unappliedContest) {
                $backlog = $null
            } else {
                $backlog = $drainSample.unfinishedContest + $drainSample.unappliedContest
            }
        } else {
            $backlog = Save-BacklogSample "drain"
        }
        if ($null -ne $backlog -and $backlog -eq 0) { break }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    $events.drainEndedAt = [datetimeoffset]::UtcNow.ToString("o")
    $events.drainSeconds = [math]::Round(
        ([datetimeoffset]::Parse($events.drainEndedAt) - [datetimeoffset]::Parse($events.drainStartedAt)).TotalSeconds, 3)
    if ($null -eq $backlog) {
        throw "The drain gate never read the backlog: at least one tick returned no count, so this run's drain is undecided rather than complete."
    }
    if ($backlog -ne 0) { throw "Pipeline did not drain within $DrainTimeoutSeconds seconds." }
    Save-MetricsSnapshot "end"
    if ($Staircase) {
        Copy-GatlingArtifacts -StartedAt $gatlingStarted | Out-Null
        # The predicted plan is only useful if it lands where the traffic actually ran. Gatling
        # stops the injector at maxDuration, so the last completed request is the observable end of
        # the schedule; anything larger than the tolerance means the windows are not trustworthy.
        $lastRequestMillis = Get-GatlingLastRequestMillis -StartedAt $gatlingStarted
        if ($null -eq $lastRequestMillis) {
            $events.stageWindowAlignment = "unavailable"
        } else {
            $alignmentError = [math]::Round(($staircaseTrace.planEndMillis - $lastRequestMillis) / 1000.0, 3)
            $events.stageWindowAlignmentErrorSeconds = $alignmentError
            $events.stageWindowAlignment = if ([math]::Abs($alignmentError) -le $TraceAlignmentToleranceSeconds) { "ok" } else { "degraded" }
        }

        $segmentsDocument = @($staircaseTrace.segments | ForEach-Object {
            [ordered]@{
                index = $_.index; kind = $_.kind; stageIndex = $_.stageIndex; isWarmup = $_.isWarmup
                population = $_.population; targetRps = $_.targetRps
                startMillis = $_.startMillis; endMillis = [long]$_.endMillis
                start = [datetimeoffset]::FromUnixTimeMilliseconds($_.startMillis).ToString("o")
                end = [datetimeoffset]::FromUnixTimeMilliseconds([long]$_.endMillis).ToString("o")
            }
        })
        $stagesDocument = @($staircaseStages | ForEach-Object {
            $startLabel = $_.prometheusStartLabel
            $endLabel = $_.prometheusEndLabel
            [ordered]@{
                stageIndex = $_.stageIndex; label = $_.label; isWarmup = $_.isWarmup
                targetRps = $_.targetRps; population = $_.population
                traceSegmentIndex = $_.traceSegmentIndex
                startMillis = $_.startMillis; endMillis = $_.endMillis
                measurementStartMillis = $_.measurementStartMillis; measurementEndMillis = $_.measurementEndMillis
                start = [datetimeoffset]::FromUnixTimeMilliseconds($_.startMillis).ToString("o")
                end = [datetimeoffset]::FromUnixTimeMilliseconds($_.endMillis).ToString("o")
                measurementStart = [datetimeoffset]::FromUnixTimeMilliseconds($_.measurementStartMillis).ToString("o")
                measurementEnd = [datetimeoffset]::FromUnixTimeMilliseconds($_.measurementEndMillis).ToString("o")
                prometheusStartLabel = $startLabel; prometheusEndLabel = $endLabel
                prometheusStartLagMs = if ($capturedBoundaries.ContainsKey($startLabel)) { $capturedBoundaries[$startLabel] - $_.startMillis } else { $null }
                prometheusEndLagMs = if ($capturedBoundaries.ContainsKey($endLabel)) { $capturedBoundaries[$endLabel] - $_.endMillis } else { $null }
            }
        })
        [ordered]@{
            mode = "staircase"
            stageRps = $stageRpsList
            warmupStageCount = $WarmupStageCount
            transitionRampSeconds = $RampSeconds
            stageHoldSeconds = $StageHoldSeconds
            steadyGuardSeconds = $SteadyGuardSeconds
            overloadThresholdRowsPerSec = $OverloadThresholdRowsPerSec
            traceAlignmentToleranceSeconds = $TraceAlignmentToleranceSeconds
            expectedPlan = $expectedPlan
            traceAnchorUtc = $events.traceAnchorUtc
            tracePlanEndUtc = $events.tracePlanEndUtc
            traceAlignment = $events.stageWindowAlignment
            traceAlignmentErrorSeconds = $events.stageWindowAlignmentErrorSeconds
            warmupEndedAt = $events.warmupEndedAt
            measurementStartedAt = $events.measurementStartedAt
            drainStartedAt = $events.drainStartedAt
            drainEndedAt = $events.drainEndedAt
            drainSeconds = $events.drainSeconds
            segments = $segmentsDocument
            stages = $stagesDocument
        } | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $runDirectory "stages.json") -Encoding utf8
    }
    $events.runEndedAt = [datetimeoffset]::UtcNow.ToString("o")
    $events | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $runDirectory "events.json") -Encoding utf8
    Export-Latencies $events $claimSnapshot

    $submissionCount = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission WHERE contest_id=$($seed.contestId)"
    $resultCount = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id=$($seed.contestId)"
    $scoreboardCount = Get-SqlScalar "SELECT COUNT(*) FROM contest_submission_result WHERE contest_id=$($seed.contestId) AND scoreboard_applied_at IS NOT NULL"
    $uniqueCount = Get-SqlScalar "SELECT COUNT(DISTINCT id) FROM contest_submission WHERE contest_id=$($seed.contestId)"
    $completedHttpRequests = Get-GatlingSubmitRequests $gatlingStarted
    $attemptRows = Invoke-SqlRows "SELECT attempts, COUNT(*) FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($seed.contestId) GROUP BY attempts ORDER BY attempts"
    @("attempts`trows") + $attemptRows | Set-Content (Join-Path $runDirectory "claim-attempts.tsv") -Encoding utf8
    $reclaimRows = Invoke-SqlRows "SELECT updated_at, submission_id, attempts FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($seed.contestId) AND attempts > 1 ORDER BY updated_at"
    @($reclaimRows | ForEach-Object { $p=$_ -split "`t"; [pscustomobject]@{timestamp=([datetimeoffset]::Parse($p[0]+"Z").ToString("o"));submissionId=$p[1];attempts=$p[2]} }) |
        Export-Csv (Join-Path $runDirectory "stale-reclaims.csv") -NoTypeInformation -Encoding utf8
    $duplicateEstimate = Get-SqlScalar "SELECT COALESCE(SUM(GREATEST(attempts - 1, 0)),0) FROM contest_judge_outbox o JOIN contest_submission s ON s.id=o.submission_id WHERE s.contest_id=$($seed.contestId)"
    # Integrity is only decidable when all four counts came back. Missing counts are carried as null
    # and this flag, rather than being coerced to zero, so an unverified run cannot read as passed.
    $countsAvailable = -not (@($submissionCount, $uniqueCount, $resultCount, $scoreboardCount) -contains $null)
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
    if ($FaultEnabled -and $null -ne $judgeInvocations) {
        $unavailable.Add("judge invocation and completion counters are lower bounds in SIGKILL runs because increments after the pre-fault scrape can be lost with the killed JVM")
    }
    $unavailable.Add("duplicate judge time is bounded by the deterministic 50ms/2000ms profile; exact per-claim attribution is unavailable")
    if ($DispatchMode -eq "rabbit") { $unavailable.Add("Rabbit per-node running/local-waiting/reserved gauges are unavailable; worker-count x prefetch is recorded only as the configured normalized ceiling") }
    $unavailable.Add("MySQL CPU is not exposed by the stock mysql:8.0 container; connection and InnoDB lock counters are captured instead")
    if (-not $claimSnapshot.exact -and $FaultEnabled) { $unavailable.Add("killed-node claim attribution: schema has no claimed_by column; killed-node-claims.csv contains all active claims at kill time") }
    if ($null -eq $completedHttpRequests) {
        $unavailable.Add("completed HTTP submission count: Gatling simulation.log was not found")
    }
    if (-not $countsAvailable) {
        $unavailable.Add("integrity: at least one of the accepted/unique/result/scoreboard counts returned no row, so integrity is undecidable for this run and it must not be treated as passed")
    }
    $unavailable.Add("total HTTP submission attempts are unavailable because requests still in flight at Gatling maxDuration can persist after the client log closes; completedHttpRequests is reported separately")
    $duplicateJudgements = if ($null -eq $judgeInvocations -or $null -eq $resultCount -or $FaultEnabled) { $null } else { [math]::Max(0, $judgeInvocations - $resultCount) }
    $duplicateJudgeMillisLowerBound = if ($null -eq $duplicateJudgements) { $null } else { $duplicateJudgements * 50 }
    $duplicateJudgeMillisUpperBound = if ($null -eq $duplicateJudgements) { $null } else { $duplicateJudgements * 2000 }
    $verification = [ordered]@{
        counts = @{ requests=$null; completedHttpRequests=$completedHttpRequests; accepted=$submissionCount; uniqueSubmissions=$uniqueCount; results=$resultCount; scoreboardApplied=$scoreboardCount }
        integrity = @{
            countsAvailable = $countsAvailable
            lostOrIncomplete = if ($countsAvailable) { $submissionCount - $resultCount } else { $null }
            finalResultMismatch = if ($countsAvailable) { $resultCount - $scoreboardCount } else { $null }
            passed = if ($countsAvailable) {
                $submissionCount -eq $uniqueCount -and $submissionCount -eq $resultCount -and $resultCount -eq $scoreboardCount
            } else { $false }
            reason = if ($countsAvailable) { $null } else { "one or more of the four verification counts returned no row, so this run's integrity is undecided rather than passed" }
        }
        workCost = @{ duplicateClaimEstimate=$duplicateEstimate; duplicateJudgementEstimate=$duplicateJudgements; judgeInvocations=$judgeInvocations; judgeInvocationsLowerBound=[bool]$FaultEnabled; totalJudgeMillis=if ($null -eq $judgeDurationSeconds) {$null} else {[math]::Round($judgeDurationSeconds*1000,3)}; duplicateJudgeMillisLowerBound=$duplicateJudgeMillisLowerBound; duplicateJudgeMillisUpperBound=$duplicateJudgeMillisUpperBound; claimCalls=$claimCalls; claimedRows=$claimRows; staleReclaims=$staleReclaims; completionSuccess=$completionSuccess; completionFailure=$completionFailure; staleTokenCompletions=$staleCompletions; storedResultRepublishes=$storedRepublishes; claimAttemptsFile="claim-attempts.tsv"; killedNodeClaimCount=if ($claimSnapshot.exact) {@($claimSnapshot.ids).Count} else {$null}; allActiveClaimsAtKill=$claimSnapshot.observedActiveClaimCount }
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
    # A failed run keeps what it already collected so the reason can be read against the numbers,
    # and stays out of the capacity comparison either way.
    foreach ($artifact in @("timeseries.csv", "stage-trace.csv", "capacity.csv", "backlog.csv")) {
        $candidate = Join-Path $runDirectory $artifact
        if (Test-Path $candidate) { Write-Host "Preserved for diagnosis: $candidate" }
    }
    if ($null -ne $gatlingStarted) {
        try { Copy-GatlingArtifacts -StartedAt $gatlingStarted | Out-Null } catch { Write-Warning $_ }
    }
    throw
} finally {
    if ($started -and -not $KeepStack) {
        try { Invoke-Compose -Arguments @("down") } catch { Write-Warning $_ }
    }
}

Write-Host "Experiment complete: $runDirectory"
# An explicit success exit code, so a caller inspecting $LASTEXITCODE can tell this apart from a run
# that ended without setting one. The dry-run branch has its own exit; the failure path rethrows.
exit 0
