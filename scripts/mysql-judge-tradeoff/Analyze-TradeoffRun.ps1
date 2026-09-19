[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$RunDirectory
)

$ErrorActionPreference = "Stop"
$runPath = (Resolve-Path $RunDirectory).Path
$latencyPath = Join-Path $runPath "latency.csv"
$eventsPath = Join-Path $runPath "events.json"
$verificationPath = Join-Path $runPath "db-verification.json"
$parametersPath = Join-Path $runPath "parameters.json"

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    if ($null -eq $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $index = [math]::Max(0, [math]::Ceiling($sorted.Count * $Percentile) - 1)
    return [math]::Round([double]$sorted[$index], 3)
}

function Get-LatencySummary {
    param([object[]]$Rows)
    $result = [ordered]@{}
    foreach ($name in @("L_result_ms", "L_scoreboard_ms", "L_total_ms")) {
        $values = @($Rows | ForEach-Object {
            $value = 0.0
            if ([double]::TryParse([string]$_.$name, [Globalization.NumberStyles]::Float,
                    [Globalization.CultureInfo]::InvariantCulture, [ref]$value)) { $value }
        })
        $result[$name] = [ordered]@{
            count = $values.Count
            p50 = Get-Percentile $values 0.50
            p95 = Get-Percentile $values 0.95
            p99 = Get-Percentile $values 0.99
            max = if ($values.Count -gt 0) { [math]::Round(($values | Measure-Object -Maximum).Maximum, 3) } else { $null }
        }
    }
    return $result
}

$parameters = Get-Content $parametersPath -Raw | ConvertFrom-Json
$events = Get-Content $eventsPath -Raw | ConvertFrom-Json
$verification = Get-Content $verificationPath -Raw | ConvertFrom-Json
$rows = if (Test-Path $latencyPath) { @(Import-Csv $latencyPath) } else { @() }
$cohortNames = @("all", "pre-fault-normal", "fault-window", "killed-node-claimed", "post-fault-arrivals")
$cohorts = [ordered]@{}
foreach ($cohortName in $cohortNames) {
    if ($cohortName -eq "killed-node-claimed" -and -not $verification.cohortAvailability.killedNodeClaimed) {
        $cohorts[$cohortName] = [ordered]@{ available=$false; reason="claim owner is not stored; all-active snapshot is only an upper bound" }
    } else {
        $selected = if ($cohortName -eq "all") { $rows } else { @($rows | Where-Object { $_.cohorts -split ';' -contains $cohortName }) }
        $cohorts[$cohortName] = Get-LatencySummary $selected
    }
}

$capacity = [ordered]@{}
$capacityPath = Join-Path $runPath "capacity.csv"
if (Test-Path $capacityPath) {
    foreach ($node in @("judge-1", "judge-2")) {
        $nodeRows = @(Import-Csv $capacityPath | Where-Object node -eq $node)
        $capacity[$node] = [ordered]@{}
        foreach ($metric in @("running", "localWaiting", "reserved")) {
            $values = @($nodeRows | ForEach-Object { if ([string]$_.$metric -ne "") { [double]$_.$metric } })
            $capacity[$node][$metric] = [ordered]@{
                samples=$values.Count
                max=if ($values.Count) { ($values | Measure-Object -Maximum).Maximum } else { $null }
                average=if ($values.Count) { [math]::Round(($values | Measure-Object -Average).Average, 3) } else { $null }
            }
        }
    }
}

$backlogRecoverySeconds = $null
$backlogPath = Join-Path $runPath "backlog.csv"
if ((Test-Path $backlogPath) -and $events.faultInjectedAt) {
    $faultAt = [datetimeoffset]::Parse($events.faultInjectedAt)
    $recovered = Import-Csv $backlogPath | Where-Object {
        [datetimeoffset]::Parse($_.timestamp) -ge $faultAt -and [long]$_.unfinished -eq 0
    } | Select-Object -First 1
    if ($recovered) {
        $backlogRecoverySeconds = [math]::Round(([datetimeoffset]::Parse($recovered.timestamp) - $faultAt).TotalSeconds, 3)
    }
}

$firstStaleReclaimSeconds = $null
if ($events.faultInjectedAt -and $events.firstStaleReclaimObservedAt) {
    $firstStaleReclaimSeconds = [math]::Round(
        ([datetimeoffset]::Parse($events.firstStaleReclaimObservedAt) -
            [datetimeoffset]::Parse($events.faultInjectedAt)).TotalSeconds, 3)
}

$summary = [ordered]@{
    runId = $parameters.runId
    gitCommit = $parameters.gitCommit
    dispatchMode = $parameters.dispatchMode
    events = $events
    counts = $verification.counts
    integrity = $verification.integrity
    cohorts = $cohorts
    recovery = [ordered]@{
        firstStaleReclaimSeconds = $firstStaleReclaimSeconds
        backlogNormalizedSeconds = $backlogRecoverySeconds
    }
    workCost = $verification.workCost
    capacity = $capacity
    mysql = $verification.mysql
    unavailable = @($verification.unavailable)
}
$summary | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $runPath "summary.json") -Encoding utf8

$lines = @(
    "# MySQL judge tradeoff run $($parameters.runId)", "",
    "- Dispatch: $($parameters.dispatchMode)",
    "- Git commit: $($parameters.gitCommit)",
    "- Requests / accepted / unique / results / scoreboard: $($verification.counts.requests) / $($verification.counts.accepted) / $($verification.counts.uniqueSubmissions) / $($verification.counts.results) / $($verification.counts.scoreboardApplied)",
    "- First stale reclaim after fault: $(if ($null -eq $firstStaleReclaimSeconds) { 'unavailable' } else { \"${firstStaleReclaimSeconds}s\" })",
    "- Backlog normalization after fault: $(if ($null -eq $backlogRecoverySeconds) { 'unavailable' } else { \"${backlogRecoverySeconds}s\" })", "",
    "| Cohort | Metric | count | p50 ms | p95 ms | p99 ms | max ms |",
    "|---|---|---:|---:|---:|---:|---:|"
)
foreach ($cohortName in $cohortNames) {
    if ($cohorts[$cohortName].available -eq $false) {
        $lines += "| $cohortName | unavailable | 0 |  |  |  |  |"
        continue
    }
    foreach ($metricName in @("L_result_ms", "L_scoreboard_ms", "L_total_ms")) {
        $metric = $cohorts[$cohortName][$metricName]
        $lines += "| $cohortName | $metricName | $($metric.count) | $($metric.p50) | $($metric.p95) | $($metric.p99) | $($metric.max) |"
    }
}
$lines += @("", "## Executor capacity", "", "| Node | Metric | samples | max | average |", "|---|---|---:|---:|---:|")
foreach ($node in @("judge-1", "judge-2")) {
    foreach ($metricName in @("running", "localWaiting", "reserved")) {
        $metric = if ($capacity[$node]) {
            $capacity[$node][$metricName]
        } else {
            [ordered]@{ samples=0; max=$null; average=$null }
        }
        $lines += "| $node | $metricName | $($metric.samples) | $($metric.max) | $($metric.average) |"
    }
}
$lines += @("", "## Explicitly unavailable", "")
if (@($verification.unavailable).Count -eq 0) { $lines += "- None" } else {
    $lines += @($verification.unavailable | ForEach-Object { "- $_" })
}
$lines | Set-Content (Join-Path $runPath "summary.md") -Encoding utf8

Write-Host "Wrote summary.json and summary.md to $runPath"
