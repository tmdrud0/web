# Validates and aggregates one C-stage candidate repeat set. Invalid runs are rejected as a set;
# callers must replace them rather than silently selecting favorable measurements. This script
# intentionally has no MySQL write inputs because stage 5 did not define per-submission normalization.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("outbox", "stream")]
    [string]$Candidate,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string[]]$RunDirectories,

    [ValidateRange(2, 100)]
    [int]$MinimumRuns = 5,

    [switch]$AllowLegacyMissingRunVerdict,

    [string]$OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$invariantCulture = [Globalization.CultureInfo]::InvariantCulture
$numberStyles = [Globalization.NumberStyles]::Float

$requiredArtifacts = [ordered]@{
    "state-reset.csv" = @()
    "run-diagnostics.csv" = @()
    "end-to-end-staleness.csv" = @("scenario", "metric", "p50Micros", "p95Micros", "p99Micros", "maxMicros", "sampleCount")
    "http-summary.csv" = @("phase", "totalRequests", "successfulRequests", "failedRequests", "successPercent", "p95Millis")
    "jvm-summary.csv" = @("Phase", "Node", "Samples", "ThrottleMedianPercent", "RestartChanges")
    "rabbitmq-summary.csv" = @(
        "Phase", "Queue", "Samples", "PeakReady", "PeakUnacked", "ConsumerMin", "ConsumerMax",
        "PublishedDelta", "PublishedAveragePerSecond", "PublishedPeakPerSecond",
        "DeliveredDelta", "DeliveredAveragePerSecond", "DeliveredPeakPerSecond", "CounterResets")
    "rabbitmq-metrics.csv" = @(
        "timestamp", "phase", "queue", "ready", "unacked", "consumers",
        "publishedTotal", "deliveredAckTotal", "deliveredAutoTotal")
    "redis-scoreboard-summary.csv" = @(
        "Scenario", "PipelineP99Seconds", "PipelineCalls", "LuaErrors", "CounterResets")
    "redis-scoreboard-metrics.csv" = @("timestamp", "phase", "metric", "label", "value")
    "scoreboard-neutral-metrics-summary.csv" = @(
        "phase", "metric", "node", "kind", "samples", "initial", "final", "delta", "peak",
        "resetDetected", "resetAdjustedDelta")
    "scoreboard-neutral-metrics-raw.csv" = @("phase", "metric", "node", "timestamp", "value")
}

$rabbitNumericMetrics = @(
    "PeakReady",
    "PeakUnacked",
    "ConsumerMin",
    "ConsumerMax",
    "PublishedDelta",
    "PublishedAveragePerSecond",
    "PublishedPeakPerSecond",
    "DeliveredDelta",
    "DeliveredAveragePerSecond",
    "DeliveredPeakPerSecond"
)

function Format-InvariantNumber {
    param([Parameter(Mandatory = $true)][double]$Value)

    if ([double]::IsNaN($Value) -or [double]::IsInfinity($Value)) {
        throw "Cannot format a non-finite number."
    }
    return $Value.ToString("R", $invariantCulture)
}

function Get-RequiredValue {
    param(
        [Parameter(Mandatory = $true)]$Row,
        [Parameter(Mandatory = $true)][string]$Column,
        [Parameter(Mandatory = $true)][string]$Context
    )

    $property = $Row.PSObject.Properties[$Column]
    if ($null -eq $property) {
        throw "$Context is missing the required '$Column' column."
    }
    $value = [string]$property.Value
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "$Context has an empty '$Column' value."
    }
    return $value.Trim()
}

function Get-RequiredDouble {
    param(
        [Parameter(Mandatory = $true)]$Row,
        [Parameter(Mandatory = $true)][string]$Column,
        [Parameter(Mandatory = $true)][string]$Context
    )

    $text = Get-RequiredValue -Row $Row -Column $Column -Context $Context
    $parsed = 0d
    if (-not [double]::TryParse($text, $numberStyles, $invariantCulture, [ref]$parsed) -or
        [double]::IsNaN($parsed) -or
        [double]::IsInfinity($parsed)) {
        throw "$Context has a non-finite or non-invariant '$Column' value: '$text'."
    }
    return $parsed
}

function Get-RequiredInt64 {
    param(
        [Parameter(Mandatory = $true)]$Row,
        [Parameter(Mandatory = $true)][string]$Column,
        [Parameter(Mandatory = $true)][string]$Context
    )

    $parsed = Get-RequiredDouble -Row $Row -Column $Column -Context $Context
    if ([math]::Truncate($parsed) -ne $parsed -or
        $parsed -lt [int64]::MinValue -or
        $parsed -gt [int64]::MaxValue) {
        throw "$Context has a non-integer or out-of-range '$Column' value: '$parsed'."
    }
    return [int64]$parsed
}

function Assert-Equal {
    param(
        [Parameter(Mandatory = $true)]$Actual,
        [Parameter(Mandatory = $true)]$Expected,
        [Parameter(Mandatory = $true)][string]$Description
    )

    if ($Actual -ne $Expected) {
        throw "$Description must be $Expected; actual=$Actual."
    }
}

function Assert-NonNegative {
    param(
        [Parameter(Mandatory = $true)][double]$Value,
        [Parameter(Mandatory = $true)][string]$Description
    )

    if ($Value -lt 0d) {
        throw "$Description must be nonnegative; actual=$(Format-InvariantNumber $Value)."
    }
}

function Import-RequiredArtifact {
    param(
        [Parameter(Mandatory = $true)][string]$RunDirectory,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Columns
    )

    $path = Join-Path $RunDirectory $Name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required artifact is missing: $path"
    }
    $rows = @(Import-Csv -LiteralPath $path)
    if ($rows.Count -eq 0) {
        throw "Required artifact has no data rows: $path"
    }
    $availableColumns = @($rows[0].PSObject.Properties.Name)
    foreach ($column in $Columns) {
        if ($column -notin $availableColumns) {
            throw "Required artifact '$path' is missing column '$column'."
        }
    }
    return $rows
}

function Assert-RunVerdict {
    param(
        [Parameter(Mandatory = $true)][string]$RunDirectory,
        [Parameter(Mandatory = $true)][string]$Scenario
    )

    $path = Join-Path $RunDirectory "run-verdict.csv"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        if ($AllowLegacyMissingRunVerdict) {
            Write-Warning "Legacy run has no run-verdict.csv; caller explicitly accepted external harness-exit evidence: $RunDirectory"
            return
        }
        throw "Required successful-run sentinel is missing: $path"
    }
    $rows = @(Import-Csv -LiteralPath $path)
    if ($rows.Count -ne 1) {
        throw "run-verdict.csv must have exactly one data row; actual=$($rows.Count)."
    }
    $row = $rows[0]
    foreach ($column in @("scenario", "completedSuccessfully", "oomKilledContainers")) {
        if ($null -eq $row.PSObject.Properties[$column]) {
            throw "run-verdict.csv is missing column '$column'."
        }
    }
    if ([string]$row.scenario -ne $Scenario -or
        [string]$row.completedSuccessfully -ne "true" -or
        [string]$row.oomKilledContainers -ne "0") {
        throw "run-verdict.csv does not attest a successful OOM-free '$Scenario' run."
    }
}

function Get-OneRow {
    param(
        [Parameter(Mandatory = $true)][object[]]$Rows,
        [Parameter(Mandatory = $true)][scriptblock]$Predicate,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $matches = @($Rows | Where-Object $Predicate)
    if ($matches.Count -ne 1) {
        throw "$Description must have exactly one row; actual=$($matches.Count)."
    }
    return $matches[0]
}

function Get-LinearQuantile {
    param(
        [Parameter(Mandatory = $true)][double[]]$Values,
        [Parameter(Mandatory = $true)][ValidateRange(0d, 1d)][double]$Probability
    )

    if ($Values.Count -eq 0) {
        throw "Cannot calculate a quantile from an empty value set."
    }
    $ordered = [double[]]@($Values | Sort-Object)
    if ($ordered.Count -eq 1) {
        return $ordered[0]
    }

    # This is the documented comparison rule: rank=(n-1)*p with linear interpolation.
    $rank = ($ordered.Count - 1) * $Probability
    $lowerIndex = [int][math]::Floor($rank)
    $upperIndex = [int][math]::Ceiling($rank)
    if ($lowerIndex -eq $upperIndex) {
        return $ordered[$lowerIndex]
    }
    $fraction = $rank - $lowerIndex
    return $ordered[$lowerIndex] + ($fraction * ($ordered[$upperIndex] - $ordered[$lowerIndex]))
}

function Get-DistributionStatistics {
    param([Parameter(Mandatory = $true)][double[]]$Values)

    $q1 = Get-LinearQuantile -Values $Values -Probability 0.25
    $median = Get-LinearQuantile -Values $Values -Probability 0.50
    $q3 = Get-LinearQuantile -Values $Values -Probability 0.75
    return [pscustomobject]@{
        RunCount = $Values.Count
        Median = $median
        Q1 = $q1
        Q3 = $q3
        IQR = $q3 - $q1
        Minimum = [double](($Values | Measure-Object -Minimum).Minimum)
        Maximum = [double](($Values | Measure-Object -Maximum).Maximum)
    }
}

function Add-DistributionRow {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][System.Collections.Generic.List[object]]$Rows,
        [Parameter(Mandatory = $true)][string]$Category,
        [Parameter(Mandatory = $true)][string]$Dimension,
        [Parameter(Mandatory = $true)][string]$Metric,
        [Parameter(Mandatory = $true)][string]$Unit,
        [Parameter(Mandatory = $true)][double[]]$Values,
        [switch]$ApplyP95StabilityGate
    )

    $statistics = Get-DistributionStatistics -Values $Values
    $ratioText = ""
    $lowerText = ""
    $upperText = ""
    $withinFenceText = ""
    $stabilityGate = "N/A"
    $ratio = $null
    $lowerFence = $null
    $upperFence = $null
    $allWithinFence = $null

    if ($ApplyP95StabilityGate) {
        if ($statistics.Median -eq 0d) {
            if ($statistics.IQR -eq 0d) {
                $ratio = 0d
            }
        }
        else {
            $ratio = $statistics.IQR / [math]::Abs($statistics.Median)
        }
        $lowerFence = $statistics.Q1 - (3d * $statistics.IQR)
        $upperFence = $statistics.Q3 + (3d * $statistics.IQR)
        $allWithinFence = @($Values | Where-Object {
            $_ -lt $lowerFence -or $_ -gt $upperFence
        }).Count -eq 0
        $stableRatio = $null -ne $ratio -and $ratio -le 0.05d
        $stabilityGate = if ($stableRatio -and $allWithinFence) { "PASS" } else { "FAIL" }
        if ($null -ne $ratio) { $ratioText = Format-InvariantNumber ([double]$ratio) }
        $lowerText = Format-InvariantNumber ([double]$lowerFence)
        $upperText = Format-InvariantNumber ([double]$upperFence)
        $withinFenceText = ([bool]$allWithinFence).ToString().ToLowerInvariant()
    }

    $Rows.Add([pscustomobject][ordered]@{
        Candidate = $Candidate
        Category = $Category
        Dimension = $Dimension
        Metric = $Metric
        Unit = $Unit
        RunCount = $statistics.RunCount.ToString($invariantCulture)
        Median = Format-InvariantNumber $statistics.Median
        Q1 = Format-InvariantNumber $statistics.Q1
        Q3 = Format-InvariantNumber $statistics.Q3
        IQR = Format-InvariantNumber $statistics.IQR
        Minimum = Format-InvariantNumber $statistics.Minimum
        Maximum = Format-InvariantNumber $statistics.Maximum
        IqrOverMedian = $ratioText
        FenceLower = $lowerText
        FenceUpper = $upperText
        AllRunsWithinFence = $withinFenceText
        StabilityGate = $stabilityGate
    })

    return [pscustomobject]@{
        Statistics = $statistics
        IqrOverMedian = $ratio
        FenceLower = $lowerFence
        FenceUpper = $upperFence
        AllRunsWithinFence = $allWithinFence
        StabilityGate = $stabilityGate
    }
}

function ConvertTo-ColumnToken {
    param([Parameter(Mandatory = $true)][string]$Value)

    return [regex]::Replace($Value, '[^A-Za-z0-9]+', '_').Trim('_')
}

function Read-CandidateRun {
    param([Parameter(Mandatory = $true)][string]$RunDirectory)

    $artifacts = @{}
    foreach ($entry in $requiredArtifacts.GetEnumerator()) {
        $artifacts[$entry.Key] = @(Import-RequiredArtifact `
            -RunDirectory $RunDirectory `
            -Name $entry.Key `
            -Columns ([string[]]$entry.Value))
    }

    $stateRows = [object[]]$artifacts["state-reset.csv"]
    $diagnosticRows = [object[]]$artifacts["run-diagnostics.csv"]
    if ($stateRows.Count -ne 1) {
        throw "state-reset.csv must have exactly one data row; actual=$($stateRows.Count)."
    }
    if ($diagnosticRows.Count -ne 1) {
        throw "run-diagnostics.csv must have exactly one data row; actual=$($diagnosticRows.Count)."
    }
    $state = $stateRows[0]
    $diagnostic = $diagnosticRows[0]
    $scenario = Get-RequiredValue -Row $diagnostic -Column "scenario" -Context "run-diagnostics.csv"
    Assert-RunVerdict -RunDirectory $RunDirectory -Scenario $scenario
    $contestId = Get-RequiredInt64 -Row $diagnostic -Column "contestId" -Context "run-diagnostics.csv"
    if ($contestId -le 0) {
        throw "run-diagnostics.csv contestId must be positive; actual=$contestId."
    }

    if ($Candidate -eq "outbox") {
        foreach ($column in @("redisKeys", "submissionOutboxRows", "judgeOutboxRows")) {
            Assert-Equal `
                -Actual (Get-RequiredInt64 -Row $state -Column $column -Context "state-reset.csv") `
                -Expected 0 `
                -Description "outbox state-reset.csv $column"
        }
        foreach ($column in @("resetRedisKeys", "resetSubmissionOutboxRows", "resetJudgeOutboxRows", "scoreboardRetryRows")) {
            Assert-Equal `
                -Actual (Get-RequiredInt64 -Row $diagnostic -Column $column -Context "run-diagnostics.csv") `
                -Expected 0 `
                -Description "outbox run-diagnostics.csv $column"
        }
    }
    else {
        Assert-Equal `
            -Actual (Get-RequiredInt64 -Row $state -Column "judgeOutboxRows" -Context "state-reset.csv") `
            -Expected 0 `
            -Description "stream state-reset.csv judgeOutboxRows"
        foreach ($column in @("redisKeys", "scoreboardStreamOffset", "resultStreamMessages")) {
            Assert-NonNegative `
                -Value (Get-RequiredDouble -Row $state -Column $column -Context "state-reset.csv") `
                -Description "stream state-reset.csv $column"
        }
        Assert-Equal `
            -Actual (Get-RequiredInt64 -Row $diagnostic -Column "resetJudgeOutboxRows" -Context "run-diagnostics.csv") `
            -Expected 0 `
            -Description "stream run-diagnostics.csv resetJudgeOutboxRows"
        Assert-Equal `
            -Actual (Get-RequiredInt64 -Row $diagnostic -Column "scoreboardUnappliedRows" -Context "run-diagnostics.csv") `
            -Expected 0 `
            -Description "stream run-diagnostics.csv scoreboardUnappliedRows"
        foreach ($column in @("resetRedisKeys", "resetScoreboardOffset", "resetResultStreamMessages")) {
            Assert-NonNegative `
                -Value (Get-RequiredDouble -Row $diagnostic -Column $column -Context "run-diagnostics.csv") `
                -Description "stream run-diagnostics.csv $column"
        }
    }

    Assert-Equal `
        -Actual (Get-RequiredInt64 -Row $diagnostic -Column "jvmRestartChanges" -Context "run-diagnostics.csv") `
        -Expected 0 `
        -Description "run-diagnostics.csv jvmRestartChanges"
    Assert-Equal `
        -Actual (Get-RequiredInt64 -Row $diagnostic -Column "observedJvmNodes" -Context "run-diagnostics.csv") `
        -Expected 5 `
        -Description "run-diagnostics.csv observedJvmNodes"

    $stalenessRows = [object[]]$artifacts["end-to-end-staleness.csv"]
    $resultRow = Get-OneRow -Rows $stalenessRows -Predicate {
        [string]$_.metric -eq "result-queryable"
    } -Description "end-to-end-staleness.csv result-queryable"
    $scoreboardRow = Get-OneRow -Rows $stalenessRows -Predicate {
        [string]$_.metric -eq "scoreboard-applied"
    } -Description "end-to-end-staleness.csv scoreboard-applied"
    foreach ($row in @($resultRow, $scoreboardRow)) {
        $rowScenario = Get-RequiredValue -Row $row -Column "scenario" -Context "end-to-end-staleness.csv"
        if ($rowScenario -ne $scenario) {
            throw "end-to-end-staleness.csv scenario '$rowScenario' does not match '$scenario'."
        }
    }

    $result = [pscustomobject]@{
        P50Micros = Get-RequiredDouble -Row $resultRow -Column "p50Micros" -Context "result-queryable"
        P95Micros = Get-RequiredDouble -Row $resultRow -Column "p95Micros" -Context "result-queryable"
        P99Micros = Get-RequiredDouble -Row $resultRow -Column "p99Micros" -Context "result-queryable"
        MaxMicros = Get-RequiredDouble -Row $resultRow -Column "maxMicros" -Context "result-queryable"
        SampleCount = Get-RequiredInt64 -Row $resultRow -Column "sampleCount" -Context "result-queryable"
    }
    $scoreboard = [pscustomobject]@{
        P50Micros = Get-RequiredDouble -Row $scoreboardRow -Column "p50Micros" -Context "scoreboard-applied"
        P95Micros = Get-RequiredDouble -Row $scoreboardRow -Column "p95Micros" -Context "scoreboard-applied"
        P99Micros = Get-RequiredDouble -Row $scoreboardRow -Column "p99Micros" -Context "scoreboard-applied"
        MaxMicros = Get-RequiredDouble -Row $scoreboardRow -Column "maxMicros" -Context "scoreboard-applied"
        SampleCount = Get-RequiredInt64 -Row $scoreboardRow -Column "sampleCount" -Context "scoreboard-applied"
    }
    foreach ($endpoint in @($result, $scoreboard)) {
        foreach ($column in @("P50Micros", "P95Micros", "P99Micros", "MaxMicros")) {
            Assert-NonNegative -Value ([double]$endpoint.$column) -Description "staleness $column"
        }
        if ($endpoint.SampleCount -le 0) {
            throw "Staleness sampleCount must be positive; actual=$($endpoint.SampleCount)."
        }
    }
    if ($result.SampleCount -ne $scoreboard.SampleCount) {
        throw "Staleness sample counts differ: result=$($result.SampleCount), scoreboard=$($scoreboard.SampleCount)."
    }

    $httpRows = [object[]]$artifacts["http-summary.csv"]
    foreach ($row in $httpRows) {
        $context = "http-summary.csv phase '$([string]$row.phase)'"
        Assert-Equal `
            -Actual (Get-RequiredInt64 -Row $row -Column "failedRequests" -Context $context) `
            -Expected 0 `
            -Description "$context failedRequests"
        $successPercent = Get-RequiredDouble -Row $row -Column "successPercent" -Context $context
        if ($successPercent -lt 99d) {
            throw "$context successPercent must be at least 99; actual=$(Format-InvariantNumber $successPercent)."
        }
    }
    $http = Get-OneRow -Rows $httpRows -Predicate {
        [string]$_.phase -eq $scenario
    } -Description "http-summary.csv phase '$scenario'"

    $jvmRows = @([object[]]$artifacts["jvm-summary.csv"] | Where-Object {
        [string]$_.Phase -eq $scenario
    })
    if ($jvmRows.Count -eq 0) {
        throw "jvm-summary.csv has no rows for phase '$scenario'."
    }
    $jvmNodes = @($jvmRows | ForEach-Object {
        Get-RequiredValue -Row $_ -Column "Node" -Context "jvm-summary.csv"
    } | Sort-Object -Unique)
    Assert-Equal -Actual $jvmNodes.Count -Expected 5 -Description "jvm-summary.csv unique node count"
    foreach ($row in $jvmRows) {
        $node = Get-RequiredValue -Row $row -Column "Node" -Context "jvm-summary.csv"
        $context = "jvm-summary.csv node '$node'"
        Assert-Equal `
            -Actual (Get-RequiredInt64 -Row $row -Column "RestartChanges" -Context $context) `
            -Expected 0 `
            -Description "$context RestartChanges"
        $throttleMedian = Get-RequiredDouble -Row $row -Column "ThrottleMedianPercent" -Context $context
        if ($throttleMedian -lt 0d -or $throttleMedian -gt 10d) {
            throw "$context ThrottleMedianPercent must be in [0,10]; actual=$(Format-InvariantNumber $throttleMedian)."
        }
    }

    $rabbitRaw = [object[]]$artifacts["rabbitmq-metrics.csv"]
    if (@($rabbitRaw | Where-Object { [string]$_.phase -eq $scenario }).Count -eq 0) {
        throw "rabbitmq-metrics.csv has no raw rows for phase '$scenario'."
    }
    $rabbitRows = @([object[]]$artifacts["rabbitmq-summary.csv"] | Where-Object {
        [string]$_.Phase -eq $scenario
    })
    if ($rabbitRows.Count -eq 0) {
        throw "rabbitmq-summary.csv has no rows for phase '$scenario'."
    }
    $rabbitByQueue = @{}
    foreach ($row in $rabbitRows) {
        $queue = Get-RequiredValue -Row $row -Column "Queue" -Context "rabbitmq-summary.csv"
        if ($rabbitByQueue.ContainsKey($queue)) {
            throw "rabbitmq-summary.csv has duplicate queue '$queue' rows for phase '$scenario'."
        }
        $context = "rabbitmq-summary.csv queue '$queue'"
        Assert-Equal `
            -Actual (Get-RequiredInt64 -Row $row -Column "CounterResets" -Context $context) `
            -Expected 0 `
            -Description "$context CounterResets"
        $metrics = @{}
        foreach ($metric in $rabbitNumericMetrics) {
            $metrics[$metric] = Get-RequiredDouble -Row $row -Column $metric -Context $context
            Assert-NonNegative -Value $metrics[$metric] -Description "$context $metric"
        }
        $rabbitByQueue[$queue] = $metrics
    }

    $redisRaw = [object[]]$artifacts["redis-scoreboard-metrics.csv"]
    if (@($redisRaw | Where-Object { [string]$_.phase -eq $scenario }).Count -eq 0) {
        throw "redis-scoreboard-metrics.csv has no raw rows for phase '$scenario'."
    }
    $redisRows = [object[]]$artifacts["redis-scoreboard-summary.csv"]
    $redis = Get-OneRow -Rows $redisRows -Predicate {
        [string]$_.Scenario -eq $scenario
    } -Description "redis-scoreboard-summary.csv scenario '$scenario'"
    Assert-Equal `
        -Actual (Get-RequiredInt64 -Row $redis -Column "CounterResets" -Context "redis-scoreboard-summary.csv") `
        -Expected 0 `
        -Description "redis-scoreboard-summary.csv CounterResets"
    $redisPipelineP99 = Get-RequiredDouble -Row $redis -Column "PipelineP99Seconds" -Context "redis-scoreboard-summary.csv"
    $redisPipelineCalls = Get-RequiredInt64 -Row $redis -Column "PipelineCalls" -Context "redis-scoreboard-summary.csv"
    $redisLuaErrors = Get-RequiredInt64 -Row $redis -Column "LuaErrors" -Context "redis-scoreboard-summary.csv"
    Assert-NonNegative -Value $redisPipelineP99 -Description "Redis pipeline p99"
    Assert-NonNegative -Value $redisPipelineCalls -Description "Redis pipeline calls"
    Assert-NonNegative -Value $redisLuaErrors -Description "Redis Lua errors"

    $neutralRaw = [object[]]$artifacts["scoreboard-neutral-metrics-raw.csv"]
    if (@($neutralRaw | Where-Object { [string]$_.phase -eq $scenario }).Count -eq 0) {
        throw "scoreboard-neutral-metrics-raw.csv has no raw rows for phase '$scenario'."
    }
    $neutralRows = [object[]]$artifacts["scoreboard-neutral-metrics-summary.csv"]
    $neutral = @{}
    foreach ($metric in @(
        "contest_scoreboard_pending_events",
        "contest_scoreboard_oldest_ready_seconds",
        "contest_scoreboard_applied_total")) {
        $row = Get-OneRow -Rows $neutralRows -Predicate {
            [string]$_.phase -eq $scenario -and [string]$_.metric -eq $metric
        } -Description "scoreboard-neutral-metrics-summary.csv metric '$metric'"
        $context = "scoreboard-neutral-metrics-summary.csv metric '$metric'"
        $samples = Get-RequiredInt64 -Row $row -Column "samples" -Context $context
        if ($samples -lt 2) {
            throw "$context samples must be at least 2; actual=$samples."
        }
        $initial = Get-RequiredDouble -Row $row -Column "initial" -Context $context
        $final = Get-RequiredDouble -Row $row -Column "final" -Context $context
        Assert-NonNegative -Value $initial -Description "$context initial"
        Assert-NonNegative -Value $final -Description "$context final"

        if ($metric -eq "contest_scoreboard_applied_total") {
            $resetDetected = (Get-RequiredValue -Row $row -Column "resetDetected" -Context $context).ToLowerInvariant()
            if ($resetDetected -ne "false") {
                throw "$context resetDetected must be false; actual='$resetDetected'."
            }
            $delta = Get-RequiredDouble -Row $row -Column "resetAdjustedDelta" -Context $context
            Assert-NonNegative -Value $delta -Description "$context resetAdjustedDelta"
            # The comparison stack starts fresh and restart=0 was already required above, so the
            # application counter must contain exactly one increment per materialized result. Both
            # checks are necessary: a truncated query_range can end on a zero backlog sample while
            # still omitting the tail of this counter from the exported comparison window.
            Assert-Equal `
                -Actual $final `
                -Expected $result.SampleCount `
                -Description "$context final versus staleness sampleCount"
            Assert-Equal `
                -Actual $delta `
                -Expected $result.SampleCount `
                -Description "$context resetAdjustedDelta versus staleness sampleCount"
            $neutral[$metric] = [pscustomobject]@{
                Samples = $samples
                Initial = $initial
                Final = $final
                Delta = $delta
                Peak = $null
            }
        }
        else {
            $peak = Get-RequiredDouble -Row $row -Column "peak" -Context $context
            Assert-NonNegative -Value $peak -Description "$context peak"
            # The comparison deliberately uses no scrape-lag tolerance. A non-zero last sample
            # means the run artifact did not demonstrate a fully drained neutral backlog.
            Assert-Equal -Actual $final -Expected 0 -Description "$context final"
            $neutral[$metric] = [pscustomobject]@{
                Samples = $samples
                Initial = $initial
                Final = $final
                Delta = $null
                Peak = $peak
            }
        }
    }

    return [pscustomobject]@{
        Candidate = $Candidate
        RunDirectory = $RunDirectory
        RunName = Split-Path -Leaf $RunDirectory
        Scenario = $scenario
        ContestId = $contestId
        Result = $result
        Scoreboard = $scoreboard
        HttpTotalRequests = Get-RequiredInt64 -Row $http -Column "totalRequests" -Context "http-summary.csv"
        HttpSuccessfulRequests = Get-RequiredInt64 -Row $http -Column "successfulRequests" -Context "http-summary.csv"
        HttpSuccessPercent = Get-RequiredDouble -Row $http -Column "successPercent" -Context "http-summary.csv"
        NeutralPending = $neutral["contest_scoreboard_pending_events"]
        NeutralOldest = $neutral["contest_scoreboard_oldest_ready_seconds"]
        NeutralApplied = $neutral["contest_scoreboard_applied_total"]
        RedisPipelineP99Seconds = $redisPipelineP99
        RedisPipelineCalls = $redisPipelineCalls
        RedisLuaErrors = $redisLuaErrors
        RabbitByQueue = $rabbitByQueue
    }
}

if ($RunDirectories.Count -lt $MinimumRuns) {
    throw "At least $MinimumRuns run directories are required; actual=$($RunDirectories.Count)."
}

$resolvedRuns = @()
$seenRuns = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
foreach ($directory in $RunDirectories) {
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        throw "Run directory does not exist or is not a directory: $directory"
    }
    $resolved = (Resolve-Path -LiteralPath $directory).Path
    if (-not $seenRuns.Add($resolved)) {
        throw "Run directory was supplied more than once: $resolved"
    }
    $resolvedRuns += $resolved
}

$runs = [System.Collections.Generic.List[object]]::new()
$validationFailures = [System.Collections.Generic.List[string]]::new()
foreach ($runDirectory in $resolvedRuns) {
    try {
        $runs.Add((Read-CandidateRun -RunDirectory $runDirectory))
        Write-Host "Validated $Candidate run: $runDirectory"
    }
    catch {
        $validationFailures.Add("$runDirectory :: $($_.Exception.Message)")
    }
}

if ($validationFailures.Count -gt 0) {
    Write-Host "VERDICT FAIL candidate=$Candidate validRuns=$($runs.Count)/$($resolvedRuns.Count)"
    foreach ($failure in $validationFailures) {
        Write-Host "  $failure"
    }
    throw "C-stage candidate validation failed for $($validationFailures.Count) run(s). Invalid runs must be discarded and rerun."
}

$scenarios = @($runs | Select-Object -ExpandProperty Scenario -Unique)
if ($scenarios.Count -ne 1) {
    throw "All runs must use the same scenario; observed=$($scenarios -join ',')."
}
$referenceQueues = @($runs[0].RabbitByQueue.Keys | Sort-Object)
$queueSeparator = [string][char]31
$referenceQueueSignature = $referenceQueues -join $queueSeparator
foreach ($run in $runs) {
    $queueSignature = @($run.RabbitByQueue.Keys | Sort-Object) -join $queueSeparator
    if ($queueSignature -ne $referenceQueueSignature) {
        throw "RabbitMQ queue set differs in run '$($run.RunDirectory)'. expected=$($referenceQueues -join ',') actual=$(@($run.RabbitByQueue.Keys | Sort-Object) -join ',')"
    }
}

$distributionRows = [System.Collections.Generic.List[object]]::new()
$p95Gates = @{}
$stalenessDimensions = [ordered]@{
    "result-queryable" = "Result"
    "scoreboard-applied" = "Scoreboard"
}
$stalenessMetrics = [ordered]@{
    "p50" = "P50Micros"
    "p95" = "P95Micros"
    "p99" = "P99Micros"
    "max" = "MaxMicros"
    "sampleCount" = "SampleCount"
}
foreach ($dimensionEntry in $stalenessDimensions.GetEnumerator()) {
    foreach ($metricEntry in $stalenessMetrics.GetEnumerator()) {
        $values = [double[]]@($runs | ForEach-Object {
            [double]$_.($dimensionEntry.Value).($metricEntry.Value)
        })
        $unit = if ($metricEntry.Key -eq "sampleCount") { "count" } else { "microseconds" }
        $applyGate = $metricEntry.Key -eq "p95"
        $gate = Add-DistributionRow `
            -Rows $distributionRows `
            -Category "staleness" `
            -Dimension $dimensionEntry.Key `
            -Metric $metricEntry.Key `
            -Unit $unit `
            -Values $values `
            -ApplyP95StabilityGate:$applyGate
        if ($applyGate) {
            $p95Gates[$dimensionEntry.Key] = $gate
        }
    }
}

$neutralDistributions = @(
    [pscustomobject]@{ Metric = "pendingPeak"; Unit = "events"; Values = [double[]]@($runs | ForEach-Object { $_.NeutralPending.Peak }) },
    [pscustomobject]@{ Metric = "oldestReadyPeak"; Unit = "seconds"; Values = [double[]]@($runs | ForEach-Object { $_.NeutralOldest.Peak }) },
    [pscustomobject]@{ Metric = "appliedDelta"; Unit = "events"; Values = [double[]]@($runs | ForEach-Object { $_.NeutralApplied.Delta }) }
)
foreach ($item in $neutralDistributions) {
    [void](Add-DistributionRow `
        -Rows $distributionRows `
        -Category "scoreboard-neutral" `
        -Dimension "all" `
        -Metric $item.Metric `
        -Unit $item.Unit `
        -Values $item.Values)
}

foreach ($queue in $referenceQueues) {
    foreach ($metric in $rabbitNumericMetrics) {
        $values = [double[]]@($runs | ForEach-Object { [double]$_.RabbitByQueue[$queue][$metric] })
        $unit = if ($metric -like "*PerSecond") { "events-per-second" } else { "count" }
        [void](Add-DistributionRow `
            -Rows $distributionRows `
            -Category "rabbitmq" `
            -Dimension $queue `
            -Metric $metric `
            -Unit $unit `
            -Values $values)
    }
}

$redisDistributions = @(
    [pscustomobject]@{ Metric = "pipelineP99"; Unit = "seconds"; Values = [double[]]@($runs | ForEach-Object { $_.RedisPipelineP99Seconds }) },
    [pscustomobject]@{ Metric = "pipelineCalls"; Unit = "count"; Values = [double[]]@($runs | ForEach-Object { $_.RedisPipelineCalls }) },
    [pscustomobject]@{ Metric = "luaErrors"; Unit = "count"; Values = [double[]]@($runs | ForEach-Object { $_.RedisLuaErrors }) }
)
foreach ($item in $redisDistributions) {
    [void](Add-DistributionRow `
        -Rows $distributionRows `
        -Category "redis" `
        -Dimension "scoreboard" `
        -Metric $item.Metric `
        -Unit $item.Unit `
        -Values $item.Values)
}

$candidateRows = [System.Collections.Generic.List[object]]::new()
foreach ($run in $runs) {
    $resultGate = $p95Gates["result-queryable"]
    $scoreboardGate = $p95Gates["scoreboard-applied"]
    $resultWithinFence = $run.Result.P95Micros -ge $resultGate.FenceLower -and
        $run.Result.P95Micros -le $resultGate.FenceUpper
    $scoreboardWithinFence = $run.Scoreboard.P95Micros -ge $scoreboardGate.FenceLower -and
        $run.Scoreboard.P95Micros -le $scoreboardGate.FenceUpper

    $row = [ordered]@{
        Candidate = $Candidate
        RunName = $run.RunName
        RunDirectory = $run.RunDirectory
        Scenario = $run.Scenario
        ContestId = $run.ContestId.ToString($invariantCulture)
        ResultP50Micros = Format-InvariantNumber $run.Result.P50Micros
        ResultP95Micros = Format-InvariantNumber $run.Result.P95Micros
        ResultP99Micros = Format-InvariantNumber $run.Result.P99Micros
        ResultMaxMicros = Format-InvariantNumber $run.Result.MaxMicros
        ResultSampleCount = $run.Result.SampleCount.ToString($invariantCulture)
        ResultP95WithinFence = $resultWithinFence.ToString().ToLowerInvariant()
        ScoreboardP50Micros = Format-InvariantNumber $run.Scoreboard.P50Micros
        ScoreboardP95Micros = Format-InvariantNumber $run.Scoreboard.P95Micros
        ScoreboardP99Micros = Format-InvariantNumber $run.Scoreboard.P99Micros
        ScoreboardMaxMicros = Format-InvariantNumber $run.Scoreboard.MaxMicros
        ScoreboardSampleCount = $run.Scoreboard.SampleCount.ToString($invariantCulture)
        ScoreboardP95WithinFence = $scoreboardWithinFence.ToString().ToLowerInvariant()
        HttpTotalRequests = $run.HttpTotalRequests.ToString($invariantCulture)
        HttpSuccessfulRequests = $run.HttpSuccessfulRequests.ToString($invariantCulture)
        HttpSuccessPercent = Format-InvariantNumber $run.HttpSuccessPercent
        NeutralPendingPeak = Format-InvariantNumber $run.NeutralPending.Peak
        NeutralPendingFinal = Format-InvariantNumber $run.NeutralPending.Final
        NeutralOldestReadyPeakSeconds = Format-InvariantNumber $run.NeutralOldest.Peak
        NeutralOldestReadyFinalSeconds = Format-InvariantNumber $run.NeutralOldest.Final
        NeutralAppliedDelta = Format-InvariantNumber $run.NeutralApplied.Delta
        RedisPipelineP99Seconds = Format-InvariantNumber $run.RedisPipelineP99Seconds
        RedisPipelineCalls = $run.RedisPipelineCalls.ToString($invariantCulture)
        RedisLuaErrors = $run.RedisLuaErrors.ToString($invariantCulture)
        RabbitQueues = $referenceQueues -join ";"
    }
    foreach ($queue in $referenceQueues) {
        $queueToken = ConvertTo-ColumnToken $queue
        foreach ($metric in $rabbitNumericMetrics) {
            $row["Rabbit_${queueToken}_$metric"] = Format-InvariantNumber $run.RabbitByQueue[$queue][$metric]
        }
    }
    $candidateRows.Add([pscustomobject]$row)
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) "var\c-stage-summary"
}
if (-not (Test-Path -LiteralPath $OutputDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
}
$resolvedOutput = (Resolve-Path -LiteralPath $OutputDirectory).Path
$candidateRunsPath = Join-Path $resolvedOutput "candidate-runs-$Candidate.csv"
$distributionPath = Join-Path $resolvedOutput "distribution-summary-$Candidate.csv"
$candidateRows | Export-Csv -LiteralPath $candidateRunsPath -NoTypeInformation -Encoding utf8
$distributionRows | Export-Csv -LiteralPath $distributionPath -NoTypeInformation -Encoding utf8

$failedP95Gates = @($p95Gates.GetEnumerator() | Where-Object {
    $_.Value.StabilityGate -ne "PASS"
})
foreach ($endpoint in $stalenessDimensions.Keys) {
    $gate = $p95Gates[$endpoint]
    $ratio = if ($null -eq $gate.IqrOverMedian) { "undefined" } else {
        (100d * [double]$gate.IqrOverMedian).ToString("0.###", $invariantCulture) + "%"
    }
    Write-Host ("p95 stability {0}: {1} (IQR/median={2}, fence=[{3},{4}], allWithinFence={5})" -f
        $endpoint,
        $gate.StabilityGate,
        $ratio,
        (Format-InvariantNumber ([double]$gate.FenceLower)),
        (Format-InvariantNumber ([double]$gate.FenceUpper)),
        ([bool]$gate.AllRunsWithinFence).ToString().ToLowerInvariant())
}

Write-Host "candidate runs: $candidateRunsPath"
Write-Host "distribution summary: $distributionPath"
if ($failedP95Gates.Count -gt 0) {
    Write-Host "VERDICT FAIL candidate=$Candidate validRuns=$($runs.Count) p95Stability=$($failedP95Gates.Count)/2 failed"
    throw "C-stage p95 repeat-set stability failed. Discard the whole candidate repeat set and measure it again."
}

Write-Host "VERDICT PASS candidate=$Candidate validRuns=$($runs.Count) scenario=$($scenarios[0])"
